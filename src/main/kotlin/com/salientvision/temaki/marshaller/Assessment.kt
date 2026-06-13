package com.salientvision.temaki.marshaller

/** The turn-state of a wrapped terminal-agent session, as judged by a [Marshaller]. */
enum class SessionState {
    /** The agent is still producing output / thinking; the turn is not finished. */
    WORKING,

    /** The agent paused for more input — a prompt, a continuation, a confirmation. */
    AWAITING_INPUT,

    /** The turn finished and a response is available. */
    DONE,

    /** The session reported an error — a stack trace, an error line, a crash. */
    ERROR,
}

/**
 * A marshaller's verdict about a session at a point in time.
 *
 * @property state      the detected turn-state.
 * @property response   the clean response text, stripped of terminal noise. Non-null when the
 *                      turn produced something to hand back (typically [SessionState.DONE] or
 *                      [SessionState.ERROR]); null when there is nothing to return yet.
 * @property confidence how strongly the marshaller trusts this verdict, in `0.0..1.0`.
 * @property rationale  optional short, human-readable note on the reasoning — for debugging.
 */
data class Assessment(
    val state: SessionState,
    val response: String?,
    val confidence: Double,
    val rationale: String? = null,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0.0..1.0, was $confidence" }
    }

    /** True when the turn has reached a terminal state and the driver can stop polling. */
    val isTerminal: Boolean
        get() = state == SessionState.DONE ||
            state == SessionState.ERROR ||
            state == SessionState.AWAITING_INPUT
}
