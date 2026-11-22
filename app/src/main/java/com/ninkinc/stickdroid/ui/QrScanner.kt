package com.ninkinc.stickdroid.ui

import android.os.Build
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as CSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.ninkinc.stickdroid.core.QrPreviewMapper
import com.ninkinc.stickdroid.core.ScanQr
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

private data class RawQr(
    val text: String,
    val points: List<QrPreviewMapper.Pt>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int
)

@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    allowDebugHosts: Boolean = false,
    onValid: (String) -> Unit
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val locked = remember { AtomicBoolean(false) }
    var raw by remember { mutableStateOf<RawQr?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var hasTorch by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hint by remember { mutableStateOf("Point at the StickLink QR on the kiosk") }
    var boxColor by remember { mutableStateOf(Color.White) }
    var accepted by remember { mutableStateOf(false) }
    var lastSeen by remember { mutableStateOf(0L) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var minZoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(1f) }
    val camRef = remember { AtomicReference<Camera?>(null) }
    val zoomRef = remember { AtomicReference(floatArrayOf(1f, 1f, 1f)) } // min, max, current

    val verdict = raw?.let { ScanQr.classify(it.text, allowDebugHosts = allowDebugHosts) }
    val mapped = raw?.let { det ->
        QrPreviewMapper.mapToPreview(
            det.points,
            det.imageWidth,
            det.imageHeight,
            det.rotationDegrees,
            viewSize.width.toFloat(),
            viewSize.height.toFloat()
        )
    }

    LaunchedEffect(lastSeen, accepted) {
        if (accepted) return@LaunchedEffect
        delay(700)
        if (!locked.get() && raw != null && System.currentTimeMillis() - lastSeen >= 650) {
            raw = null
            boxColor = Color.White
            hint = "Point at the StickLink QR on the kiosk"
        }
    }

    LaunchedEffect(verdict?.kind, raw?.text) {
        val v = verdict ?: return@LaunchedEffect
        when (v.kind) {
            ScanQr.Kind.VALID -> {
                if (accepted || !locked.compareAndSet(false, true)) return@LaunchedEffect
                boxColor = Color(0xFF22C55E)
                hint = v.message
                tick(view)
                delay(300)
                accepted = true
                onValid(raw!!.text)
            }
            ScanQr.Kind.EXPIRED -> {
                if (locked.get()) return@LaunchedEffect
                boxColor = Color(0xFFF59E0B)
                hint = v.message
            }
            ScanQr.Kind.NOT_STICKLINK -> {
                if (locked.get()) return@LaunchedEffect
                boxColor = Color(0xFFEF4444)
                hint = v.message
            }
        }
    }

    DisposableEffect(owner) {
        val exec = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = MultiFormatReader().apply {
                setHints(mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                ))
            }
            analysis.setAnalyzer(exec) { img ->
                if (locked.get()) { img.close(); return@setAnalyzer }
                try {
                    val plane = img.planes[0]
                    val y = plane.buffer
                    val data = ByteArray(y.remaining())
                    y.get(data)
                    // Y rows can be padded (rowStride > width) on Pixel cameras.
                    val stride = plane.rowStride
                    val rows = data.size / stride
                    val srcW = img.width
                    val srcH = minOf(img.height, rows)
                    val src = PlanarYUVLuminanceSource(data, stride, rows, 0, 0, srcW, srcH, false)
                    val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
                    reader.reset()
                    val pts = result.resultPoints?.mapNotNull {
                        if (it == null) null else QrPreviewMapper.Pt(it.x, it.y)
                    } ?: emptyList()
                    val det = RawQr(
                        text = result.text ?: return@setAnalyzer,
                        points = pts,
                        imageWidth = srcW,
                        imageHeight = srcH,
                        rotationDegrees = img.imageInfo.rotationDegrees
                    )
                    ContextCompat.getMainExecutor(ctx).execute {
                        if (!locked.get()) {
                            raw = det
                            lastSeen = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {
                    reader.reset()
                } finally {
                    img.close()
                }
            }
            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                camRef.set(cam)
                hasTorch = cam.cameraInfo.hasFlashUnit()
                if (hasTorch) cam.cameraControl.enableTorch(torchOn)
                cam.cameraInfo.zoomState.observe(owner) { z ->
                    minZoom = z.minZoomRatio
                    maxZoom = z.maxZoomRatio
                    zoomRatio = z.zoomRatio
                    zoomRef.set(floatArrayOf(z.minZoomRatio, z.maxZoomRatio, z.zoomRatio))
                }
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            exec.shutdown()
            try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(torchOn, camera, hasTorch) {
        val cam = camera ?: return@LaunchedEffect
        if (hasTorch) {
            try { cam.cameraControl.enableTorch(torchOn) } catch (_: Exception) {}
        }
    }

    Box(
        modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val cam = camRef.get() ?: return@detectTransformGestures
                    val b = zoomRef.get()
                    val next = (b[2] * zoom).coerceIn(b[0], b[1].coerceAtLeast(b[0]))
                    b[2] = next
                    zoomRef.set(b)
                    try { cam.cameraControl.setZoomRatio(next) } catch (_: Exception) {}
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    val cam = camRef.get() ?: return@detectTapGestures
                    val b = zoomRef.get()
                    val next = 1f.coerceIn(b[0], b[1].coerceAtLeast(b[0]))
                    b[2] = next
                    zoomRef.set(b)
                    try { cam.cameraControl.setZoomRatio(next) } catch (_: Exception) {}
                })
            }
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // CameraX zoom crops Preview and ImageAnalysis together, so ZXing points
        // already live in the zoomed frame; the existing mapper is unchanged.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cut = minOf(w, h) * 0.72f
            val left = (w - cut) / 2f
            val top = (h - cut) / 2f
            val dim = Color(0x99000000)
            // Dim the surroundings of the viewfinder, leaving a rounded window.
            drawRect(dim, topLeft = Offset(0f, 0f), size = CSize(w, top))
            drawRect(dim, topLeft = Offset(0f, top + cut), size = CSize(w, h - top - cut))
            drawRect(dim, topLeft = Offset(0f, top), size = CSize(left, cut))
            drawRect(dim, topLeft = Offset(left + cut, top), size = CSize(w - left - cut, cut))

            val bracket = minOf(cut * 0.14f, 72f)
            val stroke = Stroke(width = 6f, cap = StrokeCap.Round)
            val bracketColor = Color.White
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                val path = Path().apply {
                    moveTo(x, y + dy)
                    lineTo(x, y)
                    lineTo(x + dx, y)
                }
                drawPath(path, bracketColor, style = stroke)
            }
            corner(left, top, bracket, bracket)
            corner(left + cut, top, -bracket, bracket)
            corner(left, top + cut, bracket, -bracket)
            corner(left + cut, top + cut, -bracket, -bracket)

            val box = mapped
            if (box != null) {
                val bw = (box.right - box.left).coerceAtLeast(1f)
                val bh = (box.bottom - box.top).coerceAtLeast(1f)
                drawRoundRect(
                    color = boxColor,
                    topLeft = Offset(box.left, box.top),
                    size = CSize(bw, bh),
                    cornerRadius = CornerRadius(18f, 18f),
                    style = Stroke(width = 7f)
                )
            }
        }
        Text(
            text = String.format("%.1fx", zoomRatio),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        )
        if (hasTorch) {
            IconButton(
                onClick = { torchOn = !torchOn },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                    tint = Color.White
                )
            }
        }
        Text(
            text = hint,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

private fun tick(view: View) {
    val code = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.KEYBOARD_TAP
    view.performHapticFeedback(code)
}
