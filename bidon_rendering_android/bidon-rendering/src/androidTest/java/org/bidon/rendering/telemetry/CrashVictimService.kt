package org.bidon.rendering.telemetry

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.example.host.ThrowingListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bidon.rendering.AdInfo
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.AdType
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.rendering.Integration
import org.bidon.rendering.RendererTelemetry
import org.bidon.rendering.TelemetryDirectory
import org.bidon.rendering.vast.VastRenderer
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs in its own `:crash_victim` process and crashes it on command, the way an app with the renderer and a crash
 * reporter of its own would crash.
 *
 * Every crash command starts from an empty telemetry directory, installs [HostCrashReporter] the way a host app
 * installs its crash reporter, before the SDK, then initialises the renderer, creates one for a MAX bidding ad, and
 * crashes. [READ] starts the renderer in the next process and reports what reached its error queue.
 *
 * [CONFIGURE_THEN_CRASH] does the same, but first gives the renderer, before `init`, a telemetry block that sends to
 * [URL] as [APP_KEY], and waits until the telemetry thread has stored it before it replies. [START] trusts the test
 * ingest's [CERTIFICATE] and only initialises the renderer, with no telemetry block, as an app's next launch does
 * before its config arrives. [CONFIGURE] gives the renderer a telemetry block before and after `init`, with the
 * manifest flag on when `arg1` is 1 and off otherwise, and reports whether the settings were stored.
 *
 * The process ends only once the test has unbound and the service is destroyed: the crash, or after [READ] a plain
 * exit. A bound service whose process dies is scheduled for a restart, with a delay that grows with each death, and
 * a new bind waits for that restart, so ending the process while still bound made the next test wait at random.
 */
class CrashVictimService : Service() {

