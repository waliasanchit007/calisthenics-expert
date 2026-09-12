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

val replayTracePath = providers.gradleProperty("tracePath")

tasks.register<JavaExec>("replayMovementTrace") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.viewer.experimental.ReplayMovementTraceKt")
    doFirst {
        val path = replayTracePath.orNull
            ?: throw GradleException("provide the private trace with -PtracePath=/path/to/trace.jsonl")
        args("-PtracePath=$path")
    }
}

val squatPosePath = providers.gradleProperty("posePath")
val squatConfigPath = providers.gradleProperty("squatConfigPath")
val squatOutputPath = providers.gradleProperty("squatOutputPath")

tasks.register<JavaExec>("replaySquatPose") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.viewer.experimental.ReplaySquatPoseKt")
    doFirst {
        val pose = squatPosePath.orNull
            ?: throw GradleException("provide private pose JSONL with -PposePath=/path/to/pose.jsonl")
        val config = squatConfigPath.orNull
            ?: throw GradleException("provide private squat config JSON with -PsquatConfigPath=/path/to/config.json")
        val output = squatOutputPath.orNull
            ?: throw GradleException("provide output JSONL with -PsquatOutputPath=/path/to/output.jsonl")
        args("--posePath=$pose", "--squatConfigPath=$config", "--squatOutputPath=$output")
    }
}

val plankConfigPath = providers.gradleProperty("plankConfigPath")
val plankOutputPath = providers.gradleProperty("plankOutputPath")

tasks.register<JavaExec>("replayPlankPose") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.viewer.experimental.ReplayPlankPoseKt")
    doFirst {
        val pose = squatPosePath.orNull
            ?: throw GradleException("provide private pose JSONL with -PposePath=/path/to/pose.jsonl")
        val config = plankConfigPath.orNull
            ?: throw GradleException("provide private plank config JSON with -PplankConfigPath=/path/to/config.json")
        val output = plankOutputPath.orNull
            ?: throw GradleException("provide output JSONL with -PplankOutputPath=/path/to/output.jsonl")
        args("--posePath=$pose", "--plankConfigPath=$config", "--plankOutputPath=$output")
    }
}

val lungeConfigPath = providers.gradleProperty("lungeConfigPath")
val lungeOutputPath = providers.gradleProperty("lungeOutputPath")

tasks.register<JavaExec>("replayLungePose") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.viewer.experimental.ReplayLungePoseKt")
    doFirst {
        val pose = squatPosePath.orNull
            ?: throw GradleException("provide private pose JSONL with -PposePath=/path/to/pose.jsonl")
        val config = lungeConfigPath.orNull
            ?: throw GradleException("provide private lunge config JSON with -PlungeConfigPath=/path/to/config.json")
        val output = lungeOutputPath.orNull
            ?: throw GradleException("provide output JSONL with -PlungeOutputPath=/path/to/output.jsonl")
        args("--posePath=$pose", "--lungeConfigPath=$config", "--lungeOutputPath=$output")
    }
}

val jumpingJackConfigPath = providers.gradleProperty("jumpingJackConfigPath")
val jumpingJackOutputPath = providers.gradleProperty("jumpingJackOutputPath")

tasks.register<JavaExec>("replayJumpingJackPose") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.viewer.experimental.ReplayJumpingJackPoseKt")
    doFirst {
        val pose = squatPosePath.orNull
            ?: throw GradleException("provide private pose JSONL with -PposePath=/path/to/pose.jsonl")
        val config = jumpingJackConfigPath.orNull
            ?: throw GradleException("provide private jumping jack config JSON with -PjumpingJackConfigPath=/path/to/config.json")
        val output = jumpingJackOutputPath.orNull
            ?: throw GradleException("provide output JSONL with -PjumpingJackOutputPath=/path/to/output.jsonl")
        args("--posePath=$pose", "--jumpingJackConfigPath=$config", "--jumpingJackOutputPath=$output")
    }
}

val situpConfigPath = providers.gradleProperty("situpConfigPath")
// Offline-only breadth experiments; input media and predictions remain outside Git.
listOf("Dip" to "dip", "HangingLegRaise" to "hangingLegRaise", "StaticSkill" to "staticSkill")
    .forEach { (classStem, propertyStem) ->
        tasks.register<JavaExec>("replay${classStem}Pose") {
            dependsOn("jvmTestClasses")
            classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
            mainClass.set("com.calisthenics.diagnostic.viewer.experimental.Replay${classStem}PoseKt")
            doFirst {
                val pose = providers.gradleProperty("posePath").orNull
                    ?: throw GradleException("provide -PposePath=/private/pose.jsonl")
                val config = providers.gradleProperty("${propertyStem}ConfigPath").orNull
                    ?: throw GradleException("provide -P${propertyStem}ConfigPath=/private/config.json")
                val output = providers.gradleProperty("${propertyStem}OutputPath").orNull
                    ?: throw GradleException("provide -P${propertyStem}OutputPath=/private/output.jsonl")
                args("--posePath=$pose", "--${propertyStem}ConfigPath=$config", "--${propertyStem}OutputPath=$output")
            }
        }
    }

val situpOutputPath = providers.gradleProperty("situpOutputPath")

tasks.register<JavaExec>("replaySitupPose") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.viewer.experimental.ReplaySitupPoseKt")
    doFirst {
        val pose = squatPosePath.orNull
            ?: throw GradleException("provide private pose JSONL with -PposePath=/path/to/pose.jsonl")
        val config = situpConfigPath.orNull
            ?: throw GradleException("provide private situp config JSON with -PsitupConfigPath=/path/to/config.json")
        val output = situpOutputPath.orNull
            ?: throw GradleException("provide output JSONL with -PsitupOutputPath=/path/to/output.jsonl")
        args("--posePath=$pose", "--situpConfigPath=$config", "--situpOutputPath=$output")
    }
}
