package org.bidon.rendering.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The last good [TelemetrySettings], kept in one small file so the next process starts with them before any config
 * arrives. Written as a temporary file and a rename, so a crash mid-write leaves the old file whole.
 *
 * The file is one flat JSON object with only these keys, each written when its part is present: `url`, `app_key`,
 * `bundle`, `sdk_version`, `enabled`, `sample_rate`, `event_rates`, `event_names`. No advertising id, consent string or
 * event content. Reading applies the same rules as [TelemetryBlock], so a hand-edited or corrupt file gives
 * [TelemetrySettings.NONE] rather than an error. The stored `bundle` and `sdk_version` are the ones of the process that
 * wrote them: after an app update, batches carry the previous SDK version until the adapter's config arrives.
 *
 * Used only on the telemetry thread.
 */
internal class SettingsFile(private val locate: () -> File) {

    /** The stored settings; [TelemetrySettings.NONE] when there is no file or it cannot be read. Never throws. */
    fun read(): TelemetrySettings = try {
        val file = locate()
        if (!file.exists()) {
            TelemetrySettings.NONE
        } else {
            val json = JSONObject(file.readText())
            val bundle = json.opt("bundle") as? String
            val sdkVersion = json.opt("sdk_version") as? String
            val endpoint = if (bundle != null && sdkVersion != null) {
                TelemetryBlock.endpoint(json.opt("url") as? String, json.opt("app_key") as? String, bundle, sdkVersion)
            } else {
                null
            }
            TelemetrySettings(endpoint, TelemetryBlock.switches(json))
        }
    } catch (_: Exception) {
        // A file another build wrote, a half-written one, or one the system will not let us read: start from nothing.
        TelemetrySettings.NONE
    }

    /** Replaces the stored settings with [settings]. Throws [IOException] when the write fails. */
    @Throws(IOException::class)
    fun write(settings: TelemetrySettings) {
        val json = JSONObject()
        settings.endpoint?.let {
            json.put("url", it.url.toString())
            json.put("app_key", it.appKey)
            json.put("bundle", it.bundle)
            json.put("sdk_version", it.sdkVersion)
        }
        settings.config?.let {
            json.put("enabled", it.enabled)
            json.put("sample_rate", it.samplingRate)
            json.put("event_rates", JSONObject(it.eventRates))
            it.allowedEvents?.let { names -> json.put("event_names", JSONArray(names.sorted())) }
        }
        val file = locate()
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        if (!temporary.renameTo(file)) throw IOException("could not rename $temporary to $file")
    }

    companion object {
        const val FILE_NAME = "telemetry_config"
    }
}
