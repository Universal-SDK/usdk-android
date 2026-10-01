package org.bidon.rendering.vast

internal data class VastAd(

    val impressions: List<String> = emptyList(),

    val mediaFiles: List<MediaFile> = emptyList(),

    val trackingEvents: List<TrackingEvent> = emptyList(),

    val companions: List<VastCompanion> = emptyList(),

    val videoClicks: VideoClicks? = null,

    val skipOffset: SkipOffset? = null,

    val adParameters: String? = null,

    val error: VastError? = null
)

internal data class MediaFile(

    val url: String,

    val mimeType: String,

    val width: Int,

    val height: Int,

    val bitrate: Int? = null,

    val delivery: String? = null,

    val scalable: Boolean = true,

    val maintainAspectRatio: Boolean = true
)

internal data class TrackingEvent(

    val event: String,

    val url: String
)

internal data class VideoClicks(

    val clickThroughUrl: String? = null,

    val clickTrackingUrls: List<String> = emptyList()
)

internal data class VastCompanion(

    val width: Int,

    val height: Int,

    val resource: CompanionResource,

    val clickThroughUrl: String? = null,

    val clickTrackingUrls: List<String> = emptyList(),

    val trackingEvents: List<TrackingEvent> = emptyList()
)

internal sealed class CompanionResource {

    data class StaticImage(
        val url: String,
        val creativeType: String? = null
    ) : CompanionResource()

    data class Html(
        val html: String
    ) : CompanionResource()

    data class IFrame(
        val url: String
    ) : CompanionResource()
}

internal data class VastError(

    val code: Int,

    val description: String? = null
)