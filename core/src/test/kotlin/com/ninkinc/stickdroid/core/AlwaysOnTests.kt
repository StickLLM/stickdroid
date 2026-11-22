package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessCryptoTests {
    @Test fun pskV1Deterministic() {
        val a = SessCrypto.resumePskV1("abcdefghijklmnopqrstuv")
        val b = SessCrypto.resumePskV1("abcdefghijklmnopqrstuv")
        assertTrue(a.contentEquals(b))
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(SessCrypto.resumePskV1("abcdefghijklmnopqrstuw")))
    }

    /** Vectors from the LIVE stick peer (stick-client methodc_peer.py sess_resume_psk, crypto.hkdf). */
    @Test fun pskV2MatchesLiveStick() {
        val pp = ByteArray(32) { it.toByte() }
        val tok = "AbCdEfGhIjKlMnOpQrStUv"
        assertEquals("c166cba0ff19b106c678c9f969376b4a19447ac941eb923f54108bda99780c4d", hex(SessCrypto.resumePskV2(tok, pp)))
        assertEquals("643faa76a68f522a25acf0ef2c874f1b01146970f3c2cf8ecad8b22f324f4249", hex(SessCrypto.resumePskV1(tok)))
        assertEquals("8bfb4a7f6c1cb407", hex(SessCrypto.tokenId(tok)))
        assertFalse(SessCrypto.resumePskV2(tok, pp).contentEquals(SessCrypto.resumePskV1(tok)))
    }

    /** A v1 initiator cannot complete against a v2 responder (the 0.3.0 field bug). */
    @Test fun v1PhoneFailsAgainstV2Stick() {
        val phonePriv = Crypto.x25519Generate(); val phonePub = Crypto.x25519Public(phonePriv)
        val stickPriv = Crypto.x25519Generate(); val stickPub = Crypto.x25519Public(stickPriv)
        val home = ByteArray(16) { 1 }; val tok = "abcdefghijklmnopqrstuv"; val pp = ByteArray(32) { 5 }
        val pro = SessCrypto.prologue(home, tok, phonePub, stickPub)
        val ini = Noise.Initiator(phonePriv, stickPub, SessCrypto.resumePskV1(tok), pro)
        val resp = Noise.Responder(stickPriv, pro)
        resp.readMsg1(ini.writeMsg1())
        val (_, msg2) = resp.writeMsg2(SessCrypto.resumePskV2(tok, pp))
        org.junit.jupiter.api.Assertions.assertThrows(NoiseError::class.java) { ini.readMsg2(msg2) }
    }

    @Test fun clientDefaultsToV2() {
        // Default WssSessClient must use v2 (no pair_psk → UNAVAILABLE, never silently v1).
        val c = WssSessClient()
        val f = WssSessClient::class.java.getDeclaredField("usePskV2"); f.isAccessible = true
        assertTrue(f.getBoolean(c))
    }

    @Test fun prologueLayout() {
        val home = ByteArray(16) { 1 }
        val phone = ByteArray(32) { 2 }
        val stick = ByteArray(32) { 3 }
        val p = SessCrypto.prologue(home, "abcdefghijklmnopqrstuv", phone, stick)
        assertEquals(8 + 1 + 16 + 8 + 32 + 32, p.size)
        assertTrue(p.copyOfRange(0, 8).contentEquals(Bytes.ascii("sl1/sess")))
        assertEquals(0x01, p[8].toInt())
        assertTrue(p.copyOfRange(9, 25).contentEquals(home))
        assertTrue(p.copyOfRange(25, 33).contentEquals(SessCrypto.tokenId("abcdefghijklmnopqrstuv")))
    }

    @Test fun authUrlRtIsolated() {
        val u = SessAuthUrl.RtQuery.build("wss://link.stickllm.com/v1/ws", "tokentokentoken12", "AAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(u.contains("rt="))
        assertFalse(u.contains("sess=1"))
        val u2 = SessAuthUrl.SessHome.build("wss://link.stickllm.com/v1/ws", "tokentokentoken12", "AAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(u2.contains("sess=1") && u2.contains("home=") && !u2.contains("rt="))
    }

    @Test fun noFreshStaticOnResumePins() {
        val priv = Crypto.x25519Generate()
        val pub = Crypto.x25519Public(priv)
        val a = sample(priv, pub)
        assertTrue(a.phonePriv.contentEquals(priv))
        assertTrue(a.phonePub.contentEquals(pub))
    }

    @Test fun noiseIkWithSessPrologue() {
        val phonePriv = Crypto.x25519Generate()
        val phonePub = Crypto.x25519Public(phonePriv)
        val stickPriv = Crypto.x25519Generate()
        val stickPub = Crypto.x25519Public(stickPriv)
        val home = ByteArray(16) { 1 }
        val token = "abcdefghijklmnopqrstuv"
        val psk = SessCrypto.resumePskV2(token, ByteArray(32) { 4 })
        val prologue = SessCrypto.prologue(home, token, phonePub, stickPub)
        val ini = Noise.Initiator(phonePriv, stickPub, psk, prologue)
        val msg1 = ini.writeMsg1()
        val resp = Noise.Responder(stickPriv, prologue)
        resp.readMsg1(msg1)
        val (sessR, msg2) = resp.writeMsg2(psk)
        val (sessI, _) = ini.readMsg2(msg2)
        val (n, ct) = sessI.encrypt("""{"t":"ping"}""".toByteArray())
        val pt = sessR.decrypt(n, ct)
        assertEquals("""{"t":"ping"}""", String(pt))
    }
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

class AlwaysOnCodecTests {
    @Test fun roundTrip() {
        val a = sample()
        val b = AlwaysOnCodec.decode(AlwaysOnCodec.encode(a))!!
        assertEquals(a.token, b.token)
        assertTrue(a.home.contentEquals(b.home))
        assertTrue(a.phonePriv.contentEquals(b.phonePriv))
        assertEquals(a.hwm, b.hwm)
    }

    @Test fun tagRedacts() {
        val a = sample()
        assertEquals("…" + a.token.takeLast(4), a.tag())
        assertFalse(a.toString().contains(a.token.dropLast(4)))
    }

    @Test fun fromAgentReady() {
        val msg = buildJsonObject {
            put("t", "agent_ready")
            putJsonObject("resume") {
                put("token", "abcdefghijklmnopqrstuv")
                put("origin", "https://link.stickllm.com")
                put("ws", "wss://link.stickllm.com/v1/ws")
            }
        }
        assertTrue(ResumeCodec.fromAgentReady(msg) is ResumeOffer.Present)
    }

    @Test fun legacyJoinOnlyWhenV1() {
        assertNotNull(AlwaysOnCodec.legacyJoinFromBody("""{"v":1,"join":"https://link.stickllm.com/browser/agent_join.html?ws=wss://x&qr=sl1:M=1","exp":1}"""))
        assertNull(AlwaysOnCodec.legacyJoinFromBody("""{"v":2,"mode":"wss","ws":"wss://link.stickllm.com/v1/ws"}"""))
    }

    @Test fun enrichV2() {
        val base = sample()
        val body = """{"v":2,"mode":"wss","ws":"wss://link.stickllm.com/v1/ws","home":"${Bytes.b64u(base.home)}","stick_pub":"${Bytes.b64u(base.stickPub)}"}"""
        assertNotNull(AlwaysOnCodec.enrichFromV2Body(body, base))
    }
}

class AlwaysOnLinkManagerTests {
    private var t = 1_000_000L
    private val store = MemoryAlwaysOnStore()

    @Test fun disconnectClearsPins() {
        store.save(sample())
        val m = LinkManager(
            store = store,
            connector = LinkConnector { error("no") },
            http = ResumeHttp { 404 to "" },
            clock = { t },
            waiter = object : Waiter { override fun await(ms: Long) {}; override fun wake() {} },
            spawn = { _, b -> b() },
        )
        m.disconnect()
        assertNull(store.load())
        assertTrue(m.state is LinkState.Disconnected)
        assertEquals(1, store.clears)
    }

    @Test fun coldStartWithoutPinsNeedsScan() {
        val m = LinkManager(
            store = store,
            connector = LinkConnector { error("no") },
            http = ResumeHttp { 404 to "" },
            spawn = { _, b -> b() },
        )
        m.start()
        assertTrue(m.state is LinkState.NeedScan)
    }

    @Test fun scanNewClears() {
        store.save(sample())
        val m = LinkManager(
            store = store,
            connector = LinkConnector { error("no") },
            http = ResumeHttp { 404 to "" },
            spawn = { _, b -> b() },
        )
        m.scanNew()
        assertNull(store.load())
        assertTrue(m.state is LinkState.NeedScan)
    }
}

private fun sample(priv: ByteArray = Crypto.x25519Generate(), pub: ByteArray = Crypto.x25519Public(priv)) = AlwaysOnResume(
    token = "abcdefghijklmnopqrstuv",
    origin = "https://link.stickllm.com",
    ws = "wss://link.stickllm.com/v1/ws",
    home = ByteArray(16) { 7 },
    stickPub = ByteArray(32) { 8 },
    phonePriv = priv,
    phonePub = pub,
    pairPsk = ByteArray(32) { 9 },
    hwm = 3,
)
