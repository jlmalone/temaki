package com.salientvision.temaki.marshaller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeuristicMarshallerTest {
    private val marshaller = HeuristicMarshaller()

    /** A settled pane: the same rendered content repeated past the stability threshold. */
    private fun settled(vararg lines: String): List<String> {
        val pane = lines.joinToString("\n")
        return List(4) { pane }
    }

    @Test
    fun doneWithResultAfterReadyPrompt() {
        val a = marshaller.assess("2+2", settled(">>> 2+2", "4", ">>>"))
        assertEquals(SessionState.DONE, a.state)
        assertEquals("4", a.response)
    }

    @Test
    fun doneEvenFromASingleSnapshotWhenPromptHasReturned() {
        // A returned prompt is itself a settle signal, so one snapshot is enough to conclude.
        val a = marshaller.assess("2+2", listOf(">>> 2+2\n4\n>>>"))
        assertEquals(SessionState.DONE, a.state)
        assertEquals("4", a.response)
    }

    @Test
    fun awaitingOnContinuationPrompt() {
        val a = marshaller.assess("def f():", settled(">>> def f():", "..."))
        assertEquals(SessionState.AWAITING_INPUT, a.state)
        assertNull(a.response)
    }

    @Test
    fun awaitingOnIncompleteBlock() {
        // Howard's bc re-shows ">>>" mid-definition; the open brace is the only awaiting signal.
        val a = marshaller.assess("define f() {", settled(">>> define f() {", ">>>"))
        assertEquals(SessionState.AWAITING_INPUT, a.state)
    }

    @Test
    fun awaitingOnUnterminatedString() {
        val a = marshaller.assess("\"hello", settled(">>> \"hello", ">>>"))
        assertEquals(SessionState.AWAITING_INPUT, a.state)
    }

    @Test
    fun errorOnPythonTraceback() {
        val a = marshaller.assess(
            "1/0",
            settled(">>> 1/0", "Traceback (most recent call last):", "ZeroDivisionError: division by zero", ">>>"),
        )
        assertEquals(SessionState.ERROR, a.state)
    }

    @Test
    fun errorOnBcParseError() {
        val a = marshaller.assess(
            "(2+2",
            settled(">>> (2+2", "Parse error: bad expression", "    <stdin>:1", ">>>"),
        )
        assertEquals(SessionState.ERROR, a.state)
    }

    @Test
    fun workingWhilePaneKeepsChanging() {
        val snaps = listOf(
            ">>> slow\nstep 1",
            ">>> slow\nstep 12",
            ">>> slow\nstep 123",
        )
        val a = marshaller.assess("slow", snaps)
        assertEquals(SessionState.WORKING, a.state)
        assertNull(a.response)
    }

    @Test
    fun doneEmptyWhenPromptReturnsWithNoOutput() {
        val a = marshaller.assess("x = 5", settled(">>> x = 5", ">>>"))
        assertEquals(SessionState.DONE, a.state)
        assertEquals("", a.response)
    }

    @Test
    fun extractsMultilineResponse() {
        val a = marshaller.assess("show", settled(">>> show", "line one", "line two", ">>>"))
        assertEquals(SessionState.DONE, a.state)
        assertEquals("line one\nline two", a.response)
    }

    @Test
    fun emptySnapshotsRejected() {
        assertFailsWith<IllegalArgumentException> { marshaller.assess("x", emptyList()) }
    }

    @Test
    fun confidenceIsAlwaysInRange() {
        for (snaps in listOf(
            settled(">>> 2+2", "4", ">>>"),
            settled(">>> def f():", "..."),
            listOf(">>> slow\nrun"),
        )) {
            val a = marshaller.assess("x", snaps)
            assertTrue(a.confidence in 0.0..1.0, "confidence out of range: ${a.confidence}")
        }
    }
}
