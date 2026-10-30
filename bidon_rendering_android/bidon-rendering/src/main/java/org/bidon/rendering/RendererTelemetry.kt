package org.bidon.rendering

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.AdType as WireAdType
import org.bidon.rendering.telemetry.BackgroundTelemetry
import org.bidon.rendering.telemetry.BatchBody
import org.bidon.rendering.telemetry.BatchSender
import org.bidon.rendering.telemetry.Clock
import org.bidon.rendering.telemetry.CrashCapture
import org.bidon.rendering.telemetry.CrashReport
import org.bidon.rendering.telemetry.CrashSlot
import org.bidon.rendering.telemetry.Renderer
import org.bidon.rendering.telemetry.DiskEventStore
import org.bidon.rendering.telemetry.ConfiguredSender
import org.bidon.rendering.telemetry.HttpTelemetryClient
import org.bidon.rendering.telemetry.MonotonicClock
import org.bidon.rendering.telemetry.RateLimiter
import org.bidon.rendering.telemetry.SessionId
import org.bidon.rendering.telemetry.SettingsFile
import org.bidon.rendering.telemetry.Telemetry
import org.bidon.rendering.telemetry.TelemetryEndpoint
import org.bidon.rendering.telemetry.TelemetryGate
import org.bidon.rendering.telemetry.UptimeClock
import org.bidon.rendering.telemetry.WireIntegration
import java.io.File

/**
 * Connects the renderer's telemetry to Android: whether it runs, where it keeps its files, and when it syncs.
 *
 * Telemetry is off unless the host app's manifest switches it on:
 *
 * ```xml
 * <meta-data android:name="org.bidon.rendering.telemetry" android:value="true" />
 * ```
 *
 * Events are sent once there is a [TelemetryEndpoint]: from the settings the server sends through
 * [BidonRenderer.configureTelemetry], or from the ones an earlier process stored. Until then an enabled library only
 * stores events. The server's switches decide what is captured and whether it is sent; whether telemetry runs at all
 * stays with the flag. Crash capture follows the flag too: with telemetry off, no handler is installed.
 */
internal object RendererTelemetry {

    private const val ENABLED_FLAG = "org.bidon.rendering.telemetry"

    private const val TAG = "BidonTelemetry"

    /**
     * A frame in one of these packages makes a crash ours: the renderer, and the adapter that ships with it. Not
     * `org.bidon.` as a whole, which would claim the mediation SDK's crashes too.
     */
    private val OWN_PACKAGES = listOf("org.bidon.rendering.", "org.bidon.bidon.")

    /** This process's telemetry session, minted once (D-025), when telemetry first needs it. */
    val sessionId: SessionId by lazy { SessionId.random() }

    /**
     * Milliseconds since telemetry started, for every event of this process. It reads zero until [start] puts the
     * device's elapsed clock in its place, so the origin is the first `BidonRenderer.init`: a later one returns before
     * it reaches [start]. No event is captured before then: renderers need `init`, and [start] installs the crash
     * handler.
     */
    @Volatile
    private var uptime: UptimeClock = UptimeClock { 0L }

    /**
     * The integration of the last renderer created in this process, or `null` when none was or it did not say. A
     * crash, which belongs to no ad, is labelled with it.
     */
    @Volatile
    var lastIntegration: WireIntegration? = null
        private set

    /**
     * Starts telemetry if the manifest asks for it, and [Telemetry.Disabled] otherwise. Does no disk work on the
     * calling thread: the store's directory is resolved on the telemetry thread, because resolving it creates it.
     *
     * Never throws. Reading the manifest goes through the package manager, which can fail with a runtime exception
     * when the system service dies, and starting a thread fails when the process has run out of them. Either way the
     * renderer carries on without telemetry.
     */
    fun start(context: Context): Telemetry =
        try {
            uptime = ElapsedUptimeClock()
            if (isEnabled(context)) startThread(context) else Telemetry.Disabled
        } catch (failure: Throwable) {
            reportFailure(failure)
            Telemetry.Disabled
        }

