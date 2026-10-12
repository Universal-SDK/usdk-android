package org.bidon.rendering.telemetry

import org.json.JSONObject
import java.util.UUID

/**
 * The fields every telemetry event carries, captured at the moment the event happened.
 *
 * These are the fields the client may set in the ingest contract. The server stamps the rest (`schema_version`,
 * `app_id`, `country`, `trace_id`) and ignores them if a client sends them, so they are not here.
 *
 * An envelope is only made by [capture], which takes the identifier and the timestamp itself. Every other value arrives
 * as a type that cannot hold an invalid value, so capturing never throws on the ad path.
 */
internal class EventEnvelope private constructor(
    /** A UUID v4 minted on the device. The server deduplicates on it, so a retry must resend the same bytes. */
    val eventId: String,
    val eventName: String,
    /** Wall-clock Unix milliseconds. */
    val eventTs: Long,
    /** Absent only on events that belong to no auction. */
    val auctionId: String?,
    val sessionId: SessionId,
    /** Absent only on events that belong to no ad. */
    val adType: AdType?,
    /** The banner format, such as `BANNER` or `MREC`, when [adType] is [AdType.BANNER]. */
    val adFormat: String?,
    val samplingRate: SamplingRate,
) : TelemetryEvent {

    /**
     * An event that is only its envelope, as one UTF-8 JSON object: the fields, with snake_case keys, under `envelope`.
     * Absent fields are left out rather than written as `null`: the contract types them as plain strings, which
     * `null` is not.
     */
    override fun encode(): ByteArray = eventJson().toString().toByteArray(Charsets.UTF_8)

    /**
     * The event as a JSON object holding the envelope's fields under `envelope`, for events that add fields of their
     * own next to it. The ingest contract reads an event's name and identity only from there.
     */
    fun eventJson(): JSONObject = JSONObject().put("envelope", fields())

    private fun fields() = JSONObject().apply {
        put("event_id", eventId)
        put("event_name", eventName)
        put("event_ts", eventTs)
        if (auctionId != null) put("auction_id", auctionId)
        put("session_id", sessionId.value)
        if (adType != null) put("ad_type", adType.wireName)
        if (adFormat != null) put("ad_format", adFormat)
        put("sampling_rate", samplingRate.fraction)
    }

    companion object {

        /** Captures an event named [eventName] as of now on [clock]. */
        fun capture(
            eventName: String,
            sessionId: SessionId,
            clock: Clock,
            auctionId: String? = null,
            adType: AdType? = null,
            adFormat: String? = null,
            samplingRate: SamplingRate,
        ) = EventEnvelope(
            eventId = UUID.randomUUID().toString(),
            eventName = eventName,
            eventTs = clock.currentTimeMillis(),
            auctionId = auctionId,
            sessionId = sessionId,
            adType = adType,
            adFormat = adFormat,
            samplingRate = samplingRate,
        )
    }
}

/**
 * The session an event belongs to. The renderer mints one per process with [random] (D-025): the mediation SDK's own
 * session is not visible to it. Renderer events join the server's on `auction_id`, so the session only has to group the
 * events of one process and fill the contract's required field.
 */
@JvmInline
internal value class SessionId private constructor(val value: String) {

    companion object {
        /** [value] as a session id, or `null` when it is blank, in which case there is no event to send. */
        fun of(value: String): SessionId? = if (value.isBlank()) null else SessionId(value)

        /** A new session id, a random UUID. */
        fun random(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}

internal enum class AdType(val wireName: String) {
    BANNER("banner"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
}

/**
 * The share of events of this kind the device kept, as a fraction: `1.0` means every event was kept, `0.1` one in ten.
 * The query side weights each event by `1 / fraction`.
 *
 * Zero is not a rate an event can carry. An event sampled at zero was never kept, so it is never sent.
 */
@JvmInline
internal value class SamplingRate private constructor(val fraction: Double) {

    companion object {
        /** Every event kept. */
        val ALL = SamplingRate(1.0)

        /**
         * The rate for [fraction], or `null` when it is not in (0, 1]. Zero, which config may send to switch an event
         * off, lands here too, as does any other value outside that range: either way there is no event to send.
         */
        fun of(fraction: Double): SamplingRate? =
            if (fraction > 0.0 && fraction <= 1.0) SamplingRate(fraction) else null
    }
}
