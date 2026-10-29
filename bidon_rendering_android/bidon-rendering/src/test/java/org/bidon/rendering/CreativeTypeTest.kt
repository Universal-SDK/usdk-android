package org.bidon.rendering

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server fills in `static_image` when the DSP declares nothing, so most bids arrive without a
 * usable type and the markup has to decide. A type the DSP did declare settles what the markup
 * cannot.
 */
class CreativeTypeTest {

    private val undeclared = AdRenderingConfig()

    private fun declared(type: CreativeType) = AdRenderingConfig(declaredType = type.name.lowercase())

    @Test
    fun `plain markup is html`() {
        assertEquals(CreativeType.HTML, CreativeType.of("<a href=\"x\"><img src=\"y\"/></a>", undeclared))
        assertEquals(CreativeType.HTML, CreativeType.of("", undeclared))
    }

    @Test
    fun `a vast document is vast`() {
        assertEquals(CreativeType.VAST, CreativeType.of("<VAST version=\"4.2\"><Ad/></VAST>", undeclared))
    }

    @Test
    fun `markup pulling in mraid js is mraid`() {
        assertEquals(
            CreativeType.MRAID,
            CreativeType.of("<script src=\"mraid.js\"></script><div/>", undeclared)
        )
    }

    @Test
    fun `detection ignores case`() {
        assertEquals(CreativeType.VAST, CreativeType.of("<vast version=\"3.0\">", undeclared))
        assertEquals(CreativeType.MRAID, CreativeType.of("<script src=\"MRAID.JS\">", undeclared))
    }

    @Test
    fun `vast wins over mraid when both appear`() {
        // A VAST document whose companion happens to reference mraid.js must still be played as
        // video, not handed to the MRAID renderer.
        assertEquals(
            CreativeType.VAST,
            CreativeType.of("<VAST><Companion>mraid.js</Companion></VAST>", undeclared)
        )
    }

    @Test
    fun `known false positive - with nothing declared, html that mentions mraid js is misrouted`() {
        // Documented, not desired. Nothing in the markup tells this apart from a real MRAID
        // creative; only a declared type does, as the next test shows.
        assertEquals(
            CreativeType.MRAID,
            CreativeType.of("<!-- this creative does not use mraid.js -->", undeclared)
        )
        assertEquals(
            CreativeType.MRAID,
            CreativeType.of("<img src=\"https://t.example/px?ref=mraid.js\"/>", undeclared)
        )
    }

    @Test
    fun `a declared html creative stays html even when it mentions mraid js`() {
        assertEquals(
            CreativeType.HTML,
            CreativeType.of("<!-- this creative does not use mraid.js -->", declared(CreativeType.HTML))
        )
    }

    @Test
    fun `a declared mraid creative is mraid even when the markup never names mraid js`() {
        assertEquals(
            CreativeType.MRAID,
            CreativeType.of("<div id=\"ad\"></div>", declared(CreativeType.MRAID))
        )
    }

    @Test
    fun `vast markup is video whatever the dsp declared`() {
        val vast = "<VAST version=\"4.2\"><Ad/></VAST>"
        assertEquals(CreativeType.VAST, CreativeType.of(vast, declared(CreativeType.HTML)))
        assertEquals(CreativeType.VAST, CreativeType.of(vast, declared(CreativeType.MRAID)))
    }

    @Test
    fun `a bid declaring vast is routed by its markup`() {
        // VAST markup is recognised on its own, so a declared vast adds nothing. Markup that is not
        // VAST must not be sent to the video renderer only to fail there.
        val declaredVast = AdRenderingConfig.fromJson(JSONObject("""{ "creative": { "type": "vast" } }"""))
        assertEquals(CreativeType.HTML, CreativeType.of("<div/>", declaredVast))
        assertEquals(CreativeType.MRAID, CreativeType.of("<script src=\"mraid.js\"></script>", declaredVast))
    }
}
