package com.salientvision.temaki.marshaller

/**
 * Judges the turn-state of a wrapped terminal-agent session and extracts its response.
 *
 * An implementation is handed the prompt that was injected and an ordered series of pane
 * snapshots (oldest first, newest last) captured while waiting for the turn to settle. From
 * those it must decide whether the agent is [SessionState.WORKING], [SessionState.AWAITING_INPUT],
 * [SessionState.DONE], or in [SessionState.ERROR], and — when applicable — return the clean
 * response text.
 */
fun interface Marshaller {
    /**
     * @param prompt        the text injected into the session for this turn.
     * @param paneSnapshots ordered raw pane captures, oldest first. Must be non-empty; the last
     *                      element is the current pane. Elements may contain ANSI / terminal noise.
     */
    fun assess(prompt: String, paneSnapshots: List<String>): Assessment
}
