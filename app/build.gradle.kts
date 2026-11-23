plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.ninkinc.stickdroid"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ninkinc.stickdroid"
        minSdk = 31
        targetSdk = 35
        // Pixel 6a is arm64; x86_64 kept for an emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = 17
        versionName = "0.3.9-methodc"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR: CameraX + ZXing core (Apache-2.0). No ML Kit, no Play services.
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.zxing:core:3.5.3")
    // WebRTC DataChannel for StickLink Method C: prebuilt libwebrtc (BSD), no Play services.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Built-in offline speech-to-text (0.2.4): Vosk/Kaldi, Apache-2.0. JNA is
    // dual LGPL-2.1/Apache-2.0; used under Apache-2.0. Model (assets) is
    // vosk-model-small-en-us-0.15, Apache-2.0; see scripts/fetch-vosk-model.sh.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
// The bundled speech model is fetched (checksum-pinned) at build time, not committed.
val checkVoskModel by tasks.registering {
    val marker = file("src/main/assets/vosk-model-small-en-us-0.15/am/final.mdl")
    doLast {
        if (!marker.exists()) throw GradleException("Speech model missing. Run scripts/fetch-vosk-model.sh first.")
    }
}
tasks.named("preBuild") { dependsOn(checkVoskModel) }
