plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from environment variables (GitHub Actions secrets).
// The same key signs debug builds when available, so CI builds install over each other.
val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val keyAliasName = providers.environmentVariable("KEY_ALIAS").orNull
val hasSigningKey = keystoreFile != null && file(keystoreFile).exists() &&
    keystorePassword != null && keyAliasName != null

// Monotonic version code on CI; tag v1.2.3 -> versionName 1.2.3.
val ciRun = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val refName = providers.environmentVariable("GITHUB_REF_NAME").orNull
val tagVersion = refName?.takeIf { providers.environmentVariable("GITHUB_REF_TYPE").orNull == "tag" }
    ?.removePrefix("v")

android {
    namespace = "io.github.z3f1rr.autovol"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.z3f1rr.autovol"
        minSdk = 29
        targetSdk = 36
        versionCode = ciRun ?: 1
        versionName = tagVersion ?: "0.1.0" + (ciRun?.let { "-dev.$it" } ?: "-local")
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull ?: keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // F-Droid: no dependency metadata blob signed by Google.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
