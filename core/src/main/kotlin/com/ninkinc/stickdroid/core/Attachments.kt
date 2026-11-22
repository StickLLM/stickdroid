package com.ninkinc.stickdroid.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** One file the user attached to a message. [bytes] stay in memory (capped by [AttachmentRules]). */
class Attachment(val name: String, val mime: String, val bytes: ByteArray) {
    val size: Int get() = bytes.size
    val isImage: Boolean get() = mime.startsWith("image/")
    override fun toString() = "Attachment(name=$name, mime=$mime, size=$size)"
}

/**
 * multipart/form-data exactly like the Agent Zero web UI's `FormData` for
 * POST /api/message_async (webui/index.js L142-155 @ ba9b67df):
 *   text, context, message_id, then one `attachments` part per file (with filename).
 * A0 reads it in api/message.py L30-50 (request.form + request.files.getlist("attachments")).
 */
object MultipartForm {
    class Built(val contentType: String, val body: ByteArray, val boundary: String)

    private val rnd = SecureRandom()

    fun newBoundary(): String {
        val b = ByteArray(16); rnd.nextBytes(b)
        return "----StickDroidFormBoundary" + b.joinToString("") { "%02x".format(it) }
    }

    /**
     * Browser-style escaping for a quoted header param (WHATWG multipart/form-data
     * encoding: `"` → %22, CR → %0D, LF → %0A; other UTF-8 passes through).
     */
    fun quoteParam(s: String): String =
        s.replace("\r", "%0D").replace("\n", "%0A").replace("\"", "%22")

    /** Last path segment only (no directories), never empty. */
    fun cleanFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.ifEmpty { "file" }
    }

    fun build(fields: List<Pair<String, String>>, files: List<Attachment>, fileField: String = "attachments",
              boundary: String = newBoundary()): Built {
        val out = ByteArrayOutputStream(files.sumOf { it.size } + 1024)
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"${quoteParam(k)}\"\r\n\r\n")
            w(v); w("\r\n")
        }
        for (f in files) {
            w("--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"${quoteParam(fileField)}\"; filename=\"${quoteParam(cleanFilename(f.name))}\"\r\n")
            w("Content-Type: ${f.mime.ifBlank { "application/octet-stream" }}\r\n\r\n")
            out.write(f.bytes); w("\r\n")
        }
        w("--$boundary--\r\n")
        return Built("multipart/form-data; boundary=$boundary", out.toByteArray(), boundary)
    }

    /** Exact multipart size without building it (for limit checks). */
    fun estimate(fields: List<Pair<String, String>>, files: List<Pair<String, Int>>): Long {
        val bl = 2 + newBoundary().length
        var n = 0L
        for ((k, v) in fields) n += bl + 2 + "Content-Disposition: form-data; name=\"\"\r\n\r\n".length + quoteParam(k).toByteArray().size + v.toByteArray().size + 2
        for ((name, size) in files) n += bl + 2 + 200 + quoteParam(cleanFilename(name)).toByteArray().size + size + 2
        return n + bl + 4
    }
}

/**
 * Limits for attachments over StickLink. The binding one is the stick's app-mux
 * bridge: a request body over 8 MiB is refused with 413 (app_mux_bridge.py
 * MAX_BODY = 8 * 1024 * 1024, checked in _http_chunk_req). Agent Zero itself
 * accepts up to 5 GiB (helpers/ui_server.py UPLOAD_LIMIT_BYTES), so the whole
 * multipart body (text + all files + headers) must fit in 8 MiB.
 */
object AttachmentRules {
    const val BRIDGE_MAX_BODY: Long = 8L * 1024 * 1024
    /** Room for text, form fields and part headers. */
    const val OVERHEAD_RESERVE: Long = 64L * 1024
    const val MAX_TOTAL: Long = BRIDGE_MAX_BODY - OVERHEAD_RESERVE
    /** Per file: the app's nominal 20 MB cap, but never more than what fits through the bridge. */
    const val NOMINAL_PER_FILE: Long = 20L * 1000 * 1000
    val MAX_PER_FILE: Long = minOf(NOMINAL_PER_FILE, MAX_TOTAL)
    const val MAX_FILES = 10

    fun mb(n: Long): String = "%.1f MB".format(n / 1_000_000.0)

    /** null = OK to add a file of [size] bytes to [current] sizes; else a user-facing error. */
    fun checkAdd(current: List<Long>, name: String, size: Long): String? = when {
        current.size >= MAX_FILES -> "You can attach up to $MAX_FILES files per message."
        size > MAX_PER_FILE ->
            "\"$name\" is ${mb(size)}. StickLink can carry up to ${mb(MAX_PER_FILE)} per message right now (the stick's link limit is 8 MiB per request)."
        current.sum() + size > MAX_TOTAL ->
            "\"$name\" would bring this message to ${mb(current.sum() + size)}. The limit over StickLink is ${mb(MAX_TOTAL)} per message; send it in a separate message."
        else -> null
    }

    fun humanSize(n: Long): String = when {
        n < 1000 -> "$n B"
        n < 1_000_000 -> "%.0f KB".format(n / 1000.0)
        else -> mb(n)
    }
}
