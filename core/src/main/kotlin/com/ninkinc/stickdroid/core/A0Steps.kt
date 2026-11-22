package com.ninkinc.stickdroid.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One Agent Zero log item kept on a reply ("Show Agent Zero steps").
 * Fields follow A0 helpers/log.py LogItem.output(): no, type, heading, content, kvps.
 * Tool items carry the tool name in kvps["_tool_name"] (helpers/tool.py get_log_object).
 */
data class A0Step(
    val no: Int,
    val type: String,
    val heading: String,
    val tool: String?,
    val content: String,
    val kvps: List<Pair<String, String>>,
    val truncated: Boolean,
)

object A0Steps {
    /** Per-step budget (heading + content + kvps), UTF-16 chars ≈ bytes for ASCII. */
    const val MAX_STEP_CHARS = 4096
    const val MAX_STEPS = 200
    private val ICON = Regex("^icon://\\S+\\s*")
    private val TOOL_IN_HEADING = Regex("Using tool '([^':]+)")

    fun cleanHeading(h: String?): String = (h ?: "").replace(ICON, "").trim()

    /**
     * Every log item after the user's message, in order. The final reply
     * ([answerNo]) is the bubble itself and is not repeated as a step.
     */
    fun capture(items: Map<Int, JsonObject>, userNo: Int, answerNo: Int? = null): List<A0Step> {
        val out = ArrayList<A0Step>()
        for (no in items.keys.sorted()) {
            if (no <= userNo || no == answerNo) continue
            val o = items[no] ?: continue
            out.add(fromItem(no, o))
            if (out.size >= MAX_STEPS) break
        }
        return out
    }

    fun fromItem(no: Int, o: JsonObject): A0Step {
        val type = str(o["type"]) ?: ""
        val heading = cleanHeading(str(o["heading"]))
        val kv = (o["kvps"] as? JsonObject) ?: JsonObject(emptyMap())
        val tool = str(kv["_tool_name"])?.takeIf { it.isNotBlank() }
            ?: TOOL_IN_HEADING.find(heading)?.groupValues?.get(1)
        var budget = MAX_STEP_CHARS - heading.length
        var truncated = false
        fun take(s: String): String {
            if (budget <= 0) { if (s.isNotEmpty()) truncated = true; return "" }
            return if (s.length <= budget) { budget -= s.length; s }
            else { val r = s.take(budget) + "…"; budget = 0; truncated = true; r }
        }
        val content = take(str(o["content"]) ?: "")
        val kvps = ArrayList<Pair<String, String>>()
        for ((k, v) in kv) {
            if (k == "_tool_name") continue
            if (budget <= 0) { truncated = true; break }
            budget -= k.length
            kvps.add(k to take(flat(v)))
        }
        return A0Step(no, type, heading, tool, content, kvps, truncated)
    }

    /** Compact one-line label for a collapsed step. */
    fun label(s: A0Step): String = when {
        s.heading.isNotBlank() -> s.heading
        s.tool != null -> "Tool: ${s.tool}"
        else -> s.type.ifBlank { "step" }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun flat(e: JsonElement): String = when (e) {
        is JsonNull -> ""
        is JsonPrimitive -> e.contentOrNull ?: ""
        is JsonArray -> e.joinToString(", ") { flat(it) }
        is JsonObject -> e.toString()
    }
}
