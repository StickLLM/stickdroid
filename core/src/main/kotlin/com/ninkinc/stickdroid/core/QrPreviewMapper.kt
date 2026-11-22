package com.ninkinc.stickdroid.core

import kotlin.math.max

/**
 * Maps ZXing ResultPoints from the cropped luminance-source / ImageProxy
 * coordinate space onto a CameraX PreviewView that uses FILL_CENTER
 * (scale-to-fill, overflow cropped, centered).
 *
 * Image size must be the visible frame (ImageProxy.width × the height
 * passed to PlanarYUVLuminanceSource), **not** the Y-plane rowStride.
 * rotationDegrees is ImageProxy.imageInfo.rotationDegrees: the clockwise
 * rotation that stands the frame up to the current display orientation.
 */
object QrPreviewMapper {
    data class Pt(val x: Float, val y: Float)
    data class Mapped(
        val points: List<Pt>,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    fun rotatePoint(x: Float, y: Float, imageWidth: Int, imageHeight: Int, rotationDegrees: Int): Pt {
        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()
        return when (((rotationDegrees % 360) + 360) % 360) {
            0 -> Pt(x, y)
            90 -> Pt(h - y, x)
            180 -> Pt(w - x, h - y)
            270 -> Pt(y, w - x)
            else -> Pt(x, y)
        }
    }

    fun rotatedSize(imageWidth: Int, imageHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        return if (((rotationDegrees % 180) + 180) % 180 == 90) imageHeight to imageWidth
        else imageWidth to imageHeight
    }

    /**
     * @param padFraction extra margin around the finder-pattern bounding box
     *        (finders sit slightly inside the QR's true corners).
     */
    fun mapToPreview(
        points: List<Pt>,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        previewWidth: Float,
        previewHeight: Float,
        padFraction: Float = 0.12f
    ): Mapped? {
        if (points.isEmpty() || imageWidth <= 0 || imageHeight <= 0 || previewWidth <= 0f || previewHeight <= 0f) {
            return null
        }
        val (rw, rh) = rotatedSize(imageWidth, imageHeight, rotationDegrees)
        val scale = max(previewWidth / rw.toFloat(), previewHeight / rh.toFloat())
        val ox = (previewWidth - rw * scale) / 2f
        val oy = (previewHeight - rh * scale) / 2f
        val mapped = points.map { p ->
            val r = rotatePoint(p.x, p.y, imageWidth, imageHeight, rotationDegrees)
            Pt(r.x * scale + ox, r.y * scale + oy)
        }
        var l = mapped.minOf { it.x }
        var t = mapped.minOf { it.y }
        var rgt = mapped.maxOf { it.x }
        var b = mapped.maxOf { it.y }
        val padX = (rgt - l) * padFraction
        val padY = (b - t) * padFraction
        l = (l - padX).coerceIn(0f, previewWidth)
        t = (t - padY).coerceIn(0f, previewHeight)
        rgt = (rgt + padX).coerceIn(0f, previewWidth)
        b = (b + padY).coerceIn(0f, previewHeight)
        return Mapped(mapped, l, t, rgt, b)
    }
}
