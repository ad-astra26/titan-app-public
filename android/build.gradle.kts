import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

// Release signing (secret-managed, AD-7): the keystore + its creds live OUTSIDE both repos at
// ~/.titan/titan-app-keystore.properties (storeFile/storePassword/keyAlias/keyPassword). Absent
// ⇒ the release build stays unsigned (debug + CI-without-secrets still work). Never committed.
val keystorePropsFile = File(System.getProperty("user.home"), ".titan/titan-app-keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.containsKey("storeFile")

android {
    namespace = "tech.iamtitan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.iamtitan.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // DEV fallback (insecure seed store) is compiled ONLY in debug — see KeystoreDeviceKey.
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(libs.androidx.core.splashscreen)
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
    // Event channel (RFP event-channel Phase 1): deep-background queue drain.
    implementation(libs.androidx.work.runtime.ktx)
}
