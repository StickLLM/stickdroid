package com.ninkinc.stickdroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.ninkinc.stickdroid.ui.AppNav

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val camGranted = mutableStateOf(false)
    private val micGranted = mutableStateOf(false)
    private val reqCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        camGranted.value = it
    }
    private val reqMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted.value = it
        vm.onMicPermissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 is drawn edge-to-edge on Android 15+ (and 16/17 have no opt-out);
        // the Compose tree pads with safeDrawing insets (status/nav bars, cutout, IME).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        camGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        micGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted.value) vm.onMicPermissionResult(true)
        val palette = darkColorScheme(
            primary = Color(0xFFC8F0A8),
            background = Color(0xFF121510),
            surface = Color(0xFF1A1F16),
            onPrimary = Color(0xFF1A1F16),
            onBackground = Color(0xFFE8EDE3),
            onSurface = Color(0xFFE8EDE3)
        )
        setContent {
            MaterialTheme(colorScheme = palette) {
                Surface(Modifier.fillMaxSize()) {
                    AppNav(
                        vm = vm,
                        cameraGranted = camGranted.value,
                        onRequestCamera = { reqCam.launch(Manifest.permission.CAMERA) },
                        micGranted = micGranted.value,
                        onRequestMic = { reqMic.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenAppSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        micGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        vm.onAppForeground(true)
        if (micGranted.value) vm.syncMicPermission(true)
    }

    override fun onStop() {
        vm.onAppForeground(false)
        super.onStop()
    }
}
