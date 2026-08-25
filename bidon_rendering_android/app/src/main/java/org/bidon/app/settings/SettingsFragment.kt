package org.bidon.app.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.demo.tetris.R
import org.bidon.sdk.BidonSdk

/**
 * Lets anyone running the demo change what the SDK initializes with, without editing MainActivity
 * and rebuilding.
 *
 * The SDK initializes once per process and ignores later calls, so nothing on this screen takes
 * effect until the app restarts. The screen says so, and the "Restart app" item does it.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen(rootKey)
    }

    private fun buildScreen(rootKey: String? = null) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        showStatus()
        wireActions()
        flagChangesAsPendingRestart()
    }

    private fun showStatus() {
        val initialized = BidonSdk.isInitialized()

        findPreference<Preference>("status_sdk_state")?.summary = getString(
            if (initialized) R.string.settings_state_initialized
            else R.string.settings_state_not_initialized
        )

        findPreference<Preference>("status_sdk_version")?.summary = BidonSdk.SdkVersion

        // requireContext() is the real one, so this reports what the app is actually installed
        // as when no override is set.
        val bundleId = SdkSettings.load(requireContext()).bundleId
        findPreference<Preference>("status_bundle_id")?.summary = when (bundleId) {
            null -> getString(R.string.settings_bundle_id_real, requireContext().packageName)
            else -> getString(R.string.settings_bundle_id_overridden, bundleId)
        }

        // baseUrl resolves through the SDK's own dependency graph, which only exists once
        // initialize() has run. Reading it earlier throws.
        findPreference<Preference>("status_base_url")?.summary =
            if (initialized) BidonSdk.baseUrl else getString(R.string.settings_not_set)
    }

    private fun wireActions() {
        findPreference<Preference>("action_restart")?.setOnPreferenceClickListener {
            restartApp()
            true
        }

        findPreference<Preference>("action_reset")?.setOnPreferenceClickListener {
            SdkSettings.resetToDefaults(requireContext())
            // Rebuild so the controls show the defaults instead of what was just cleared.
            buildScreen()
            toast(getString(R.string.settings_reset_done))
            true
        }
    }

    /** Every editable field needs a restart to reach the SDK, so say so the moment one changes. */
    private fun flagChangesAsPendingRestart() {
        forEachPreference(preferenceScreen) { preference ->
            if (!preference.isPersistent) return@forEachPreference

            preference.setOnPreferenceChangeListener { _, _ ->
                toast(getString(R.string.settings_pending_restart))
                true
            }
        }
    }

    private fun forEachPreference(group: PreferenceGroup, action: (Preference) -> Unit) {
        for (i in 0 until group.preferenceCount) {
            when (val preference = group.getPreference(i)) {
                is PreferenceGroup -> forEachPreference(preference, action)
                else -> action(preference)
            }
        }
    }

    /**
     * Kills the process rather than just recreating the activity. The SDK holds its initialized
     * state in a static, so a fresh process is the only way to make it initialize again.
     */
    private fun restartApp() {
        val context = requireContext()
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launch == null) {
            toast("Could not find the launcher activity to restart")
            return
        }

        startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
