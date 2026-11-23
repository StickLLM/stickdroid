package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageShrinkTests {
    @Test fun doesNotResizeAlreadySmall() {
        assertFalse(ImageShrink.shouldResize(800, 600))
        assertFalse(ImageShrink.shouldResize(1600, 900))
        assertEquals(1600 to 900, ImageShrink.targetSize(1600, 900))
    }

    @Test fun resizesLongSideTo1600() {
        assertTrue(ImageShrink.shouldResize(4000, 3000))
        assertEquals(1600 to 1200, ImageShrink.targetSize(4000, 3000))
        assertTrue(ImageShrink.shouldResize(1200, 2400))
        assertEquals(800 to 1600, ImageShrink.targetSize(1200, 2400))
    }

    @Test fun jpegNameKeepsOrSwapsExtension() {
        assertEquals("photo.jpg", ImageShrink.jpegName("photo.jpg"))
        assertEquals("shot.jpeg", ImageShrink.jpegName("shot.jpeg"))
        assertEquals("crop.jpg", ImageShrink.jpegName("crop.png"))
        assertEquals("39.jpg", ImageShrink.jpegName("39.PNG"))
    }

    @Test fun imageNameDetection() {
        assertTrue(ImageShrink.isImageName("39.jpg"))
        assertTrue(ImageShrink.isImageName("a.WEBP"))
        assertFalse(ImageShrink.isImageName("notes.pdf"))
        assertTrue(ImageShrink.isImageMime("image/jpeg"))
        assertFalse(ImageShrink.isImageMime("application/pdf"))
    }
}
