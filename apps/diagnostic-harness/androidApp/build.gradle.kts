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
// check is unconditional: no Gradle property can disable it, and it is attached to every
// APK-producing "package*" task (packageDebug, packageRelease, ...) directly, so a caller invoking
// that task graph at any level (packageDebug or assembleDebug) cannot bypass it. The JVM/CI host
// (not a delivery platform, see ADR-0004; never runs on a device) instead builds a mechanically
// distinct `jvmVerification` build type/variant below, with its own application ID and version
// suffix, whose own `packageJvmVerification` task is the sole exclusion from this gate.
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

// Gates every real APK-producing task (packageDebug, packageRelease, and their AGP-generated
// siblings such as packageDebugResources) directly, so both assembleDebug/assembleRelease and a
// direct `:androidApp:packageDebug` invocation require the verified asset. Every task belonging to
// the non-delivery `jvmVerification` build type defined below carries "JvmVerification" in its
// AGP-generated name (packageJvmVerification, packageJvmVerificationResources, ...) and is the sole
// exclusion; this is a structural property of the distinct build type, not a caller-controlled flag.
tasks.matching { it.name.startsWith("package") && "JvmVerification" !in it.name }.configureEach {
    dependsOn(verifyModelAsset)
}

val copyJvmSyntheticExportFixture = tasks.register<Copy>("copyJvmSyntheticExportFixture") {
    dependsOn(":shared:generateSyntheticExport")
    from(project(":shared").layout.buildDirectory.dir("conformance/synthetic-session"))
    into(layout.buildDirectory.dir("generated/androidTestAssets/synthetic-session"))
}

// Task 5: the UI's real replay path needs the verified model asset and the committed detection
// fixture on the device at runtime, not only staged for an instrumented test. This stages both
// into this delivery module's own main `assets/`, so the packaged APK carries exactly the bytes
// `verifyModelAsset` above already SHA-verified, under the same `replay/...` layout
// `:shared:stageDeviceTestReplayResources` uses — one asset shape, two staging sites, so
// `RealReplayComposition`'s `context.assets.open("replay/...")` reads work identically whichever
// build produced the APK.
val repositoryRoot = rootDir.parentFile.parentFile
val detectionFixtureDir = repositoryRoot.resolve("fixtures/synthetic/valid/fixture_detection")
val deliveryReplayAssetsDir = layout.buildDirectory.dir("generated/deliveryReplayAssets")

val stageDeliveryReplayAssets = tasks.register("stageDeliveryReplayAssets") {
    description = "Stages the verified pinned model asset and the detection fixture into this app's main assets."
    val fixtureMetadataFile = detectionFixtureDir.resolve("fixture_metadata.json")
    inputs.file(modelProvenanceFile)
    inputs.file(fixtureMetadataFile)
    inputs.dir(detectionFixtureDir.resolve("decoded"))
    outputs.dir(deliveryReplayAssetsDir)
    outputs.upToDateWhen { false }
    doLast {
        val provenanceText = modelProvenanceFile.readText()
        val provenance = JsonSlurper().parseText(provenanceText) as Map<*, *>
        val assetName = provenance["asset_name"] as String
        val assetFile = modelAssetsDir.resolve(assetName)
        if (!assetFile.isFile) {
            throw GradleException(
                "model asset missing at $assetFile; run make fetch-model-asset before assembling " +
                    "a delivery build",
            )
        }

        val staged = deliveryReplayAssetsDir.get().asFile.resolve("replay")
        staged.deleteRecursively()
        staged.resolve("detection/decoded").mkdirs()
        staged.resolve("model_provenance.json").writeText(provenanceText)
        staged.resolve(assetName).writeBytes(assetFile.readBytes())
        staged.resolve("detection/fixture_metadata.json").writeText(fixtureMetadataFile.readText())
        detectionFixtureDir.resolve("decoded").listFiles().orEmpty().sorted().forEach { plane ->
            staged.resolve("detection/decoded/${plane.name}").writeBytes(plane.readBytes())
        }
    }
}

// Every real asset-merging task (mergeDebugAssets, mergeReleaseAssets, ...) needs the staged
// bytes; the non-delivery jvmVerification variant is excluded exactly like verifyModelAsset above,
// so `verify-harness-jvm` never requires the fetched model.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && "JvmVerification" !in it.name }
    .configureEach { dependsOn(stageDeliveryReplayAssets) }

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

    // A mechanically distinct, non-delivery build type/variant for the JVM/CI verification host
    // only (ADR-0004: not a delivery platform, never runs on a device). Its own applicationId and
    // versionName make it structurally impossible to confuse with a delivery artifact, and its own
    // `packageJvmVerification` task is the sole task excluded from the verifyModelAsset gate below.
    buildTypes {
        getByName("debug") {}
        create("jvmVerification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".jvmverification"
            versionNameSuffix = "-jvmverification"
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir(layout.buildDirectory.dir("generated/androidTestAssets").get().asFile)
        }
        getByName("main") {
            assets.srcDir(deliveryReplayAssetsDir.get().asFile)
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
    implementation(libs.mediapipe.tasks.vision)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
}

tasks.matching { "AndroidTestAssets" in it.name || it.name == "assembleDebugAndroidTest" || it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(copyJvmSyntheticExportFixture)
}
