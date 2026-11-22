package com.ninkinc.stickdroid.core

import java.util.Base64

object Bytes {
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fromHex(s: String): ByteArray {
        val t = s.trim().lowercase().replace(" ", "")
        require(t.length % 2 == 0) { "odd hex length" }
        return ByteArray(t.length / 2) { i ->
            val hi = t[i * 2].digitToIntOrNull(16) ?: throw IllegalArgumentException("bad hex")
            val lo = t[i * 2 + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException("bad hex")
            ((hi shl 4) or lo).toByte()
        }
    }

    fun b64u(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun unb64u(s: String, want: Int? = null): ByteArray {
        if ("=" in s) throw IllegalArgumentException("bad b64url")
        val pad = "=".repeat((4 - s.length % 4) % 4)
        val b = try {
            Base64.getUrlDecoder().decode(s + pad)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad b64url", e)
        }
        if (b64u(b) != s) throw IllegalArgumentException("non-canonical b64url")
        if (want != null && b.size != want) throw IllegalArgumentException("want $want bytes, got ${b.size}")
        return b
    }

    /** enc(x) = u16be(len) || x  (PAIRING-SPEC §4.5) */
    fun enc(x: ByteArray): ByteArray {
        require(x.size <= 0xffff) { "enc overflow" }
        return u16be(x.size) + x
    }

    fun u16be(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())
    fun u32be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        (v and 0xff).toByte()
    )
    fun u32be(v: Long): ByteArray = u32be(v.toInt())

    fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun ctEq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) {
            // still walk a to keep a constant-ish time for equal lengths; length leak is ok
            var acc = a.size xor b.size
            for (x in a) acc = acc or (x.toInt() and 0xff)
            return false
        }
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    fun wipe(b: ByteArray) {
        java.util.Arrays.fill(b, 0)
    }

    fun fp(b: ByteArray): String = hex(b).take(8)
}
