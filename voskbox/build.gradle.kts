// Box-only harness: runs the bundled Vosk model through core's UtteranceLoop
// (same endpointing as the phone) on a WAV file. Not part of the APK.
plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("com.alphacephei:vosk:0.3.45") // Apache-2.0, desktop natives
    implementation("net.java.dev.jna:jna:5.13.0")
}
application { mainClass.set("com.ninkinc.stickdroid.voskbox.MainKt") }
