package org.bidon.rendering.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Whatever colour the DSP picks for the cross or text, the disc behind it must contrast. */
class BackdropTest {

    private val darkDisc = 0xB3000000.toInt()
    private val lightDisc = 0xB3FFFFFF.toInt()

    @Test
    fun `a colour that looks light gets a dark disc`() {
        // Pure red contrasts 5.3:1 with black and 4:1 with white, so it belongs here too.
        listOf(0xFFFFFFFF, 0xFFFFD600, 0xFFFF0000, 0xFF808080).forEach {
            assertEquals(it.toString(16), darkDisc, backdropFor(it.toInt(), pressed = false))
        }
    }

    @Test
    fun `a colour that looks dark gets a light disc`() {
        listOf(0xFF000000, 0xFF1A237E, 0xFF737373).forEach {
            assertEquals(it.toString(16), lightDisc, backdropFor(it.toInt(), pressed = false))
        }
    }

    @Test
    fun `pressing deepens the disc`() {
        assertEquals(0xE6000000.toInt(), backdropFor(0xFFFFFFFF.toInt(), pressed = true))
        assertEquals(0xE6FFFFFF.toInt(), backdropFor(0xFF000000.toInt(), pressed = true))
    }
}
