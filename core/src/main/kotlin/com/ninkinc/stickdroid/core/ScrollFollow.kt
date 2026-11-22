package com.ninkinc.stickdroid.core

/**
 * When the chat list follows new messages, and the count on the "jump to newest" button.
 *
 * - At the bottom: new lines scroll into view (follow).
 * - Scrolled up to read: the list is never moved; new replies are counted instead.
 * - Your own message always brings you to the bottom (you just sent it).
 * - Jumping (the down-arrow button) or scrolling back to the bottom clears the count.
 */
class ScrollFollow {
    var following = true
        private set
    var unseen = 0
        private set
    private var replies = -1

    /** The user scrolled (or a scroll settled); [atBottom] = the newest line is in view. */
    fun onScrolled(atBottom: Boolean) {
        following = atBottom
        if (atBottom) unseen = 0
    }

    /** The lines changed. [replyCount] = finished replies shown; [sentByMe] = a new line of yours. Returns true to scroll to the newest line. */
    fun onLines(replyCount: Int, sentByMe: Boolean): Boolean {
        val added = if (replies < 0) 0 else (replyCount - replies).coerceAtLeast(0)
        replies = replyCount
        if (following || sentByMe) { following = true; unseen = 0; return true }
        unseen += added
        return false
    }

    /** The down-arrow button was tapped. */
    fun onJump() { following = true; unseen = 0 }

    /** Show the down-arrow button? */
    val showJump: Boolean get() = !following
}
