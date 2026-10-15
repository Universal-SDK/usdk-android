package org.bidon.rendering.telemetry

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.ui.FullscreenAdActivity

/**
 * Runs in its own `:ads` process, the way a host that loads and shows ads outside the process of the ad activities
 * would, and runs the real [PresentedAds.crossProcessError] there.
 */
class AdsService : Service() {

    private val worker = HandlerThread("ads-queue").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { handle(it); true })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        val reply = Message.obtain(null, message.what, Process.myPid(), 0)
        when (message.what) {
            CHECK -> {
                val error = PresentedAds.crossProcessError(this, FullscreenAdActivity::class.java)
                reply.data = Bundle().apply { putString(ERROR, error?.message) }
            }
        }
        message.replyTo.send(reply)
    }

    companion object {
        const val CHECK = 1
        const val ERROR = "error"
    }
}