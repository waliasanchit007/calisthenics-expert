import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val probeGitSha = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

val copyJvmSyntheticExportFixture = tasks.register<Copy>("copyJvmSyntheticExportFixture") {
    dependsOn(":shared:generateSyntheticExport")
    from(project(":shared").layout.buildDirectory.dir("conformance/synthetic-session"))
    into(layout.buildDirectory.dir("generated/androidTestAssets/synthetic-session"))
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PROBE_GIT_SHA", "\"${probeGitSha.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir(layout.buildDirectory.dir("generated/androidTestAssets").get().asFile)
        }
    }
}

dependencies {
    implementation(projects.shared)
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
}

tasks.matching { "AndroidTestAssets" in it.name || it.name == "assembleDebugAndroidTest" || it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(copyJvmSyntheticExportFixture)
}
