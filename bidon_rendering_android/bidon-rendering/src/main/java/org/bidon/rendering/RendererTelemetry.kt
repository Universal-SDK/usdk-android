package org.bidon.rendering

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import org.bidon.rendering.telemetry.BackgroundTelemetry
import org.bidon.rendering.telemetry.DiskEventStore
import org.bidon.rendering.telemetry.Telemetry

/**
 * Connects the renderer's telemetry to Android: whether it runs, where it keeps its files, and when it syncs.
 *
 * Telemetry is off unless the host app's manifest switches it on:
 *
 * ```xml
 * <meta-data android:name="org.bidon.rendering.telemetry" android:value="true" />
 * ```
 *
 * Nothing sends the events anywhere yet, so an enabled library would only fill publishers' storage. A remote switch
 * replaces the flag once events are sent.
 */
internal object RendererTelemetry {

    private const val ENABLED_FLAG = "org.bidon.rendering.telemetry"

    private const val TAG = "BidonTelemetry"

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
            if (isEnabled(context)) startThread(context) else Telemetry.Disabled
        } catch (failure: Throwable) {
            reportFailure(failure)
            Telemetry.Disabled
        }

    private fun startThread(context: Context): Telemetry {
        val telemetry = BackgroundTelemetry.start(
            openStore = { DiskEventStore.open(TelemetryDirectory.forCurrentProcess(context)) },
            onFailure = ::reportFailure,
        )
        context.registerComponentCallbacks(SyncInBackground(telemetry))
        return telemetry
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
     * Syncs when the app leaves the foreground: from then on the process can be frozen or killed without another
     * callback. Later trim levels sync again, because a process with no UI never receives `TRIM_MEMORY_UI_HIDDEN` but
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
