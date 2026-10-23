package org.bidon.rendering.vast

import android.security.NetworkSecurityPolicy
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

internal class VastParser {

    companion object {
        private const val MAX_WRAPPER_DEPTH = 5
    }

    /*suspend fun parse(
        xml: String
    ): Result<VastAd> {

        return parseInternal(
            xml = xml,
            wrapperDepth = 0,
            inheritedImpressions = mutableListOf(),
            inheritedTracking = mutableListOf(),
            inheritedClickTracking = mutableListOf()
        )
    }*/

    suspend fun parse(xml: String): Result<VastAd> {
        val normalized = xml
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\${AUCTION_PRICE}", "")
            .replace("%24%7BAUCTION_PRICE%7D", "0")
            .trim()

        return parseInternal(
            xml = normalized,
            wrapperDepth = 0,
            inheritedImpressions = mutableListOf(),
            inheritedTracking = mutableListOf(),
            inheritedClickTracking = mutableListOf(),
            inheritedCompanions = mutableListOf()
        )
    }

    suspend fun parseInternal(
        xml: String,
        wrapperDepth: Int,
        inheritedImpressions: MutableList<String>,
        inheritedTracking: MutableList<TrackingEvent>,
        inheritedClickTracking: MutableList<String>,
        inheritedCompanions: MutableList<VastCompanion> = mutableListOf(),
        podsAllowed: Boolean = true
    ): Result<VastAd> = withContext(Dispatchers.IO) {

        try {

            // Only one ad of the response is read, so only that ad's pixels can fire.
            val sequences = adSequences(xml)
            val chosenAd = VastAdChoice.choose(sequences, podsAllowed)
            if (chosenAd == null && wrapperDepth > 0) {
                return@withContext Result.failure(
                    VastParsingException(
                        VastError(
                            303,
                            "No ad in the wrapped response may play"
                        )
                    )
                )
            }

            val parser = newParser(xml)

            var eventType = parser.eventType

            var adIndex = -1
            // A document with no <Ad> directly under <VAST> is read whole, as before.
            var inChosenAd = sequences.isEmpty()

            var isWrapper = false
            var wrapperAllowsPods = false
            var wrapperUrl: String? = null
            var skipOffset: SkipOffset? = null

            val impressions = mutableListOf<String>()

            val mediaFiles = mutableListOf<MediaFile>()

            val trackingEvents = mutableListOf<TrackingEvent>()

            val companions = mutableListOf<VastCompanion>()

            var clickThroughUrl: String? = null

            val clickTrackingUrls = mutableListOf<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {

                when {
                    eventType == XmlPullParser.START_TAG && isAdElement(parser) ->
                        inChosenAd = ++adIndex == chosenAd

                    eventType == XmlPullParser.END_TAG && isAdElement(parser) ->
                        inChosenAd = false

                    eventType == XmlPullParser.START_TAG && inChosenAd ->
                        when (parser.name) {

                            "Wrapper" -> {
                                isWrapper = true
                                val allowMultipleAds = parser.getAttributeValue(null, "allowMultipleAds")?.trim()
                                wrapperAllowsPods = allowMultipleAds == "true" || allowMultipleAds == "1"
                            }

                            "VASTAdTagURI" -> {
                                wrapperUrl = parser.nextText().trim()
                            }

                            "Impression" -> {
                                impressions.add(
                                    parser.nextText().trim()
                                )
                            }

                            "Linear" -> {
                                val skipAttr = parser.getAttributeValue(null, "skipoffset")
                                if (skipAttr != null) {
                                    skipOffset = SkipOffset.parse(skipAttr)
                                }
                            }

                            "MediaFile" -> {
                                parseMediaFile(parser)?.let {
                                    mediaFiles.add(it)
                                }
                            }

                            "Tracking" -> {
                                parseTracking(parser)?.let {
                                    trackingEvents.add(it)
                                }
                            }

                            "ClickThrough" -> {
                                clickThroughUrl =
                                    parser.nextText().trim()
                            }

                            "ClickTracking" -> {
                                clickTrackingUrls.add(
                                    parser.nextText().trim()
                                )
                            }

                            "Companion" -> {
                                parseCompanion(parser)?.let {
                                    companions.add(it)
                                }
                            }
                        }
                }

                eventType = parser.next()
            }

            impressions.addAll(
                0,
                inheritedImpressions
            )

            trackingEvents.addAll(
                0,
                inheritedTracking
            )

            clickTrackingUrls.addAll(
                0,
                inheritedClickTracking
            )

            if (isWrapper) {

                if (wrapperDepth >= MAX_WRAPPER_DEPTH) {

                    return@withContext Result.failure(
                        VastParsingException(
                            VastError(
                                302,
                                "Wrapper limit reached"
                            )
                        )
                    )
                }

                val wrapperAddress = wrapperUrl?.let(::wrapperUrlOrNull)
                    ?: return@withContext Result.failure(
                        VastParsingException(
                            VastError(
                                300,
                                "Wrapper URL missing or not http(s)"
                            )
                        )
                    )

                val wrappedXml = try {
                    fetchWrapper(wrapperAddress)
                } catch (e: IOException) {
                    return@withContext Result.failure(
                        VastParsingException(
                            VastError(
                                301,
                                "Wrapper fetch failed"
                            ),
                            e
                        )
                    )
                }

                return@withContext parseInternal(
                    xml = wrappedXml,
                    wrapperDepth = wrapperDepth + 1,
                    inheritedImpressions = impressions,
                    inheritedTracking = trackingEvents,
                    inheritedClickTracking = clickTrackingUrls,
                    inheritedCompanions = companions,
                    podsAllowed = wrapperAllowsPods
                )
            }

            // An inline companion wins; the wrapper's is the fallback when the inline
            // ad declares none.
            val resolvedCompanions =
                companions.ifEmpty { inheritedCompanions }

            Result.success(
                VastAd(
                    impressions = impressions,
                    mediaFiles = mediaFiles,
                    trackingEvents = trackingEvents,
                    companions = resolvedCompanions,
                    videoClicks = VideoClicks(
                        clickThroughUrl = clickThroughUrl,
                        clickTrackingUrls = clickTrackingUrls
                    ),
                    skipOffset = skipOffset
                )
            )

        } catch (e: Exception) {

            // A cancelled load, from a destroyed renderer, stops here; anything else is a parsing failure, a stray
            // CancellationException while the load is still active included.
            ensureActive()
            Result.failure(
                VastParsingException(
                    VastError(
                        code = 100,
                        description = e.message
                            ?: "VAST parsing error"
                    ),
                    e
                )
            )
        }
    }

