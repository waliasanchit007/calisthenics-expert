plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.calisthenics.diagnostic.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.calisthenics.diagnostic.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation(projects.shared)
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
