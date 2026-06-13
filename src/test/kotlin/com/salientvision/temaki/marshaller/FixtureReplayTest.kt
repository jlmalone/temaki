package com.salientvision.temaki.marshaller

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replays every checked-in fixture (real `bc` / `python3 -q` captures plus the synthetic messy-TUI
 * captures) through the deterministic [HeuristicMarshaller] and checks the verdict against each
 * fixture's `expected.json`. This is the offline regression net for the marshaller's classification.
 */
class FixtureReplayTest {
    private val marshaller = HeuristicMarshaller()

    private fun fixturesRoot(): File {
        val url = javaClass.classLoader.getResource("fixtures")
            ?: error("fixtures resource directory not found on the test classpath")
        return File(url.toURI())
    }

    @Test
    fun everyFixtureClassifiesAsExpected() {
        val fixtures = Fixtures.loadAll(fixturesRoot())
        assertTrue(fixtures.isNotEmpty(), "no fixtures discovered under ${fixturesRoot()}")

        val failures = mutableListOf<String>()
        for (fx in fixtures) {
            val a = marshaller.assess(fx.prompt, fx.snapshots)
            val expectedState = fx.expectedState
            if (expectedState == null) {
                failures += "${fx.name}: fixture has no expected state"
                continue
            }
            if (a.state != expectedState) {
                failures += "${fx.name}: expected $expectedState but got ${a.state}  (rationale: ${a.rationale})"
            }
            if (expectedState == SessionState.DONE && fx.expectedResponse != null && a.response != fx.expectedResponse) {
                failures += "${fx.name}: expected response '${fx.expectedResponse}' but got '${a.response}'"
            }
        }

        assertTrue(failures.isEmpty(), "fixture classification mismatches:\n  " + failures.joinToString("\n  "))
    }
}
