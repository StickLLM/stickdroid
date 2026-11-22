plugins {
    kotlin("jvm")
    application
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // JVM WebRTC (libwebrtc, BSD) for the box harness only; the app uses an Android AAR.
    implementation("dev.onvoid.webrtc:webrtc-java:1.0.1")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:1.0.1:linux-x86_64")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
application {
    mainClass.set("com.ninkinc.stickdroid.interop.MainKt")
}
