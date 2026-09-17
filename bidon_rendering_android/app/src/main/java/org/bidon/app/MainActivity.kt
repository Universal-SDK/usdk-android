package org.bidon.app

import android.content.Context
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
import org.bidon.app.settings.BundleIdApplication
import org.bidon.app.settings.SdkSettings
import org.bidon.app.settings.SettingsFragment
import org.bidon.bidon.BidonAdapter
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.BidonSdk

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top)
            insets
        }

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
            .initialize(context = sdkContext(settings), appKey = settings.appKey)
    }

    /**
     * The SDK takes its bundle id from whatever context it is initialized with, so a bundle id
     * override means handing it a stand-in. Without an override it gets this activity, exactly as
     * before.
     */
    private fun sdkContext(settings: SdkSettings): Context {
        val bundleId = settings.bundleId ?: return this
        return BundleIdApplication(real = application, bundleId = bundleId)
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
