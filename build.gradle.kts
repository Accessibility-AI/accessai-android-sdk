plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    // JitPack rewrites the group to com.github.<user>.<repo>; this is the default for local publishing.
    group = "com.github.Accessibility-AI.accessai-android-sdk"
    version = providers.gradleProperty("VERSION_NAME").get()
}
