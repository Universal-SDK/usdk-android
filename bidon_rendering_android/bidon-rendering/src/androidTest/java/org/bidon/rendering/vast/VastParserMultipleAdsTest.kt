package org.bidon.rendering.vast

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.LocalHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A response with several `<Ad>` elements plays one of them, so only that ad's pixels, video, click
 * and end card may come out of the parser: a buyer is never told about an ad nobody saw. A pod's
 * first ad wins where pods are allowed, otherwise the first stand-alone ad, and a wrapper decides
 * for the response it fetches.
 *
 * Every ad is named by a letter and keeps all its URLs under its own host (`ad-a.example`), so that
 * "nothing of another ad" is a plain check over every URL of the result. The skip offset is the
 * ad's position in the alphabet in seconds, which makes it a fingerprint of the ad as well.
 *
 * Instrumented because the parser is built on android.util.Xml, which has no implementation on the
 * JVM. Wrapped responses are served by a local server, the way a real wrapper is fetched.
 */
@RunWith(AndroidJUnit4::class)
class VastParserMultipleAdsTest {

    private val server = LocalHttpServer()

    @After
    fun stopServer() {
        server.close()
    }

    private fun parseResult(xml: String): Result<VastAd> = runBlocking { VastParser().parse(xml) }

    private fun parse(xml: String): VastAd = parseResult(xml).getOrThrow()

    private fun skipSecondsOf(name: String) = name[0] - 'a' + 1

    private fun host(name: String) = "https://ad-$name.example"

    private fun vast(vararg ads: String) = """<VAST version="4.2">${ads.joinToString("\n")}</VAST>"""

    /** An inline ad with its own impression, tracking, click, media file, skip offset and end card. */
    private fun inlineAd(name: String, attributes: String = ""): String = """
        <Ad id="$name"$attributes><InLine>
        <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
        <Impression><![CDATA[${host(name)}/impression]]></Impression>
        <Creatives>
        <Creative><Linear skipoffset="00:00:0${skipSecondsOf(name)}">
        <Duration>00:00:30</Duration>
        <TrackingEvents><Tracking event="start"><![CDATA[${host(name)}/start]]></Tracking></TrackingEvents>
        <VideoClicks>
        <ClickThrough><![CDATA[${host(name)}/advertiser]]></ClickThrough>
        <ClickTracking><![CDATA[${host(name)}/click]]></ClickTracking>
        </VideoClicks>
        <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="360" height="640"><![CDATA[${host(name)}/video.mp4]]></MediaFile></MediaFiles>
        </Linear></Creative>
        <Creative><CompanionAds><Companion width="360" height="640">
        <StaticResource creativeType="image/png"><![CDATA[${host(name)}/end-card.png]]></StaticResource>
        <TrackingEvents><Tracking event="creativeView"><![CDATA[${host(name)}/end-card-view]]></Tracking></TrackingEvents>
        <CompanionClickThrough><![CDATA[${host(name)}/end-card-advertiser]]></CompanionClickThrough>
        <CompanionClickTracking><![CDATA[${host(name)}/end-card-click]]></CompanionClickTracking>
        </Companion></CompanionAds></Creative>
        </Creatives>
        </InLine></Ad>
    """.trimIndent()

    /** A wrapper ad with its own impression, tracking and click tracker, leading to [tagUrl]. */
    private fun wrapperAd(
        name: String,
        tagUrl: String,
        attributes: String = "",
        wrapperAttributes: String = ""
    ): String = """
        <Ad id="$name"$attributes><Wrapper$wrapperAttributes>
        <AdSystem>test</AdSystem>
        <VASTAdTagURI><![CDATA[$tagUrl]]></VASTAdTagURI>
        <Impression><![CDATA[${host(name)}/impression]]></Impression>
        <Creatives><Creative><Linear>
        <TrackingEvents><Tracking event="start"><![CDATA[${host(name)}/start]]></Tracking></TrackingEvents>
        <VideoClicks><ClickTracking><![CDATA[${host(name)}/click]]></ClickTracking></VideoClicks>
        </Linear></Creative></Creatives>
        </Wrapper></Ad>
    """.trimIndent()

    private fun sequence(value: Any) = """ sequence="$value""""

    /** Serves [ads] as the response behind a wrapper and returns the wrapper's response pointing to it. */
    private fun wrappedResponse(wrapperAttributes: String, vararg ads: String): String {
        server.serve("/fetched.xml", "application/xml", vast(*ads).toByteArray())
        return vast(wrapperAd("w", server.url("/fetched.xml"), wrapperAttributes = wrapperAttributes))
    }

