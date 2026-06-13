package com.salientvision.temaki.marshaller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnsiTest {
    private val esc = Char(0x1B)
    private val bel = Char(0x07)

    @Test
    fun stripsCsiColorSequences() {
        val raw = "${esc}[36mhello${esc}[0m world"
        assertEquals("hello world", Ansi.strip(raw))
    }

    @Test
    fun stripsOscSequences() {
        val raw = "${esc}]0;window title${bel}content"
        assertEquals("content", Ansi.strip(raw))
    }

    @Test
    fun cleanStripsAnsiButKeepsText() {
        val raw = "${esc}[1mThe capital is ${esc}[32mParis${esc}[0m."
        assertEquals("The capital is Paris.", Ansi.clean(raw))
    }

    @Test
    fun cleanResolvesCarriageReturnOverwrite() {
        assertEquals("Xbc", Ansi.clean("abc\rX"))
    }

    @Test
    fun cleanNormalisesCrlf() {
        assertEquals("a\nb", Ansi.clean("a\r\nb"))
    }

    @Test
    fun cleanDropsStrayControlChars() {
        // A bare BEL (0x07) in the middle of text is removed; printable text is preserved.
        assertEquals("ok", Ansi.clean("o${bel}k"))
    }

    @Test
    fun spinnerOnlyDetectsAnimationGlyphLines() {
        assertTrue(Ansi.isSpinnerOnly("⠙"))
        assertTrue(Ansi.isSpinnerOnly("  ⠹  "))
        assertFalse(Ansi.isSpinnerOnly("⠙ working"))
        assertFalse(Ansi.isSpinnerOnly("done"))
        assertFalse(Ansi.isSpinnerOnly("   "))
    }
}
