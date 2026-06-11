import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "tech.iamtitan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.iamtitan.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            // DEV fallback (insecure seed store) is compiled ONLY in debug — see KeystoreDeviceKey.
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false // signing/minify configured in a later phase
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    // Phase 1 (all FOSS — AD-7): device key, QR pairing, async chat.
    implementation(libs.androidx.biometric)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
}
