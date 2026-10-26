plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties
import java.io.FileInputStream

val secretsPropertiesFile = rootProject.file("secrets.properties")
val secretsProperties = Properties()
if (secretsPropertiesFile.exists()) {
    FileInputStream(secretsPropertiesFile).use { secretsProperties.load(it) }
}
val mapsApiKey: String = System.getenv("MAPS_API_KEY")
    ?: (if (secretsPropertiesFile.exists()) {
        FileInputStream(secretsPropertiesFile).use { secretsProperties.load(it) }
        secretsProperties.getProperty("MAPS_API_KEY") ?: ""
    } else "")

android {
    namespace = "com.aladdin.dashcam"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    defaultConfig {
        applicationId = "com.aladdin.dashcam"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigning = signingConfigs.create("release") {
        val configuredStorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            ?: secretsProperties.getProperty("RELEASE_KEYSTORE_PATH")
        val configuredStorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            ?: secretsProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
        val configuredKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
            ?: secretsProperties.getProperty("RELEASE_KEY_ALIAS")
        val configuredKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            ?: secretsProperties.getProperty("RELEASE_KEY_PASSWORD")

        if (!configuredStorePath.isNullOrBlank() && file(configuredStorePath).exists()) {
            storeFile = file(configuredStorePath)
            storePassword = configuredStorePassword ?: ""
            keyAlias = configuredKeyAlias ?: ""
            keyPassword = configuredKeyPassword ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Maps SDK for Android
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
