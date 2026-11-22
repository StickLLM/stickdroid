package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtraTests {
    @Test
    fun naclBoxMatchesPyNaCl() {
        val ska = Bytes.fromHex("11".repeat(32))
        val skb = Bytes.fromHex("33".repeat(32))
        assertEquals("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13", Bytes.hex(Crypto.x25519Public(ska)))
        val nonce = ByteArray(24) { it.toByte() }
        val pt = """{"version":2,"CanAckPings":true}""".toByteArray()
        val boxed = Crypto.naclBox(ska, Crypto.x25519Public(skb), pt, nonce)
        assertEquals(
            "000102030405060708090a0b0c0d0e0f1011121314151617c61df1f43b1b62c32ed79f08649b47639d4ad19f3117289ce79907c99ebfc1c47f11a90a5036516cb4bde980d46e2c6f",
            Bytes.hex(boxed)
        )
        assertArrayEquals(pt, Crypto.naclBoxOpen(skb, Crypto.x25519Public(ska), boxed))
    }

    @Test
    fun fragmentsRoundTrip() {
        val msg = ByteArray(3000) { it.toByte() }
        val parts = Fragments.split(42, msg, maxBody = 200)
        assertTrue(parts.size >= 2)
        val r = Fragments.Reassembly()
        var out: ByteArray? = null
        for (p in parts.shuffled()) {
            out = r.push(Fragments.parse(p)!!) ?: out
        }
        assertArrayEquals(msg, out)
    }

    @Test
    fun protocolEnvelope() {
        val env = Protocol.turnStart(2, 1, "AAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBB", "hello stick")
        val back = Protocol.parse(env.toBytes())
        assertEquals("turn.start", back.t)
        assertEquals(2, back.s)
        assertEquals("hello stick", Protocol.str(back.b, "text"))
        val id = Protocol.newId()
        assertEquals(16, id.length)
    }
}
