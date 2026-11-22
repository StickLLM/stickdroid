package com.ninkinc.stickdroid.core

/** Classify a scanned string the same way the camera overlay does. */
object ScanQr {
    enum class Kind { VALID, EXPIRED, NOT_STICKLINK }

    data class Verdict(val kind: Kind, val message: String)

    const val GOT_IT = "Got it, connecting…"
    const val EXPIRED = "Code expired, tap Refresh on the kiosk"
    const val NOT_STICKLINK = "Not a StickLink code"

    fun classify(
        text: String,
        allowDebugHosts: Boolean = false,
        nowUnix: Long = System.currentTimeMillis() / 1000
    ): Verdict {
        return try {
            JoinLink.parse(text, allowDebugHosts = allowDebugHosts, nowUnix = nowUnix)
            Verdict(Kind.VALID, GOT_IT)
        } catch (e: JoinLinkException) {
            if (e.expired) Verdict(Kind.EXPIRED, EXPIRED)
            else Verdict(Kind.NOT_STICKLINK, NOT_STICKLINK)
        }
    }
}
