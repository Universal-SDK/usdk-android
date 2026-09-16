package org.bidon.rendering.vast

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The skip offset has to come through the real parser, which reads it off `<Linear>` while walking
 * the document; SkipOffsetTest covers how the value itself is read.
 *
 * Instrumented rather than a plain unit test because the parser is built on android.util.Xml, which
 * has no implementation on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class VastParserSkipOffsetTest {

    private fun skipOffsetOf(linearAttributes: String): SkipOffset? {
        val xml = """
            <VAST version="4.2"><Ad id="1"><InLine>
            <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
            <Impression><![CDATA[https://example.com/impression]]></Impression>
            <Creatives><Creative><Linear$linearAttributes>
            <Duration>00:00:30</Duration>
            <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="360" height="640"><![CDATA[https://example.com/video.mp4]]></MediaFile></MediaFiles>
            </Linear></Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()
        return runBlocking { VastParser().parse(xml).getOrThrow().skipOffset }
    }

    @Test
    fun aPercentageReachesTheAd() {
        assertEquals(SkipOffset.Percent(25.0), skipOffsetOf(""" skipoffset="25%""""))
    }

    @Test
    fun aTimeReachesTheAdWithItsMilliseconds() {
        assertEquals(SkipOffset.Time(5_500), skipOffsetOf(""" skipoffset="00:00:05.500""""))
    }

    @Test
    fun noAttributeLeavesTheOffsetToTheFallback() {
        assertNull(skipOffsetOf(""))
    }

    @Test
    fun anUnreadableValueLeavesTheOffsetToTheFallback() {
        assertNull(skipOffsetOf(""" skipoffset="aa:bb:cc""""))
    }
}