    private val worker = HandlerThread("crash-victim").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { handle(it); true })

    private val directory by lazy { TelemetryDirectory.forCurrentProcess(this) }
    private val hostReport by lazy { File(filesDir, "host_crash_report") }

    /** The command this process ends with once the service is destroyed. */
    @Volatile
    private var ending: Int? = null

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message) {
        val reply = Message.obtain(null, message.what, Process.myPid(), 0)
        when (message.what) {
            CRASH_IN_OUR_THREAD, CRASH_IN_HOST_CODE, CRASH_IN_OUR_COROUTINE, CRASH_IN_VAST_LISTENER -> {
                directory.deleteRecursively()
                hostReport.delete()
                HostCrashReporter.install(hostReport)
                BidonRenderer.init(this)
                // The crash, which belongs to no ad, is labelled with this renderer's integration.
                BidonRenderer.createRenderer(
                    CreativeType.VAST,
                    ad = AdInfo(auctionId = null, AdType.REWARDED, Integration.MAX_BIDDING),
                )
                reply.data = Bundle().apply { putString(SESSION, RendererTelemetry.sessionId.value) }
            }
            READ -> reply.data = read()
            CONFIGURE_THEN_CRASH -> {
                directory.deleteRecursively()
                hostReport.delete()
                HostCrashReporter.install(hostReport)
                val block = JSONObject()
                    .put("url", message.data.getString(URL))
                    .put("app_key", message.data.getString(APP_KEY))
                // Before init, as when the adapter's config arrives first: the renderer keeps it until it starts.
                BidonRenderer.configureTelemetry(block, packageName, SDK_VERSION, fallbackAppKey = null)
                BidonRenderer.init(this)
                awaitFile(File(directory, SettingsFile.FILE_NAME))
                reply.data = Bundle().apply { putString(SESSION, RendererTelemetry.sessionId.value) }
            }
            CONFIGURE -> {
                directory.deleteRecursively()
                val block = JSONObject().put("url", "https://localhost:1/v2/telemetry").put("app_key", "key")
                BidonRenderer.configureTelemetry(block, packageName, SDK_VERSION, fallbackAppKey = null)
                BidonRenderer.init(if (message.arg1 == 1) this else WithoutTelemetryFlag(this))
                BidonRenderer.configureTelemetry(block.put("sample_rate", 0.5), packageName, SDK_VERSION, null)
                // Long enough for the telemetry thread, had it been started, to store what it was given.
                Thread.sleep(1_000)
                reply.data = Bundle().apply {
                    putBoolean(STORED, File(directory, SettingsFile.FILE_NAME).exists())
                    putBoolean(DIRECTORY_CREATED, directory.exists())
                }
            }
            START -> {
                FakeIngest.trustInThisProcess(message.data.getString(CERTIFICATE)!!)
                BidonRenderer.init(this)
                reply.data = Bundle().apply { putString(SESSION, RendererTelemetry.sessionId.value) }
            }
        }
        ending = message.what
        message.replyTo.send(reply)
    }

    override fun onDestroy() {
        super.onDestroy()
        when (val how = ending) {
            null -> Unit
            READ, START, CONFIGURE -> Process.killProcess(Process.myPid())
            else -> crash(how)
        }
    }

    private fun crash(how: Int) {
        when (how) {
            CRASH_IN_OUR_THREAD, CONFIGURE_THEN_CRASH -> Thread({ throw IllegalStateException("ours") }, "renderer-work").start()
            CRASH_IN_HOST_CODE -> Thread({ throw hostException() }, "game-loop").start()
            CRASH_IN_OUR_COROUTINE -> CoroutineScope(Dispatchers.Main).launch { throw IllegalStateException("ours") }
            CRASH_IN_VAST_LISTENER -> VastRenderer(this, AdRenderingConfig(), events = null).load(VALID_VAST, ThrowingListener)
        }
    }

    /** What reached the error queue once the renderer has started in this process and moved the last crash in. */
    private fun read(): Bundle {
        BidonRenderer.init(this)
        val deadline = System.currentTimeMillis() + 5_000
        while (File(directory, "crash").exists() || File(directory, "crash.claimed").exists()) {
            check(System.currentTimeMillis() < deadline) { "the crash slot was not moved" }
            Thread.sleep(10)
        }
        val errors = File(directory, "errors")
        val events = if (errors.exists()) {
            EventQueue.open(errors, DroppedEvents.open(File(directory, "read_dropped")))
                .peek(Int.MAX_VALUE, Int.MAX_VALUE)
                .map { it.payload.decodeToString() }
        } else {
            emptyList()
        }
        return Bundle().apply {
            putStringArrayList(ERRORS, ArrayList(events))
            putString(HOST_REPORT, hostReport.takeIf { it.exists() }?.readText())
            putString(SESSION, RendererTelemetry.sessionId.value)
        }
    }

    private fun awaitFile(file: File) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists()) {
            check(System.currentTimeMillis() < deadline) { "$file was never written" }
            Thread.sleep(10)
        }
    }

    /**
     * This service with the manifest of the system package, which has no telemetry flag; the test app's own manifest
     * switches telemetry on. It is its own application context, so the renderer, which keeps the application
     * context, reads the flag through it too.
     */
    private class WithoutTelemetryFlag(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "android"
        override fun getApplicationContext(): Context = this
    }

    /** An exception thrown from the host app's code: nothing of ours in its stack. */
    private fun hostException() = IllegalStateException("host").apply {
        stackTrace = arrayOf(
            StackTraceElement("com.publisher.game.Level", "update", "Level.kt", 42),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1012),
        )
    }

    /**
     * Stands in for a host app's crash reporter: it notes the crash in a file, then ends the process itself, as some
     * reporters do. Handing the crash on to the system instead would bring in its crash handling, which keeps a
     * process that crashes again within a minute alive behind a dialog and then refuses to restart it, and these
     * tests crash the same process several times in a row.
     */
    private class HostCrashReporter(private val report: File) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            report.writeText("${thread.name}: ${throwable.javaClass.name}: ${throwable.message}")
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }

        companion object {
            fun install(report: File) = Thread.setDefaultUncaughtExceptionHandler(HostCrashReporter(report))
        }
    }

    companion object {
        const val CRASH_IN_OUR_THREAD = 1
        const val CRASH_IN_HOST_CODE = 2
        const val CRASH_IN_OUR_COROUTINE = 3
        const val READ = 4
        const val CONFIGURE_THEN_CRASH = 5
        const val START = 6
        const val CONFIGURE = 7
        const val CRASH_IN_VAST_LISTENER = 8

        const val ERRORS = "errors"
        const val HOST_REPORT = "host_report"
        const val SESSION = "session"
        const val URL = "url"
        const val APP_KEY = "app_key"
        const val CERTIFICATE = "certificate"
        const val STORED = "stored"
        const val DIRECTORY_CREATED = "directory_created"

        /** A VAST document that parses without a network: the load succeeds and calls `onLoaded`. */
        private val VALID_VAST = """
            <VAST version="4.2"><Ad id="1"><InLine>
            <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
            <Impression><![CDATA[https://localhost:1/impression]]></Impression>
            <Creatives><Creative><Linear>
            <Duration>00:00:10</Duration>
            <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="64" height="64"><![CDATA[https://localhost:1/video.mp4]]></MediaFile></MediaFiles>
            </Linear></Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()

        /** The SDK version the configured process sends with its batches. */
        const val SDK_VERSION = "9.9.9-test"
    }
}
