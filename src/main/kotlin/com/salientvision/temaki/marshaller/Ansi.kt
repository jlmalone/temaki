package com.salientvision.temaki.marshaller

/**
 * Turns a raw terminal pane capture into clean, comparable text: strips ANSI/OSC escape
 * sequences, resolves carriage-return overwrites, and drops stray control characters.
 *
 * A pane captured with `tmux capture-pane -p` is already mostly rendered text, so on clean
 * input these operations are near no-ops; the work matters for richer captures (`-e`) and for
 * messy TUIs that emit spinners and partial redraws.
 *
 * Control and glyph characters are built from code points via [Char] so this source file
 * stays pure ASCII (no raw escape bytes embedded in the regex literals).
 */
object Ansi {
    private val ESC = Char(0x1B)
    private val BEL = Char(0x07)

    private fun range(from: Int, to: Int) = "${Char(from)}-${Char(to)}"

    // CSI: ESC [ <params> <intermediates> <final>  — colors, cursor moves, erases, ...
    private val CSI = Regex("$ESC\\[[0-?]*[ -/]*[@-~]")

    // OSC: ESC ] <data> (BEL | ST)  — window titles, hyperlinks, ...
    private val OSC = Regex("$ESC\\][\\s\\S]*?(?:$BEL|$ESC\\\\)")

    // Charset designations (ESC ( B, ESC ) 0, ...) and lone 2-byte escapes (keypad, reset, ...).
    private val ESC_CHARSET = Regex("$ESC[()][0-9A-Za-z]")
    private val ESC_2BYTE = Regex("$ESC[=>78cDEHM]")

    // C0 control chars except tab (0x09) and newline (0x0A); carriage returns are resolved first.
    private val OTHER_CONTROLS = Regex(
        "[" + range(0x00, 0x08) + Char(0x0B) + Char(0x0C) + range(0x0E, 0x1F) + Char(0x7F) + "]",
    )

    // Glyphs that animate in place (braille spinners, partial circles, blocks). Used only to
    // recognise a line that is *nothing but* animation, never to edit content out of a line.
    private val ANIMATED_GLYPHS = Regex(
        "[" + range(0x2800, 0x28FF) + range(0x25CB, 0x25CF) + range(0x25D0, 0x25D3) +
            range(0x25E2, 0x25E5) + range(0x25F0, 0x25F7) + range(0x2596, 0x259F) + "]",
    )

    /** Remove ANSI/OSC escape sequences from [s]. Carriage returns are left intact. */
    fun strip(s: String): String =
        s.replace(OSC, "")
            .replace(CSI, "")
            .replace(ESC_CHARSET, "")
            .replace(ESC_2BYTE, "")

    /**
     * Fully clean a raw capture: strip escapes, apply carriage-return overwrites per line, drop
     * remaining control characters, and normalise line endings to `\n`. Trailing whitespace on
     * each line is preserved (callers trim where they need to compare).
     */
    fun clean(raw: String): String =
        strip(raw)
            .replace("\r\n", "\n")
            .split('\n')
            .joinToString("\n") { applyCarriageReturns(it).replace(OTHER_CONTROLS, "") }

    /** True when [line] is non-blank but contains only spinner/animation glyphs and whitespace. */
    fun isSpinnerOnly(line: String): Boolean {
        if (line.isBlank()) return false
        return line.replace(ANIMATED_GLYPHS, "").isBlank()
    }

    /**
     * Resolve carriage-return overwrites within one logical line: text after a bare `\r`
     * overwrites from the start of the line, as a terminal renders it. `"abc\rX"` -> `"Xbc"`.
     */
    private fun applyCarriageReturns(line: String): String {
        if ('\r' !in line) return line
        val sb = StringBuilder()
        var col = 0
        for (ch in line) {
            if (ch == '\r') {
                col = 0
                continue
            }
            if (col < sb.length) sb.setCharAt(col, ch) else sb.append(ch)
            col++
        }
        return sb.toString()
    }
}
