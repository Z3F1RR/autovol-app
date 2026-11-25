plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same signing key as AutoVol when the secrets are present, so CI builds install over each other.
val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val keyAliasName = providers.environmentVariable("KEY_ALIAS").orNull
val hasSigningKey = keystoreFile != null && file(keystoreFile).exists() &&
    keystorePassword != null && keyAliasName != null
val ciRun = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull

android {
    namespace = "io.github.z3f1rr.pdm"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.z3f1rr.pdm"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0." + (ciRun ?: "local")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("ru")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
