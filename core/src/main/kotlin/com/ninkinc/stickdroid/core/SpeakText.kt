package com.ninkinc.stickdroid.core

/**
 * Turns a filtered Agent Zero reply into something Android TTS can read:
 * drop markdown and fenced/inline code, and never read URLs verbatim.
 */
object SpeakText {
    private val FENCE = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val MD_LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val BARE_URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\\]]+")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
    private val ITALIC = Regex("(?<!\\w)\\*([^*]+)\\*(?!\\w)|(?<!\\w)_([^_]+)_(?!\\w)")
    private val HEADING = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
    private val LIST = Regex("^[\\t ]*(?:[-*+]|\\d+\\.)\\s+", RegexOption.MULTILINE)
    private val TRAIL_PUNCT = Regex("[\\)\\\\],.;:!?]+$")

    fun clean(src: String): String {
        var s = src.replace("\r\n", "\n")
        s = FENCE.replace(s, " code ")
        s = INLINE_CODE.replace(s) { m ->
            val inner = m.groupValues[1].trim()
            if (inner.length in 1..20 && !inner.contains('\n')) inner else " code "
        }
        s = IMAGE.replace(s, " ")
        s = MD_LINK.replace(s) { m ->
            val label = m.groupValues[1].trim()
            val dest = m.groupValues[2].trim()
            if (isUrl(dest)) listOf(label, "link").filter { it.isNotEmpty() }.joinToString(" ")
            else label
        }
        s = BARE_URL.replace(s) { m ->
            val raw = TRAIL_PUNCT.replace(m.value, "")
            if (raw.isEmpty()) m.value else " link "
        }
        s = BOLD.replace(s) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        s = ITALIC.replace(s) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        s = HEADING.replace(s, "")
        s = LIST.replace(s, "")
        s = s.replace("&amp;", "and").replace("&nbsp;", " ").replace("&lt;", " ").replace("&gt;", " ")
        return Regex("\\s+").replace(s, " ").trim()
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("https://", ignoreCase = true) ||
            s.startsWith("www.", ignoreCase = true) ||
            s.startsWith("mailto:", ignoreCase = true)
}
