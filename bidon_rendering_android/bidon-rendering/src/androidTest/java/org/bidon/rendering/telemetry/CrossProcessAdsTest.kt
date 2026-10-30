package org.bidon.rendering.telemetry

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.bidon.rendering.ui.FullscreenAdActivity
import org.bidon.rendering.vast.ui.VideoAdActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The cross-process check on a device, with a real second process. [AdsService] runs in `:ads`, while the ad activities
 * run in the main process.
 */
@RunWith(AndroidJUnit4::class)
class CrossProcessAdsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_show_from_the_ads_process_is_refused_and_names_both_processes() {
        val reply: Message = withService<AdsService, Message>(context) { request(AdsService.CHECK) }

        assertNotEquals("the service must run in another process", Process.myPid(), reply.arg1)

        val message = reply.data.getString(AdsService.ERROR)
        assertNotNull("the check let a show from another process through", message)
        assertTrue(message, message!!.contains("The ad cannot be shown from process '${context.packageName}:ads'"))
        assertTrue(message, message.contains("its screen runs in '${context.packageName}'"))
    }

    @Test
    fun a_show_from_the_main_process_is_allowed() {
        val declared = context.packageManager
            .getActivityInfo(ComponentName(context, FullscreenAdActivity::class.java), 0)
        assertEquals(context.packageName, declared.processName)

        assertNull(PresentedAds.crossProcessError(context, FullscreenAdActivity::class.java))
    }

    @Test
    fun showing_a_loaded_html_ad_from_the_ads_process_ends_in_onError() = aShowFromTheAdsProcessEndsInOnError(Renderer.HTML)

    @Test
    fun showing_a_loaded_mraid_ad_from_the_ads_process_ends_in_onError() = aShowFromTheAdsProcessEndsInOnError(Renderer.MRAID)

    @Test
    fun showing_a_loaded_vast_ad_from_the_ads_process_ends_in_onError() = aShowFromTheAdsProcessEndsInOnError(Renderer.VAST)

    /**
     * The renderer of [kind] loads and shows an ad in `:ads`: the show ends in one `onError` naming both processes, the
     * `:ads` queue holds `ad_show_requested` and then `ad_show_failed` 11003, and no ad screen starts in the main process.
     */
    private fun aShowFromTheAdsProcessEndsInOnError(kind: Renderer) {
        val replies = LinkedBlockingQueue<Message>()
        val adScreens = CopyOnWriteArrayList<String>()
        val monitor = ActivityLifecycleCallback { activity, _ ->
            if (activity is FullscreenAdActivity || activity is MraidAdActivity || activity is VideoAdActivity) {
                adScreens += activity.javaClass.simpleName
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(monitor)
        val thread = HandlerThread("show-replies").apply { start() }
        try {
            val replyTo = Messenger(Handler(thread.looper) { replies.add(Message.obtain(it)); true })
            context.startActivity(
                Intent(context, ShowFromAdsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ShowFromAdsActivity.REPLY_TO, replyTo)
                    .putExtra(ShowFromAdsActivity.KIND, kind.name),
            )

            val reply = replies.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("the ads process reported nothing")
            val text = reply.data.getString(ShowFromAdsActivity.TEXT)
            assertEquals("the show must end in onError, got: $text", ShowFromAdsActivity.ERROR, reply.what)
            assertNotEquals("the show must run in another process", Process.myPid(), reply.arg1)
            assertTrue(text, text!!.contains("The ad cannot be shown from process '${context.packageName}:ads'"))
            assertTrue(text, text.contains("its screen runs in '${context.packageName}'"))
            assertNull(reply.data.getString(ShowFromAdsActivity.TELEMETRY_PROBLEM))

            assertNull(replies.poll(2, TimeUnit.SECONDS))
            assertTrue("an ad screen started: $adScreens", adScreens.isEmpty())
        } finally {
            thread.quitSafely()
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(monitor)
        }
    }
}
