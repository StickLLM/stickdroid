package com.ninkinc.stickdroid.core

/**
 * sl1 Method C QR payload, as minted by the stick (overlay/.../pair_qr.py encode_qr):
 *   sl1:M=<16 hex mailbox>&K=<b64url stick X25519 pub>&P=<b64url invite_secret>&E=<unix expiry>
 * K and P are base64url without padding (43 chars for 32 bytes). The older
 * 64-hex form used by the phase-1 vectors is still accepted.
 * Query order is not significant. Extra unknown fields are refused (strict parse)
 * so a future incompatible payload can't be silently accepted.
 */
data class QrInvite(
    val mailbox: ByteArray,       // 8 B
    val stickStaticPub: ByteArray, // 32 B
    val inviteSecret: ByteArray,   // 32 B
    val expiryUnix: Long
) {
    fun isExpired(nowUnix: Long = System.currentTimeMillis() / 1000): Boolean = nowUnix >= expiryUnix

    fun wipe() { Bytes.wipe(inviteSecret) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrInvite) return false
        return mailbox.contentEquals(other.mailbox) &&
            stickStaticPub.contentEquals(other.stickStaticPub) &&
            inviteSecret.contentEquals(other.inviteSecret) &&
            expiryUnix == other.expiryUnix
    }
    override fun hashCode(): Int = mailbox.contentHashCode()
}

class QrParseException(msg: String) : IllegalArgumentException(msg)

object Qr {
    private val KEYS = setOf("M", "K", "P", "E")

    fun parse(text: String, nowUnix: Long = System.currentTimeMillis() / 1000, allowExpired: Boolean = false): QrInvite {
        val s = text.trim()
        if (!s.startsWith("sl1:")) throw QrParseException("not an sl1: QR")
        val rest = s.substring(4)
        if (rest.isEmpty()) throw QrParseException("empty payload")
        val fields = linkedMapOf<String, String>()
        for (part in rest.split("&")) {
            val eq = part.indexOf('=')
            if (eq <= 0) throw QrParseException("malformed field")
            val k = part.substring(0, eq)
            val v = part.substring(eq + 1)
            if (k !in KEYS) throw QrParseException("unexpected field")
            if (k in fields) throw QrParseException("duplicate field")
            if (v.isEmpty()) throw QrParseException("empty field")
            fields[k] = v
        }
        if (fields.keys != KEYS) throw QrParseException("missing field")
        val mailbox = parseHex("M", fields["M"]!!, 8)
        val stick = parseKey("K", fields["K"]!!)
        val invite = parseKey("P", fields["P"]!!)
        val exp = fields["E"]!!.toLongOrNull() ?: throw QrParseException("bad expiry")
        if (exp <= 0L) throw QrParseException("bad expiry")
        if (stick.all { it == 0.toByte() }) throw QrParseException("low-order stick key")
        val qr = QrInvite(mailbox, stick, invite, exp)
        if (!allowExpired && qr.isExpired(nowUnix)) {
            qr.wipe()
            throw QrParseException("expired")
        }
        return qr
    }

    /** Stick wire format (b64url K/P), same as pair_qr.encode_qr. */
    fun format(qr: QrInvite): String {
        return "sl1:M=${Bytes.hex(qr.mailbox)}&K=${Bytes.b64u(qr.stickStaticPub)}&P=${Bytes.b64u(qr.inviteSecret)}&E=${qr.expiryUnix}"
    }

    /** Legacy phase-1 hex format. */
    fun formatHex(qr: QrInvite): String {
        return "sl1:M=${Bytes.hex(qr.mailbox)}&K=${Bytes.hex(qr.stickStaticPub)}&P=${Bytes.hex(qr.inviteSecret)}&E=${qr.expiryUnix}"
    }

    private val B64U = Regex("^[A-Za-z0-9_-]+$")

    /** 32-byte key: 43-char base64url (stick) or 64-char hex (legacy). */
    private fun parseKey(name: String, v: String): ByteArray {
        if (v.length == 64 && v.all { it in "0123456789abcdefABCDEF" }) return Bytes.fromHex(v)
        if (v.length != 43 || !B64U.matches(v)) throw QrParseException("bad $name")
        return try { Bytes.unb64u(v, 32) } catch (e: Exception) { throw QrParseException("bad $name") }
    }

    private fun parseHex(name: String, v: String, want: Int): ByteArray {
        if (v.length != want * 2 || v.any { it !in "0123456789abcdefABCDEF" }) {
            throw QrParseException("bad $name")
        }
        return Bytes.fromHex(v)
    }
}
