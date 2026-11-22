package com.ninkinc.stickdroid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ninkinc.stickdroid.core.PairingState
import com.ninkinc.stickdroid.core.ReadAloudMode
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * pair_psk and the phone static key are encrypted with an Android Keystore
 * AES-GCM key and stored under noBackupFilesDir (and the app sets allowBackup=false).
 */
class SecureStore(private val ctx: Context) {
    private val file = File(ctx.noBackupFilesDir, "pairing.bin")
    private val prefs = ctx.getSharedPreferences("stickdroid", Context.MODE_PRIVATE)

    var signallingWs: String
        get() = prefs.getString("ws", DEFAULT_WS) ?: DEFAULT_WS
        set(v) { prefs.edit().putString("ws", v).apply() }

    var derpUrl: String
        get() = prefs.getString("derp", DEFAULT_DERP) ?: DEFAULT_DERP
        set(v) { prefs.edit().putString("derp", v).apply() }

    /** Debug builds only: accept loopback/emulator join hosts (box lab). Off by default. */
    var labHosts: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean("lab_hosts", false)
        set(v) { prefs.edit().putBoolean("lab_hosts", v && BuildConfig.DEBUG).apply() }

    /** When off (default), hide Agent Zero system-detail dumps in chat. */
    var showSystemDetails: Boolean
        get() = prefs.getBoolean("show_system_details", false)
        set(v) { prefs.edit().putBoolean("show_system_details", v).apply() }

    var showSteps: Boolean
        get() = prefs.getBoolean("show_a0_steps", false)
        set(v) { prefs.edit().putBoolean("show_a0_steps", v).apply() }

    /** Send the transcript automatically when the user stops talking. Default on. */
    var autoSendSpeech: Boolean
        get() = prefs.getBoolean("speech_auto_send", true)
        set(v) { prefs.edit().putBoolean("speech_auto_send", v).apply() }

    /** When to read Agent Zero replies with the phone's TTS. Default: after a spoken message. */
    var readAloud: ReadAloudMode
        get() = when (prefs.getString("speech_read_aloud", "after")) {
            "always" -> ReadAloudMode.Always
            "never" -> ReadAloudMode.Never
            else -> ReadAloudMode.AfterSpeech
        }
        set(v) {
            val key = when (v) {
                ReadAloudMode.Always -> "always"
                ReadAloudMode.Never -> "never"
                ReadAloudMode.AfterSpeech -> "after"
            }
            prefs.edit().putString("speech_read_aloud", key).apply()
        }

    /** Speech-to-text engine. Default: built-in (offline Vosk, works on GrapheneOS). */
    var speechEngine: com.ninkinc.stickdroid.core.RecognizerPref
        get() = when (prefs.getString("speech_engine", "builtin")) {
            "system" -> com.ninkinc.stickdroid.core.RecognizerPref.System
            else -> com.ninkinc.stickdroid.core.RecognizerPref.BuiltIn
        }
        set(v) {
            val key = if (v == com.ninkinc.stickdroid.core.RecognizerPref.System) "system" else "builtin"
            prefs.edit().putString("speech_engine", key).apply()
        }

    /** After TTS (or a skipped reading), listen again. Off by default. */
    var handsFree: Boolean
        get() = prefs.getBoolean("speech_hands_free", false)
        set(v) { prefs.edit().putBoolean("speech_hands_free", v).apply() }

    fun hasPairing(): Boolean = file.exists() && file.length() > 12

    fun load(): PairingState? {
        if (!hasPairing()) return null
        val blob = file.readBytes()
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return PairingState.fromPeerJson(String(c.doFinal(ct), Charsets.UTF_8))
    }

    fun save(state: PairingState) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(state.toPeerJson().toByteArray(Charsets.UTF_8))
        val out = c.iv + ct
        val tmp = File(file.parentFile, "pairing.bin.tmp")
        tmp.writeBytes(out)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "pairing.bin.tmp").delete()
    }

    // ---- Always-on resume pins (same Keystore AES-GCM key as pairing.bin) ----
    private val resumeFile = File(ctx.noBackupFilesDir, "resume.bin")

    /** [AlwaysOnStore]: token + Noise pins; survives app kill; not backed up. */
    val resumeStore: com.ninkinc.stickdroid.core.AlwaysOnStore = object : com.ninkinc.stickdroid.core.AlwaysOnStore {
        override fun load(): com.ninkinc.stickdroid.core.AlwaysOnResume? = synchronized(resumeFile) {
            if (!resumeFile.exists() || resumeFile.length() <= 12) return null
            return try {
                val blob = resumeFile.readBytes()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                val raw = String(c.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
                com.ninkinc.stickdroid.core.AlwaysOnCodec.decode(raw)
                    ?: null // v1 token-only blob: cannot WSS-resume; treat as unpaired
            } catch (_: Exception) {
                null
            }
        }

        override fun save(info: com.ninkinc.stickdroid.core.AlwaysOnResume) = synchronized(resumeFile) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(com.ninkinc.stickdroid.core.AlwaysOnCodec.encode(info).toByteArray(Charsets.UTF_8))
            val tmp = File(resumeFile.parentFile, "resume.bin.tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(resumeFile)) { resumeFile.writeBytes(out); tmp.delete() }
        }

        override fun clear() = synchronized(resumeFile) {
            resumeFile.delete()
            File(resumeFile.parentFile, "resume.bin.tmp").delete()
            Unit
        }
    }

    fun isStrongBoxBacked(): Boolean? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val e = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
            val kf = javax.crypto.SecretKeyFactory.getInstance(e.secretKey.algorithm, "AndroidKeyStore")
            val info = kf.getKeySpec(e.secretKey, android.security.keystore.KeyInfo::class.java)
            val m = info.javaClass.methods.firstOrNull { it.name == "isStrongBoxBacked" }
                ?: info.javaClass.methods.firstOrNull { it.name == "isInsideSecureHardware" }
            m?.invoke(info) as? Boolean
        } catch (_: Exception) { null }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(ALIAS)) {
            return (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        try { builder.setIsStrongBoxBacked(true) } catch (_: Exception) {}
        try {
            gen.init(builder.build())
            return gen.generateKey()
        } catch (_: Exception) {
            // StrongBox may be unavailable (emulator, some Graphene builds): retry without it.
            val b2 = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            gen.init(b2.build())
            return gen.generateKey()
        }
    }

    companion object {
        private const val ALIAS = "stickdroid.pairing.aes"
        const val DEFAULT_WS = "wss://link.stickllm.com/v1/ws"
        const val DEFAULT_DERP = "https://relay-tor1.stickllm.com/derp"
    }
}
