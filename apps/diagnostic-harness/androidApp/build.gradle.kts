import groovy.json.JsonSlurper
import org.gradle.api.tasks.Copy
import java.security.MessageDigest

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val probeGitSha = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

// Default delivery assembly must never package an unfetched or corrupted model: fetching stays a
// separate, explicit step (`make fetch-model-asset`), never an implicit assembly fallback. This
// check is unconditional: no Gradle property can disable it, because a property is readable by
// any invocation of the standard assembleDebug/assembleRelease tasks, which would let a direct
// delivery command silently bypass verification. The JVM/CI host (not a delivery platform, see
// ADR-0004; never runs on a device) instead uses the dedicated `assembleDebugForJvmVerification`
// task below, which reaches the same real compiled/packaged output without ever depending on
// verifyModelAsset.
val modelAssetsDir = rootDir.resolve("model-assets")
val modelProvenanceFile = modelAssetsDir.resolve("model_provenance.json")

val verifyModelAsset = tasks.register("verifyModelAsset") {
    description = "Requires the pinned model asset present and SHA-256 verified before packaging."
    outputs.upToDateWhen { false }
    doLast {
        val manifest = JsonSlurper().parse(modelProvenanceFile) as Map<*, *>
        val assetName = manifest["asset_name"] as String
        val expectedSha = manifest["sha256"] as String
        val expectedBytes = (manifest["byte_count"] as Number).toLong()
        val assetFile = modelAssetsDir.resolve(assetName)
        if (!assetFile.isFile) {
            throw GradleException(
                "model asset missing at $assetFile; run make fetch-model-asset before " +
                    "assembling a delivery build",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var actualBytes = 0L
        assetFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                actualBytes += read
            }
        }
        if (actualBytes != expectedBytes) {
            throw GradleException(
                "model asset byte_count mismatch: pinned $expectedBytes, actual $actualBytes",
            )
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) {
            throw GradleException(
                "model asset sha256 mismatch: pinned $expectedSha, actual $actualSha",
            )
        }
    }
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyModelAsset)
}

// The JVM/CI verification host builds the real debug APK to prove the module compiles and
// packages, but is not a delivery platform and never runs on a device, so it must not require the
// model asset. It depends directly on the real packaging task rather than on assembleDebug, so it
// never inherits the verifyModelAsset dependency above and no property can be used to reach the
// same bypass from a genuine delivery invocation.
val assembleDebugForJvmVerification = tasks.register("assembleDebugForJvmVerification") {
    group = "verification"
    description = "Assembles the debug APK for the JVM/CI host only; intentionally skips model-" +
        "asset verification because this host is not a delivery platform (ADR-0004). Delivery " +
        "must always use assembleDebug/assembleRelease."
    dependsOn("packageDebug")
}

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
