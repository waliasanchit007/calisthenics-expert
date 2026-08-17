import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
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
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidDeviceTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
            }
        }
    }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<JavaExec>("generateSyntheticExport") {
    dependsOn("jvmTestClasses")
    classpath(jvmTestCompilation.runtimeDependencyFiles, jvmTestCompilation.output.allOutputs)
    mainClass.set("com.calisthenics.diagnostic.contract.GenerateSyntheticExportKt")
    args(layout.buildDirectory.dir("conformance/synthetic-session").get().asFile.absolutePath)
}
