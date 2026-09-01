import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

val repositoryRoot = rootDir.parentFile.parentFile
val modelAssetsDir = rootDir.resolve("model-assets")
val detectionFixtureDir = repositoryRoot.resolve("fixtures/synthetic/valid/fixture_detection")
val deviceTestReplayResourcesDir = layout.buildDirectory.dir("generated/deviceTestReplayResources")

val stageDeviceTestReplayResources = tasks.register("stageDeviceTestReplayResources") {
    description = "Stages the verified pinned model asset and the detection fixture for the instrumented replay run."
    val provenanceFile = modelAssetsDir.resolve("model_provenance.json")
    val fixtureMetadataFile = detectionFixtureDir.resolve("fixture_metadata.json")
    inputs.file(provenanceFile)
    inputs.file(fixtureMetadataFile)
    inputs.dir(detectionFixtureDir.resolve("decoded"))
    outputs.dir(deviceTestReplayResourcesDir)
    outputs.upToDateWhen { false }
    doLast {
        val provenanceText = provenanceFile.readText()
        val provenance = groovy.json.JsonSlurper().parseText(provenanceText) as Map<*, *>
        val assetName = provenance["asset_name"] as String
        val expectedSha = provenance["sha256"] as String
        val expectedBytes = (provenance["byte_count"] as Number).toLong()
        val assetFile = modelAssetsDir.resolve(assetName)
        if (!assetFile.isFile) {
            throw GradleException(
                "model asset missing at $assetFile; run make fetch-model-asset before assembling " +
                    "the instrumented replay run",
            )
        }
        val assetBytes = assetFile.readBytes()
        if (assetBytes.size.toLong() != expectedBytes) {
            throw GradleException(
                "model asset byte_count mismatch: pinned $expectedBytes, actual ${assetBytes.size}",
            )
        }
        val actualSha = MessageDigest.getInstance("SHA-256").digest(assetBytes)
            .joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) {
            throw GradleException("model asset sha256 mismatch: pinned $expectedSha, actual $actualSha")
        }

        val staged = deviceTestReplayResourcesDir.get().asFile.resolve("replay")
        staged.deleteRecursively()
        staged.resolve("detection/decoded").mkdirs()
        staged.resolve("model_provenance.json").writeText(provenanceText)
        staged.resolve(assetName).writeBytes(assetBytes)
        staged.resolve("detection/fixture_metadata.json").writeText(fixtureMetadataFile.readText())
        detectionFixtureDir.resolve("decoded").listFiles().orEmpty().sorted().forEach { plane ->
            staged.resolve("detection/decoded/${plane.name}").writeBytes(plane.readBytes())
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    androidLibrary {
        namespace = "com.calisthenics.diagnostic.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {}.configure {}
    }
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // JvmMain and androidMain both run on a JVM with java.nio.file available, and the purge
        // filesystem is security-sensitive: traversal, symlink, and prefix guards must exist in
        // exactly one place. A second copy for Android would be the wrong kind of duplication.
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.mediapipe.tasks.vision)
        }
        getByName("androidDeviceTest") {
            dependsOn(commonTest.get())
            resources.srcDir(deviceTestReplayResourcesDir)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
            }
        }
    }
}

// The instrumented replay run needs two things on the device that cannot simply be committed
// beside the test: the pinned model binary is fetched and never tracked (design section 7 /
// repository guard), and the detection fixture planes are 1.1 MiB of committed bytes that must
// reach the device unchanged. Both are staged into generated device-test resources here, so the
// device reads exactly the bytes `make verify` hashes and the model binary never enters the
// source tree.
//
// Fail-closed, deliberately: a missing or mismatched model asset stops the device-test assembly
// with the same "run make fetch-model-asset" instruction the delivery gate uses, rather than
// producing a test APK whose replay run cannot construct a backend. Only device-test assembly
// depends on this, so no host or JVM verification target is coupled to the fetched asset.
tasks.matching { it.name == "processAndroidDeviceTestJavaRes" }.configureEach {
    dependsOn(stageDeviceTestReplayResources)
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<JavaExec>("generateSyntheticExport") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.contract.GenerateSyntheticExportKt")
    args(layout.buildDirectory.dir("conformance/synthetic-session").get().asFile.absolutePath)
}