    private fun newParser(xml: String): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

    /** An `<Ad>` directly under `<VAST>`. */
    private fun isAdElement(parser: XmlPullParser): Boolean =
        parser.depth == 2 && parser.name == "Ad"

    /**
     * The `sequence` of each `<Ad>` in document order, or null when it has none or it is not a
     * positive integer: such an ad is stand-alone.
     */
    private fun adSequences(xml: String): List<Int?> {
        val parser = newParser(xml)
        val sequences = mutableListOf<Int?>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && isAdElement(parser)) {
                sequences += parser.getAttributeValue(null, "sequence")
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it >= 1 }
            }
            eventType = parser.next()
        }
        return sequences
    }

    private fun parseMediaFile(
        parser: XmlPullParser
    ): MediaFile? {

        return try {

            val mimeType =
                parser.getAttributeValue(null, "type")
                    ?: ""

            val width =
                parser.getAttributeValue(null, "width")
                    ?.toIntOrNull()
                    ?: 0

            val height =
                parser.getAttributeValue(null, "height")
                    ?.toIntOrNull()
                    ?: 0

            val bitrate =
                parser.getAttributeValue(null, "bitrate")
                    ?.toIntOrNull()

            val delivery =
                parser.getAttributeValue(null, "delivery")
                    ?: "progressive"

            val url = parser.nextText().trim()

            MediaFile(
                url = url,
                mimeType = mimeType,
                width = width,
                height = height,
                bitrate = bitrate,
                delivery = delivery
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun parseTracking(
        parser: XmlPullParser
    ): TrackingEvent? {

        return try {

            val event =
                parser.getAttributeValue(null, "event")
                    ?: return null

            val url = parser.nextText().trim()

            TrackingEvent(
                event = event,
                url = url
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun parseCompanion(
        parser: XmlPullParser
    ): VastCompanion? {

        return try {

            val width =
                parser.getAttributeValue(null, "width")
                    ?.toIntOrNull()
                    ?: 0

            val height =
                parser.getAttributeValue(null, "height")
                    ?.toIntOrNull()
                    ?: 0

            var staticImageUrl: String? = null
            var staticCreativeType: String? = null
            var htmlResource: String? = null
            var iframeUrl: String? = null
            var clickThroughUrl: String? = null

            val clickTrackingUrls = mutableListOf<String>()

            val trackingEvents = mutableListOf<TrackingEvent>()

            val startDepth = parser.depth

            loop@ while (true) {

                when (parser.next()) {

                    XmlPullParser.START_TAG -> {

                        when (parser.name) {

                            "StaticResource" -> {
                                staticCreativeType =
                                    parser.getAttributeValue(null, "creativeType")
                                staticImageUrl =
                                    parser.nextText().trim()
                            }

                            "HTMLResource" -> {
                                htmlResource =
                                    parser.nextText()
                            }

                            "IFrameResource" -> {
                                iframeUrl =
                                    parser.nextText().trim()
                            }

                            "CompanionClickThrough" -> {
                                clickThroughUrl =
                                    parser.nextText().trim()
                            }

                            "CompanionClickTracking" -> {
                                clickTrackingUrls.add(
                                    parser.nextText().trim()
                                )
                            }

                            // Companion tracking lives inside the Companion element, so the
                            // outer parse loop never sees it. Collect it here or it is lost.
                            "Tracking" -> {
                                parseTracking(parser)?.let {
                                    trackingEvents.add(it)
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {

                        if (
                            parser.depth == startDepth &&
                            parser.name == "Companion"
                        ) {
                            break@loop
                        }
                    }
                }
            }

            val resource: CompanionResource =
                when {

                    !staticImageUrl.isNullOrBlank() -> {
                        CompanionResource.StaticImage(
                            url = staticImageUrl,
                            creativeType = staticCreativeType
                        )
                    }

                    !htmlResource.isNullOrBlank() -> {
                        CompanionResource.Html(
                            htmlResource
                        )
                    }

                    !iframeUrl.isNullOrBlank() -> {
                        CompanionResource.IFrame(
                            iframeUrl
                        )
                    }

                    else -> {
                        return null
                    }
                }

            VastCompanion(
                width = width,
                height = height,
                resource = resource,
                clickThroughUrl = clickThroughUrl,
                clickTrackingUrls = clickTrackingUrls,
                trackingEvents = trackingEvents
            )

        } catch (_: Exception) {
            null
        }
    }

    /**
     * The wrapped document at [url]; throws [VastWrapperCleartextException] for an `http` address the app's network
     * security policy refuses, and [VastWrapperHttpException] for a status outside 200 to 299.
     */
    private fun fetchWrapper(
        url: URL
    ): String {

        if (url.protocol == "http" && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(url.host)) {
            throw VastWrapperCleartextException()
        }

        val connection =
            url.openConnection() as HttpURLConnection

        return try {

            connection.connectTimeout = 10000

            connection.readTimeout = 10000

            connection.requestMethod = "GET"

            connection.connect()

            val status = connection.responseCode
            if (status !in 200..299) throw VastWrapperHttpException(status)

            connection.inputStream
                .bufferedReader()
                .use { it.readText() }

        } finally {
            connection.disconnect()
        }
    }
}

internal class VastParsingException(
    val vastError: VastError,
    cause: Throwable? = null
) : Exception(vastError.description, cause)

/** A wrapper's server answered with [status], outside 200 to 299. */
internal class VastWrapperHttpException(val status: Int) : IOException("HTTP $status")

/** The app's network security policy does not permit cleartext traffic to a wrapper's `http` address. */
internal class VastWrapperCleartextException : IOException("Cleartext traffic to the wrapper's host is not permitted")

/** A wrapper's [address] as a URL when it is an absolute http or https one with a host, else `null`. */
internal fun wrapperUrlOrNull(address: String): URL? =
    runCatching { URL(address) }
        .getOrNull()
        ?.takeIf { (it.protocol == "http" || it.protocol == "https") && it.host.isNotEmpty() }
