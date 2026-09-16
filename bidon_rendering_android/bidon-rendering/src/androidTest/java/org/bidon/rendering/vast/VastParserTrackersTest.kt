package org.bidon.rendering.vast

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.LocalHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The buyer's impression and click trackers have to survive parsing, including the ones a wrapper
 * adds on its way to the ad: every level of a wrapper chain belongs to someone who counts the
 * impression. Companion click trackers are kept apart from the video's, since each belongs to a
 * different click.
 *
 * Instrumented because the parser is built on android.util.Xml, which has no implementation on the
 * JVM. The wrapper chain goes through a local server, the way a real wrapper is fetched.
 */
@RunWith(AndroidJUnit4::class)
class VastParserTrackersTest {

    private val server = LocalHttpServer()

    @After
    fun stopServer() {
        server.close()
    }

    private fun parse(xml: String): VastAd = runBlocking { VastParser().parse(xml).getOrThrow() }

    private fun inline(impressions: String, videoClicks: String = "", companionAds: String = "") = """
        <VAST version="4.2"><Ad id="1"><InLine>
        <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
        $impressions
        <Creatives>
        <Creative><Linear>
        <Duration>00:00:30</Duration>
        <VideoClicks><ClickThrough><![CDATA[https://example.com/advertiser]]></ClickThrough>$videoClicks</VideoClicks>
        <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="360" height="640"><![CDATA[https://example.com/video.mp4]]></MediaFile></MediaFiles>
        </Linear></Creative>
        $companionAds
        </Creatives>
        </InLine></Ad></VAST>
    """.trimIndent()

    @Test
    fun everyImpressionOfAnInlineAdIsKept() {
        val ad = parse(
            inline(
                impressions = """
                    <Impression><![CDATA[https://dsp.example/impression]]></Impression>
                    <Impression><![CDATA[https://verifier.example/impression]]></Impression>
                """
            )
        )

        assertEquals(
            listOf("https://dsp.example/impression", "https://verifier.example/impression"),
            ad.impressions
        )
    }

    @Test
    fun aWrapperChainKeepsTheTrackersOfEveryLevel() {
        server.serve(
            "/inline.xml",
            "application/xml",
            inline(
                impressions = "<Impression><![CDATA[https://dsp.example/impression]]></Impression>",
                videoClicks = "<ClickTracking><![CDATA[https://dsp.example/click]]></ClickTracking>"
            ).toByteArray()
        )
        val wrapper = """
            <VAST version="4.2"><Ad id="1"><Wrapper>
            <AdSystem>test</AdSystem>
            <VASTAdTagURI><![CDATA[${server.url("/inline.xml")}]]></VASTAdTagURI>
            <Impression><![CDATA[https://exchange.example/impression]]></Impression>
            <Creatives><Creative><Linear>
            <VideoClicks><ClickTracking><![CDATA[https://exchange.example/click]]></ClickTracking></VideoClicks>
            </Linear></Creative></Creatives>
            </Wrapper></Ad></VAST>
        """.trimIndent()

        val ad = parse(wrapper)

        assertEquals(
            listOf("https://exchange.example/impression", "https://dsp.example/impression"),
            ad.impressions
        )
        assertEquals(
            listOf("https://exchange.example/click", "https://dsp.example/click"),
            ad.videoClicks?.clickTrackingUrls
        )
    }

    @Test
    fun companionClickTrackersStayWithTheCompanion() {
        val ad = parse(
            inline(
                impressions = "<Impression><![CDATA[https://dsp.example/impression]]></Impression>",
                videoClicks = "<ClickTracking><![CDATA[https://dsp.example/video-click]]></ClickTracking>",
                companionAds = """
                    <Creative><CompanionAds><Companion width="360" height="640">
                    <StaticResource creativeType="image/png"><![CDATA[https://example.com/end-card.png]]></StaticResource>
                    <CompanionClickThrough><![CDATA[https://example.com/advertiser]]></CompanionClickThrough>
                    <CompanionClickTracking><![CDATA[https://dsp.example/end-card-click]]></CompanionClickTracking>
                    <CompanionClickTracking><![CDATA[https://verifier.example/end-card-click]]></CompanionClickTracking>
                    </Companion></CompanionAds></Creative>
                """
            )
        )

        assertEquals(
            listOf("https://dsp.example/end-card-click", "https://verifier.example/end-card-click"),
            ad.companions.single().clickTrackingUrls
        )
        assertEquals(listOf("https://dsp.example/video-click"), ad.videoClicks?.clickTrackingUrls)
    }
}
