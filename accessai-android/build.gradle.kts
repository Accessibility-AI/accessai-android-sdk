plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.accessai.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":accessai-core"))
    // api: hosts show AccessAI from a FragmentActivity, which BiometricPrompt needs.
    api(libs.androidx.biometric)
    // biometric 1.1.0 pulls in fragment 1.2.5, whose FragmentActivity rejects the request codes that
    // ActivityResultRegistry generates, crashing the microphone permission request. 1.3.0+ fixes it.
    api(libs.androidx.fragment)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "accessai-android"
            afterEvaluate { from(components["release"]) }
        }
    }
}
