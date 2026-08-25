package org.bidon.bidon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The auction response does not say what kind of creative it carries, so the adapter guesses from
 * the markup. These pin the current behaviour, including where the guess is wrong - the guessing
 * goes away once the DSP's rendering config supplies `creative.type` (BID-51).
 */
class CreativeTypeTest {

    @Test
    fun `plain markup is html`() {
        assertEquals("HTML", creativeTypeOf("<a href=\"x\"><img src=\"y\"/></a>"))
        assertEquals("HTML", creativeTypeOf(""))
    }

    @Test
    fun `a vast document is vast`() {
        assertEquals("VAST", creativeTypeOf("<VAST version=\"4.2\"><Ad/></VAST>"))
    }

    @Test
    fun `markup pulling in mraid js is mraid`() {
        assertEquals("MRAID", creativeTypeOf("<script src=\"mraid.js\"></script><div/>"))
    }

    @Test
    fun `detection ignores case`() {
        assertEquals("VAST", creativeTypeOf("<vast version=\"3.0\">"))
        assertEquals("MRAID", creativeTypeOf("<script src=\"MRAID.JS\">"))
    }

    @Test
    fun `vast wins over mraid when both appear`() {
        // A VAST document whose companion happens to reference mraid.js must still be played as
        // video, not handed to the MRAID renderer.
        assertEquals("VAST", creativeTypeOf("<VAST><Companion>mraid.js</Companion></VAST>"))
    }

    @Test
    fun `known false positive - html that merely mentions mraid js is misrouted`() {
        // Documented, not desired. An HTML creative that only names the file in a comment or a
        // tracking URL is sent to the MRAID renderer. Nothing in the markup distinguishes it,
        // which is why the creative type belongs in the bid response.
        assertEquals("MRAID", creativeTypeOf("<!-- this creative does not use mraid.js -->"))
        assertEquals("MRAID", creativeTypeOf("<img src=\"https://t.example/px?ref=mraid.js\"/>"))
    }
}
