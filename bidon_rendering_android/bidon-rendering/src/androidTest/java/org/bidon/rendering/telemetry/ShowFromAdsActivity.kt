package org.bidon.rendering.telemetry

import android.app.Activity
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.webkit.WebView
import androidx.core.content.IntentCompat
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.html.HtmlRenderer

class ShowFromAdsActivity : Activity() {

    private var renderer: HtmlRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching { WebView.setDataDirectorySuffix("ads") }

        val replyTo = IntentCompat.getParcelableExtra(intent, REPLY_TO, Messenger::class.java) ?: return finish()

        val html = HtmlRenderer(this, AdRenderingConfig.fromJson(null), null)
        renderer = html
        html.load("<html><body>ad</body></html>", object : AdRenderer.Listener {
            override fun onLoaded() = html.showFullscreen(this@ShowFromAdsActivity)

            override fun onError(error: Throwable) {
                replyTo.report(ERROR, error.message)
                finish()
            }

            override fun onImpression() = replyTo.report(IMPRESSION)

            override fun onClick() = Unit

            override fun onClose() = Unit

            override fun onReward() = Unit
        })
    }

    override fun onDestroy() {
        renderer?.destroy()
        renderer = null
        super.onDestroy()
    }

    private fun Messenger.report(what: Int, text: String? = null) {
        send(
            Message.obtain(null, what, Process.myPid(), 0).apply {
                data = Bundle().apply { putString(TEXT, text) }
            },
        )
    }

    companion object {
        const val REPLY_TO = "reply_to"
        const val TEXT = "text"
        const val ERROR = 1
        const val IMPRESSION = 2
    }
}