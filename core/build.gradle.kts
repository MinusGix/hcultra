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
            implementation(libs.serialization.json)
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
