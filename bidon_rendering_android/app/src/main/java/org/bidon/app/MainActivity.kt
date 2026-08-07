package org.bidon.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.demo.tetris.R
import com.demo.tetris.databinding.ActivityMainBinding
import org.bidon.app.realflow.BannerFragment
import org.bidon.app.realflow.InterstitialFragment
import org.bidon.app.realflow.RewardedFragment
import org.bidon.app.renderer.RendererOnlyFragment
import org.bidon.app.settings.SdkSettings
import org.bidon.app.settings.SettingsFragment
import org.bidon.bidon.BidonAdapter
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.BidonSdk

/**
 * Holds the bottom navigation and initializes both SDKs. All ad work lives in the fragments:
 *
 * - [BannerFragment], [InterstitialFragment], [RewardedFragment] run the real flow
 *   (publisher app -> BidOn SDK -> live auction -> bidon adapter -> uSDK renderer).
 * - [RendererOnlyFragment] drives the renderer directly with hardcoded creatives and does not
 *   touch the SDK at all.
 * - [SettingsFragment] edits what the SDK initializes with. It only writes to shared preferences;
 *   the values are read here, once, on the next app start.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BidonRenderer.init(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.tab_banner -> BannerFragment::class.java
                    R.id.tab_interstitial -> InterstitialFragment::class.java
                    R.id.tab_rewarded -> RewardedFragment::class.java
                    R.id.tab_renderer -> RendererOnlyFragment::class.java
                    R.id.tab_settings -> SettingsFragment::class.java
                    else -> return@setOnItemSelectedListener false
                },
                tag = item.itemId.toString()
            )
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.tab_banner
            initBidonSdk()
        }
    }

    private fun show(fragment: Class<out Fragment>, tag: String) {
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment, null, tag)
        }
    }

    private fun initBidonSdk() {
        val settings = SdkSettings.load(this)

        BidonSdk
            .registerAdapters(BidonAdapter())
            .setLoggerLevel(settings.logLevel)
            .setTestMode(settings.testMode)
            .setBaseUrl(settings.baseUrl)
            .setInitializationCallback { applyRegulation(settings) }
            .initialize(context = this, appKey = settings.appKey)
    }

    /**
     * Regulation resolves through the SDK's own dependency graph, which only exists once
     * initialize() has run - reading it any earlier throws. So it is applied on the init callback,
     * which lands well before any fragment can ask for an ad.
     */
    private fun applyRegulation(settings: SdkSettings) {
        BidonSdk.regulation.apply {
            gdpr = settings.gdpr
            gdprConsentString = settings.gdprConsentString
            usPrivacyString = settings.usPrivacyString
            coppa = settings.coppa
        }
    }
}
