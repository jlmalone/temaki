package com.salientvision.temaki.marshaller

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Marshaller that asks a local model for a structured verdict. It cleans the pane (strips ANSI)
 * before sending, prompts for strict JSON, and parses the verdict back out leniently.
 *
 * The model never decides anything destructive — it only labels state and extracts text — and the
 * pane is sent only to the *local* endpoint configured via [MarshallerConfig]. If the model is
 * unreachable or returns something unparseable, this delegates to [fallback] (the deterministic
 * [HeuristicMarshaller] by default) so a turn is always assessable.
 */
class LlmMarshaller(
    private val client: ChatClient,
    private val fallback: Marshaller = HeuristicMarshaller(),
    private val maxPaneChars: Int = 4000,
) : Marshaller {

    override fun assess(prompt: String, paneSnapshots: List<String>): Assessment {
        require(paneSnapshots.isNotEmpty()) { "paneSnapshots must not be empty" }

        val pane = Ansi.clean(paneSnapshots.last()).takeLastChars(maxPaneChars)
        val user = buildUserPrompt(prompt, pane)

        val raw = try {
            client.complete(SYSTEM_PROMPT, user)
        } catch (e: Exception) {
            return fallbackWith("llm call failed (${e.message})", prompt, paneSnapshots)
        }

        return parseVerdict(raw)
            ?: fallbackWith("llm verdict unparseable", prompt, paneSnapshots)
    }

    private fun fallbackWith(why: String, prompt: String, snapshots: List<String>): Assessment {
        val fb = fallback.assess(prompt, snapshots)
        val tail = fb.rationale?.let { " ($it)" } ?: ""
        return fb.copy(rationale = "$why; fell back to heuristic$tail")
    }

    /** Parse a model reply into an [Assessment], or null if it cannot be understood. Internal for tests. */
    internal fun parseVerdict(raw: String): Assessment? {
        val json = extractJsonObject(raw) ?: return null
        val verdict = try {
            LENIENT.decodeFromString(LlmVerdict.serializer(), json)
        } catch (e: Exception) {
            return null
        }
        val state = mapState(verdict.state) ?: return null
        val confidence = verdict.confidence?.coerceIn(0.0, 1.0) ?: 0.6
        val response = verdict.response
            ?.takeUnless { it.equals("null", ignoreCase = true) }
            ?.let { Ansi.clean(it).trimEnd() }
        return Assessment(state, response, confidence, verdict.rationale ?: "llm verdict")
    }

    private fun buildUserPrompt(prompt: String, pane: String): String = buildString {
        appendLine("PROMPT SENT TO THE AGENT:")
        appendLine(prompt)
        appendLine()
        appendLine("CURRENT TERMINAL PANE (cleaned):")
        appendLine("--- PANE START ---")
        appendLine(pane)
        appendLine("--- PANE END ---")
    }

    private fun mapState(s: String?): SessionState? = when (s?.trim()?.lowercase()) {
        "working", "busy", "running", "thinking", "in_progress" -> SessionState.WORKING
        "awaiting_input", "awaiting", "await", "input", "waiting", "needs_input" ->
            SessionState.AWAITING_INPUT
        "done", "complete", "completed", "finished" -> SessionState.DONE
        "error", "failed", "failure", "crash", "crashed" -> SessionState.ERROR
        else -> null
    }

    /** Pull the first balanced-looking `{ ... }` out of a reply that may have fences or prose. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    @Serializable
    internal data class LlmVerdict(
        val state: String? = null,
        val response: String? = null,
        val confidence: Double? = null,
        val rationale: String? = null,
    )

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        val SYSTEM_PROMPT = """
            You classify the state of an interactive terminal agent session and extract its reply.
            You are given the prompt that was sent and the current terminal pane (already cleaned).
            Respond with ONE JSON object and nothing else, of exactly this shape:
            {"state":"working|awaiting_input|done|error","response":"clean reply text or null","confidence":0.0,"rationale":"short reason"}
            Rules:
            - working: the agent is still producing output or thinking; the turn is not finished.
            - awaiting_input: the agent paused for more input (a prompt, continuation, or confirmation).
            - done: the turn finished; put the agent's clean reply (no prompts, spinners, echoes) in response.
            - error: the session shows an error or crash; put the error text in response.
            - response must contain only the agent's actual answer, terminal noise removed; use null when nothing to return.
            - confidence is your certainty from 0.0 to 1.0.
            - Output strict JSON only. No markdown, no code fences, no commentary.
        """.trimIndent()
    }
}

private fun String.takeLastChars(n: Int): String =
    if (length <= n) this else substring(length - n)
