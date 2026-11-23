package com.ninkinc.stickdroid.core

import kotlin.math.max

/**
 * Policy for shrinking photos before they go over StickLink.
 * Encoding is done on the Android side ([ChatViewModel]); this object is
 * the shared size math, so it can be unit-tested on the JVM.
 */
object ImageShrink {
    const val MAX_LONG_SIDE = 1600
    const val JPEG_QUALITY = 85
    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS

    fun isImageMime(mime: String): Boolean = mime.startsWith("image/")

    fun shouldResize(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && max(width, height) > MAX_LONG_SIDE

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MAX_LONG_SIDE || long <= 0) return width to height
        val s = MAX_LONG_SIDE.toDouble() / long
        return max(1, (width * s).toInt()) to max(1, (height * s).toInt())
    }

    /** JPEG name after a transcode (keeps .jpg / .jpeg, otherwise swaps the extension). */
    fun jpegName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "jpg" || ext == "jpeg") return name.ifBlank { "image.jpg" }
        val base = name.substringBeforeLast('.', name).ifBlank { "image" }
        return "$base.jpg"
    }
}
