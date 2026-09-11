package org.bidon.rendering

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RenderingConfigTest {

    /**
     * The `rendering` object of a live staging auction on 2026-09-11. The DSP sent no config, so
     * every value in it is a server default.
     */
    private val liveServerDefaults = """
        {
          "close_button": {
            "visible": true, "delay_seconds": 5, "countdown_visible": true, "style": "icon_x",
            "size_dp": 30, "position": "top_right", "color": "#FFFFFF", "opacity": 0.9,
            "padding_dp": 12
          },
          "endcards": {
            "enabled": false, "count": 1, "layout": "single", "auto_advance_seconds": 0,
            "cta_text": "Install Now", "cta_color": "#007AFF", "dismiss_on_background_tap": true
          },
          "container": {
            "format": "interstitial", "orientation": "responsive", "background_color": "#000000",
            "background_blur": false, "max_duration_seconds": 30, "skip_offset_seconds": 15,
            "mute_on_start": true,
            "impression_tracking": { "min_viewable_pct": 50, "min_viewable_seconds": 1 }
          },
          "creative": {
            "type": "static_image", "source": "seatbid.bid.adm", "mraid_version": "3.0",
            "vast_version": "4.2", "vpaid_enabled": false, "html_sandbox_policy": "allow-scripts",
            "preload_strategy": "eager"
          },
          "store_kit": {
            "enabled": false, "trigger": "on_endcard", "delay_seconds": 0,
            "user_dismissable": false, "dismiss_on_close": true, "position": "bottom"
          }
        }
    """.trimIndent()

    private fun parse(json: String) = RenderingConfig.fromJson(JSONObject(json))

    private fun closeButton(fields: String) = parse("""{ "close_button": { $fields } }""").closeButton

    @Test
    fun `the renderer defaults are the server defaults`() {
        // Until the config reaches the device, the renderer runs on its own defaults. Keeping them
        // equal to the server's means nothing changes on screen the day it starts arriving.
        assertEquals(RenderingConfig(), parse(liveServerDefaults))
    }

    @Test
    fun `the defaults are the values the server documents`() {
        val config = RenderingConfig()
        with(config.closeButton) {
            assertEquals(5_000L, delayMs)
            assertEquals(true, showCountdown)
            assertEquals(CloseButtonConfig.Style.ICON_X, style)
            assertEquals(30, sizeDp)
            assertEquals(CloseButtonConfig.Position.TOP_RIGHT, position)
            assertEquals(0xFFFFFFFF.toInt(), color)
            assertEquals(0.9f, opacity)
            assertEquals(12, paddingDp)
        }
        assertEquals(15_000L, config.skipOffsetMs)
        assertNull(config.declaredCreativeType)
        assertEquals(50, config.impressionTracking.minViewablePercent)
        assertEquals(1_000L, config.impressionTracking.minViewableMs)
    }

    @Test
    fun `no config means the defaults`() {
        assertEquals(RenderingConfig(), RenderingConfig.fromJson(null))
        assertEquals(RenderingConfig(), RenderingConfig.fromJson(JSONObject()))
    }

    @Test
    fun `a config the dsp set is read`() {
        val config = parse(
            """
            {
              "close_button": {
                "delay_seconds": 7.5, "countdown_visible": false, "style": "icon_circle",
                "size_dp": 40, "position": "bottom_left", "color": "#F00", "opacity": 0.6,
                "padding_dp": 20
              },
              "container": {
                "skip_offset_seconds": 3,
                "impression_tracking": { "min_viewable_pct": 70, "min_viewable_seconds": 2 }
              },
              "creative": { "type": "mraid" }
            }
            """
        )
        assertEquals(
            RenderingConfig(
                closeButton = CloseButtonConfig(
                    delayMs = 7_500,
                    showCountdown = false,
                    style = CloseButtonConfig.Style.ICON_CIRCLE,
                    sizeDp = 40,
                    position = CloseButtonConfig.Position.BOTTOM_LEFT,
                    color = 0xFFFF0000.toInt(),
                    opacity = 0.6f,
                    paddingDp = 20
                ),
                skipOffsetMs = 3_000,
                declaredCreativeType = CreativeType.MRAID,
                impressionTracking = ImpressionTracking(minViewablePercent = 70, minViewableMs = 2_000)
            ),
            config
        )
    }

    @Test
    fun `the close delay is held to thirty seconds`() {
        assertEquals(30_000L, closeButton(""" "delay_seconds": 3600 """).delayMs)
        assertEquals(0L, closeButton(""" "delay_seconds": -5 """).delayMs)
    }

    @Test
    fun `the close button cannot be hidden`() {
        assertEquals(CloseButtonConfig(), closeButton(""" "visible": false """))
        assertEquals(0.5f, closeButton(""" "opacity": 0 """).opacity)
        assertEquals(16, closeButton(""" "size_dp": 2 """).sizeDp)
    }

    @Test
    fun `sizes are held to their limits`() {
        assertEquals(48, closeButton(""" "size_dp": 500 """).sizeDp)
        assertEquals(32, closeButton(""" "padding_dp": 1000 """).paddingDp)
        assertEquals(0, closeButton(""" "padding_dp": -1 """).paddingDp)
    }

    @Test
    fun `a custom image button is drawn as the default`() {
        val button = closeButton(""" "style": "custom", "custom_asset_url": "https://x.example/c.png" """)
        assertEquals(CloseButtonConfig.Style.ICON_X, button.style)
    }

    @Test
    fun `text close is read`() {
        assertEquals(CloseButtonConfig.Style.TEXT, closeButton(""" "style": "text_close" """).style)
    }

    @Test
    fun `only html and mraid count as declared`() {
        fun declared(type: String) =
            parse("""{ "creative": { "type": "$type" } }""").declaredCreativeType

        assertNull(declared("vast"))
        assertEquals(CreativeType.MRAID, declared("mraid"))
        assertEquals(CreativeType.HTML, declared("html"))
        assertNull(declared("static_image"))
        assertNull(declared("playable"))
        assertNull(declared("native"))
        assertNull(declared("VAST"))
    }

    @Test
    fun `unknown values fall back to the defaults`() {
        val button = closeButton(""" "style": "huge", "position": "center", "color": "white" """)
        assertEquals(CloseButtonConfig(), button)
    }

    @Test
    fun `values of the wrong type fall back to the defaults`() {
        assertEquals(RenderingConfig(), parse("""{ "close_button": "x", "container": 5, "creative": [] }"""))
        assertEquals(
            CloseButtonConfig(),
            closeButton(""" "delay_seconds": "soon", "countdown_visible": 3, "opacity": null """)
        )
    }

    @Test
    fun `hex colours in both forms the server accepts`() {
        assertEquals(0xFFFFFFFF.toInt(), parseHexColor("#FFF"))
        assertEquals(0xFFFFFFFF.toInt(), parseHexColor("#ffffff"))
        assertEquals(0xFF1A2B3C.toInt(), parseHexColor("#1a2B3c"))
    }

    @Test
    fun `anything else is not a colour`() {
        listOf("", "#", "FFFFFF", "#FFFF", "#FFFFFFFF", "#GGGGGG", "#-FFFFF", "#+FFFFF", " #FFF")
            .forEach { assertNull(it, parseHexColor(it)) }
    }

    @Test
    fun `a config built in code cannot bypass the limits`() {
        assertThrows(IllegalArgumentException::class.java) { CloseButtonConfig(delayMs = 30_001) }
        assertThrows(IllegalArgumentException::class.java) { CloseButtonConfig(opacity = 0.1f) }
        assertThrows(IllegalArgumentException::class.java) { CloseButtonConfig(opacity = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { CloseButtonConfig(sizeDp = 100) }
        assertThrows(IllegalArgumentException::class.java) { CloseButtonConfig(color = 0x80FFFFFF.toInt()) }
        assertThrows(IllegalArgumentException::class.java) { RenderingConfig(skipOffsetMs = -1) }
    }
}
