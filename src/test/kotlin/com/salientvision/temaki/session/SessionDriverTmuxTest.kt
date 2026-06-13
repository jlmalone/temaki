package com.salientvision.temaki.session

import com.salientvision.temaki.marshaller.SessionState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live runtime smoke for the session driver. Spawns a real tmux session running `bc -q` (a free
 * local REPL) and drives it end to end. Skipped — not failed — when tmux or bc are unavailable, so
 * `./gradlew test` stays green in environments without them.
 */
class SessionDriverTmuxTest {
    private val tmux = TmuxControl()
    private val sessionName = "temaki-test-${ProcessHandle.current().pid()}"
    private var session: TmuxSession? = null

    private fun bcAvailable(): Boolean =
        listOf("/usr/bin/bc", "/opt/homebrew/bin/bc", "/usr/local/bin/bc", "/bin/bc")
            .any { File(it).canExecute() }

    @AfterTest
    fun tearDown() {
        session?.kill()
    }

    @Test
    fun drivesBcThroughDoneThenAwaiting() {
        assumeTrue(tmux.available(), "tmux not available; skipping live driver smoke")
        assumeTrue(bcAvailable(), "bc not available; skipping live driver smoke")

        val s = TmuxSession(sessionName, tmux).also { session = it }
        s.start("bc -q")
        val driver = SessionDriver(s, turnTimeout = Duration.ofSeconds(15))

        val done = driver.runTurn("2+2")
        assertEquals(SessionState.DONE, done.state, "2+2 should be DONE (rationale: ${done.rationale})")
        assertEquals("4", done.response, "2+2 should extract '4'")

        // Howard's bc re-shows its prompt mid-definition; the open brace is the awaiting signal.
        val awaiting = driver.runTurn("define f() {")
        assertEquals(
            SessionState.AWAITING_INPUT, awaiting.state,
            "open block should be AWAITING_INPUT (rationale: ${awaiting.rationale})",
        )
    }
}
