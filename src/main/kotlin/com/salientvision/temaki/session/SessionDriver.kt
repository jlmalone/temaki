package com.salientvision.temaki.session

import com.salientvision.temaki.marshaller.Ansi
import com.salientvision.temaki.marshaller.Assessment
import com.salientvision.temaki.marshaller.HeuristicMarshaller
import com.salientvision.temaki.marshaller.Marshaller
import com.salientvision.temaki.marshaller.SessionState
import java.time.Duration

/**
 * Drives one turn of a live [TmuxSession]: inject the prompt, then poll the pane and hand the
 * growing snapshot series to a [Marshaller] until it reports a terminal state (DONE / AWAITING_INPUT
 * / ERROR) or the turn times out.
 *
 * A verdict is only trusted once the pane has actually changed from the pre-send baseline, so a
 * just-returned prompt from the *previous* turn cannot be mistaken for this turn finishing instantly.
 */
class SessionDriver(
    private val session: TmuxSession,
    private val marshaller: Marshaller = HeuristicMarshaller(),
    private val pollInterval: Duration = Duration.ofMillis(350),
    private val turnTimeout: Duration = Duration.ofSeconds(30),
    private val maxSnapshots: Int = 200,
) {
    fun runTurn(prompt: String): Assessment {
        val baselineClean = Ansi.clean(session.capture())

        session.sendLine(prompt)

        val snapshots = ArrayDeque<String>()
        var last = Assessment(SessionState.WORKING, null, 0.5, "prompt sent, awaiting first capture")
        val deadline = System.nanoTime() + turnTimeout.toNanos()

        while (System.nanoTime() < deadline) {
            sleepMillis(pollInterval.toMillis())

            if (!session.isAlive()) {
                return Assessment(SessionState.ERROR, last.response, 0.9, "session process exited during the turn")
            }

            val snap = session.capture()
            snapshots.addLast(snap)
            while (snapshots.size > maxSnapshots) snapshots.removeFirst()

            last = marshaller.assess(prompt, snapshots.toList())

            val changedFromBaseline = Ansi.clean(snap) != baselineClean
            if (changedFromBaseline && last.isTerminal) {
                return last
            }
        }

        return Assessment(
            SessionState.ERROR, last.response, 0.4,
            "turn timed out after $turnTimeout (last state ${last.state}: ${last.rationale})",
        )
    }

    private fun sleepMillis(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
