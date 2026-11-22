package com.ninkinc.stickdroid.core

/**
 * Phone-side display filter for Agent Zero replies that dump system metadata
 * (date/time, agent number, profile, model path, …) even when the user only
 * said hello. Off when the user asked for those details, or when Settings
 * "Show system details" is on.
 */
object ReplyFilter {
    const val FALLBACK = "Hi! How can I help?"

    private val PREAMBLE = Regex(
        "\\bhere are the details you requested\\b\\s*[:.]?|\\bhere is the (information|info) you requested\\b\\s*[:.]?|\\bhere are the (requested )?details\\b\\s*[:.]?",
        RegexOption.IGNORE_CASE
    )

    private val METADATA_KEYS = listOf(
        "current date and time",
        "date and time",
        "agent information",
        "agent number",
        "profile",
        "llm",
        "model",
        "runtime/system info",
        "runtime info",
        "system information",
        "system info",
        "runtime",
    ).sortedByDescending { it.length }

    private val ASKED = Regex(
        "\\b(date and time|date|time|datetime|model|llm|agent number|profile|system details|system information|system info|runtime)\\b",
        RegexOption.IGNORE_CASE
    )

    private val GREETING = Regex(
        "^(hello|hi|hey|howdy|greetings|good (morning|afternoon|evening))([,!. ]*)$",
        RegexOption.IGNORE_CASE
    )

    private val LIST_ITEM = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+")
    private val KEY_LINE = Regex(
        "^\\s*(?:[-*•]|\\d+[.)])?\\s*\\**\\s*([^:*]+?)\\s*\\**\\s*:\\s*.+"
    )
    // Header with no value: "Agent Information:" or "**Agent Information**"
    private val KEY_HEADER = Regex("^\\s*(?:[-*•]|\\d+[.)])?\\s*\\**\\s*([^:*]+?)\\s*\\**\\s*:?\\s*$")

    fun userAskedForSystemDetails(userText: String): Boolean = ASKED.containsMatchIn(userText)

    fun display(reply: String, userText: String, showSystemDetails: Boolean): String {
        if (showSystemDetails || userAskedForSystemDetails(userText)) return reply
        return strip(reply)
    }

    fun strip(reply: String): String {
        val lines = reply.lines().toMutableList()
        val drop = BooleanArray(lines.size)
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            if (PREAMBLE.containsMatchIn(trimmed)) {
                val kept = PREAMBLE.replace(trimmed, "").trim()
                if (kept.isEmpty()) drop[i] = true
                else lines[i] = kept
                i++
                continue
            }
            val key = metadataKey(trimmed)
            if (key != null) {
                drop[i] = true
                val base = indent(raw)
                var j = i + 1
                while (j < lines.size) {
                    if (lines[j].isBlank()) {
                        j++
                        continue
                    }
                    if (isListItem(lines[j]) && indent(lines[j]) > base) {
                        drop[j] = true
                        j++
                        continue
                    }
                    break
                }
                for (k in i + 1 until j) {
                    if (lines[k].isBlank()) drop[k] = true
                }
                i = j
                continue
            }
            i++
        }
        val kept = ArrayList<String>()
        for (n in lines.indices) {
            if (!drop[n]) kept.add(lines[n])
        }
        val text = kept.joinToString("\n").trim().replace(Regex("\n{3,}"), "\n\n")
        if (text.isEmpty()) return FALLBACK
        if (GREETING.matches(text)) return text
        return text
    }

    private fun indent(s: String): Int {
        var n = 0
        for (c in s) {
            if (c == ' ') n++
            else if (c == '\t') n += 2
            else break
        }
        return n
    }

    private fun isListItem(s: String): Boolean = LIST_ITEM.containsMatchIn(s)

    private fun metadataKey(trimmed: String): String? {
        val fromLine = KEY_LINE.matchEntire(trimmed)?.groupValues?.get(1)?.trim()
        val fromHeader = KEY_HEADER.matchEntire(trimmed)?.groupValues?.get(1)?.trim()
        val cand = fromLine ?: fromHeader ?: return null
        val norm = cand.lowercase().trim()
        return METADATA_KEYS.firstOrNull { norm == it }
    }
}
