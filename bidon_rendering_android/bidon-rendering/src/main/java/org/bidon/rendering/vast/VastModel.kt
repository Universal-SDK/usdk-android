package org.bidon.rendering.vast

data class VastAd(

    val impressions: List<String> = emptyList(),

    val mediaFiles: List<MediaFile> = emptyList(),

    val trackingEvents: List<TrackingEvent> = emptyList(),

    val companions: List<VastCompanion> = emptyList(),

    val videoClicks: VideoClicks? = null,

    val skipOffsetMs: Long? = null,

    val adParameters: String? = null,

    val error: VastError? = null
)

data class MediaFile(

    val url: String,

    val mimeType: String,

    val width: Int,

    val height: Int,

    val bitrate: Int? = null,

    val delivery: String? = null,

    val scalable: Boolean = true,

    val maintainAspectRatio: Boolean = true
)

data class TrackingEvent(

    val event: String,

    val url: String
)

data class VideoClicks(

    val clickThroughUrl: String? = null,

    val clickTrackingUrls: List<String> = emptyList()
)

data class VastCompanion(

    val width: Int,

    val height: Int,

    val resource: CompanionResource,

    val clickThroughUrl: String? = null,

    val trackingEvents: List<TrackingEvent> = emptyList()
)

sealed class CompanionResource {

    data class StaticImage(
        val url: String
    ) : CompanionResource()

    data class Html(
        val html: String
    ) : CompanionResource()

    data class IFrame(
        val url: String
    ) : CompanionResource()
}

data class VastError(

    val code: Int,

    val description: String? = null
)