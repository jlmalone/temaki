package com.salientvision.temaki.marshaller

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmMarshallerTest {
    private fun clientReturning(reply: String) = ChatClient { _, _ -> reply }
    private fun failingClient() = ChatClient { _, _ -> throw ChatClientException("no local server") }

    private val doneSnapshots = List(4) { ">>> 2+2\n4\n>>>" }

    @Test
    fun parsesStrictJsonVerdict() {
        val m = LlmMarshaller(clientReturning("""{"state":"done","response":"4","confidence":0.9,"rationale":"clear"}"""))
        val a = m.assess("2+2", listOf(">>> 2+2\n4\n>>>"))
        assertEquals(SessionState.DONE, a.state)
        assertEquals("4", a.response)
        assertTrue(abs(a.confidence - 0.9) < 1e-9)
    }

    @Test
    fun parsesJsonWrappedInProseAndCodeFences() {
        val reply = "Here is my verdict:\n```json\n" +
            """{"state":"awaiting_input","response":null,"confidence":0.8}""" +
            "\n```"
        val m = LlmMarshaller(clientReturning(reply))
        val a = m.assess("def f():", listOf(">>> def f():\n..."))
        assertEquals(SessionState.AWAITING_INPUT, a.state)
        assertNull(a.response)
    }

    @Test
    fun mapsStateSynonyms() {
        val m = LlmMarshaller(clientReturning("""{"state":"completed","response":"ok","confidence":0.7}"""))
        assertEquals(SessionState.DONE, m.assess("x", listOf("x")).state)
    }

    @Test
    fun treatsLiteralNullStringResponseAsAbsent() {
        val m = LlmMarshaller(clientReturning("""{"state":"working","response":"null","confidence":0.5}"""))
        val a = m.assess("x", listOf("running"))
        assertEquals(SessionState.WORKING, a.state)
        assertNull(a.response)
    }

    @Test
    fun fallsBackToHeuristicWhenClientFails() {
        val m = LlmMarshaller(failingClient())
        val a = m.assess("2+2", doneSnapshots)
        assertEquals(SessionState.DONE, a.state)
        assertEquals("4", a.response)
        assertTrue(a.rationale.orEmpty().contains("fell back"))
    }

    @Test
    fun fallsBackToHeuristicWhenVerdictUnparseable() {
        val m = LlmMarshaller(clientReturning("I'm not sure what you mean."))
        val a = m.assess("2+2", doneSnapshots)
        assertEquals(SessionState.DONE, a.state)
        assertTrue(a.rationale.orEmpty().contains("fell back"))
    }

    @Test
    fun trimsTrailingWhitespaceFromResponse() {
        val m = LlmMarshaller(clientReturning("""{"state":"done","response":"Paris   ","confidence":0.9}"""))
        val a = m.assess("capital?", listOf("done"))
        assertEquals(SessionState.DONE, a.state)
        assertEquals("Paris", a.response)
    }

    @Test
    fun clampsOutOfRangeConfidence() {
        val m = LlmMarshaller(clientReturning("""{"state":"done","response":"x","confidence":5.0}"""))
        assertTrue(m.assess("x", listOf("x")).confidence <= 1.0)
    }
}
