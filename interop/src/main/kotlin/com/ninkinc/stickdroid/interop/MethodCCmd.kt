package com.ninkinc.stickdroid.interop

import com.ninkinc.stickdroid.core.A0Chat
import com.ninkinc.stickdroid.core.ReplyFilter
import com.ninkinc.stickdroid.core.ThinkingStatus
import com.ninkinc.stickdroid.core.JoinLink
import com.ninkinc.stickdroid.core.JoinLinkException
import com.ninkinc.stickdroid.core.MethodCClient
import com.ninkinc.stickdroid.core.MethodCConfig
import com.ninkinc.stickdroid.core.MethodCError
import java.io.File

/**
 * methodc --url-file F [--debug-hosts] [--text MSG]... [--long] [--cancel]
 *         [--skip-client-expiry] [--expect ok|BAD_LINK|EXPIRED|MAILBOX_GONE|STICK_BUSY|...]
 *         [--hold-sec N] [--offer-timeout-ms N]
 * Reads the join URL from a file (never from argv / logs). ICE is forced to [] (host
 * candidates only) so the box never contacts public STUN, incl. relay-tor1.
 * Prints RESULT lines; exit 0 iff the outcome matches --expect.
 */
fun cmdMethodC(a: Map<String, String>): Int {
    val t0 = System.currentTimeMillis()
    fun log(m: String) = println("%6.2fs harness: %s".format((System.currentTimeMillis() - t0) / 1000.0, m))
    val expect = a["expect"] ?: "ok"
    val url = File(a["url-file"] ?: error("--url-file")).readText().trim()
    var outcome = "ok"
    try {
        val target = JoinLink.parse(url, allowDebugHosts = a.containsKey("debug-hosts"),
            allowExpired = a.containsKey("skip-client-expiry"))
        log("join link ok ws_host=${java.net.URI(target.wsUrl).host} debug=${target.debugHost}")
        val cfg = MethodCConfig(
            iceServers = emptyList(),
            offerTimeoutMs = a["offer-timeout-ms"]?.toLong() ?: 30_000,
            skipClientExpiryCheck = a.containsKey("skip-client-expiry"),
        )
        val cli = MethodCClient(target, JvmRtcFactory(), cfg, log = { log(it) })
        val sess = cli.connect()
        log("SESSION up path=${sess.path}")
        val chat = A0Chat(sess.mux, log = { log(it) })
        val texts = a.entries.filter { it.key == "text" }.map { it.value } +
            listOfNotNull(a["text2"], a["text3"])
        for (txt in texts) {
            val seen = ArrayList<String>()
            val t0 = System.currentTimeMillis()
            val turn = chat.ask(txt, timeoutMs = 60_000, pollMs = 500) { p ->
                seen.add(p); log("progress: $p")
            }
            val waited = System.currentTimeMillis() - t0
            log("ANSWER ctx=${turn.context.take(6)}… len=${turn.answer?.length} text=${turn.answer?.take(120)}")
            if (txt.trim().startsWith("WAIT")) {
                val bubble = ThinkingStatus.display(waited, seen.lastOrNull())
                log("THINKING bubble after ${waited}ms -> ${bubble.replace("\n", " | ")}")
                if (seen.isEmpty()) throw IllegalStateException("WAIT turn had no progress for the thinking bubble")
                if (waited < 1_000) throw IllegalStateException("WAIT reply was not delayed")
                if (!seen.any { it == "Using a tool" || it == "Searching" || it.contains("tool") || it.contains("Search") }) {
                    throw IllegalStateException("WAIT progress was not sanitized: $seen")
                }
            }
            if (txt.trim().startsWith("DUMP")) {
                val raw = turn.answer ?: ""
                val shown = ReplyFilter.display(raw, txt, showSystemDetails = false)
                log("FILTERED dump -> ${shown.take(80)}")
                if ("Agent Number" in raw && ("Agent Number" in shown || "Current Date" in shown || "qwen2.5" in shown.lowercase())) {
                    throw IllegalStateException("dump was not filtered: $shown")
                }
                if (shown.isBlank() || shown.contains("**LLM**")) {
                    throw IllegalStateException("dump filter left metadata: $shown")
                }
            }
        }
        if (a.containsKey("long")) {
            val turn = chat.ask("LONG please", timeoutMs = 60_000, pollMs = 500) { p -> log("progress: $p") }
            log("LONG answer len=${turn.answer?.length} tail=${turn.answer?.takeLast(24)}")
            if ((turn.answer?.length ?: 0) < 100_000) throw IllegalStateException("long answer too short")
        }
        if (a.containsKey("cancel")) {
            val th = Thread { Thread.sleep(1500); log("cancel → /agent-zero/api/stop"); chat.cancel() }
            th.start()
            val turn = chat.ask("SLOW task", timeoutMs = 60_000, pollMs = 500) { p -> log("progress: $p") }
            log("CANCEL stopped=${turn.stopped} answer=${turn.answer?.take(40)}")
            if (!turn.stopped) throw IllegalStateException("cancel did not stop")
        }
        if (a.containsKey("forbidden")) {
            val r = sess.mux.http("GET", "/api/speech/power", mapOf("Accept" to "application/json"))
            log("FORBIDDEN /api/speech/power -> ${r.status} ${r.text.take(30)}")
            if (r.status != 403) throw IllegalStateException("expected 403")
        }
        a["hold-sec"]?.toLong()?.let {
            log("holding session ${it}s")
            val end = System.currentTimeMillis() + it * 1000
            while (System.currentTimeMillis() < end && sess.isAlive) Thread.sleep(200)
            log("still alive=${sess.isAlive}${sess.lostReason?.let { r -> " lost=$r" } ?: ""}")
            if (!sess.isAlive) outcome = "LOST"
        }
        sess.close()
        log("closed")
    } catch (e: MethodCError) {
        outcome = e.kind.name
        log("MethodCError ${e.kind}: ${e.message} | user: ${e.userText()}")
    } catch (e: JoinLinkException) {
        outcome = if (e.expired) "EXPIRED" else "BAD_LINK"
        log("JoinLinkException: ${e.message}")
    }
    val pass = outcome == expect
    println("RESULT outcome=$outcome expect=$expect ${if (pass) "PASS" else "FAIL"}")
    return if (pass) 0 else 1
}
