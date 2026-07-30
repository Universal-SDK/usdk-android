package org.bidon.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.demo.tetris.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.bidon.bidon.BidonAdapter
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.logs.logging.Logger

private const val BASE_URL = "https://sdkapi.bidon.squads.com"
private const val APP_KEY = "tetris_2005"

/**
 * Holds the bottom navigation and initializes both SDKs. All ad work lives in the fragments:
 *
 * - [BannerFragment], [InterstitialFragment], [RewardedFragment] run the real flow
 *   (publisher app -> BidOn SDK -> live auction -> bidon adapter -> uSDK renderer).
 * - [RendererOnlyFragment] drives the renderer directly with hardcoded creatives and does not
 *   touch the SDK at all.
 */
class MainActivity : AppCompatActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BidonRenderer.init(this)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.tab_banner -> BannerFragment::class.java
                    R.id.tab_interstitial -> InterstitialFragment::class.java
                    R.id.tab_rewarded -> RewardedFragment::class.java
                    R.id.tab_renderer -> RendererOnlyFragment::class.java
                    else -> return@setOnItemSelectedListener false
                },
                tag = item.itemId.toString()
            )
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.tab_banner
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
        FlowLog.step("init 1", "BidonSdk.initialize (appKey=$APP_KEY, $BASE_URL)")
        BidonSdk
            .registerAdapters(BidonAdapter())
            .setBaseUrl(BASE_URL)
            .setLoggerLevel(Logger.Level.Verbose)
            .setInitializationCallback {
                FlowLog.step("init 2", "POST /v2/config done, the adapter is registered as adikteev")
                FlowLog.step("init 3", "ready - pick a format from the bottom bar")
            }
            .initialize(context = this, appKey = APP_KEY)
    }
}
