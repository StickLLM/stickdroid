package com.ninkinc.stickdroid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeakTextTests {
    @Test fun plainTextUnchanged() {
        assertEquals("The capital of France is Paris.", SpeakText.clean("The capital of France is Paris."))
    }

    @Test fun stripsEmphasisAndHeadings() {
        val src = """
            ## Hello **world**
            This is *italic* and __bold__ too.
            - first
            - second
            1. numbered
        """.trimIndent()
        val out = SpeakText.clean(src)
        assertEquals("Hello world This is italic and bold too. first second numbered", out)
    }

    @Test fun fencedCodeBecomesCode() {
        val src = "Intro\n```kotlin\nval x = 1\n```\nOutro"
        assertEquals("Intro code Outro", SpeakText.clean(src))
    }

    @Test fun shortInlineCodeIsSpoken() {
        assertEquals("Call foo() next.", SpeakText.clean("Call `foo()` next."))
    }

    @Test fun longInlineCodeBecomesCode() {
        val src = "See `this_is_a_very_long_identifier_name` please."
        assertEquals("See code please.", SpeakText.clean(src))
    }

    @Test fun markdownLinkSaysLinkNotUrl() {
        val out = SpeakText.clean("See [the docs](https://example.com/path?q=1) for more.")
        assertEquals("See the docs link for more.", out)
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("https"))
    }

    @Test fun bareUrlsSayLink() {
        val out = SpeakText.clean("Visit https://example.com/a/b and www.example.org/x.")
        assertEquals("Visit link and link", out)
        assertFalse(out.contains("example"))
        assertFalse(out.contains("http"))
        assertFalse(out.contains("www."))
    }

    @Test fun imageMarkdownDropped() {
        val out = SpeakText.clean("Hi ![alt](https://cdn.example/pic.png) there")
        assertEquals("Hi there", out)
        assertFalse(out.contains("cdn"))
    }

    @Test fun onlyCodeYieldsCode() {
        assertEquals("code", SpeakText.clean("```\nsecret\n```"))
    }

    @Test fun whitespaceCollapsed() {
        assertEquals("Hello world", SpeakText.clean("Hello    \n\n   world"))
    }

    @Test fun mixedReply() {
        val src = """
            Sure — try **this**:
            ```
            rm -rf /
            ```
            More at https://link.stickllm.com/help and [chat](https://example.com).
        """.trimIndent()
        val out = SpeakText.clean(src)
        assertTrue(out.startsWith("Sure — try this:"))
        assertTrue(out.contains("code"))
        assertTrue(out.contains("link"))
        assertFalse(out.contains("rm -rf"))
        assertFalse(out.contains("stickllm.com"))
        assertFalse(out.contains("example.com"))
        assertTrue(out.contains("chat link"))
    }
}
