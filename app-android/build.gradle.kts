plugins {
    // AGP 9 registers the Kotlin extension itself; applying kotlin-android on
    // top of it fails with a duplicate-extension error.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Release signing comes from the environment, never from the repo.
 *
 * CI decodes the keystore from a secret to a temp file and exports these; a
 * local release build works the same way with the same four variables. When
 * they are absent the release config is simply not created, so `assembleDebug`
 * and every test task still work on a clean checkout with no key at all.
 *
 * The key must stay the same forever: Android identifies an app by its
 * signature, and installing an update signed with a different key is refused
 * outright, with uninstall-and-lose-your-data as the only way out.
 */
val releaseKeystore: File? = System.getenv("HCULTRA_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::file)

android {
    namespace = "chat.hc.ultra"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "chat.hc.ultra"
        minSdk = 26
        targetSdk = 36
        // Overridden by the release workflow from the pushed tag, so a tag is
        // the single source of truth for what a build calls itself.
        versionCode = (System.getenv("HCULTRA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HCULTRA_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("HCULTRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HCULTRA_KEY_ALIAS")
                keyPassword = System.getenv("HCULTRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.coroutines.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
}
