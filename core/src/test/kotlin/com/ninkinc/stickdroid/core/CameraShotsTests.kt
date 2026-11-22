package com.ninkinc.stickdroid.core

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class CameraShotsTests {
    private fun dir(): File = Files.createTempDirectory("cam").toFile()
    private fun shot(d: File, name: String, bytes: Int, mtime: Long) =
        File(d, name).apply { writeBytes(ByteArray(bytes)); setLastModified(mtime) }

    @Test fun picksNewestNonEmptyRecentJpg() {
        val d = dir(); val now = 1_800_000_000_000L
        shot(d, "photo-a.jpg", 3_145_526, now - 60_000)
        val b = shot(d, "photo-b.jpg", 1000, now - 10_000)
        shot(d, "photo-c.jpg", 0, now - 1_000)   // camera opened, nothing written
        shot(d, "x.tmp", 10, now)
        assertEquals(b.name, CameraShots.newest(d, now)?.name)
    }

    @Test fun ignoresOldAndEmptyAndMissingDir() {
        val d = dir(); val now = 1_800_000_000_000L
        shot(d, "old.jpg", 100, now - CameraShots.MAX_AGE_MS - 1)
        shot(d, "empty.jpg", 0, now)
        assertNull(CameraShots.newest(d, now))
        assertNull(CameraShots.newest(File(d, "nope"), now))
    }
}