    /**
     * The ad is the one named [name] and nothing but it: its impression, tracking, click,
     * media file, skip offset and end card, behind those of the wrapper named [via] when it was
     * reached through one. No URL anywhere in the result belongs to any of the [others].
     */
    private fun assertPlays(ad: VastAd, name: String, others: List<String>, via: String? = null) {
        val viaHost = via?.let(::host)

        assertEquals(listOfNotNull(viaHost?.plus("/impression"), host(name) + "/impression"), ad.impressions)
        assertEquals(listOf(host(name) + "/video.mp4"), ad.mediaFiles.map { it.url })
        assertEquals(
            listOfNotNull(viaHost?.plus("/start"), host(name) + "/start"),
            ad.trackingEvents.map { it.url }
        )
        assertEquals(List(ad.trackingEvents.size) { "start" }, ad.trackingEvents.map { it.event })
        assertEquals(host(name) + "/advertiser", ad.videoClicks?.clickThroughUrl)
        assertEquals(
            listOfNotNull(viaHost?.plus("/click"), host(name) + "/click"),
            ad.videoClicks?.clickTrackingUrls
        )
        assertEquals(SkipOffset.Time(skipSecondsOf(name) * 1_000L), ad.skipOffset)

        val companion = ad.companions.single()
        assertEquals(CompanionResource.StaticImage(host(name) + "/end-card.png", "image/png"), companion.resource)
        assertEquals(host(name) + "/end-card-advertiser", companion.clickThroughUrl)
        assertEquals(listOf(host(name) + "/end-card-click"), companion.clickTrackingUrls)
        assertEquals(
            listOf(TrackingEvent("creativeView", host(name) + "/end-card-view")),
            companion.trackingEvents
        )

        val urls = ad.impressions + ad.mediaFiles.map { it.url } + ad.trackingEvents.map { it.url } +
            listOfNotNull(ad.videoClicks?.clickThroughUrl) + ad.videoClicks?.clickTrackingUrls.orEmpty() +
            ad.companions.flatMap { c ->
                listOfNotNull(c.clickThroughUrl) + c.clickTrackingUrls + c.trackingEvents.map { it.url } +
                    (c.resource as? CompanionResource.StaticImage)?.url.let(::listOfNotNull)
            }
        others.forEach { other ->
            urls.filter { host(other) in it }.let { assertTrue("$other leaked into the ad: $it", it.isEmpty()) }
        }
    }

    private fun assertFailsWith303(result: Result<VastAd>) {
        val failure = result.exceptionOrNull()
        assertNotNull("the response parsed", failure)
        assertTrue("was $failure", failure is VastParsingException)
        assertEquals(303, (failure as VastParsingException).vastError.code)
        assertEquals("No ad in the wrapped response may play", failure.message)
    }

    @Test
    fun aPodPlaysItsFirstAdWhateverTheDocumentOrder() {
        val ad = parse(vast(inlineAd("a", sequence(2)), inlineAd("b", sequence(1))))

        assertPlays(ad, "b", others = listOf("a"))
    }

    @Test
    fun aBuffetPlaysItsFirstAd() {
        val ad = parse(vast(inlineAd("a"), inlineAd("b")))

        assertPlays(ad, "a", others = listOf("b"))
    }

    @Test
    fun aPodBeatsAStandAloneAdThatComesFirst() {
        val ad = parse(vast(inlineAd("a"), inlineAd("b", sequence(1)), inlineAd("c", sequence(2))))

        assertPlays(ad, "b", others = listOf("a", "c"))
    }

    @Test
    fun anAdWithASequenceOfZeroIsStandAlone() = assertInvalidSequenceIsStandAlone("0")

    @Test
    fun anAdWithANegativeSequenceIsStandAlone() = assertInvalidSequenceIsStandAlone("-1")

    @Test
    fun anAdWithANonNumericSequenceIsStandAlone() = assertInvalidSequenceIsStandAlone("x")

    /** Were the ad read as a pod, 0 and -1 would sort before the pod ad and win; here it must lose to it. */
    private fun assertInvalidSequenceIsStandAlone(value: String) {
        val topLevel = parse(vast(inlineAd("a", sequence(value)), inlineAd("b", sequence(2))))
        assertPlays(topLevel, "b", others = listOf("a"))

        // Where only stand-alone ads may play, the same ad is the one that does.
        val wrapped = parse(wrappedResponse("", inlineAd("b", sequence(1)), inlineAd("a", sequence(value))))
        assertPlays(wrapped, "a", others = listOf("b"), via = "w")
    }

