package org.bidon.rendering.ui

import android.os.Bundle

/*
 * What an ad screen keeps across its own recreation, for a theme, language or density change, or when the system
 * destroys it in the background and restores it later.
 */

private const val STATE_CLOSE_AVAILABLE_AT = "org.bidon.rendering.close_available_at"

private const val STATE_IMPRESSION_REPORTED = "org.bidon.rendering.impression_reported"

/** When the close button of the activity this state was saved from becomes usable, if saved. */
internal fun Bundle.closeAvailableAtMs(): Long? =
    if (containsKey(STATE_CLOSE_AVAILABLE_AT)) getLong(STATE_CLOSE_AVAILABLE_AT) else null

internal fun Bundle.putCloseAvailableAtMs(availableAtMs: Long) =
    putLong(STATE_CLOSE_AVAILABLE_AT, availableAtMs)

/**
 * Whether the ad screen that saved this state had already reported the impression. A recreated screen carries it over,
 * so it does not count the ad as shown a second time.
 */
internal fun Bundle.impressionReported(): Boolean = getBoolean(STATE_IMPRESSION_REPORTED, false)

internal fun Bundle.putImpressionReported(reported: Boolean) = putBoolean(STATE_IMPRESSION_REPORTED, reported)
