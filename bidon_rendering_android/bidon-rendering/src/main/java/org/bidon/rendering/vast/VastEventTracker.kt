package org.bidon.rendering.vast

import org.bidon.rendering.tracking.TrackingDispatcher
import java.util.UUID

/**
 * Sends the buyer's trackers for one showing of a video ad: its impression, progress, skip, click
 * and companion view events, with the VAST macros filled in.
 */
class VastEventTracker internal constructor(
    private val send: (String) -> Unit
) {

    constructor() : this(TrackingDispatcher::fire)

    private var started = false
    private var firstQuartile = false
    private var midpoint = false
    private var thirdQuartile = false
    private var complete = false
    private var skipped = false
    private var companionViewed = false

    private var cacheBusting: String = UUID.randomUUID().toString()
    private var currentPositionMs: Long = 0L

    fun reset() {
        started = false
        firstQuartile = false
        midpoint = false
        thirdQuartile = false
        complete = false
        skipped = false
        companionViewed = false
        cacheBusting = UUID.randomUUID().toString()
        currentPositionMs = 0L
    }

    /**
     * Called when video progresses.
     * @param progress Float 0.0 to 1.0
     * @param durationMs Total video duration in milliseconds
     * @param positionMs Current position in milliseconds
     * @param trackingEvents List of tracking events from VAST
     */
    fun onProgress(
        progress: Float,
        durationMs: Long,
        positionMs: Long,
        trackingEvents: List<TrackingEvent>
    ) {
        if (progress < 0f || progress > 1f) return

        currentPositionMs = positionMs

        if (!started) {
            fire("start", trackingEvents, durationMs, positionMs)
            started = true
        }

        if (progress >= 0.25f && !firstQuartile) {
            fire("firstQuartile", trackingEvents, durationMs, positionMs)
            firstQuartile = true
        }

        if (progress >= 0.50f && !midpoint) {
            fire("midpoint", trackingEvents, durationMs, positionMs)
            midpoint = true
        }

        if (progress >= 0.75f && !thirdQuartile) {
            fire("thirdQuartile", trackingEvents, durationMs, positionMs)
            thirdQuartile = true
        }

        if (progress >= 1f && !complete) {
            fire("complete", trackingEvents, durationMs, positionMs)
            complete = true
        }
    }

    /**
     * Called when the video impression is counted. When that is, and that it happens once for the
     * ad, is the video screen's call; every URL given here is sent.
     * @param impressionUrls The `<Impression>` URLs of the whole wrapper chain
     */
    fun onImpression(impressionUrls: List<String>) {
        sendAll(impressionUrls)
    }

    fun onSkip(trackingEvents: List<TrackingEvent>) {
        if (skipped) return

        fire("skip", trackingEvents, 0L, currentPositionMs)
        skipped = true
    }

    /**
     * Called when the companion end card becomes visible.
     * @param trackingEvents Tracking events declared inside the Companion element
     */
    fun onCompanionView(trackingEvents: List<TrackingEvent>) {
        if (companionViewed) return

        fire("creativeView", trackingEvents, 0L, currentPositionMs)
        companionViewed = true
    }

    private fun fire(
        event: String,
        trackingEvents: List<TrackingEvent>,
        durationMs: Long,
        positionMs: Long
    ) {
        trackingEvents
            .asSequence()
            .filter { it.event.equals(event, ignoreCase = true) }
            .forEach { tracking ->
                send(substituteMacros(tracking.url, durationMs, positionMs))
            }
    }

    private fun sendAll(urls: List<String>) {
        urls.forEach { send(substituteMacros(it, 0L, currentPositionMs)) }
    }

    private fun substituteMacros(
        url: String,
        durationMs: Long,
        positionMs: Long
    ): String {

        val timestamp = System.currentTimeMillis()
        val playheadSeconds = positionMs / 1000
        val hours = playheadSeconds / 3600
        val minutes = (playheadSeconds % 3600) / 60
        val seconds = playheadSeconds % 60
        val adPlayhead = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        return url
            .replace("[TIMESTAMP]", timestamp.toString())
            .replace("[CACHEBUSTING]", cacheBusting)
            .replace("[RANDOM]", (Math.random() * 1_000_000).toInt().toString())
            .replace("[ADPLAYHEAD]", adPlayhead)
    }
}