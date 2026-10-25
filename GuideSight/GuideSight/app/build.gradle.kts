import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guidesight.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guidesight.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Downloads the free Google object-detection model automatically before building.
val modelFile = file("src/main/assets/efficientdet_lite0.tflite")
tasks.register("downloadModel") {
    outputs.file(modelFile)
    doLast {
        if (!modelFile.exists() || modelFile.length() < 1_000_000) {
            modelFile.parentFile.mkdirs()
            URL("https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite")
                .openStream().use { input -> modelFile.outputStream().use { input.copyTo(it) } }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("downloadModel") }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
}
