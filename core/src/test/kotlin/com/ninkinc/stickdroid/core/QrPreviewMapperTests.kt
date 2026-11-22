package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class QrPreviewMapperTests {
    private fun almost(a: Float, b: Float, eps: Float = 0.01f) =
        assertTrue(abs(a - b) < eps, "expected $b but was $a")

    @Test fun identityNoRotationSameAspect() {
        val pts = listOf(QrPreviewMapper.Pt(0f, 0f), QrPreviewMapper.Pt(100f, 80f), QrPreviewMapper.Pt(50f, 40f))
        val m = QrPreviewMapper.mapToPreview(pts, 100, 80, 0, 100f, 80f, padFraction = 0f)!!
        almost(m.points[0].x, 0f); almost(m.points[0].y, 0f)
        almost(m.points[1].x, 100f); almost(m.points[1].y, 80f)
        almost(m.points[2].x, 50f); almost(m.points[2].y, 40f)
        almost(m.left, 0f); almost(m.top, 0f)
        almost(m.right, 100f); almost(m.bottom, 80f)
    }

    @Test fun rotate90Clockwise() {
        // Landscape 200x100, top-right (200, 0) -> after 90° CW, portrait 100x200, bottom-right (100, 200).
        val r = QrPreviewMapper.rotatePoint(200f, 0f, 200, 100, 90)
        almost(r.x, 100f); almost(r.y, 200f)
        val tl = QrPreviewMapper.rotatePoint(0f, 0f, 200, 100, 90)
        almost(tl.x, 100f); almost(tl.y, 0f)
        assertEquals(100 to 200, QrPreviewMapper.rotatedSize(200, 100, 90))
        // Rotated frame matches a portrait preview 1:1.
        val m = QrPreviewMapper.mapToPreview(
            listOf(QrPreviewMapper.Pt(0f, 0f), QrPreviewMapper.Pt(200f, 0f)),
            200, 100, 90, 100f, 200f, padFraction = 0f
        )!!
        almost(m.points[0].x, 100f); almost(m.points[0].y, 0f)
        almost(m.points[1].x, 100f); almost(m.points[1].y, 200f)
    }

    @Test fun rotate180And270() {
        val p180 = QrPreviewMapper.rotatePoint(10f, 20f, 200, 100, 180)
        almost(p180.x, 190f); almost(p180.y, 80f)
        val p270 = QrPreviewMapper.rotatePoint(0f, 0f, 200, 100, 270)
        almost(p270.x, 0f); almost(p270.y, 200f)
        assertEquals(100 to 200, QrPreviewMapper.rotatedSize(200, 100, 270))
    }

    @Test fun fillCenterCropsWiderImage() {
        // 200x100 image into a 100x100 preview, no rotation: scale=1, x offset -50.
        val m = QrPreviewMapper.mapToPreview(
            listOf(QrPreviewMapper.Pt(50f, 0f), QrPreviewMapper.Pt(150f, 100f)),
            200, 100, 0, 100f, 100f, padFraction = 0f
        )!!
        almost(m.points[0].x, 0f); almost(m.points[0].y, 0f)
        almost(m.points[1].x, 100f); almost(m.points[1].y, 100f)
    }

    @Test fun portraitPhoneWithLandscapeSensor() {
        // Typical Pixel analysis frame 1280x720, rotation 90, preview 1080x1920 (same 9:16 after rotate).
        // After 90°: 720x1280. scale = max(1080/720, 1920/1280) = 1.5. No crop.
        val m = QrPreviewMapper.mapToPreview(
            listOf(QrPreviewMapper.Pt(0f, 0f), QrPreviewMapper.Pt(1280f, 720f)),
            1280, 720, 90, 1080f, 1920f, padFraction = 0f
        )!!
        // (0,0) -> rot (720, 0) -> *1.5 = (1080, 0)
        almost(m.points[0].x, 1080f); almost(m.points[0].y, 0f)
        // (1280, 720) -> rot (0, 1280) -> *1.5 = (0, 1920)
        almost(m.points[1].x, 0f); almost(m.points[1].y, 1920f)
    }

    @Test fun paddedRowStrideIsNotTheImageWidth() {
        // ImageProxy.width=100, rowStride=112. Points are in crop space (0..100).
        // Mapping with the padded stride as width would shift the result.
        val pt = listOf(QrPreviewMapper.Pt(50f, 40f))
        val correct = QrPreviewMapper.mapToPreview(pt, 100, 80, 0, 200f, 160f, padFraction = 0f)!!
        val wrong = QrPreviewMapper.mapToPreview(pt, 112, 80, 0, 200f, 160f, padFraction = 0f)!!
        almost(correct.points[0].x, 100f)
        almost(correct.points[0].y, 80f)
        assertNotEquals(correct.points[0].x, wrong.points[0].x)
    }

    @Test fun paddingExpandsFinderBboxAndClamps() {
        val m = QrPreviewMapper.mapToPreview(
            listOf(QrPreviewMapper.Pt(40f, 40f), QrPreviewMapper.Pt(60f, 60f)),
            100, 100, 0, 100f, 100f, padFraction = 0.25f
        )!!
        // raw box 40..60, pad 5 on each side -> 35..65
        almost(m.left, 35f); almost(m.top, 35f)
        almost(m.right, 65f); almost(m.bottom, 65f)
        val edge = QrPreviewMapper.mapToPreview(
            listOf(QrPreviewMapper.Pt(0f, 0f), QrPreviewMapper.Pt(10f, 10f)),
            100, 100, 0, 100f, 100f, padFraction = 0.5f
        )!!
        almost(edge.left, 0f); almost(edge.top, 0f)
    }

    @Test fun rejectsEmptyOrZeroSizes() {
        assertNull(QrPreviewMapper.mapToPreview(emptyList(), 100, 100, 0, 100f, 100f))
        assertNull(QrPreviewMapper.mapToPreview(listOf(QrPreviewMapper.Pt(1f, 1f)), 0, 100, 0, 100f, 100f))
        assertNull(QrPreviewMapper.mapToPreview(listOf(QrPreviewMapper.Pt(1f, 1f)), 100, 100, 0, 0f, 100f))
        assertNotNull(QrPreviewMapper.mapToPreview(listOf(QrPreviewMapper.Pt(1f, 1f)), 100, 100, 0, 100f, 100f))
    }

    @Test fun zoomDoesNotChangeMappingOnceFrameIsCropped() {
        // CameraX zoom crops Preview and ImageAnalysis together. ResultPoints
        // are already in the zoomed image; the mapper must not apply a second
        // zoom scale (a Compose-scale overlay would misplace the tracking box).
        val pts = listOf(QrPreviewMapper.Pt(40f, 20f), QrPreviewMapper.Pt(80f, 60f))
        val a = QrPreviewMapper.mapToPreview(pts, 200, 100, 0, 400f, 200f, padFraction = 0f)!!
        val b = QrPreviewMapper.mapToPreview(pts, 200, 100, 0, 400f, 200f, padFraction = 0f)!!
        almost(a.points[0].x, b.points[0].x)
        almost(a.points[0].y, b.points[0].y)
        almost(a.left, 80f); almost(a.top, 40f)
    }
}

