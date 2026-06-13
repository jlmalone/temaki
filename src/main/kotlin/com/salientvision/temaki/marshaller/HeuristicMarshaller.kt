package com.salientvision.temaki.marshaller

/**
 * Deterministic, always-available marshaller. It hardens the Phase-0 spike's "pane unchanged
 * for N ticks" idea into a real classifier by combining three signals:
 *
 *  1. **Stability** — how many of the most recent snapshots are byte-identical. A pane that has
 *     stopped changing has (probably) finished its turn.
 *  2. **Ready-prompt detection** — a returned shell/REPL prompt (`$`, `>>>`, ...) is a strong
 *     "ready for the next turn" signal; a continuation prompt (`...`) means "needs more input".
 *  3. **New-output extraction** — the text printed after the echoed prompt is the response;
 *     its presence or absence disambiguates DONE from AWAITING_INPUT when there is no prompt
 *     glyph (e.g. `bc`, which prints a result but no prompt).
 *
 * It needs no model and never makes a network call, so it is the safe fallback for
 * [LlmMarshaller] and the default for offline / CI runs.
 */
class HeuristicMarshaller(
    private val stabilityThreshold: Int = 3,
    private val sigilMax: Int = 8,
) : Marshaller {

    init {
        require(stabilityThreshold >= 1) { "stabilityThreshold must be >= 1" }
    }

    override fun assess(prompt: String, paneSnapshots: List<String>): Assessment {
        require(paneSnapshots.isNotEmpty()) { "paneSnapshots must not be empty" }

        val cleaned = paneSnapshots.map { Ansi.clean(it) }
        val latest = cleaned.last()
        val latestLines = latest.split("\n").map { it.trimEnd() }
        val baselineLines = cleaned.first().split("\n").map { it.trimEnd() }

        // How many trailing snapshots are identical to the current one.
        var stable = 1
        for (i in cleaned.size - 2 downTo 0) {
            if (cleaned[i] == latest) stable++ else break
        }

        val lastNonBlank = latestLines.lastOrNull { it.isNotBlank() } ?: ""

        // A continuation prompt is an unambiguous "I need more input to finish this".
        if (isContinuationPrompt(lastNonBlank)) {
            return Assessment(
                SessionState.AWAITING_INPUT, null, 0.9,
                "continuation prompt: '${lastNonBlank.trim()}'",
            )
        }

        val readyNow = isPrimaryReadyPrompt(lastNonBlank)
        // A returned prompt is itself a settle signal; otherwise rely on pane stability.
        val settled = stable >= stabilityThreshold || readyNow

        val response = extractResponse(prompt, latestLines, baselineLines)

        if (response.isNotBlank() && looksLikeError(response)) {
            return Assessment(
                SessionState.ERROR, response, if (settled) 0.8 else 0.6,
                "error markers in new output",
            )
        }

        if (!settled) {
            val conf = (0.55 + 0.1 * (stable - 1)).coerceIn(0.0, 0.85)
            return Assessment(
                SessionState.WORKING, null, conf,
                "pane still changing (stable=$stable/$stabilityThreshold)",
            )
        }

        val incompleteInput = submittedInputLooksIncomplete(latestLines)

        return when {
            response.isNotBlank() -> Assessment(
                SessionState.DONE, response, if (readyNow) 0.9 else 0.8,
                "settled with output" + if (readyNow) " and ready prompt" else "",
            )
            incompleteInput -> Assessment(
                // The prompt may have returned, but the submitted statement is still open (an
                // unclosed brace/quote or trailing line-continuation): the agent needs the rest.
                // REPLs like Howard's `bc` re-show their prompt even mid-definition, so the open
                // input — not a continuation glyph — is the only "awaiting" signal in the pane.
                SessionState.AWAITING_INPUT, null, 0.7,
                "submitted input is incomplete (open block/quote/continuation)",
            )
            readyNow -> Assessment(
                // Prompt returned and nothing new was printed: turn done, empty output.
                SessionState.DONE, "", 0.7, "ready prompt returned, no new output",
            )
            else -> Assessment(
                // Settled, no prompt, no new output: treat as awaiting more input.
                SessionState.AWAITING_INPUT, null, 0.55,
                "settled with no output and no ready prompt",
            )
        }
    }

    /** Text printed after the echoed prompt, with trailing prompt/blank/spinner lines removed. */
    private fun extractResponse(
        prompt: String,
        latestLines: List<String>,
        baselineLines: List<String>,
    ): String {
        val promptTrim = prompt.trim()
        var start = -1
        if (promptTrim.isNotEmpty()) {
            // Prefer a line that is exactly the echoed prompt, scanning from the end.
            start = latestLines.indexOfLast { it.trim() == promptTrim }
            if (start < 0) {
                // Then a line ending with the prompt after a short sigil, e.g. ">>> 2+2".
                start = latestLines.indexOfLast {
                    val t = it.trim()
                    t.endsWith(promptTrim) && (t.length - promptTrim.length) in 1..sigilMax
                }
            }
        }

        val body = if (start >= 0) {
            latestLines.subList(start + 1, latestLines.size)
        } else {
            diffSuffix(latestLines, baselineLines)
        }

        return body
            .filterNot { Ansi.isSpinnerOnly(it) }
            .dropLastWhile { it.isBlank() || isPrimaryReadyPrompt(it) || isContinuationPrompt(it) }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trimEnd()
    }

    /** Lines of [latest] beyond the longest shared prefix with [baseline] — the appended tail. */
    private fun diffSuffix(latest: List<String>, baseline: List<String>): List<String> {
        var i = 0
        while (i < latest.size && i < baseline.size && latest[i] == baseline[i]) i++
        return latest.subList(i, latest.size)
    }

    /** A shell/REPL prompt that has returned to "ready for the next command" (`$`, `>>>`, ...). */
    private fun isPrimaryReadyPrompt(line: String): Boolean {
        val t = line.trimEnd()
        if (t.isEmpty()) return false
        return t.endsWith(">>>") ||
            t.endsWith("$") ||
            t.endsWith("#") ||
            t.endsWith("%") ||
            t.endsWith(HEAVY_ANGLE) ||
            (t.endsWith(">") && !t.endsWith("->") && !t.endsWith("=>") && !t.endsWith(">>"))
    }

    /** A REPL continuation prompt asking for the rest of an incomplete statement (`...`). */
    private fun isContinuationPrompt(line: String): Boolean {
        val t = line.trimStart()
        return t == "..." || t.startsWith("... ")
    }

    /** Conservative scan for error/crash markers in freshly printed [text]. */
    private fun looksLikeError(text: String): Boolean {
        val lower = text.lowercase()
        if (
            lower.contains("traceback (most recent call last)") ||
            lower.contains("command not found") ||
            lower.contains("syntax error") ||
            lower.contains("parse error") ||
            lower.contains("no such file or directory") ||
            lower.contains("segmentation fault") ||
            lower.contains("panic:")
        ) {
            return true
        }
        return text.lineSequence().any { line ->
            val l = line.trim()
            val lower2 = l.lowercase()
            (lower2.contains("error") && l.contains(":")) ||
                l.contains("Exception:") ||
                l.endsWith("Error")
        }
    }

    /**
     * Heuristic: does the most recently submitted input line look syntactically unfinished —
     * an unclosed brace/paren/bracket, a trailing backslash, or an odd number of quotes?
     */
    private fun submittedInputLooksIncomplete(latestLines: List<String>): Boolean {
        val command = latestLines
            .map { stripLeadingPrompt(it) }
            .lastOrNull { it.isNotBlank() }
            ?.trimEnd()
            ?: return false
        if (
            command.endsWith("\\") || command.endsWith("{") ||
            command.endsWith("(") || command.endsWith("[")
        ) {
            return true
        }
        return command.count { it == '"' } % 2 == 1
    }

    /** Strip a leading shell/REPL prompt sigil (`>>> `, `... `, `$ `, ...) from [line] if present. */
    private fun stripLeadingPrompt(line: String): String {
        for (sigil in PROMPT_SIGILS) {
            if (line.startsWith(sigil)) return line.removePrefix(sigil)
        }
        return line
    }

    private companion object {
        // Heavy right-angle prompt glyph used by some agent REPLs, built from its code point.
        val HEAVY_ANGLE = Char(0x276F).toString()

        // Leading prompt sigils stripped to recover the logical input/output of a line. Longer
        // (space-suffixed) variants come first so they win over their bare forms.
        val PROMPT_SIGILS = listOf(
            ">>> ", ">>>", "... ", "...", "\$ ", "# ", "% ", "$HEAVY_ANGLE ", "> ",
        )
    }
}
