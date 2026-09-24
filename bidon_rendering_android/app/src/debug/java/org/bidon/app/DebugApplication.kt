package org.bidon.app

import android.app.Application
import android.os.StrictMode

/**
 * Logs every StrictMode violation on the main thread, from the first moment of the process, so disk or network work
 * that slips onto it shows up in logcat under `StrictMode`.
 *
 * It logs rather than crashes: WebView reads the disk on the main thread the first time it is used, and a crash would
 * stop the demo on code that is not ours. The renderer's own telemetry is held to zero violations by a device test.
 */
class DebugApplication : Application() {

    override fun onCreate() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        super.onCreate()
    }
}
