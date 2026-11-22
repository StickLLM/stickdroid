package com.ninkinc.stickdroid

import android.content.Context
import android.util.Log
import java.io.File

/**
 * What StickDroid keeps on the phone (0.3.4 rule: no user data on the phone; chats, history and
 * the message queue live on the stick / Agent Zero):
 *
 *  - noBackupFilesDir/pairing.bin, resume.bin: pairing keys / resume token, AES-GCM with an
 *    Android Keystore key (SecureStore).
 *  - shared_prefs/stickdroid.xml: app settings only (hosts, toggles, speech options).
 *  - filesDir/vosk/<model>: the built-in speech model unpacked from the APK (no audio is written;
 *    the mic is read into memory and fed to the recognizer).
 *  - cacheDir/camera/photo-*.jpg: a camera shot only while it is being taken; read into memory and
 *    deleted right after. Leftovers older than [CAMERA_MAX_AGE_MS] are deleted at start; newer
 *    ones are offered once (camera result that never arrived).
 *
 * Earlier versions (≤ 0.3.3) never wrote chats, replies or the queue to disk, so there is nothing
 * to migrate; [cleanUp] still removes anything under the names a chat cache could have used.
 */
object PhoneData {
    const val CAMERA_MAX_AGE_MS = 15 * 60 * 1000L
    private val LEGACY = listOf("chats", "history", "transcripts", "queue", "outbox")

    fun cleanUp(ctx: Context, now: Long = System.currentTimeMillis()) {
        var n = 0
        File(ctx.cacheDir, "camera").listFiles()?.forEach { f ->
            if (now - f.lastModified() > CAMERA_MAX_AGE_MS) { if (f.delete()) n++ }
        }
        for (name in LEGACY) {
            for (dir in listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)) {
                val f = File(dir, name)
                if (f.exists() && f.deleteRecursively()) n++
            }
        }
        for (db in ctx.databaseList()) if (ctx.deleteDatabase(db)) n++
        if (n > 0) Log.i("StickDroid", "phone data cleanup removed $n item(s)")
    }
}
