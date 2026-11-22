package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Companion JSON protocol (design-doc v0.3 §6), carried as D_APP bodies
 * with the §6.1 fragment header because the sl1 pipe is a 1280 B datagram
 * (not a reliable ordered stream).
 *
 * Envelope: {"t": type, "s": seq, "a": ack, "b": body}
 */
object Protocol {
    const val PROTO: Int = 1
    const val APP_VERSION: String = "0.1.0"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class Envelope(val t: String, val s: Int?, val a: Int?, val b: JsonObject) {
        fun toBytes(): ByteArray {
            val obj = buildJsonObject {
                put("t", t)
                if (s != null) put("s", s)
                if (a != null) put("a", a)
                if (b.isNotEmpty()) put("b", b)
            }
            return obj.toString().toByteArray(Charsets.UTF_8)
        }
    }

    fun parse(raw: ByteArray): Envelope {
        val o = json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        val t = o["t"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("missing t")
        val s = o["s"]?.jsonPrimitive?.intOrNull
        val a = o["a"]?.jsonPrimitive?.intOrNull
        val b = o["b"]?.jsonObject ?: JsonObject(emptyMap())
        return Envelope(t, s, a, b)
    }

    fun hello(appVer: String, dev: String, resumeAck: Int?): Envelope {
        val b = buildJsonObject {
            put("proto", PROTO)
            put("app", appVer)
            put("dev", dev)
            if (resumeAck != null) putJsonObject("resume") { put("a", resumeAck) }
        }
        return Envelope("hello", s = 1, a = 0, b = b)
    }

    fun turnStart(s: Int, a: Int, turn: String, conv: String, text: String, ctx: String? = null): Envelope {
        val b = buildJsonObject {
            put("turn", turn)
            put("conv", conv)
            put("text", text)
            if (ctx != null) put("ctx", ctx)
        }
        return Envelope("turn.start", s, a, b)
    }

    fun answerAck(s: Int, a: Int, turn: String) =
        Envelope("answer.ack", s, a, buildJsonObject { put("turn", turn) })

    fun turnCancel(s: Int, a: Int, turn: String) =
        Envelope("turn.cancel", s, a, buildJsonObject { put("turn", turn) })

    fun convNew(s: Int, a: Int, conv: String) =
        Envelope("conv.new", s, a, buildJsonObject { put("conv", conv) })

    fun ping(s: Int, a: Int) = Envelope("ping", s, a, JsonObject(emptyMap()))
    fun pong(s: Int, a: Int) = Envelope("pong", s, a, JsonObject(emptyMap()))
    fun ack(a: Int) = Envelope("ack", s = null, a = a, b = JsonObject(emptyMap()))

    fun newId(): String = Bytes.b64u(Crypto.random(12)) // 96-bit, 16 chars b64u

    fun str(b: JsonObject, k: String): String? = b[k]?.jsonPrimitive?.contentOrNull
    fun int(b: JsonObject, k: String): Int? = b[k]?.jsonPrimitive?.intOrNull
    fun bool(b: JsonObject, k: String): Boolean? = b[k]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        ?: (b[k] as? JsonPrimitive)?.let { if (it == JsonPrimitive(true)) true else if (it == JsonPrimitive(false)) false else null }
}
