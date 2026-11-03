package org.bidon.rendering.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An ad locks its orientation so it cannot rotate while it is up, and it takes that orientation
 * from the publisher rather than forcing portrait, which would break a landscape game.
 */
class AdWindowTest {

    @Test
    fun `a portrait app gets a portrait ad`() {
        for (portrait in listOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        )) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                AdWindow.lockedFor(portrait)
            )
        }
    }

    @Test
    fun `a landscape app gets a landscape ad`() {
        for (landscape in listOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        )) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                AdWindow.lockedFor(landscape)
            )
        }
    }

    @Test
    fun `an app that allows both freezes the ad where the screen already is`() {
        for (free in listOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_USER,
            ActivityInfo.SCREEN_ORIENTATION_BEHIND
        )) {
            assertEquals(
                "$free should freeze rather than follow the sensor",
                ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                AdWindow.lockedFor(free)
            )
        }
    }

    @Test
    fun `the result is never one that lets the ad rotate`() {
        val rotatable = setOf(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_USER,
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )
        for (input in -1..14) {
            assert(AdWindow.lockedFor(input) !in rotatable) {
                "orientation $input resolved to something that can still rotate"
            }
        }
    }
}
