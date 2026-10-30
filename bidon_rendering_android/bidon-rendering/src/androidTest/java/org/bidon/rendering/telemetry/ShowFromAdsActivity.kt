package org.bidon.rendering.telemetry

import android.app.Activity
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.content.IntentCompat
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.html.HtmlRenderer
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.vast.VastRenderer
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs in the `:ads` process: loads an ad of the [KIND] renderer and shows it from here, while its ad screen is declared
 * in the main process. It reports the first `onError` or impression to [REPLY_TO], together with the result of
 * checking this process's telemetry queue for `ad_show_requested` and then `ad_show_failed` 11003.
 */
class ShowFromAdsActivity : Activity() {

    private var renderer: AdRenderer? = null
    private val directory by lazy { File(cacheDir, "cross-process-${SystemClock.elapsedRealtimeNanos()}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching { WebView.setDataDirectorySuffix("ads") }

        val replyTo = IntentCompat.getParcelableExtra(intent, REPLY_TO, Messenger::class.java) ?: return finish()
        val kind = Renderer.valueOf(intent.getStringExtra(KIND) ?: return finish())

        val queued = QueuedEvents(directory)
        val ad = newRenderer(kind, queued.adEvents(kind))
        renderer = ad
        ad.load(markup(kind), object : AdRenderer.Listener {
            override fun onLoaded() = ad.showFullscreen(this@ShowFromAdsActivity)

            override fun onError(error: Throwable) {
                thread {
                    val problem = runCatching {
                        queued.assertShowRequested(queued.next())
                        queued.assertShowFailed(
                            queued.next(),
                            ErrorCode.SHOWN_FROM_OTHER_PROCESS,
                            exceptionClass = IllegalStateException::class.java.name,
                        )
                        queued.assertNoMore(500)
                    }.exceptionOrNull()
                    replyTo.report(ERROR, error.message, problem?.toString())
                    runOnUiThread { finish() }
                }
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
        directory.deleteRecursively()
        super.onDestroy()
    }

    private fun newRenderer(kind: Renderer, events: AdEvents): AdRenderer = when (kind) {
        Renderer.HTML -> HtmlRenderer(this, AdRenderingConfig(), events)
        Renderer.MRAID -> MraidRenderer(this, AdRenderingConfig(), events)
        Renderer.VAST -> VastRenderer(this, AdRenderingConfig(), events)
    }

    /** A playable ad of [kind]. The video is never fetched: the show is refused before a media file is chosen. */
    private fun markup(kind: Renderer): String = when (kind) {
        Renderer.HTML -> "<html><body><p>ad</p></body></html>"
        Renderer.MRAID -> "<html><body><script src=\"mraid.js\"></script><p>ad</p></body></html>"
        Renderer.VAST -> """
            <VAST version="4.2"><Ad id="1"><InLine>
            <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
            <Impression><![CDATA[https://ads.invalid/impression]]></Impression>
            <Creatives><Creative><Linear>
            <Duration>00:00:10</Duration>
            <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="64" height="64"><![CDATA[https://ads.invalid/video.mp4]]></MediaFile></MediaFiles>
            </Linear></Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()
    }

    private fun Messenger.report(what: Int, text: String? = null, telemetryProblem: String? = null) {
        send(
            Message.obtain(null, what, Process.myPid(), 0).apply {
                data = Bundle().apply {
                    putString(TEXT, text)
                    putString(TELEMETRY_PROBLEM, telemetryProblem)
                }
            },
        )
    }

    companion object {
        const val REPLY_TO = "reply_to"
        const val KIND = "kind"
        const val TEXT = "text"
        const val TELEMETRY_PROBLEM = "telemetry_problem"
        const val ERROR = 1
        const val IMPRESSION = 2
    }
}
