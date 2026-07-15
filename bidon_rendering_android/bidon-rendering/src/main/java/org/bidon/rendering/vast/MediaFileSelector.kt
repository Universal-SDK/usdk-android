package org.bidon.rendering.vast

import android.content.Context
import android.util.DisplayMetrics
import kotlin.math.abs

object MediaFileSelector {

    private val preferredMimeTypes = listOf(
        "video/mp4",
        "video/webm",
        "application/mp4"
    )

    fun select(
        context: Context,
        mediaFiles: List<MediaFile>
    ): Result<MediaFile> {

        if (mediaFiles.isEmpty()) {
            return Result.failure(
                IllegalStateException("No media files available")
            )
        }

        val supportedFiles = mediaFiles.filter {
            isMimeTypeSupported(it.mimeType)
        }

        if (supportedFiles.isEmpty()) {
            return Result.failure(
                IllegalStateException("No supported media formats")
            )
        }

        val metrics = context.resources.displayMetrics

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val best = supportedFiles.maxByOrNull { media ->
            calculateScore(
                media = media,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }

        return best?.let {
            Result.success(it)
        } ?: Result.failure(
            IllegalStateException("Failed to select media file")
        )
    }

    private fun calculateScore(
        media: MediaFile,
        screenWidth: Int,
        screenHeight: Int
    ): Int {

        var score = 0

        score += when (media.mimeType.lowercase()) {
            "video/mp4" -> 1000
            "video/webm" -> 700
            "application/mp4" -> 500
            else -> 0
        }

        val widthDiff = abs(screenWidth - media.width)
        val heightDiff = abs(screenHeight - media.height)

        val resolutionPenalty = (widthDiff + heightDiff) / 10
        score -= resolutionPenalty

        val bitrate = media.bitrate ?: 0

        score += when {
            bitrate in 500..2500 -> 400
            bitrate in 2501..5000 -> 250
            bitrate > 5000 -> 100
            bitrate > 0 -> 50
            else -> 0
        }

        score += when (media.delivery?.lowercase()) {
            "progressive" -> 300
            "streaming" -> 150
            else -> 0
        }

        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val mediaRatio = media.width.toFloat() / media.height.toFloat()

        val ratioDiff = abs(screenRatio - mediaRatio)
        score -= (ratioDiff * 100).toInt()

        return score
    }

    private fun isMimeTypeSupported(mimeType: String): Boolean {
        return preferredMimeTypes.any {
            it.equals(mimeType, ignoreCase = true)
        }
    }
}