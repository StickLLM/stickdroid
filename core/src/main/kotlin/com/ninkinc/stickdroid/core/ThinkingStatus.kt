package com.ninkinc.stickdroid.core

/**
 * Phone-side waiting-bubble copy: a timer, a single sanitized A0 progress
 * line, and a slow-wait hint. Pure functions so the UI and unit tests share
 * the same wording.
 */
object ThinkingStatus {
    const val SLOW_AFTER_MS = 60_000L
    const val SLOW_HINT = "Still working — the stick's model can be slow"
    const val TITLE = "Thinking…"

    private val EMPTY_PROGRESS = setOf("", "0", "waiting for input", "thinking", "thinking…", "thinking...")
    private val SKIP_TYPES = setOf("user", "response")
    private val USEFUL_TYPES = setOf("agent", "util", "tool", "info", "hint", "progress")

    fun timerLabel(elapsedMs: Long): String {
        val s = (elapsedMs.coerceAtLeast(0L) / 1000L)
        return "$TITLE ${s}s"
    }

    /**
     * Lines to show in the waiting bubble, in order:
     * timer, optional slow hint, optional live progress.
     */
    fun lines(elapsedMs: Long, progress: String?): List<String> {
        val out = ArrayList<String>(3)
        out.add(timerLabel(elapsedMs))
        if (elapsedMs >= SLOW_AFTER_MS) out.add(SLOW_HINT)
        sanitize(progress)?.let { out.add(it) }
        return out
    }

    fun display(elapsedMs: Long, progress: String?): String = lines(elapsedMs, progress).joinToString("\n")

    /** One-line, user-facing progress, or null if there's nothing useful to show. */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim().lineSequence().firstOrNull()?.trim() ?: return null
        if (s.isEmpty()) return null
        s = s.replace(Regex("\\s+"), " ")
        s = s.trim('"', '\'', '`', '*')
        s = s.replace(Regex("^(A0|Agent(?:\\s*0)?|Agent Zero)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
        s = s.trim().trimEnd('.', ':')
        if (s.length > 80) s = s.take(79).trimEnd() + "…"
        val lower = s.lowercase()
        if (lower in EMPTY_PROGRESS) return null
        if (isMetadata(s)) return null
        return friendly(s)
    }

    /**
     * Prefer the latest useful log heading (tools, search, …) over the generic
     * log_progress spinner text.
     */
    fun fromPoll(logProgress: String?, headings: List<String>): String? {
        for (i in headings.indices.reversed()) {
            sanitize(headings[i])?.let { return it }
        }
        return sanitize(logProgress)
    }

    fun headingsFromLogs(items: Iterable<Map<String, String>>): List<String> {
        val out = ArrayList<String>()
        for (o in items) {
            val typ = (o["type"] ?: "").lowercase()
            if (typ in SKIP_TYPES) continue
            if (typ.isNotEmpty() && typ !in USEFUL_TYPES) continue
            val heading = o["heading"].orEmpty()
            if (heading.isNotBlank()) out.add(heading)
        }
        return out
    }

    private fun isMetadata(s: String): Boolean {
        val n = s.lowercase()
        return n.contains("current date and time") ||
            n.contains("agent number") ||
            n.contains("agent information") ||
            n.startsWith("llm") ||
            n.startsWith("profile:") ||
            n.contains("system info") ||
            n.contains("qwen") ||
            n.contains(".gguf")
    }

    private fun friendly(s: String): String {
        val n = s.lowercase()
        return when {
            n.contains("using tool") || n.contains("calling tool") || n.contains("function call") -> "Using a tool"
            n.contains("search") || n.contains("brows") -> "Searching"
            n.contains("tool") || n.contains("calling") -> "Using a tool"
            n.contains("code") || n.contains("python") || n.contains("shell") || n.contains("terminal") -> "Running code"
            n.contains("web") && n.contains("search") -> "Searching"
            n.contains("memor") -> "Reading memory"
            else -> s
        }
    }
}
