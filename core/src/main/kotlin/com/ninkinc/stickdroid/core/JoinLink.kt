package com.ninkinc.stickdroid.core

import java.net.URI

/**
 * What the kiosk's "StickLink QR" encodes (methodc_peer.py, run_one_session):
 *
 *   https://link.stickllm.com/browser/agent_join.html?ws=<urlenc wss url>&qr=<urlenc sl1:...>
 *
 * A bare `sl1:` payload is accepted too; it then uses the default signalling URL.
 *
 * Host allowlist: production accepts only link.stickllm.com for both the
 * join page and the signalling socket (wss). Debug builds may additionally
 * allow loopback / emulator hosts (http/ws) for the box lab; release never does.
 */
data class JoinTarget(
    val wsUrl: String,
    val joinOrigin: String,
    val invite: QrInvite,
    val debugHost: Boolean
) {
    /** STUN-only ICE JSON served next to the join page (index.ts joinIceServers). */
    val iceUrl: String get() = "$joinOrigin/lab/ice"
}

class JoinLinkException(msg: String, val expired: Boolean = false) : IllegalArgumentException(msg)

object JoinLink {
    const val PROD_HOST = "link.stickllm.com"
    const val PROD_WS = "wss://link.stickllm.com/v1/ws"
    const val PROD_ORIGIN = "https://link.stickllm.com"
    const val JOIN_PATH = "/browser/agent_join.html"
    val DEBUG_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")

    /**
     * @param allowDebugHosts true only in a debug build with the lab switch on.
     */
    fun parse(
        scanned: String,
        allowDebugHosts: Boolean = false,
        nowUnix: Long = System.currentTimeMillis() / 1000,
        allowExpired: Boolean = false,
        defaultWs: String = PROD_WS
    ): JoinTarget {
        val s = scanned.trim()
        if (s.startsWith("sl1:")) {
            val ws = checkWs(defaultWs, allowDebugHosts)
            val inv = parseQr(s, nowUnix, allowExpired)
            return JoinTarget(ws.first, originForWs(ws.first), inv, ws.second)
        }
        val uri = try { URI(s) } catch (e: Exception) { throw JoinLinkException("not a StickLink QR") }
        val scheme = uri.scheme?.lowercase() ?: throw JoinLinkException("not a StickLink QR")
        val host = uri.host?.lowercase() ?: throw JoinLinkException("not a StickLink QR")
        val debug = when {
            scheme == "https" && host == PROD_HOST && (uri.port == -1 || uri.port == 443) -> false
            allowDebugHosts && host in DEBUG_HOSTS && (scheme == "http" || scheme == "https") -> true
            else -> throw JoinLinkException("QR is not from link.stickllm.com")
        }
        if (uri.rawPath != JOIN_PATH) throw JoinLinkException("not a StickLink join link")
        if (uri.rawUserInfo != null || uri.rawFragment != null) throw JoinLinkException("not a StickLink join link")
        val q = query(uri.rawQuery ?: throw JoinLinkException("join link has no ws/qr"))
        val wsRaw = q["ws"] ?: throw JoinLinkException("join link has no ws")
        val qrRaw = q["qr"] ?: throw JoinLinkException("join link has no qr")
        val ws = checkWs(wsRaw, allowDebugHosts)
        if (!debug && ws.second) throw JoinLinkException("mixed production / lab hosts")
        val inv = parseQr(qrRaw, nowUnix, allowExpired)
        val origin = if (debug) "${scheme}://${uri.rawAuthority}" else PROD_ORIGIN
        return JoinTarget(ws.first, origin, inv, debug || ws.second)
    }

    private fun parseQr(s: String, now: Long, allowExpired: Boolean): QrInvite = try {
        Qr.parse(s, now, allowExpired)
    } catch (e: QrParseException) {
        if (e.message == "expired") throw JoinLinkException("QR expired, tap Refresh StickLink QR on the kiosk", expired = true)
        throw JoinLinkException("bad StickLink QR (${e.message})")
    }

    /** Returns (normalised ws url, isDebugHost). */
    private fun checkWs(ws: String, allowDebug: Boolean): Pair<String, Boolean> {
        val u = try { URI(ws) } catch (e: Exception) { throw JoinLinkException("bad signalling URL") }
        val scheme = u.scheme?.lowercase()
        val host = u.host?.lowercase() ?: throw JoinLinkException("bad signalling URL")
        if (u.rawQuery != null || u.rawFragment != null || u.rawUserInfo != null) throw JoinLinkException("bad signalling URL")
        if (scheme == "wss" && host == PROD_HOST && (u.port == -1 || u.port == 443) && u.rawPath == "/v1/ws") {
            return PROD_WS to false
        }
        if (allowDebug && host in DEBUG_HOSTS && (scheme == "ws" || scheme == "wss") && u.rawPath == "/v1/ws") {
            return ws to true
        }
        throw JoinLinkException("signalling host not allowed")
    }

    private fun originForWs(ws: String): String {
        if (ws == PROD_WS) return PROD_ORIGIN
        val u = URI(ws)
        val sch = if (u.scheme == "wss") "https" else "http"
        return "$sch://${u.rawAuthority}"
    }

    /** Strict query split; percent-decoding only ('+' is literal, as with quote(safe='')). */
    private fun query(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in raw.split("&")) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) throw JoinLinkException("bad join link query")
            val k = pct(part.substring(0, eq))
            val v = pct(part.substring(eq + 1))
            if (k in out) throw JoinLinkException("duplicate $k in join link")
            out[k] = v
        }
        return out
    }

    private fun pct(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) throw JoinLinkException("bad escape")
                val h = s.substring(i + 1, i + 3).toIntOrNull(16) ?: throw JoinLinkException("bad escape")
                bytes.write(h); i += 3
            } else {
                val b = c.toString().toByteArray(Charsets.UTF_8); bytes.write(b, 0, b.size); i++
            }
        }
        return bytes.toString("UTF-8")
    }
}
