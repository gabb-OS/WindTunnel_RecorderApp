plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lom.lom_windtunnelrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lom.lom_windtunnelrecorder"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // Kotlin extensions for core Android functionalities.
    implementation(libs.androidx.core.ktx)
    // Backward compatibility for modern Android features (e.g., AppCompatActivity).
    implementation(libs.androidx.appcompat)
    // Material Design UI components (Buttons, Cards, etc.).
    implementation(libs.material)
    // Flexible layout manager for complex UIs.
    implementation(libs.androidx.constraintlayout)
    // Lifecycle-aware observable data holder (used in ViewModels).
    implementation(libs.androidx.lifecycle.livedata.ktx)
    // Core ViewModel class for UI-related data management (used for your ViewModels).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Fragment navigation library (NavController, NavHostFragment).
    implementation(libs.androidx.navigation.fragment.ktx)
    // UI utilities for Navigation component (e.g., ActionBar/Toolbar integration).
    implementation(libs.androidx.navigation.ui.ktx)

    // --- CameraX dependencies for camera usage ---
    // Core CameraX APIs and building blocks.
    implementation(libs.androidx.camera.core)
    // CameraX implementation using the Camera2 API backend.
    implementation(libs.androidx.camera.camera2)
    // CameraX module for video capture (VideoCapture use case).
    implementation(libs.androidx.camera.video)
    // CameraX integration with Android Lifecycle components.
    implementation(libs.androidx.camera.lifecycle)
    // CameraX PreviewView widget for displaying camera preview.
    implementation(libs.androidx.camera.view)

    // --- Testing Libraries ---
    // JUnit 4 for unit testing.
    testImplementation(libs.junit)
    // AndroidX extensions for JUnit for Android-specific unit tests.
    androidTestImplementation(libs.androidx.junit)
    // Espresso for UI testing on devices/emulators.
    androidTestImplementation(libs.androidx.espresso.core)

    // --- Image Loading Library ---
    // Efficient image loading and caching library (used for video thumbnails).
    implementation(libs.glide)
}