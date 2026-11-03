package org.bidon.app.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.demo.tetris.R
import org.bidon.sdk.logs.logging.Logger
import org.bidon.sdk.regulation.Coppa
import org.bidon.sdk.regulation.Gdpr

/**
 * Everything the Settings tab can change, read back out of shared preferences.
 *
 * The screen itself is [SettingsFragment]; the keys and defaults live in res/values/settings.xml.
 * MainActivity reads a [SdkSettings] once at startup and initializes the SDK from it.
 */
data class SdkSettings(
    val appKey: String,
    val baseUrl: String,
    /** Null means send the real package name. */
    val bundleId: String?,
    /** Where the renderer sends telemetry until the server's config says; null leaves it to the server. */
    val telemetryUrl: String?,
    val testMode: Boolean,
    val logLevel: Logger.Level,
    val advertisingId: String,
    val gdpr: Gdpr,
    val gdprConsentString: String?,
    val usPrivacyString: String?,
    val coppa: Coppa,
) {
    companion object {

        fun load(context: Context): SdkSettings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            fun string(keyRes: Int, defaultRes: Int): String {
                val default = context.getString(defaultRes)
                val value = prefs.getString(context.getString(keyRes), default)
                return value?.trim().takeUnless { it.isNullOrEmpty() } ?: default
            }

            fun optionalString(keyRes: Int): String? =
                prefs.getString(context.getString(keyRes), null)
                    ?.trim()
                    ?.takeUnless { it.isEmpty() }

            fun code(keyRes: Int): Int {
                val unknown = context.getString(R.string.pref_tristate_default)
                return prefs.getString(context.getString(keyRes), unknown)?.toIntOrNull()
                    ?: unknown.toInt()
            }

            return SdkSettings(
                appKey = string(R.string.pref_app_key, R.string.pref_app_key_default),
                baseUrl = string(R.string.pref_base_url, R.string.pref_base_url_default),
                bundleId = optionalString(R.string.pref_bundle_id),
                telemetryUrl = optionalString(R.string.pref_telemetry_url),
                testMode = prefs.getBoolean(
                    context.getString(R.string.pref_test_mode),
                    context.resources.getBoolean(R.bool.pref_test_mode_default)
                ),
                logLevel = string(R.string.pref_log_level, R.string.pref_log_level_default)
                    .toLogLevel(),
                advertisingId = string(
                    R.string.pref_advertising_id,
                    R.string.pref_advertising_id_default
                ),
                gdpr = Gdpr.entries.firstOrNull { it.code == code(R.string.pref_gdpr) }
                    ?: Gdpr.Default,
                gdprConsentString = optionalString(R.string.pref_gdpr_consent_string),
                usPrivacyString = optionalString(R.string.pref_us_privacy_string),
                coppa = Coppa.entries.firstOrNull { it.code == code(R.string.pref_coppa) }
                    ?: Coppa.Default,
            )
        }

        /**
         * Wipes every value this screen owns. The next read falls back to the defaults declared in
         * res/values/settings.xml, which are the ones the demo app shipped with.
         */
        fun resetToDefaults(context: Context) {
            val keys = listOf(
                R.string.pref_app_key,
                R.string.pref_base_url,
                R.string.pref_bundle_id,
                R.string.pref_test_mode,
                R.string.pref_log_level,
                R.string.pref_advertising_id,
                R.string.pref_gdpr,
                R.string.pref_gdpr_consent_string,
                R.string.pref_us_privacy_string,
                R.string.pref_coppa,
            )

            PreferenceManager.getDefaultSharedPreferences(context).edit {
                keys.forEach { remove(context.getString(it)) }
            }
        }

        private fun String.toLogLevel(): Logger.Level =
            Logger.Level.entries.firstOrNull { it.name == this } ?: Logger.Level.Verbose
    }
}
