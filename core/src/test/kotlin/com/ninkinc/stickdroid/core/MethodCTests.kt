package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Cross-checks against vectors produced by the stick's own Python (scripts/methodc/gen_stick_vectors.py). */
class MethodCTests {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader.getResource("methodc-stick-v1.json")!!.readText()
    ).jsonObject
    private fun hx(k: String) = Bytes.fromHex(v[k]!!.jsonPrimitive.content)
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val now = 1_800_000_000L

    @Test fun stickQrStringParses() {
        val q = Qr.parse(s("qr"), now)
        assertArrayEquals(hx("mbx"), q.mailbox)
        assertArrayEquals(hx("stick_pub"), q.stickStaticPub)
        assertArrayEquals(hx("invite"), q.inviteSecret)
        assertEquals(v["exp"]!!.jsonPrimitive.content.toLong(), q.expiryUnix)
        assertEquals(s("qr"), Qr.format(q)) // byte-identical to pair_qr.encode_qr
    }

    @Test fun kioskJoinUrlParses() {
        val t = JoinLink.parse(s("join_url"), nowUnix = now)
        assertEquals("wss://link.stickllm.com/v1/ws", t.wsUrl)
        assertEquals("https://link.stickllm.com/lab/ice", t.iceUrl)
        assertFalse(t.debugHost)
        assertArrayEquals(hx("invite"), t.invite.inviteSecret)
        val bare = JoinLink.parse(s("qr"), nowUnix = now)
        assertEquals(JoinLink.PROD_WS, bare.wsUrl)
    }

    @Test fun joinUrlRejections() {
        val u = s("join_url")
        fun bad(x: String, debug: Boolean = false) =
            assertThrows(JoinLinkException::class.java) { JoinLink.parse(x, allowDebugHosts = debug, nowUnix = now) }
        bad(u.replace("link.stickllm.com/browser", "evil.example/browser"))          // wrong page host
        bad(u.replace("https://link.stickllm.com", "http://link.stickllm.com"))       // not https
        bad(u.replace("link.stickllm.com/browser", "link.stickllm.com.evil.example/browser"))
        bad(u.replace("wss%3A%2F%2Flink.stickllm.com", "wss%3A%2F%2Fevil.example"))  // wrong ws host
        bad(u.replace("wss%3A%2F%2Flink.stickllm.com", "ws%3A%2F%2Flink.stickllm.com")) // ws not wss
        bad(u.replace("agent_join.html", "other.html"))
        bad(u.replace("&qr=", "&qq="))
        bad(u + "&qr=x")                                                               // duplicate
        bad("https://127.0.0.1:8770/browser/agent_join.html?ws=ws%3A%2F%2F127.0.0.1%3A8800%2Fv1%2Fws&qr=" +
            java.net.URLEncoder.encode(s("qr"), "UTF-8"))                              // lab host, release mode
        // expired
        val ex = assertThrows(JoinLinkException::class.java) { JoinLink.parse(u, nowUnix = 1_950_000_000L) }
        assertTrue(ex.message!!.contains("expired")); assertTrue(ex.expired)
        // corrupted P: canonical b64 but wrong length / non-canonical / wrong alphabet
        val p = Bytes.b64u(hx("invite"))
        bad(u.replace(p, p.dropLast(1)))
        bad(u.replace(p, p.dropLast(1) + "B"))   // non-canonical tail bits
        bad(u.replace(p, p.dropLast(1) + "%2B"))  // '+' not base64url
        // debug lab URL is accepted only with the debug switch
        val lab = "http://127.0.0.1:8770/browser/agent_join.html?ws=ws%3A%2F%2F127.0.0.1%3A8800%2Fv1%2Fws&qr=" +
            java.net.URLEncoder.encode(s("qr"), "UTF-8")
        val t = JoinLink.parse(lab, allowDebugHosts = true, nowUnix = now)
        assertTrue(t.debugHost)
        assertEquals("ws://127.0.0.1:8800/v1/ws", t.wsUrl)
        // mixing prod page with lab ws is refused even in debug
        bad(u.replace("wss%3A%2F%2Flink.stickllm.com%2Fv1%2Fws", "ws%3A%2F%2F127.0.0.1%3A8800%2Fv1%2Fws"), debug = true)
    }

    @Test fun derivationsMatchStick() {
        val inv = hx("invite"); val mbx = hx("mbx"); val home = hx("home")
        val jk = Derive.jkQr(inv)
        assertArrayEquals(hx("jk"), jk)
        assertArrayEquals(hx("join_mac"), Derive.qrJoinMac(jk, hx("server_nonce"), mbx))
        val envK = Derive.envQr(inv, mbx, home)
        assertArrayEquals(hx("env_k"), envK)
        assertEquals(s("offer_pt"), String(Derive.openEnvQr(envK, mbx, 0x02, hx("env_box_dir2"))))
        val th = Derive.qrTh(mbx, home, hx("stick_pub"))
        assertArrayEquals(hx("th"), th)
        assertArrayEquals(hx("boot"), Derive.qrBootPsk(inv, th))
        // wrong direction must not open
        assertThrows(Exception::class.java) { Derive.openEnvQr(envK, mbx, 0x01, hx("env_box_dir2")) }
    }

    @Test fun noiseAndConfirmMatchStick() {
        val mbx = hx("mbx"); val home = hx("home"); val stickPub = hx("stick_pub")
        val prologue = Bytes.concat(Bytes.ascii("sl1/qr"), byteArrayOf(1), home, mbx, stickPub)
        assertArrayEquals(hx("prologue"), prologue)
        val ini = Noise.Initiator(hx("phone_priv"), stickPub, hx("boot"), prologue, ePriv = hx("phone_e"))
        assertArrayEquals(hx("msg1"), ini.writeMsg1())
        val (sess, _) = ini.readMsg2(hx("msg2"))
        assertArrayEquals(hx("hh"), sess.handshakeHash)
        val psk = Derive.pairPsk(hx("r_i"), hx("r_r"), hx("boot"), sess.handshakeHash, hx("th"),
            hx("phone_pub"), stickPub, keyEpoch = 0)
        assertArrayEquals(hx("pair_psk"), psk)
        val kc = Derive.pairConfirmKey(psk)
        assertArrayEquals(hx("confirm_i"), Derive.confirmI(kc, sess.handshakeHash))
        assertArrayEquals(hx("confirm_r"), Derive.confirmR(kc, sess.handshakeHash))
        // first stick transport frame (u64le n || ct) decrypts on the phone side
        val f = hx("stick_frame0")
        var n = 0L; for (i in 7 downTo 0) n = (n shl 8) or (f[i].toLong() and 0xff)
        val pt = String(sess.decrypt(n, f.copyOfRange(8, f.size)))
        assertTrue(pt.contains("\"r_r\""))
        // wrong PSK (= corrupted invite) fails msg2
        val bad = Noise.Initiator(hx("phone_priv"), stickPub, ByteArray(32), prologue, ePriv = hx("phone_e"))
        bad.writeMsg1()
        assertThrows(NoiseError::class.java) { bad.readMsg2(hx("msg2")) }
    }

    @Test fun appMuxSingleChunkedAndGzip() {
        lateinit var mux: AppMux
        val sent = ArrayList<JsonObject>()
        val big = ByteArray(100_000) { (it % 251).toByte() }
        val text = ("hello agent zero ".repeat(400)).toByteArray()
        mux = AppMux({ m ->
            sent.add(m)
            if (m["t"]!!.jsonPrimitive.content != "http_req") return@AppMux
            val id = m["id"]!!.jsonPrimitive.content
            val path = m["path"]!!.jsonPrimitive.content
            Thread {
                when (path) {
                    "/small" -> mux.dispatch(buildJsonObject {
                        put("t", "http_res"); put("id", id); put("status", 200)
                        putJsonObject("headers") { put("content-type", "text/plain") }
                        put("body_b64", Bytes.b64u("ok".toByteArray()))
                    })
                    "/big" -> {
                        mux.dispatch(buildJsonObject {
                            put("t", "http_res"); put("id", id); put("status", 200)
                            putJsonObject("headers") { }; put("body_len", big.size)
                        })
                        var i = 0; var q = 0
                        while (i < big.size) {
                            val e = minOf(i + AppMux.CHUNK, big.size)
                            mux.dispatch(buildJsonObject {
                                put("t", "http_chunk"); put("id", id); put("role", "res"); put("seq", q++)
                                put("eof", e >= big.size); put("body_b64", Bytes.b64u(big.copyOfRange(i, e)))
                            })
                            i = e
                        }
                    }
                    "/gz" -> {
                        val bo = ByteArrayOutputStream(); GZIPOutputStream(bo).use { it.write(text) }
                        mux.dispatch(buildJsonObject {
                            put("t", "http_res"); put("id", id); put("status", 201)
                            putJsonObject("headers") { put("X-StickLink-Ce", "gzip") }
                            put("body_b64", Bytes.b64u(bo.toByteArray()))
                        })
                    }
                }
            }.start()
        })
        assertEquals("ok", mux.http("GET", "/small").text)
        assertArrayEquals(big, mux.http("GET", "/big").body)
        val g = mux.http("POST", "/gz", mapOf("Content-Type" to "application/json"), ByteArray(80_000))
        assertEquals(201, g.status); assertArrayEquals(text, g.body)
        // 80 kB request body went out as http_req(body_len) + 3 http_chunk(role=req)
        val reqChunks = sent.filter { it["t"]!!.jsonPrimitive.content == "http_chunk" }
        assertEquals(3, reqChunks.size)
        assertTrue(reqChunks.last()["eof"]!!.jsonPrimitive.content == "true")
    }

    @Test fun sdpFitsMailbox() {
        val base = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val v4 = (1..6).joinToString("") { "a=candidate:$it 1 udp 2122260223 192.168.1.$it 5${it}000 typ host generation 0\r\n" }
        val v6 = (1..10).joinToString("") { "a=candidate:6$it 1 udp 2122262783 2001:db8::$it 5${it}001 typ host generation 0 network-id 2\r\n" }
        val tcp = (1..10).joinToString("") { "a=candidate:7$it 1 tcp 1518280447 192.168.1.$it 9 typ host tcptype active generation 0\r\n" }
        val small = base + v4
        assertEquals(small, Sdp.fitEnvelope(small, MethodCClient.BOX_MAX))
        val fat = base + v4 + v6 + tcp + "a=fingerprint:sha-256 " + "AB:".repeat(31) + "AB\r\n"
        val fit = Sdp.fitEnvelope(fat, MethodCClient.BOX_MAX)
        assertTrue(fit.contains("192.168.1.1 51000 typ host"))
        assertFalse(fit.contains(" tcp "))
        assertTrue(fit.contains("a=fingerprint"))
    }
}
