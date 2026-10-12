package org.bidon.rendering

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.mraid.MraidController
import org.bidon.rendering.mraid.MraidPlacementType
import org.bidon.rendering.mraid.MraidWebView
import org.bidon.rendering.ui.BlankActivity
import org.bidon.rendering.vast.CompanionResource
import org.bidon.rendering.vast.VastCompanion
import org.bidon.rendering.vast.ui.EndCardView
import org.bidon.rendering.vast.ui.VideoAdView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every place a creative opens a link holds to the rules a browser does: a screen of the host app that is not exported
 * stays out of reach, while a web page and an app that accepts links from the web still open. The rules themselves are
 * tested in `IntentLinkTest`; this test shows each path goes through them. The views are built in an activity, as in an
 * ad. A tap on the video itself opens its link the same way as Learn More, but only once playback has started, which
 * needs a player this test does not run.
 *
 * `bidontest://` opens the host's own deep link screen, which is not exported; `bidontest-open://` an exported screen
 * that accepts links. The monitor sees every start before the system resolves it, and a refused link is never started,
 * so the starts it records are the links the renderer let through.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class ClickThroughTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Keeps the address of each link the renderer starts, without letting anything open. A monitor that sees every
     * start, whatever it matches, came with Android 8. */
    private class Starts : Instrumentation.ActivityMonitor() {
        val addresses = mutableListOf<String>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            val address = intent.dataString ?: return null
            addresses += address
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    /** The links [open] starts for each of [LINKS], opened from a screen of the host app on the main thread. */
    private fun startsFrom(open: (activity: Activity, url: String) -> Unit): List<String> {
        val starts = Starts()
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var activity: Activity
            scenario.onActivity { activity = it }
            instrumentation.addMonitor(starts)
            try {
                for (url in LINKS) {
                    instrumentation.runOnMainSync { open(activity, url) }
                    instrumentation.waitForIdleSync()
                }
            } finally {
                instrumentation.removeMonitor(starts)
            }
        }
        return starts.addresses
    }

    private fun mraidOpens(command: MraidController.(url: String) -> Unit) = startsFrom { activity, url ->
        MraidController(activity, MraidWebView(activity), MraidPlacementType.INLINE, onClose = {}, onClick = {})
            .command(url)
    }

    private fun endCardOpens(resource: CompanionResource, click: (EndCardView) -> View) = startsFrom { activity, url ->
        val endCard = EndCardView(activity)
        try {
            endCard.showCompanion(VastCompanion(320, 480, resource, clickThroughUrl = url))
            click(endCard).performClick()
        } finally {
            endCard.destroy()
        }
    }

    private inline fun <reified T : View> View.find(crossinline matches: (T) -> Boolean = { true }): View =
        descendants().firstOrNull { it is T && matches(it) } ?: throw AssertionError("no ${T::class.simpleName} here")

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) children.forEach { yieldAll(it.descendants()) }
    }

    @Test
    fun mraidOpenReachesNoClosedHostScreen() {
        assertEquals(OPENED, mraidOpens { open(it) })
    }

    @Test
    fun mraidPlayVideoReachesNoClosedHostScreen() {
        assertEquals(OPENED, mraidOpens { playVideo(it) })
    }

    @Test
    fun mraidStorePictureReachesNoClosedHostScreen() {
        assertEquals(OPENED, mraidOpens { storePicture(it) })
    }

    @Test
    fun theVideoClickThroughReachesNoClosedHostScreen() {
        val starts = startsFrom { activity, url ->
            VideoAdView(activity).apply { setClickThroughUrl(url) }.find<TextView> { it.text == "Learn More" }.performClick()
        }

        assertEquals(OPENED, starts)
    }

    @Test
    fun theEndCardButtonReachesNoClosedHostScreen() {
        val starts = endCardOpens(CompanionResource.Html("<html></html>")) { endCard ->
            endCard.find<TextView> { it.text == "Learn More" }
        }

        assertEquals(OPENED, starts)
    }

    /** The image is never served: a tap on it opens the link before it has loaded. */
    @Test
    fun theEndCardImageReachesNoClosedHostScreen() {
        val starts = endCardOpens(CompanionResource.StaticImage("http://127.0.0.1:9/companion.png")) { endCard ->
            endCard.find<ImageView>()
        }

        assertEquals(OPENED, starts)
    }

    private companion object {
        val LINKS = listOf("bidontest://screen", "https://advertiser.example/landing", "bidontest-open://x")
        val OPENED = listOf("https://advertiser.example/landing", "bidontest-open://x")
    }
}
