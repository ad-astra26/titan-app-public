import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // JVM target = headless unit tests on localhost. Android target = the app.
    // The iOS arc adds iosX64/iosArm64/iosSimulatorArm64 (reuses commonMain).
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // BouncyCastle Ed25519 — identical on both JVM (tests) and Android (app).
        jvmMain.dependencies {
            implementation(libs.bouncycastle)
        }
        androidMain.dependencies {
            implementation(libs.bouncycastle)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.core) // runBlocking for the suspend signing test
        }
    }
}

android {
    namespace = "tech.iamtitan.app.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
