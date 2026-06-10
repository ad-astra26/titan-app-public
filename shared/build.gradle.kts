plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // Step A: JVM target only — JVM-unit-testable on localhost, no Android SDK.
    // Step B adds androidTarget(); the iOS arc adds iosX64/iosArm64/iosSimulatorArm64.
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