    /**
     * Starts the telemetry thread and the crash capture. Opening the store also moves in the crash the previous
     * process left, if it left one. The settings an earlier process stored are read first, on the telemetry thread,
     * and events wait for them.
     */
    private fun startThread(context: Context): Telemetry {
        val directory = { TelemetryDirectory.forCurrentProcess(context) }
        val sender = ConfiguredSender(SettingsFile { File(directory(), SettingsFile.FILE_NAME) }) { store, to ->
            BatchSender(store, HttpTelemetryClient(to), BatchBody(to, sessionId))
        }
        val telemetry = BackgroundTelemetry.start(
            openStore = {
                val storeDirectory = directory()
                DiskEventStore.open(storeDirectory).also { CrashSlot(storeDirectory).moveInto(it) }
            },
            onFailure = ::reportFailure,
            openSender = { store -> sender.also { it.attach(store) } },
            gate = TelemetryGate(sessionId, RateLimiter(MonotonicClock.System), initial = null),
            settings = sender,
        )
        context.registerComponentCallbacks(SyncInBackground(telemetry))
        installCrashCapture(directory)
        return telemetry
    }

    private fun installCrashCapture(directory: () -> File) {
        val report = CrashReport(OWN_PACKAGES, sessionId, Clock.System, uptime, { lastIntegration })
        val mainThread = Looper.getMainLooper().thread
        CrashCapture.install { previous -> CrashCapture(previous, report, mainThread) { CrashSlot(directory()) } }
    }

    /**
     * The telemetry events of one ad of [type], labelled with [ad], or none when [telemetry] is off, so an app with
     * telemetry off never mints a session (D-026). Every renderer is created through here, so it also records the
     * integration a crash is labelled with: this renderer's, or none when [ad] does not say.
     */
    fun adEvents(telemetry: Telemetry, type: CreativeType, ad: AdInfo?, rendering: AdRenderingConfig): AdEvents? {
        if (telemetry === Telemetry.Disabled) return null
        val integration = ad?.integration?.let(::wireIntegration)
        lastIntegration = integration
        return AdEvents(
            telemetry = telemetry,
            sessionId = sessionId,
            auctionId = ad?.auctionId,
            adType = ad?.type?.let(::wireAdType),
            integration = integration,
            renderer = renderer(type),
            declaredType = rendering.declaredType,
            clock = Clock.System,
            uptime = uptime,
            monotonicClock = MonotonicClock.System,
        )
    }

    private fun wireIntegration(integration: Integration): WireIntegration = when (integration) {
        Integration.DIRECT -> WireIntegration.DIRECT
        Integration.MAX_CUSTOM -> WireIntegration.MAX_CUSTOM
        Integration.MAX_BIDDING -> WireIntegration.MAX_BIDDING
    }

    private fun wireAdType(type: AdType): WireAdType = when (type) {
        AdType.BANNER -> WireAdType.BANNER
        AdType.INTERSTITIAL -> WireAdType.INTERSTITIAL
        AdType.REWARDED -> WireAdType.REWARDED
    }

    private fun renderer(type: CreativeType): Renderer = when (type) {
        CreativeType.HTML -> Renderer.HTML
        CreativeType.MRAID -> Renderer.MRAID
        CreativeType.VAST -> Renderer.VAST
    }

    private fun reportFailure(failure: Throwable) {
        Log.w(TAG, "Telemetry failed and is off until the app restarts", failure)
    }

    private fun isEnabled(context: Context): Boolean =
        try {
            applicationInfoWithMetaData(context).metaData?.getBoolean(ENABLED_FLAG, false) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun applicationInfoWithMetaData(context: Context): ApplicationInfo {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
    }

    /**
     * Syncs, and then sends, when the app leaves the foreground: from then on the process can be frozen or killed
     * without another callback. Later trim levels sync again, because a process with no UI never receives `TRIM_MEMORY_UI_HIDDEN` but
     * does receive them. A sync with nothing new to write costs little.
     */
    private class SyncInBackground(private val telemetry: Telemetry) : ComponentCallbacks2 {

        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) telemetry.sync()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }
}

/**
 * Milliseconds since this clock was created, on `SystemClock.elapsedRealtime()`, which never goes back and keeps
 * counting through deep sleep, unlike `System.nanoTime()`. The origin is the reading at creation and never moves.
 *
 * @param elapsedRealtime the reading of the elapsed-time clock, replaced in tests
 */
internal class ElapsedUptimeClock(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : UptimeClock {

    private val origin = elapsedRealtime()

    override fun uptimeMillis(): Long = elapsedRealtime() - origin
}