    @Test
    fun aWrappedResponsePlaysItsFirstStandAloneAdWhenTheWrapperSaysNothingOfPods() {
        val ad = parse(
            wrappedResponse("", inlineAd("a", sequence(1)), inlineAd("b"), inlineAd("c"))
        )

        assertPlays(ad, "b", others = listOf("a", "c"), via = "w")
    }

    @Test
    fun aWrappedResponsePlaysItsFirstStandAloneAdWhenTheWrapperForbidsPods() {
        val ad = parse(
            wrappedResponse(""" allowMultipleAds="false"""", inlineAd("a", sequence(1)), inlineAd("b"), inlineAd("c"))
        )

        assertPlays(ad, "b", others = listOf("a", "c"), via = "w")
    }

    @Test
    fun aWrappedResponsePlaysItsPodWhenTheWrapperAllowsMultipleAds() {
        val ad = parse(
            wrappedResponse(
                """ allowMultipleAds="true"""",
                inlineAd("a", sequence(2)),
                inlineAd("b", sequence(1)),
                inlineAd("c")
            )
        )

        assertPlays(ad, "b", others = listOf("a", "c"), via = "w")
    }

    @Test
    fun aWrappedResponseOfOnlySequencedAdsFailsWithNoAdWhenTheWrapperSaysNothingOfPods() {
        val result = parseResult(wrappedResponse("", inlineAd("a", sequence(1)), inlineAd("b", sequence(2))))

        assertFailsWith303(result)
    }

    @Test
    fun aWrappedResponseOfOnlySequencedAdsFailsWithNoAdWhenTheWrapperForbidsPods() {
        val result = parseResult(
            wrappedResponse(""" allowMultipleAds="false"""", inlineAd("a", sequence(1)), inlineAd("b", sequence(2)))
        )

        assertFailsWith303(result)
    }

    @Test
    fun aWrappedResponseWithNoAdsFailsWithNoAd() {
        val result = parseResult(wrappedResponse(""))

        assertFailsWith303(result)
    }

    @Test
    fun onlyTheChosenWrapperIsFollowedWhenTheResponseHasSeveralAds() {
        server.serve("/chosen.xml", "application/xml", vast(inlineAd("c")).toByteArray())
        server.serve("/other.xml", "application/xml", vast(inlineAd("d")).toByteArray())
        val response = vast(
            wrapperAd("a", server.url("/other.xml"), attributes = sequence(2)),
            wrapperAd("b", server.url("/chosen.xml"), attributes = sequence(1))
        )

        val ad = parse(response)

        assertPlays(ad, "c", others = listOf("a", "d"), via = "b")
        assertEquals(1, server.requestCount("/chosen.xml"))
        assertFalse("the other wrapper was fetched", server.requestCount("/other.xml") > 0)
    }

    /** Serves [body] at [path] and returns a wrapper ad leading to it. */
    private fun wrapperTo(name: String, path: String, body: String, wrapperAttributes: String = ""): String {
        server.serve(path, "application/xml", body.toByteArray())
        return wrapperAd(name, server.url(path), wrapperAttributes = wrapperAttributes)
    }

    @Test
    fun aWrappedSingleAdWithASequencePlays() {
        val ad = parse(wrappedResponse("", inlineAd("a", sequence(1))))

        assertPlays(ad, "a", others = emptyList(), via = "w")
    }

    @Test
    fun aTopLevelSingleAdWithASequencePlays() {
        val ad = parse(vast(inlineAd("a", sequence(3))))

        assertPlays(ad, "a", others = emptyList())
    }

    @Test
    fun aWrapperWithAllowMultipleAdsOnePlaysThePod() {
        val ad = parse(
            wrappedResponse(""" allowMultipleAds="1"""", inlineAd("a", sequence(2)), inlineAd("b", sequence(1)))
        )

        assertPlays(ad, "b", others = listOf("a"), via = "w")
    }

    @Test
    fun aWrapperWithAnyOtherAllowMultipleAdsValueDoesNotAllowPods() {
        listOf("TRUE", "0", "yes", "").forEach { value ->
            val result = parseResult(
                wrappedResponse(
                    """ allowMultipleAds="$value"""",
                    inlineAd("a", sequence(1)),
                    inlineAd("b", sequence(2))
                )
            )

            assertFailsWith303(result)
        }
    }

    @Test
    fun aWrapperWithPaddedAllowMultipleAdsAllowsPods() {
        val ad = parse(
            wrappedResponse(""" allowMultipleAds=" true """", inlineAd("a", sequence(2)), inlineAd("b", sequence(1)))
        )

        assertPlays(ad, "b", others = listOf("a"), via = "w")
    }

    @Test
    fun anInnerWrapperWithoutAllowMultipleAdsForbidsAPodTheOuterOneAllowed() {
        val pod = vast(inlineAd("a", sequence(1)), inlineAd("b", sequence(2)))
        val inner = vast(wrapperTo("x", "/pod.xml", pod))
        val response = vast(wrapperTo("w", "/inner.xml", inner, """ allowMultipleAds="true""""))

        assertFailsWith303(parseResult(response))
    }

    @Test
    fun anInnerWrapperWithAllowMultipleAdsAllowsAPodTheOuterOneForbade() {
        val pod = vast(inlineAd("a", sequence(2)), inlineAd("b", sequence(1)))
        val inner = vast(wrapperTo("x", "/pod.xml", pod, """ allowMultipleAds="true""""))
        val response = vast(wrapperTo("w", "/inner.xml", inner))

        val ad = parse(response)

        assertEquals(listOf(host("w") + "/impression", host("x") + "/impression", host("b") + "/impression"), ad.impressions)
        assertEquals(listOf(host("b") + "/video.mp4"), ad.mediaFiles.map { it.url })
        assertEquals(SkipOffset.Time(skipSecondsOf("b") * 1_000L), ad.skipOffset)
        assertTrue("a leaked into the ad", (ad.impressions + ad.trackingEvents.map { it.url }).none { host("a") in it })
    }

    @Test
    fun anUnchosenWrapperAllowingPodsDoesNotAllowThemForTheChosenOne() {
        val pod = vast(inlineAd("c", sequence(1)), inlineAd("d", sequence(2)))
        server.serve("/pod.xml", "application/xml", pod.toByteArray())
        val response = vast(
            wrapperAd("a", server.url("/other.xml"), attributes = sequence(2), wrapperAttributes = """ allowMultipleAds="true""""),
            wrapperAd("b", server.url("/pod.xml"), attributes = sequence(1))
        )

        assertFailsWith303(parseResult(response))
        assertFalse("the other wrapper was fetched", server.requestCount("/other.xml") > 0)
    }

    @Test
    fun aRootLevelErrorElementDoesNotShiftTheChoiceOrLeakItsUrl() {
        val error = "<Error><![CDATA[https://ad-z.example/error]]></Error>"
        val ad = parse(
            """<VAST version="4.2">$error${inlineAd("a", sequence(2))}$error${inlineAd("b", sequence(1))}$error</VAST>"""
        )

        assertPlays(ad, "b", others = listOf("a", "z"))
    }

    @Test
    fun anElementOutsideEveryAdIsNeverRead() {
        val stray = "<Impression><![CDATA[https://ad-z.example/impression]]></Impression>"
        val ad = parse("""<VAST version="4.2">${inlineAd("a")}$stray${inlineAd("b")}</VAST>""")

        assertPlays(ad, "a", others = listOf("b", "z"))
    }

    @Test
    fun anAdNestedInsideAnAdIsNotASeparateAd() {
        // Placed before the ad's own pixels, so counting it as an ad would lose them.
        val nested = """<Extensions><Extension><Ad id="nested"><InLine><AdSystem>nested</AdSystem></InLine></Ad></Extension></Extensions>"""
        val a = inlineAd("a").replaceFirst("<InLine>", "<InLine>$nested")
        check(nested in a)

        val ad = parse(vast(a, inlineAd("b")))

        assertPlays(ad, "a", others = listOf("b"))
    }

    @Test
    fun aResponseWithoutAdsParsesToAnEmptyAd() {
        val ad = parse("""<VAST version="4.2"></VAST>""")

        assertTrue(ad.impressions.isEmpty())
        assertTrue(ad.mediaFiles.isEmpty())
    }

    @Test
    fun aSequenceWithSurroundingSpacesCountsAsThatSequence() {
        val ad = parse(vast(inlineAd("a"), inlineAd("b", sequence(" 1 "))))

        assertPlays(ad, "b", others = listOf("a"))
    }
}
