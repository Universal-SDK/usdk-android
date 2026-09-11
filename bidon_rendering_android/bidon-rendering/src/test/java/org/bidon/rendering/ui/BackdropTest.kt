package org.bidon.rendering.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Whatever colour the DSP picks for the cross or text, the disc behind it must contrast. */
class BackdropTest {

    @Test
    fun `a light colour gets a dark disc`() {
        assertEquals(0x80000000.toInt(), backdropFor(0xFFFFFFFF.toInt(), pressed = false))
        assertEquals(0x80000000.toInt(), backdropFor(0xFFFFD600.toInt(), pressed = false))
    }

    @Test
    fun `a dark colour gets a light disc`() {
        assertEquals(0x80FFFFFF.toInt(), backdropFor(0xFF000000.toInt(), pressed = false))
        assertEquals(0x80FFFFFF.toInt(), backdropFor(0xFF1A237E.toInt(), pressed = false))
    }

    @Test
    fun `pressing deepens the disc`() {
        assertEquals(0xCC000000.toInt(), backdropFor(0xFFFFFFFF.toInt(), pressed = true))
        assertEquals(0xCCFFFFFF.toInt(), backdropFor(0xFF000000.toInt(), pressed = true))
    }
}
