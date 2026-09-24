package org.bidon.rendering

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Where this process keeps its telemetry on disk.
 *
 * Each process gets its own directory. A publisher that initialises the SDK in `Application.onCreate()` without
 * checking the process initialises it in every process of the app, including ones other libraries start, and all of
 * them share the same app storage. Two processes writing one queue file would lose records silently (one rewrites the
 * file while the other keeps appending to the replaced copy) and send the rest twice.
 *
 * The directory is under `noBackupFilesDir` because the rest of the app's files are copied by Auto Backup and
 * restored on a new device, which would send another device's stale events.
 */
internal object TelemetryDirectory {

    fun forCurrentProcess(context: Context): File =
        File(File(context.noBackupFilesDir, "bidon_telemetry"), directoryName(processName()))

    /**
     * [processName] reduced to characters that are safe in a file name, for example `com.app:remote` becomes
     * `com.app_remote`.
     */
    internal fun directoryName(processName: String): String =
        processName.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '_' }
            .joinToString("")

    private fun processName(): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            readProcessNameFromProc()
        }
        // A fixed name, not the pid: records left under a name that changes every launch would never be read again.
        return name?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    /** The kernel's record of this process's name, NUL-terminated. Available before API 28, where nothing else is. */
    private fun readProcessNameFromProc(): String? =
        try {
            File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
}
