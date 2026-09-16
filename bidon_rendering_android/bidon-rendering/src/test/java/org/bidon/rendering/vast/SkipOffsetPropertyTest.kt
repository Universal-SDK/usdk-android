package org.bidon.rendering.vast

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * `skipoffset` comes straight from the DSP's markup, so the reader is checked across every value of
 * each form VAST allows and against strings made of the characters those forms use.
 */
class SkipOffsetPropertyTest {

    @Test
    fun `every time in the VAST form reads back as its milliseconds`() {
        runBlocking {
            checkAll(2_000, Arb.int(0..99), Arb.int(0..59), Arb.int(0..59), Arb.int(0..999)) { h, m, s, ms ->
                val whole = h * 3_600_000L + m * 60_000L + s * 1_000L
                val time = String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)

                assertEquals(time, SkipOffset.Time(whole), SkipOffset.parse(time))
                assertEquals(
                    "$time.$ms",
                    SkipOffset.Time(whole + ms),
                    SkipOffset.parse(String.format(Locale.ROOT, "%s.%03d", time, ms))
                )
            }
        }
    }

    @Test
    fun `every whole percentage reads back and lands on that share of the video`() {
        runBlocking {
            checkAll(2_000, Arb.int(0..100), Arb.long(1L..86_400_000L)) { percent, durationMs ->
                val offset = SkipOffset.parse("$percent%")

                assertEquals(SkipOffset.Percent(percent.toDouble()), offset)
                assertEquals("$percent% of $durationMs", durationMs * percent / 100, offset?.availableAtMs(durationMs))
            }
        }
    }

    private val skipOffsetLike: Arb<String> =
        Arb.list(Arb.element("0123456789:.% -".toList()), 0..14).map { it.joinToString("") }

    @Test
    fun `any string reads without failing and never lands before the start or a share past the end`() {
        runBlocking {
            checkAll(20_000, skipOffsetLike, Arb.long(1L..86_400_000L)) { value, durationMs ->
                val offset = SkipOffset.parse(value) ?: return@checkAll
                val availableAtMs = offset.availableAtMs(durationMs)

                assertTrue("\"$value\" gave $availableAtMs", availableAtMs != null && availableAtMs >= 0)
                if (offset is SkipOffset.Percent) {
                    assertTrue("\"$value\" gave $availableAtMs of $durationMs", availableAtMs!! <= durationMs)
                }
            }
        }
    }
}
