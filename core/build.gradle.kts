import java.util.concurrent.TimeUnit
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    // JVM keeps the protocol core testable on Linux without a device.
    // iOS targets land when there is a Mac in the loop — they can only be built
    // on macOS. Nothing in commonMain may depend on a platform-only API.
    jvm()

    // AGP 9 removed com.android.library compatibility with KMP; the android
    // target now comes from AGP's own multiplatform plugin.
    androidLibrary {
        namespace = "chat.hc.core"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            // CIO works on Android and avoids OkHttp; one less dependency.
            implementation(libs.ktor.client.cio)
        }
    }
}

/**
 * End-to-end check against the real server. Not part of `check` — it needs the
 * network and spends real rate-limit budget.
 */
tasks.register<JavaExec>("liveSmoke") {
    group = "verification"
    description = "Run the protocol core against live hack.chat (joins a random channel)."
    dependsOn("jvmMainClasses")
    mainClass.set("chat.hc.core.smoke.LiveSmokeKt")
    classpath(
        kotlin.jvm().compilations.getByName("main").output.allOutputs,
        configurations.getByName("jvmRuntimeClasspath"),
    )
}

/**
 * The phantom server (probe/phantom): real upstream hack.chat run locally, with
 * an admin the tests control. One process per build, shut down when the build
 * ends — including when tests fail, which a `doLast` would not survive.
 */
abstract class PhantomService : BuildService<PhantomService.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val dir: DirectoryProperty
        val port: Property<Int>
        val serverPort: Property<Int>
        val controlPort: Property<Int>
        val log: RegularFileProperty
    }

    private var process: Process? = null

    @Synchronized
    fun ensureStarted() {
        if (process?.isAlive == true) return
        val dir = parameters.dir.get().asFile
        if (!dir.resolve("srv/config.json").exists()) {
            val setup = ProcessBuilder("node", "phantom.mjs", "setup").directory(dir).inheritIO().start()
            check(setup.waitFor() == 0) { "phantom setup failed; see probe/phantom/README.md" }
        }
        val log = parameters.log.get().asFile.also { it.parentFile.mkdirs() }
        val p = ProcessBuilder(
            "node", "phantom.mjs", "serve",
            "--port", "${parameters.port.get()}",
            "--server-port", "${parameters.serverPort.get()}",
            "--control-port", "${parameters.controlPort.get()}",
        ).directory(dir).redirectError(log).start()
        process = p
        // phantom prints one JSON line on stdout once every port is listening.
        val ready = p.inputStream.bufferedReader().readLine()
        check(ready != null && "\"ready\":true" in ready) { "phantom did not start; see $log" }
    }

    override fun close() {
        process?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

val phantom = gradle.sharedServices.registerIfAbsent("phantom", PhantomService::class) {
    parameters.dir.set(rootProject.layout.projectDirectory.dir("probe/phantom"))
    // Off the defaults (6070/6071/6079) so a phantom you run by hand is not disturbed.
    parameters.port.set(6170)
    parameters.serverPort.set(6171)
    parameters.controlPort.set(6179)
    parameters.log.set(layout.buildDirectory.file("phantom/phantom.log"))
}

val phantomPackage = "chat.hc.core.phantom.*"

// The phantom tests need the server; plain jvmTest must not try to reach it.
tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(phantomPackage) }
}

tasks.register<Test>("phantomTest") {
    group = "verification"
    description = "Run the client against a local upstream server with a controllable admin (needs node)."
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    filter { includeTestsMatching(phantomPackage) }
    // The server is the input that matters, and Gradle cannot see it.
    outputs.upToDateWhen { false }
    usesService(phantom)
    environment("PHANTOM_URL", "ws://127.0.0.1:6170")
    environment("PHANTOM_CONTROL", "http://127.0.0.1:6179")
    doFirst { phantom.get().ensureStarted() }
}

/** Per-message native cost by history length; see TranscriptBench.kt. */
tasks.register<JavaExec>("transcriptBench") {
    group = "verification"
    description = "Measure what an arriving message costs natively as history grows."
    dependsOn("jvmMainClasses")
    mainClass.set("chat.hc.core.bench.TranscriptBenchKt")
    classpath(
        kotlin.jvm().compilations.getByName("main").output.allOutputs,
        configurations.getByName("jvmRuntimeClasspath"),
    )
}
