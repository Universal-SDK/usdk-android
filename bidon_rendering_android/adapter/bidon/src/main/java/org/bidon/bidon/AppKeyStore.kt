package org.bidon.bidon

import android.content.Context

/**
 * The publisher's app key as the mediation SDK stored it at `initialize()`, for when the server's telemetry block has
 * none (D-033). The mediation SDK writes it before it requests config, so it is there by the time adapters start.
 */
internal fun interface AppKeyStore {

    /** The stored app key, or `null` when there is none or it is blank. Never throws. */
    fun appKey(): String?

    companion object {
        /** The mediation SDK's own preferences file and key. */
        internal const val PREFERENCES = "bidon_preferences"
        internal const val APP_KEY = "BidonAppKey_KEY"

        fun fromPreferences(context: Context): AppKeyStore = AppKeyStore {
            try {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(APP_KEY, null)
                    ?.takeIf { it.isNotBlank() }
            } catch (_: RuntimeException) {
                // Stored under another type by some other version, or the preferences could not be read.
                null
            }
        }
    }
}
