package org.bidon.rendering

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the last stop between a DSP's config and the user's screen, so it is checked
 * against configs nobody would write by hand: every field present or missing, and each one
 * holding any JSON type, huge or negative numbers, or strings that only look like numbers.
 */
class RenderingConfigPropertyTest {

    private val anyValue: Arb<Any> = Arb.choice(
        Arb.numericDouble(),
        Arb.long(),
        Arb.int(),
        Arb.boolean(),
        Arb.string(),
        // Strings the parser gives meaning to, and strings that parse as special numbers on
        // Android, whose org.json coerces "Infinity" and "NaN" where the JVM one does not.
        Arb.element(
            "icon_circle", "text_close", "custom", "top_left", "bottom_right", "#FFF", "#00ff00",
            "vast", "mraid", "html", "5", "-5", "1e308", "Infinity", "-Infinity", "NaN"
        ),
        Arb.constant(JSONObject.NULL),
        Arb.constant(JSONObject()),
        Arb.constant(JSONArray())
    )

    private fun objectWith(keys: List<String>, values: Arb<Any> = anyValue): Arb<JSONObject> =
        arbitrary {
            JSONObject().apply {
                keys.forEach { key -> if (Arb.boolean().bind()) put(key, values.bind()) }
            }
        }

    private val closeButton = objectWith(
        listOf(
            "visible", "delay_seconds", "countdown_visible", "style", "size_dp", "position",
            "color", "opacity", "padding_dp", "custom_asset_url"
        )
    )

    private val impressionTracking = objectWith(listOf("min_viewable_pct", "min_viewable_seconds"))

    private val container: Arb<JSONObject> = arbitrary {
        objectWith(listOf("skip_offset_seconds")).bind().apply {
            if (Arb.boolean().bind()) {
                put("impression_tracking", Arb.choice(impressionTracking, anyValue).bind())
            }
        }
    }

    private val creative = objectWith(listOf("type"))

    private val anyConfig: Arb<JSONObject> = arbitrary {
        JSONObject().apply {
            // A section is usually an object, but it may also be any other value.
            listOf("close_button" to closeButton, "container" to container, "creative" to creative)
                .forEach { (key, section) ->
                    if (Arb.boolean().bind()) put(key, Arb.choice(section, anyValue).bind())
                }
        }
    }

    @Test
    fun `any config parses into one that keeps the close button reachable and visible`() {
        runBlocking {
            checkAll(2_000, anyConfig) { json ->
                val config = RenderingConfig.fromJson(json)

                with(config.closeButton) {
                    assertTrue(json.toString(), delayMs in 0..CloseButtonConfig.MAX_DELAY_MS)
                    assertTrue(json.toString(), opacity >= CloseButtonConfig.MIN_OPACITY)
                    assertTrue(
                        json.toString(),
                        sizeDp in CloseButtonConfig.MIN_SIZE_DP..CloseButtonConfig.MAX_SIZE_DP
                    )
                }
                assertTrue(json.toString(), config.skipOffsetMs >= 0)
            }
        }
    }

    @Test
    fun `any string is either rejected or read as an opaque colour`() {
        runBlocking {
            checkAll(2_000, Arb.string()) { value ->
                parseHexColor(value)?.let { assertEquals(value, 0xFF, it ushr 24) }
            }
        }
    }

    @Test
    fun `every six digit colour reads back as itself`() {
        runBlocking {
            checkAll(2_000, Arb.int(0..0xFFFFFF)) { rgb ->
                val expected = rgb or 0xFF000000.toInt()
                assertEquals(expected, parseHexColor("#%06X".format(rgb)))
                assertEquals(expected, parseHexColor("#%06x".format(rgb)))
            }
        }
    }
}
