package com.ninkinc.stickdroid.core

/**
 * Hidden instruction appended to each phone message sent to Agent Zero.
 * It is included in the message_async `text` field (the only place A0 reads
 * the turn) but is never shown in the user's chat bubble.
 *
 * When the user attached an image, [IMAGE_INSTRUCTION] is used instead of
 * [INSTRUCTION]. The default "reply briefly in plain text" line made a
 * small local model skip vision_load and just greet, or copy a canned
 * app-launcher refusal from an earlier turn.
 */
object PhonePrompt {
    const val INSTRUCTION =
        "[Phone app] Reply briefly and conversationally in plain text. " +
            "Do not list date/time, agent number, profile, model, or other system/runtime details " +
            "unless the user explicitly asks."

    const val IMAGE_INSTRUCTION =
        "[Phone app] The user attached image file(s); the paths are in the attachments list. " +
            "If they ask what is in an image or to look at it, call the vision_load tool with " +
            "those paths and describe what you see. Do not say you cannot analyze images, and " +
            "do not ask them to open the image in another app. " +
            "Do not list date/time, agent number, profile, model, or other system/runtime details " +
            "unless the user explicitly asks."

    fun instruction(hasImage: Boolean): String = if (hasImage) IMAGE_INSTRUCTION else INSTRUCTION

    fun wrap(userText: String, hasImage: Boolean = false): String {
        val t = userText.trimEnd()
        if (t.contains(INSTRUCTION) || t.contains(IMAGE_INSTRUCTION)) return userText
        return t + "\n\n" + instruction(hasImage)
    }

    /** Strip a trailing phone instruction, for mocks and tests. */
    fun unwrap(sentText: String): String {
        val t = sentText.trimEnd()
        for (inst in listOf(IMAGE_INSTRUCTION, INSTRUCTION)) {
            if (t.endsWith(inst)) return t.substring(0, t.length - inst.length).trimEnd()
        }
        return sentText
    }
}
