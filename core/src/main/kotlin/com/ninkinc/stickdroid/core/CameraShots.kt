package com.ninkinc.stickdroid.core

import java.io.File

/** Finds a camera shot left in the app's camera cache dir (see ChatViewModel.onCameraResult / recoverCameraShot). */
object CameraShots {
    /** Only recent shots are recovered, so an old leftover never turns up in a later message. */
    const val MAX_AGE_MS = 15 * 60 * 1000L

    fun newest(dir: File, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) }
            ?.filter { it.length() > 0L && nowMs - it.lastModified() in 0..maxAgeMs }
            ?.maxByOrNull { it.lastModified() }
}
