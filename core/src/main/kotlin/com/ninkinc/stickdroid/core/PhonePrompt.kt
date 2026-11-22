package com.ninkinc.stickdroid.core

/**
 * Hidden instruction appended to each phone message sent to Agent Zero.
 * It is included in the message_async `text` field (the only place A0 reads
 * the turn) but is never shown in the user's chat bubble.
 */
object PhonePrompt {
    const val INSTRUCTION =
        "[Phone app] Reply briefly and conversationally in plain text. " +
            "Do not list date/time, agent number, profile, model, or other system/runtime details " +
            "unless the user explicitly asks."

    fun wrap(userText: String): String {
        val t = userText.trimEnd()
        if (t.contains(INSTRUCTION)) return userText
        return t + "\n\n" + INSTRUCTION
    }

    /** Strip a trailing phone instruction, for mocks and tests. */
    fun unwrap(sentText: String): String {
        val t = sentText.trimEnd()
        if (!t.endsWith(INSTRUCTION)) return sentText
        return t.substring(0, t.length - INSTRUCTION.length).trimEnd()
    }
}
