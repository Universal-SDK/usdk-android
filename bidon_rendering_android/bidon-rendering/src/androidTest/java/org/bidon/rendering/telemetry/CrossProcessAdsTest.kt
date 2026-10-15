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
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.ui.FullscreenAdActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The cross-process check on a device, with a real second process. [AdsService] runs in `:ads`, while the ad activities
 * run in the main process
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
        assertTrue(message, message!!.contains("shown from process '${context.packageName}:ads'"))
        assertTrue(message, message.contains("runs in '${context.packageName}'"))
    }

    @Test
    fun a_show_from_the_main_process_is_allowed() {
        val declared = context.packageManager
            .getActivityInfo(ComponentName(context, FullscreenAdActivity::class.java), 0)
        assertEquals(context.packageName, declared.processName)

        assertNull(PresentedAds.crossProcessError(context, FullscreenAdActivity::class.java))
    }

    @Test
    fun showing_a_loaded_ad_from_the_ads_process_ends_in_onError() {
        val replies = LinkedBlockingQueue<Message>()
        val thread = HandlerThread("show-replies").apply { start() }
        try {
            val replyTo = Messenger(Handler(thread.looper) { replies.add(Message.obtain(it)); true })
            context.startActivity(
                Intent(context, ShowFromAdsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ShowFromAdsActivity.REPLY_TO, replyTo),
            )

            val reply = replies.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("the ads process reported nothing")
            val text = reply.data.getString(ShowFromAdsActivity.TEXT)
            assertEquals("the show must end in onError, got: $text", ShowFromAdsActivity.ERROR, reply.what)
            assertNotEquals("the show must run in another process", Process.myPid(), reply.arg1)
            assertTrue(text, text!!.contains("shown from process '${context.packageName}:ads'"))
            assertTrue(text, text.contains("runs in '${context.packageName}'"))

            assertNull(replies.poll(2, TimeUnit.SECONDS))
        } finally {
            thread.quitSafely()
        }
    }
}