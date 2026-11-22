package com.ninkinc.stickdroid.core

/**
 * 0.3.7: friendly setup messages for when the stick can't answer yet (Nink, 4:56 PM: Agent Zero does
 * not auto-start; "You need to load a model and harness like qwen and agent zero on the kiosk to use
 * this application").
 *
 * We never say "starting": the stick's /api/agent/status reports state "starting" ("Agent Zero is
 * starting...") whenever the image is installed but the container isn't up, even when nobody started it
 * (stickllm_agent.py status_document, the `not upstream_up` branch). Only "installing" is real progress.
 */
object StickSetup {
    /** We know something is missing but not which (e.g. the agent status couldn't be read). */
    const val SETUP_TEXT =
        "To use StickLLM from your phone, load a model (like Qwen) and start Agent Zero on the kiosk. " +
            "Your message will send once Agent Zero is running."

    /** Agent Zero isn't up (gateway 503 agent_not_running / 502 "Agent is not running"). */
    const val AGENT_DOWN_TEXT =
        "Agent Zero isn't running on the stick. Start Agent Zero on the kiosk (and load a model like Qwen " +
            "if you haven't) to use StickLLM from your phone. Your message will send once it's running."

    /** No model: A0's model_not_configured, or the stick relay's "No AI model is loaded" reply. */
    const val NO_MODEL_TEXT =
        "No model is loaded on the stick. Load a model (like Qwen) on the kiosk, then send your message again."

    /** Agent Zero really is installing (the loader writes its own progress line). */
    fun installing(detail: String?) =
        "Agent Zero is still installing on the stick" + (detail.clean()?.let { " ($it)" } ?: "") +
            ". Your message will send once it's running."

    /** Something on the stick stops Agent Zero from running (vault locked, no drive, low space, error...). */
    fun blocked(detail: String) =
        "Agent Zero can't run on the stick yet: $detail. Fix that on the kiosk and start Agent Zero; " +
            "your message will send once it's running."

    /**
     * The message for "Agent Zero is down", from the stick's /api/agent/status ([st] null = unknown).
     * States per stickllm_agent.status_document.
     */
    fun forAgentDown(st: A0Chat.AgentStatus?): String {
        if (st == null) return SETUP_TEXT
        val detail = st.message.clean()
        return when (st.state) {
            "installing" -> installing(detail)
            "locked", "settings_missing", "no_ssd", "low_space", "pull_failed", "error", "needs_install" ->
                detail?.let { blocked(it) } ?: AGENT_DOWN_TEXT
            // "starting" (not necessarily true), "stopped", "running" (gateway still says down), unknown.
            else -> AGENT_DOWN_TEXT
        }
    }

    private fun String?.clean(): String? = this?.trim()?.trimEnd('.', ' ')?.takeIf {
        it.isNotEmpty() && it != "Agent" && !it.contains("starting", ignoreCase = true)
    }?.take(160)
}