class ScanQrTests {
    private val now = 1_800_000_000L

    private fun vec(key: String): String {
        val v = kotlinx.serialization.json.Json.parseToJsonElement(
            javaClass.classLoader.getResource("methodc-stick-v1.json")!!.readText()
        ) as kotlinx.serialization.json.JsonObject
        return (v[key] as kotlinx.serialization.json.JsonPrimitive).content
    }

    @Test fun validJoinUrl() {
        val url = vec("join_url")
        val qr = vec("qr")
        assertEquals(ScanQr.Kind.VALID, ScanQr.classify(url, nowUnix = now).kind)
        assertEquals(ScanQr.GOT_IT, ScanQr.classify(url, nowUnix = now).message)
        assertEquals(ScanQr.Kind.VALID, ScanQr.classify(qr, nowUnix = now).kind)
    }

    @Test fun expiredAndNotStickLink() {
        val url = vec("join_url")
        val exp = ScanQr.classify(url, nowUnix = 1_950_000_000L)
        assertEquals(ScanQr.Kind.EXPIRED, exp.kind)
        assertEquals(ScanQr.EXPIRED, exp.message)
        val bad = ScanQr.classify("https://example.com/foo")
        assertEquals(ScanQr.Kind.NOT_STICKLINK, bad.kind)
        assertEquals(ScanQr.NOT_STICKLINK, bad.message)
        assertEquals(ScanQr.Kind.NOT_STICKLINK, ScanQr.classify("not a qr").kind)
        val lab = "http://127.0.0.1:8770/browser/agent_join.html?ws=ws%3A%2F%2F127.0.0.1%3A8800%2Fv1%2Fws&qr=" +
            java.net.URLEncoder.encode(vec("qr"), "UTF-8")
        assertEquals(ScanQr.Kind.NOT_STICKLINK, ScanQr.classify(lab, allowDebugHosts = false, nowUnix = now).kind)
        assertEquals(ScanQr.Kind.VALID, ScanQr.classify(lab, allowDebugHosts = true, nowUnix = now).kind)
    }
}
