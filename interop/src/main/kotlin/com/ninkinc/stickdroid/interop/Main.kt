package com.ninkinc.stickdroid.interop

import com.ninkinc.stickdroid.core.ConnectConfig
import com.ninkinc.stickdroid.core.EchoResult
import com.ninkinc.stickdroid.core.PairingState
import com.ninkinc.stickdroid.core.Protocol
import com.ninkinc.stickdroid.core.SessionClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

/**
 * JVM harness used for box interop against sticklinkd + mock_signal + derper.
 *
 *   pair    --offer offer.json --state peer.json --answer answer.json
 *   connect --state peer.json --ws URL [--derp URL] [--derp-pin HEX] [--derp-sni NAME]
 *           [--force-relay] [--block-udp-rx] [--duration 6] [--expect direct|derp|any]
 *   chat    --state peer.json --ws URL [--derp URL] [--text MSG] [--long] [--timeout 20]
 *   methodc --url-file F [--debug-hosts] [--text MSG] ...   (Method C kiosk QR; see MethodCCmd.kt)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: pair | connect | chat ...")
        exitProcess(2)
    }
    when (args[0]) {
        "pair" -> cmdPair(parse(args.drop(1)))
        "connect" -> exitProcess(cmdConnect(parse(args.drop(1))))
        "chat" -> exitProcess(cmdChat(parse(args.drop(1))))
        "methodc" -> exitProcess(cmdMethodC(parse(args.drop(1))))
        "methodc-resume" -> exitProcess(cmdMethodCResume(parse(args.drop(1))))
        else -> {
            System.err.println("unknown command ${args[0]}")
            exitProcess(2)
        }
    }
}

private fun parse(args: List<String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) { i++; continue }
        val k = a.removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
            out[k] = args[++i]
        } else {
            out[k] = "true"
        }
        i++
    }
    return out
}


private fun log(m: String) {
    val now = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.LocalTime.now())
    println("$now testpeer: $m")
}

private fun cmdPair(a: Map<String, String>) {
    val offer = File(a["offer"] ?: error("--offer")).readText()
    val (st, answer) = PairingState.answerOffer(offer, a["name"] ?: "stickdroid", a["kind"] ?: "phone")
    File(a["state"] ?: error("--state")).writeText(st.toPeerJson())
    File(a["answer"] ?: error("--answer")).writeText(answer)
    println("""{"answer":"${a["answer"]}","ok":true}""")
}

private fun cmdConnect(a: Map<String, String>): Int {
    val st = PairingState.fromPeerJson(File(a["state"] ?: error("--state")).readText())
    val cfg = ConnectConfig(
        ws = a["ws"] ?: error("--ws"),
        derp = a["derp"],
        derpPin = a["derp-pin"],
        derpSni = a["derp-sni"],
        forceRelay = a["force-relay"] == "true",
        blockUdpRx = a["block-udp-rx"] == "true"
    )
    val duration = ((a["duration"] ?: "6").toDouble() * 1000).toLong()
    val expect = a["expect"] ?: "any"
    val cli = SessionClient(st, cfg, log = ::log)
    return try {
        val t0 = System.currentTimeMillis()
        val ok = cli.connect()
        val hsMs = System.currentTimeMillis() - t0
        if (!ok) {
            println("RESULT " + buildJsonObject { put("handshake", false); put("handshake_ms", hsMs) })
            return 1
        }
        val r: EchoResult = cli.echo(durationMs = duration)
        val final = r.finalPath
        val expectOk = when (expect) {
            "any" -> true
            "direct" -> final == "udp"
            "derp" -> final == "derp"
            else -> true
        }
        val minEcho = (a["min-echo"] ?: "0.9").toDouble()
        val ratio = if (r.sent == 0) 0.0 else r.echoed.toDouble() / r.sent
        val pass = expectOk && ratio + 1e-9 >= minEcho
        val summary = buildJsonObject {
            put("handshake", true)
            put("handshake_ms", hsMs)
            put("first_path", r.firstPath ?: "")
            put("sent", r.sent)
            put("echoed", r.echoed)
            put("final_path", final ?: "")
            put("ratio", ratio)
            put("expect", expect)
            put("expect_ok", expectOk)
        }
        println("RESULT $summary")
        if (pass) 0 else 1
    } catch (e: Exception) {
        log("connect failed: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
        1
    } finally {
        cli.close()
    }
}

private fun cmdChat(a: Map<String, String>): Int {
    val st = PairingState.fromPeerJson(File(a["state"] ?: error("--state")).readText())
    val cfg = ConnectConfig(
        ws = a["ws"] ?: error("--ws"),
        derp = a["derp"],
        derpPin = a["derp-pin"],
        derpSni = a["derp-sni"],
        forceRelay = a["force-relay"] == "true",
        blockUdpRx = a["block-udp-rx"] == "true"
    )
    val timeoutMs = ((a["timeout"] ?: "20").toDouble() * 1000).toLong()
    val text = a["text"] ?: "hello from stickdroid"
    val doLong = a["long"] == "true"
    val cli = SessionClient(st, cfg, log = ::log)
    var seq = 1
    fun send(env: Protocol.Envelope) {
        cli.sendApp(env.toBytes())
    }
    return try {
        val t0 = System.currentTimeMillis()
        if (!cli.connect()) {
            println("RESULT {\"handshake\":false}")
            return 1
        }
        val hsMs = System.currentTimeMillis() - t0
        log("handshake $hsMs ms; starting companion chat")
        send(Protocol.hello(Protocol.APP_VERSION, "lab-peer", null))
        val events = ArrayList<String>()
        val answers = ArrayList<String>()
        var welcome = false
        var accepted = false
        var waitPulse = false
        var err: String? = null
        var parts = 0
        var gotParts = 0
        fun drainUntil(deadline: Long, done: () -> Boolean) {
            while (System.currentTimeMillis() < deadline && !done()) {
                val raw = cli.recvApp(200) ?: continue
                val env = try {
                    Protocol.parse(raw)
                } catch (e: Exception) {
                    log("bad envelope ${raw.size} B")
                    continue
                }
                events.add(env.t)
                log("rx ${env.t} ${env.b}")
                when (env.t) {
                    "welcome" -> welcome = true
                    "turn.accepted" -> accepted = true
                    "progress" -> {
                        if (Protocol.str(env.b, "stage") == "wait") waitPulse = true
                    }
                    "answer" -> {
                        answers.add(Protocol.str(env.b, "text") ?: "")
                        parts = Protocol.int(env.b, "parts") ?: 1
                        gotParts++
                    }
                    "error" -> err = Protocol.str(env.b, "code") ?: "error"
                }
            }
        }
        drainUntil(System.currentTimeMillis() + 5_000) { welcome }
        if (!welcome) {
            println("RESULT {\"handshake\":true,\"welcome\":false}")
            return 1
        }
        val turn = Protocol.newId()
        val conv = "c-lab"
        send(Protocol.turnStart(++seq, 0, turn, conv, text))
        drainUntil(System.currentTimeMillis() + timeoutMs) {
            err != null || (gotParts > 0 && gotParts >= parts && parts > 0)
        }
        val shortOk = err == null && accepted && waitPulse && answers.size == 1 && answers[0] == "echo:$text"
        var longOk = !doLong
        if (doLong && shortOk) {
            val turn2 = Protocol.newId()
            accepted = false
            answers.clear()
            parts = 0
            gotParts = 0
            send(Protocol.answerAck(++seq, 0, turn))
            send(Protocol.turnStart(++seq, 0, turn2, conv, "LONG please"))
            drainUntil(System.currentTimeMillis() + timeoutMs) {
                err != null || (gotParts > 0 && gotParts >= parts && parts > 0)
            }
            val combined = answers.joinToString("")
            longOk = err == null && parts >= 2 && gotParts >= parts && combined.length >= 10_000
            log("long parts=$gotParts/$parts bytes=${combined.length}")
        }
        val pass = shortOk && longOk
        val summary = buildJsonObject {
            put("handshake", true)
            put("handshake_ms", hsMs)
            put("welcome", welcome)
            put("accepted", accepted)
            put("wait_pulse", waitPulse)
            put("answer", answers.joinToString("").take(80))
            put("parts", parts)
            put("error", err ?: "")
            put("events", events.joinToString(","))
            put("short_ok", shortOk)
            put("long_ok", longOk)
            put("pass", pass)
        }
        println("RESULT $summary")
        if (pass) 0 else 1
    } catch (e: Exception) {
        log("chat failed: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
        1
    } finally {
        cli.close()
    }
}
