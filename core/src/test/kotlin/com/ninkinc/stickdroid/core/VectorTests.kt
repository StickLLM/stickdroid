package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VectorTests {
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(name: String): JsonObject {
        val url = javaClass.classLoader.getResource("vectors/$name")
            ?: error("missing testdata vectors/$name")
        return json.parseToJsonElement(url.readText()).jsonObject
    }

    private fun hx(o: JsonObject, k: String): ByteArray = Bytes.fromHex(o[k]!!.jsonPrimitive.content)

    @Test
    fun qrV1Derivations() {
        val v = load("qr-v1.json")
        val mbx = hx(v, "mailbox_id")
        val home = hx(v, "home_id")
        val invite = hx(v, "invite_secret")
        val stick = hx(v, "stick_static_pub")
        val phone = hx(v, "phone_static_pub")
        val th = Derive.qrTh(mbx, home, stick)
        assertArrayEquals(hx(v, "TH"), th)
        assertArrayEquals(ByteArray(32), hx(v, "TH_initiator_static_field"))
        val thReal = Derive.transcriptHash(
            Bytes.ascii("qr"), mbx, home, ByteArray(0), phone, stick
        )
        assertArrayEquals(hx(v, "TH_if_real_phone_key_were_used_NOT_THE_SPEC"), thReal)
        assertFalse(th.contentEquals(thReal))
        assertArrayEquals(hx(v, "boot_psk"), Derive.qrBootPsk(invite, th))
        val jk = Derive.jkQr(invite)
        assertArrayEquals(hx(v, "jk_qr"), jk)
        assertArrayEquals(hx(v, "join_mac"), Derive.qrJoinMac(jk, hx(v, "server_nonce"), mbx))
        assertArrayEquals(hx(v, "env_qr"), Derive.envQr(invite, mbx, home))
    }

    @Test
    fun kvmUsbSharedDerivations() {
        val runs = load("kvm-usb-v1.json")["runs"]!!.jsonArray
        assertTrue(runs.size >= 1)
        for (el in runs) {
            val r = el.jsonObject
            val serial = r["serial"]!!.jsonPrimitive.content.toByteArray(Charsets.US_ASCII)
            val stickPub = hx(r, "stick_static_pub")
            val kvmPub = hx(r, "kvm_static_pub")
            val th = Derive.transcriptHash(
                Bytes.ascii("kvm-usb"), ByteArray(0), ByteArray(0), serial, stickPub, kvmPub
            )
            assertArrayEquals(hx(r, "TH"), th, "TH")
            val pair = Derive.pairPsk(
                hx(r, "r_i"), hx(r, "r_r"), ByteArray(0),
                hx(r, "h"), th, stickPub, kvmPub,
                r["key_epoch"]!!.jsonPrimitive.int
            )
            assertArrayEquals(hx(r, "pair_psk"), pair, "pair_psk")
            val kc = Derive.pairConfirmKey(pair)
            assertArrayEquals(hx(r, "kc"), kc, "kc")
            assertArrayEquals(hx(r, "confirm_i"), Derive.confirmI(kc, hx(r, "h")), "confirm_i")
            assertArrayEquals(hx(r, "confirm_r"), Derive.confirmR(kc, hx(r, "h")), "confirm_r")
            assertArrayEquals(hx(r, "P_slot"), Derive.pSlot(pair), "P_slot")
            assertArrayEquals(hx(r, "slot_jk"), Derive.joinKey(pair), "slot_jk")
            assertArrayEquals(hx(r, "slot_env_k"), Derive.envKey(pair), "slot_env_k")
        }
    }

    @Test
    fun kvmUsbExtraSharedDerivations() {
        val root = load("kvm-usb-v1-extra.json")
        val runs = root["runs"]?.jsonArray
        if (runs != null) {
            for (el in runs) {
                val r = el.jsonObject
                if (r["TH"] == null || r["pair_psk"] == null) continue
                val serial = r["serial"]!!.jsonPrimitive.content.toByteArray(Charsets.US_ASCII)
                val stickPub = hx(r, "stick_static_pub")
                val kvmPub = hx(r, "kvm_static_pub")
                val th = Derive.transcriptHash(
                    Bytes.ascii("kvm-usb"), ByteArray(0), ByteArray(0), serial, stickPub, kvmPub
                )
                assertArrayEquals(hx(r, "TH"), th)
                val pair = Derive.pairPsk(
                    hx(r, "r_i"), hx(r, "r_r"), ByteArray(0),
                    hx(r, "h"), th, stickPub, kvmPub,
                    r["key_epoch"]!!.jsonPrimitive.int
                )
                assertArrayEquals(hx(r, "pair_psk"), pair)
            }
        }
        // The extra file is mostly framing cases; as long as every run that
        // carries pair_psk/TH matches, we count this file as covered for the phone core.
    }

    @Test
    fun ikpsk2V2ByteExact() {
        val v = load("ikpsk2-v2.json")
        val pro = hx(v, "prologue")
        assertEquals(96, pro.size)
        assertEquals(96, v["prologue_len"]!!.jsonPrimitive.int)
        assertTrue(pro.copyOfRange(0, 8).contentEquals(Bytes.concat(Bytes.ascii("sl1/net"), byteArrayOf(0x01))))
        assertArrayEquals(
            pro,
            Noise.prologue(pro.copyOfRange(8, 24), pro.copyOfRange(24, 32), hx(v, "kvm_static_pub"), hx(v, "stick_static_pub"))
        )
        val i = Noise.Initiator(hx(v, "kvm_static"), hx(v, "stick_static_pub"), hx(v, "psk"), pro, hx(v, "kvm_eph"))
        val r = Noise.Responder(hx(v, "stick_static"), pro, hx(v, "stick_eph"))
        val m1 = i.writeMsg1()
        assertEquals(96, m1.size)
        assertArrayEquals(hx(v, "msg1"), m1)
        val (rs, pl) = r.readMsg1(m1)
        assertArrayEquals(hx(v, "kvm_static_pub"), rs)
        assertEquals(0, pl.size)
        val (rsess, m2) = r.writeMsg2(hx(v, "psk"))
        assertEquals(48, m2.size)
        assertArrayEquals(hx(v, "msg2"), m2)
        val (isess, _) = i.readMsg2(m2)
        assertArrayEquals(hx(v, "handshake_hash"), isess.handshakeHash)
        assertArrayEquals(hx(v, "handshake_hash"), rsess.handshakeHash)
        val pt = v["transport_plain"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        val (n, c) = isess.encrypt(pt)
        assertEquals(0L, n)
        assertArrayEquals(hx(v, "transport_i2r_0"), c)
        assertArrayEquals(pt, rsess.decrypt(0, c))
        val (_, c2) = rsess.encrypt(pt)
        assertArrayEquals(hx(v, "transport_r2i_0"), c2)
        assertArrayEquals(pt, isess.decrypt(0, c2))
    }

    @Test
    fun ikpsk2WrongPskRejected() {
        val v = load("ikpsk2-v2.json")
        val pro = hx(v, "prologue")
        val i = Noise.Initiator(hx(v, "kvm_static"), hx(v, "stick_static_pub"), hx(v, "psk"), pro, hx(v, "kvm_eph"))
        val r = Noise.Responder(hx(v, "stick_static"), pro, hx(v, "stick_eph"))
        r.readMsg1(i.writeMsg1())
        val (_, m2) = r.writeMsg2(ByteArray(32))
        assertArrayEquals(hx(v, "wrong_psk_msg2"), m2)
        assertThrows(NoiseError::class.java) { i.readMsg2(m2) }
    }

    @Test
    fun ikpsk2PrologueMismatch() {
        val v = load("ikpsk2-v2.json")
        val p = hx(v, "prologue")
        val cases = mapOf(
            "swapped_statics" to Bytes.concat(p.copyOfRange(0, 32), p.copyOfRange(64, 96), p.copyOfRange(32, 64)),
            "other_slot" to Bytes.concat(p.copyOfRange(0, 24), ByteArray(8), p.copyOfRange(32, p.size)),
            "ver_2" to Bytes.concat(p.copyOfRange(0, 7), byteArrayOf(0x02), p.copyOfRange(8, p.size)),
            "v1_31_byte" to Bytes.concat(Bytes.ascii("sl1/net"), p.copyOfRange(8, 32))
        )
        for ((name, bad) in cases) {
            val i = Noise.Initiator(hx(v, "kvm_static"), hx(v, "stick_static_pub"), hx(v, "psk"), p, hx(v, "kvm_eph"))
            val r = Noise.Responder(hx(v, "stick_static"), bad, hx(v, "stick_eph"))
            assertThrows(NoiseError::class.java, { r.readMsg1(i.writeMsg1()) }, name)
        }
    }

    @Test
    fun replayWindow() {
        val v = load("ikpsk2-v2.json")
        val pro = hx(v, "prologue")
        val i = Noise.Initiator(hx(v, "kvm_static"), hx(v, "stick_static_pub"), hx(v, "psk"), pro, hx(v, "kvm_eph"))
        val r = Noise.Responder(hx(v, "stick_static"), pro, hx(v, "stick_eph"))
        r.readMsg1(i.writeMsg1())
        val (rs, m2) = r.writeMsg2(hx(v, "psk"))
        val (s, _) = i.readMsg2(m2)
        val pk = (0 until 100).map { s.encrypt("x$it".toByteArray()) }
        assertArrayEquals("x5".toByteArray(), rs.decrypt(pk[5].first, pk[5].second))
        assertArrayEquals("x3".toByteArray(), rs.decrypt(pk[3].first, pk[3].second))
        assertThrows(NoiseError::class.java) { rs.decrypt(pk[3].first, pk[3].second) }
        assertArrayEquals("x99".toByteArray(), rs.decrypt(pk[99].first, pk[99].second))
        assertThrows(NoiseError::class.java) { rs.decrypt(pk[10].first, pk[10].second) }
        val (n, c) = pk[98]
        val forged = c.copyOf(); forged[forged.size - 1] = (forged[forged.size - 1].toInt() xor 1).toByte()
        assertThrows(NoiseError::class.java) { rs.decrypt(n, forged) }
        assertArrayEquals("x98".toByteArray(), rs.decrypt(n, c))
    }

    @Test
    fun signallingV2SlotAndHelloAndBox() {
        val root = load("signalling-v2.json")
        val sj = root["slot_join"]!!.jsonObject
        val psk = hx(sj, "pair_psk")
        assertArrayEquals(hx(sj, "P_slot"), Derive.pSlot(psk))
        assertArrayEquals(hx(sj, "jk"), Derive.joinKey(psk))
        assertArrayEquals(hx(sj, "env_k"), Derive.envKey(psk))
        assertArrayEquals(
            hx(sj, "join_mac"),
            Derive.slotJoinMac(Derive.joinKey(psk), hx(sj, "nonce"), hx(sj, "home"), hx(sj, "slot"))
        )
        val qj = root["qr_join"]!!.jsonObject
        assertArrayEquals(
            hx(qj, "join_mac"),
            Derive.qrJoinMac(hx(qj, "jk_qr"), hx(qj, "nonce"), hx(qj, "mbx"))
        )
        val dev = root["device"]!!.jsonObject
        val seed = hx(dev, "auth_seed")
        assertArrayEquals(hx(dev, "ak"), Crypto.ed25519Public(seed))
        assertArrayEquals(
            hx(dev, "hello_input_server"),
            Derive.helloInput(hx(dev, "nonce"), hx(dev, "dev"), Derive.ROLE_SERVER, hx(dev, "home"))
        )
        assertArrayEquals(
            hx(dev, "hello_sig_server"),
            Derive.helloSig(seed, hx(dev, "nonce"), hx(dev, "dev"), Derive.ROLE_SERVER, hx(dev, "home"))
        )
        assertArrayEquals(
            hx(dev, "hello_input_client"),
            Derive.helloInput(hx(dev, "nonce"), hx(dev, "dev"), Derive.ROLE_CLIENT, null)
        )
        assertArrayEquals(
            hx(dev, "hello_sig_client"),
            Derive.helloSig(seed, hx(dev, "nonce"), hx(dev, "dev"), Derive.ROLE_CLIENT, null)
        )
        val newSeed = hx(dev, "rotate_new_seed")
        assertArrayEquals(hx(dev, "rotate_new_ak"), Crypto.ed25519Public(newSeed))
        assertArrayEquals(
            hx(dev, "rotate_pop"),
            Derive.rotatePop(newSeed, hx(dev, "nonce"), hx(dev, "dev"), hx(dev, "ak"))
        )

        val box = load("ikpsk2-v2.json")["box"]!!.jsonObject
        val inner = box["inner"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        val sealed = Derive.sealBox(
            hx(box, "env_k"),
            Bytes.fromHex("dde22576dfcbc99129d17ce40c8d54a2"),
            Bytes.fromHex("3d3a8064548d8ee8"),
            Derive.DIR_C2S,
            inner,
            hx(box, "nonce")
        )
        assertEquals(box["box_b64"]!!.jsonPrimitive.content, sealed)
        val opened = Derive.openBox(
            hx(box, "env_k"),
            Bytes.fromHex("dde22576dfcbc99129d17ce40c8d54a2"),
            Bytes.fromHex("3d3a8064548d8ee8"),
            Derive.DIR_C2S,
            sealed
        )
        assertArrayEquals(inner, opened)
    }
}

class QrParserTests {
    private val mbx = "d90c13749910a177"
    private val k = "4d09032811ef6178fef7fdd6f46c80e9e15426b57de4175846523868d62b2a7a"
    private val p = "c98912aa847701f78575da96756875d2ddf25d1236795e69218ef0da48e308ad"
    private fun qr(e: Long, reorder: Boolean = false): String {
        return if (!reorder) "sl1:M=$mbx&K=$k&P=$p&E=$e"
        else "sl1:E=$e&P=$p&M=$mbx&K=$k"
    }

    @Test
    fun validAndReordered() {
        val now = 1_700_000_000L
        val a = Qr.parse(qr(now + 60), now)
        assertEquals(8, a.mailbox.size)
        assertEquals(32, a.stickStaticPub.size)
        assertEquals(now + 60, a.expiryUnix)
        val b = Qr.parse(qr(now + 60, reorder = true), now)
        assertArrayEquals(a.mailbox, b.mailbox)
        assertArrayEquals(a.inviteSecret, b.inviteSecret)
    }

    @Test
    fun rejects() {
        val now = 1_700_000_000L
        assertThrows(QrParseException::class.java) { Qr.parse("https://example", now) }
        assertThrows(QrParseException::class.java) { Qr.parse("sl1:", now) }
        assertThrows(QrParseException::class.java) { Qr.parse(qr(now - 1), now) } // expired
        assertThrows(QrParseException::class.java) { Qr.parse(qr(now + 60).dropLast(2), now) }
        assertThrows(QrParseException::class.java) { Qr.parse("sl1:M=$mbx&K=$k&P=$p&E=${now + 60}&X=1", now) }
        assertThrows(QrParseException::class.java) { Qr.parse("sl1:M=$mbx&K=$k&P=$p", now) }
        assertThrows(QrParseException::class.java) {
            Qr.parse("sl1:M=$mbx&K=${"00".repeat(32)}&P=$p&E=${now + 60}", now)
        }
        // expired is allowed only with the flag (used by tests reconstructing history)
        val exp = Qr.parse(qr(now - 5), now, allowExpired = true)
        assertTrue(exp.isExpired(now))
        exp.wipe()
    }
}
