package org.bidon.app

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.logs.logging.Logger
import org.bidon.bidon.BidonAdapter
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.interstitial.InterstitialAd
import org.bidon.sdk.ads.interstitial.InterstitialListener
import org.bidon.sdk.ads.banner.BannerView
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerListener
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue

class MainActivity : AppCompatActivity() {

    private lateinit var bannerContainer: FrameLayout
    private lateinit var tvLog: TextView
    private lateinit var btnLoadBanner: Button
    private lateinit var btnShowBanner: Button
    private lateinit var btnLoadInterstitial: Button
    private lateinit var btnShowInterstitial: Button
    private lateinit var btnLoadVast: Button
    private lateinit var btnShowVast: Button
    private lateinit var btnLoadMraid: Button
    private lateinit var btnShowMraid: Button
    private lateinit var btnLoadMraidInterstitial: Button
    private lateinit var btnShowMraidInterstitial: Button
    private lateinit var btnDestroy: Button

    private var bannerRenderer: AdRenderer? = null
    private var interstitialRenderer: AdRenderer? = null
    private var vastRenderer: AdRenderer? = null
    private var mraidRenderer: AdRenderer? = null
    private var mraidInterstitialRenderer: AdRenderer? = null

    private var isBannerLoaded = false
    private var isInterstitialLoaded = false
    private var isVastLoaded = false
    private var isMraidLoaded = false
    private var isMraidInterstitialLoaded = false

    private var sdkInterstitial: InterstitialAd? = null
    private var sdkBanner: BannerView? = null

    // Runs a real SDK auction (POST /v2/auction) and logs every callback with "hop:".
    // No auctionKey is passed: the backend picks the auction from the app key and the ad type.
    private fun loadSdkInterstitial() {
        val ad = InterstitialAd()
        sdkInterstitial = ad
        ad.setInterstitialListener(object : InterstitialListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) { println("hop: SDK onAdLoaded ad=$ad") }
            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) { println("hop: SDK onAdLoadFailed: $cause") }
            override fun onAdShown(ad: Ad) { println("hop: SDK onAdShown") }
            override fun onAdShowFailed(cause: BidonError) { println("hop: SDK onAdShowFailed: $cause") }
            override fun onAdClicked(ad: Ad) { println("hop: SDK onAdClicked") }
            override fun onAdClosed(ad: Ad) { println("hop: SDK onAdClosed") }
            override fun onAdExpired(ad: Ad) { println("hop: SDK onAdExpired") }
            override fun onRevenuePaid(ad: Ad, adValue: AdValue) { println("hop: SDK onRevenuePaid") }
        })
        println("hop: interstitial.loadAd() -> should trigger POST /v2/auction")
        ad.loadAd(this)
    }

    // Runs a real SDK BANNER auction (POST /v2/auction/banner) and logs callbacks.
    private fun loadSdkBanner() {
        val banner = BannerView(context = this)
        sdkBanner = banner
        bannerContainer.removeAllViews()
        bannerContainer.addView(banner)
        banner.setBannerFormat(BannerFormat.Banner)
        banner.setBannerListener(object : BannerListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                println("hop: SDK banner onAdLoaded ad=$ad")
                runOnUiThread { banner.showAd() }
            }
            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) { println("hop: SDK banner onAdLoadFailed: $cause") }
            override fun onAdShown(ad: Ad) { println("hop: SDK banner onAdShown") }
            override fun onAdShowFailed(cause: BidonError) { println("hop: SDK banner onAdShowFailed: $cause") }
            override fun onAdClicked(ad: Ad) { println("hop: SDK banner onAdClicked") }
            override fun onAdExpired(ad: Ad) { println("hop: SDK banner onAdExpired") }
            override fun onRevenuePaid(ad: Ad, adValue: AdValue) { println("hop: SDK banner onRevenuePaid") }
        })
        println("hop: banner.loadAd() -> should trigger POST /v2/auction/banner")
        banner.loadAd(this, BidonSdk.DefaultPricefloor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BidonRenderer.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 500, 16, 16)
            setBackgroundColor(Color.WHITE)
        }

        btnLoadBanner = Button(this).apply {
            text = "Load Banner"
            setOnClickListener { loadBanner() }
        }
        btnShowBanner = Button(this).apply {
            text = "Show Banner"
            isEnabled = false
            setOnClickListener { showBanner() }
        }

        btnLoadInterstitial = Button(this).apply {
            text = "Load Interstitial"
            setOnClickListener { loadInterstitial() }
        }
        btnShowInterstitial = Button(this).apply {
            text = "Show Interstitial"
            isEnabled = false
            setOnClickListener { showInterstitial() }
        }

        btnLoadVast = Button(this).apply {
            text = "Load Vast"
            setOnClickListener { loadVast() }
        }
        btnShowVast = Button(this).apply {
            text = "Show Vast"
            isEnabled = false
            setOnClickListener { showVast() }
        }

        btnLoadMraid = Button(this).apply {
            text = "Load MRAID Banner"
            setOnClickListener { loadMraid() }
        }
        btnShowMraid = Button(this).apply {
            text = "Show MRAID Banner"
            isEnabled = false
            setOnClickListener { showMraid() }
        }

        btnLoadMraidInterstitial = Button(this).apply {
            text = "Load MRAID Interstitial"
            setOnClickListener { loadMraidInterstitial() }
        }
        btnShowMraidInterstitial = Button(this).apply {
            text = "Show MRAID Interstitial"
            isEnabled = false
            setOnClickListener { showMraidInterstitial() }
        }

        btnDestroy = Button(this).apply {
            text = "Destroy"
            setOnClickListener { destroyAll() }
        }

        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (50 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.LTGRAY)
        }

        tvLog = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 12f
        }

        root.addView(btnLoadBanner)
        root.addView(btnShowBanner)
        root.addView(btnLoadInterstitial)
        root.addView(btnShowInterstitial)
        root.addView(btnLoadVast)
        root.addView(btnShowVast)
        root.addView(btnLoadMraid)
        root.addView(btnShowMraid)
        root.addView(btnLoadMraidInterstitial)
        root.addView(btnShowMraidInterstitial)
        root.addView(btnDestroy)
        root.addView(bannerContainer)
        root.addView(tvLog)

        setContentView(root)

        log("App started")

        // Init the BidOn SDK last, after the views exist. On a warm start the SDK is already
        // initialized and setInitializationCallback runs the callback inline, so anything it
        // touches has to be built by now.
        println("hop: calling BidonSdk.initialize (baseUrl=sdkapi.bidon.squads.com, appKey=tetris_2005)")
        BidonSdk
            .registerAdapters(BidonAdapter())
            .setBaseUrl("https://sdkapi.bidon.squads.com")
            .setLoggerLevel(Logger.Level.Verbose)
            .setInitializationCallback {
                println("hop: BidonSdk_initialized! isInitialized=${BidonSdk.isInitialized()}")
                runOnUiThread { loadSdkBanner() }
            }
            .initialize(context = this, appKey = "tetris_2005")
    }


    //Below, I tested PubRev SSP and other ads


    // ---------------- BANNER ----------------

    private fun loadBanner() {
        log("Loading banner...")
        bannerRenderer = BidonRenderer.createHtmlRenderer()

        val width = 320
        val height = 50
        val appName = "Tetris"
        val appBundle = "com.demo.tetris"

        val appStoreUrl = "https://google.com"
        val timestamp = System.currentTimeMillis().toString()

        val stableUserAgent = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP22.250325.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/137.0.7151.115 Mobile Safari/537.36"

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        html, body {
            margin: 0;
            padding: 0;
            overflow: hidden;
            width: 100%;
            height: 100%;
            background-color: transparent;
        }
    </style>
</head>
<body>
<script id="PDS941436" type="text/javascript">
(function(d){
    var wrapper = d.createElement("script");
    wrapper.id = "WDS941436";
    wrapper.type = "text/javascript";
    wrapper.src = "https://s.adtelligent.com/?" +
            "width=$width" +
            "&height=$height" +
            "&ua=" + encodeURIComponent("$stableUserAgent") +
            "&uip=" +
            "&app_name=$appName" +
            "&app_bundle=$appBundle" +
            "&idfa=" +
            "&adid=c013dc70-1e9a-4f92-96cd-e867b31c4b7d" +
            "&geo_lat=50.4501" +
            "&geo_lon=30.5234" +
            "&zip=01001" +
            "&cb=$timestamp" +
            "&app_store_url=" + encodeURIComponent("$appStoreUrl") +
            "&floor_cpm=0.01" +
            "&aid=941436" +
            "&gdpr=0" +
            "&lmt=0" +
            "&site_url=" + encodeURIComponent("https://play.google.com");
    var s = d.getElementById("PDS941436");
    s.parentNode.insertBefore(wrapper, s);
})(document);
</script>
</body>
</html>
""".trimIndent()

        bannerRenderer?.load(adm = html, listener = object : AdRenderer.Listener {
            override fun onLoaded() { log("Banner loaded"); isBannerLoaded = true; btnShowBanner.isEnabled = true
                TrackingDispatcher.fire("https://wikimedia.org", "nurl")
            }

            override fun onImpression() { log("Banner impression")
                TrackingDispatcher.fire("https://wikimedia.org", "burl")
            }
            override fun onClick() { log("Banner click") }
            override fun onError(error: Throwable) { log("Banner error: ${error.message}") }
        })
    }

    private fun showBanner() {
        log("Show banner")
        val view = bannerRenderer?.getView() ?: run { log("Banner view is null"); return }
        bannerContainer.removeAllViews()
        bannerContainer.addView(view)
    }

    // ---------------- INTERSTITIAL ----------------

    private fun loadInterstitial() {
        log("Loading interstitial...")
        interstitialRenderer = BidonRenderer.createHtmlRenderer()




        val width = 480  //320
        val height = 320 //480
        val aid = "953543" //"931974"

        val appName = "Tetris"
        val appBundle = "com.demo.tetris"
        val appStoreUrl = "https://google.com"
        val timestamp = System.currentTimeMillis().toString()

        val stableUserAgent = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP22.250325.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/137.0.7151.115 Mobile Safari/537.36"

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        html, body {
            margin: 0;
            padding: 0;
            overflow: hidden;
            width: 100%;
            height: 100%;
            background-color: black;
            display: flex;
            justify-content: center;
            align-items: center;
        }
    </style>
</head>
<body>
<script id="PDS$aid" type="text/javascript">
(function(d){
    var wrapper = d.createElement("script");
    wrapper.id = "WDS$aid";
    wrapper.type = "text/javascript";
    wrapper.src = "https://s.adtelligent.com/?" +
            "width=$width" +
            "&height=$height" +
            "&ua=" + encodeURIComponent("$stableUserAgent") +
            "&uip=" +
            "&app_name=$appName" +
            "&app_bundle=$appBundle" +
            "&idfa=" +
            "&adid=c013dc70-1e9a-4f92-96cd-e867b31c4b7d" +
            "&geo_lat=50.4501" +
            "&geo_lon=30.5234" +
            "&zip=01001" +
            "&cb=$timestamp" +
            "&app_store_url=" + encodeURIComponent("$appStoreUrl") +
            "&floor_cpm=0.01" +
            "&aid=$aid";
    var s = d.getElementById("PDS$aid");
    s.parentNode.insertBefore(wrapper, s);
})(document);
</script>
</body>
</html>
""".trimIndent()

        interstitialRenderer?.load(adm = html, listener = object : AdRenderer.Listener {
            override fun onLoaded() { log("Interstitial loaded"); isInterstitialLoaded = true; btnShowInterstitial.isEnabled = true
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=nurl&test=true", "nurl")
            }
            override fun onImpression() {
                log("Interstitial impression")
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=burl&test=true", "burl")}
            override fun onClick() { log("Interstitial click")}
            override fun onError(error: Throwable) { log("Interstitial error: ${error.message}") }
        })
    }

    private fun showInterstitial() {
        log("Show interstitial")
        interstitialRenderer?.showFullscreen(this)
    }

    // ---------------- VAST ----------------

    private fun loadVast() {

        val vastXml = """
        <?xml version=\"1.0\" encoding=\"utf-8\"?><VAST version=\"2.0\"><Ad id=\"PEOHUpEzIx9LT0kjgOTgJ4\"><InLine><AdSystem version=\"1.0\">Adikteev RTB</AdSystem><AdTitle><![CDATA[24option]]></AdTitle><Impression><![CDATA[https://impression-eu.adikteev.com/3.0?adSession=PEOHUpEzIx9LT0kjgOTgJ4&ak_protocol_version=ak_bidder&algorithm=&android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&bid_on=0&bidrequest_id=019ea745-f9ba-7ecd-a966-f670a92a9f17&campaign=52300&ch=mobile&city=archena&click_id=y4G4gHG0GbXTt0YuPO0s9H&client_cpm_usd=8D9BA29CD83FBB83239D350A2B9B45EAC9E41C8443295193&connection_type=wifi&country=ESP&country_a2=ES&crAlgorithm=&creativeGroupId=178040&creativeGroupName=Auto_Test_CreativeGroup&creativeId=525851&creativeName=525178&creativeType=creative_video&deviceFamily=android&deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&device_model=Google+Pixel+3&device_name=Google+Google+Pixel+3&device_type=4&fm=0&h=640&impression_id=4yeJOwDDQHlfs4aUM20TWS&ip_addr=94.24.105.28&os=android&os_raw=android&osv=12&publisherAppId=com.demo.tetris&rbm=1.01&retina=0&s2s=1&supplier=344&supplier_name=appodeal&ts=1780922841&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg&user_id=&w=360&zip=30600&cpm=${1}]]></Impression><Creatives><Creative><Linear><Duration>00:00:30.000</Duration><TrackingEvents><Tracking event=\"firstQuartile\"><![CDATA[https://impression-eu.adikteev.com/engaged_view?adSession=PEOHUpEzIx9LT0kjgOTgJ4&ak_protocol_version=ak_bidder&algorithm=&android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&bid_on=0&bidrequest_id=019ea745-f9ba-7ecd-a966-f670a92a9f17&campaign=52300&ch=mobile&city=archena&client_cpm_usd=8D9BA29CD83FBB83239D350A2B9B45EAC9E41C8443295193&connection_type=wifi&country=ESP&country_a2=ES&crAlgorithm=&creativeGroupId=178040&creativeGroupName=Auto_Test_CreativeGroup&creativeId=525851&creativeName=525178&creativeType=creative_video&deviceFamily=android&deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&device_model=Google+Pixel+3&device_name=Google+Google+Pixel+3&device_type=4&h=640&impression_id=4yeJOwDDQHlfs4aUM20TWS&ip_addr=94.24.105.28&os=android&os_raw=android&osv=12&publisherAppId=com.demo.tetris&retina=0&supplier=344&supplier_name=appodeal&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg&user_id=&w=360&zip=30600&cpm=${1}]]></Tracking><Tracking event=\"firstQuartile\"><![CDATA[]]></Tracking></TrackingEvents><VideoClicks><ClickThrough><![CDATA[https://click-geo.adikteev.com/v1/click?impression_id=4yeJOwDDQHlfs4aUM20TWS&user_id=&creativeGroupId=178040&creativeId=525851&bidrequest_id=019ea745-f9ba-7ecd-a966-f670a92a9f17&campaign=52300&deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&supplier=344&android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&expires=1781066841&signature=zRa_gVgjlIrWzpHlyrbmoB7cpAUpgiy-9zHS5_rPcDQ&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&country=ESP&creativeName=525178&os=android&publisherAppId=com.demo.tetris&algorithm=&bid_on=0&click_source=video&supplier_name=appodeal&creativeGroupName=Auto_Test_CreativeGroup&creativeType=creative_video&device_type=4&ip_addr=94.24.105.28&os_raw=android&retina=0&adSession=PEOHUpEzIx9LT0kjgOTgJ4&ch=mobile&w=360&zip=30600&city=archena&country_a2=ES&osv=12&crAlgorithm=&h=640&ak_protocol_version=ak_bidder&device_model=Google+Pixel+3]]></ClickThrough></VideoClicks><MediaFiles><MediaFile delivery=\"progressive\" width=\"360\" height=\"640\" type=\"video/mp4\"><![CDATA[https://cdn-creatives.adikteev.com/video/95415/encoded-525178.mp4]]></MediaFile></MediaFiles></Linear></Creative><Creative><CompanionAds><Companion width=\"360\" height=\"640\"><StaticResource creativeType=\"image/jpeg\"><![CDATA[https://cdn-creatives.adikteev.com/companion/95415/525178.jpeg]]></StaticResource><CompanionClickThrough><![CDATA[https://click-geo.adikteev.com/v1/click?deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&impression_id=4yeJOwDDQHlfs4aUM20TWS&supplier=344&campaign=52300&bidrequest_id=019ea745-f9ba-7ecd-a966-f670a92a9f17&user_id=&creativeId=525851&creativeGroupId=178040&expires=1781066841&signature=nTNorJBhaklJ25tL68mbI-1IJn7b8V31IttVpjRHLpI&device_type=4&h=640&click_source=companion_static&creativeType=creative_video&bid_on=0&device_model=Google+Pixel+3&publisherAppId=com.demo.tetris&retina=0&w=360&creativeGroupName=Auto_Test_CreativeGroup&os=android&zip=30600&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&os_raw=android&city=archena&country_a2=ES&creativeName=525178&algorithm=&country=ESP&osv=12&supplier_name=appodeal&adSession=PEOHUpEzIx9LT0kjgOTgJ4&ak_protocol_version=ak_bidder&ch=mobile&crAlgorithm=&ip_addr=94.24.105.28&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg]]></CompanionClickThrough><TrackingEvents><Tracking event=\"creativeView\"><![CDATA[https://impression-eu.adikteev.com/companion_view?supplier=344]]></Tracking></TrackingEvents></Companion></CompanionAds></Creative></Creatives></InLine></Ad></VAST>
        """.trimIndent()
        vastRenderer = BidonRenderer.createVastRenderer()
        vastRenderer?.load(adm = vastXml, listener = object : AdRenderer.Listener {
            override fun onLoaded() { log("VAST loaded"); isVastLoaded = true; btnShowVast.isEnabled = true
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=nurl&test=true", "nurl")
            }
            override fun onImpression() { log("VAST impression")
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=burl&test=true", "burl")
            }
            override fun onClick() { log("VAST click") }
            override fun onError(error: Throwable) { log("VAST error: ${error.message}") }
        })
    }

    private fun showVast() {
        vastRenderer?.showFullscreen(this)
    }

    // ---------------- MRAID BANNER ----------------

    private fun loadMraid() {
        log("Loading MRAID banner...")
        mraidRenderer = BidonRenderer.createMraidRenderer()

        val mraidHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                body { margin: 0; background: #1a1a2e; display: flex;
                       align-items: center; justify-content: center;
                       width: 320px; height: 50px; overflow: hidden; }
                .banner { width: 100%; height: 100%; display: flex;
                          align-items: center; justify-content: center; cursor: pointer; }
                .text { color: #e94560; font-family: sans-serif;
                        font-size: 14px; font-weight: bold; }
              </style>
            </head>
            <body>
              <div class="banner" onclick="onBannerTap()">
                <span class="text">🎯 MRAID Banner — Tap to Expand</span>
              </div>
              <script>
                function onBannerTap() {
                  if (typeof mraid !== 'undefined' && mraid.getState() === 'default') {
                    mraid.setExpandProperties({ width: 320, height: 480 });
                    mraid.expand();
                  }
                }
                function onMraidReady() {
                  mraid.addEventListener('stateChange', function(state) {
                    console.log('MRAID state:', state);
                  });
                }
                if (typeof mraid !== 'undefined') {
                  if (mraid.isReady()) { onMraidReady(); }
                  else { mraid.addEventListener('ready', onMraidReady); }
                }
              </script>
            </body>
            </html>
        """.trimIndent()

        mraidRenderer?.load(adm = mraidHtml, listener = object : AdRenderer.Listener {
            override fun onLoaded() { log("MRAID banner loaded"); isMraidLoaded = true; btnShowMraid.isEnabled = true
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=nurl&test=true", "nurl")
            }
            override fun onImpression() { log("MRAID banner impression")
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=burl&test=true", "burl")
            }
            override fun onClick() { log("MRAID banner click") }
            override fun onError(error: Throwable) { log("MRAID banner error: ${error.message}") }
        })
    }

    private fun showMraid() {
        log("Show MRAID banner")
        val view = mraidRenderer?.getView() ?: run { log("MRAID view is null"); return }
        bannerContainer.removeAllViews()
        bannerContainer.layoutParams = (bannerContainer.layoutParams as LinearLayout.LayoutParams).apply {
            height = (50 * resources.displayMetrics.density).toInt()
        }
        bannerContainer.addView(view)
    }

    // ---------------- MRAID INTERSTITIAL ----------------

    private fun loadMraidInterstitial() {
        log("Loading MRAID interstitial...")
        mraidInterstitialRenderer = BidonRenderer.createMraidRenderer()
        val mraidHtml = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"><title>Ad</title></head><body><script src='mraid.js'></script> <script> var BASE_URL = 'https://cdn-ww.adikteev.com/craftsman/Hbz20iChA9f/48d6033c9db4d7d03c7b16a472364a49585b8bd3/'; var AK_CLICK_DESTINATION_URL = (false && 'android' === 'ios') ? 'https://www.adikteev.com?cr=48d6033c9db4d7d03c7b16a472364a49585b8bd3' : 'https://click-geo.adikteev.com/v1/click?user_id=&bidrequest_id=019eca06-aca2-70bf-91c0-e6527b83b55e&creativeGroupId=178040&impression_id=Lnh-L2_zidqFF-yklqcnP2&android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&supplier=344&campaign=52300&creativeId=525852&expires=1781649895&signature=6wJJxoLbM52bHtCCjTtk8TCGWNbKCgt0siW8WzSl_v4&adSession=yg6L9wP0-klQUtjZxkUQxe&h=480&w=320&crAlgorithm=&device_model=Google+Pixel+3&ip_addr=94.24.105.28&osv=12&supplier_name=appodeal&publisherAppId=com.demo.tetris&creativeGroupName=Auto_Test_CreativeGroup&ak_protocol_version=ak_bidder&country=ESP&creativeName=48d6033c9db4d7d03c7b16a472364a49585b8bd3&creativeType=creative_mraid_banner&os_raw=android&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg&zip=30600&country_a2=ES&retina=0&algorithm=&bid_on=0&city=archena&device_type=4&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&ch=mobile&os=android'; var AK_CLICK_PIXEL_URL = (false && 'android' === 'ios') ? 'https://click-geo.adikteev.com/v1/click?android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&creativeGroupId=178040&user_id=&campaign=52300&deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&impression_id=Lnh-L2_zidqFF-yklqcnP2&creativeId=525852&supplier=344&bidrequest_id=019eca06-aca2-70bf-91c0-e6527b83b55e&expires=1781649895&signature=vhx_N3FEfWmhyrQ4H9JsjulJxMhyuyBPSPyYI0ycYtg&device_model=Google+Pixel+3&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&creativeGroupName=Auto_Test_CreativeGroup&device_type=4&bid_on=0&country_a2=ES&city=archena&country=ESP&noredirect=1&osv=12&retina=0&zip=30600&crAlgorithm=&creativeType=creative_mraid_banner&h=480&os_raw=android&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg&adSession=yg6L9wP0-klQUtjZxkUQxe&os=android&w=320&ch=mobile&creativeName=48d6033c9db4d7d03c7b16a472364a49585b8bd3&ip_addr=94.24.105.28&publisherAppId=com.demo.tetris&algorithm=&click_id=jHRnwPzXM02mXdx3pX1Fao&supplier_name=appodeal&ak_protocol_version=ak_bidder' : undefined; </script> <script src='https://cdn-ww.adikteev.com/craftsman/Hbz20iChA9f/48d6033c9db4d7d03c7b16a472364a49585b8bd3/playable.js'></script><img src="https://impression-eu.adikteev.com/3.0?adSession=yg6L9wP0-klQUtjZxkUQxe&ak_protocol_version=ak_bidder&algorithm=&android_id=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&android_id_plain=013b8c47-8beb-41b4-aefe-60fe466f9fd8&bid_on=0&bidrequest_id=019eca06-aca2-70bf-91c0-e6527b83b55e&campaign=52300&ch=mobile&city=archena&click_id=jHRnwPzXM02mXdx3pX1Fao&client_cpm_usd=8D9BA29CD83FBB83239D350A2B9B45EAC9E41C8443295193&connection_type=wifi&country=ESP&country_a2=ES&crAlgorithm=&creativeGroupId=178040&creativeGroupName=Auto_Test_CreativeGroup&creativeId=525852&creativeName=48d6033c9db4d7d03c7b16a472364a49585b8bd3&creativeType=creative_mraid_banner&deviceFamily=android&deviceId=27ea682c281e7dc4fb8e2291c811a7c4b26b93a3&device_model=Google+Pixel+3&device_name=Google+Google+Pixel+3&device_type=4&fm=0&h=480&impression_id=Lnh-L2_zidqFF-yklqcnP2&ip_addr=94.24.105.28&os=android&os_raw=android&osv=12&publisherAppId=com.demo.tetris&rbm=1.01&retina=0&s2s=1&supplier=344&supplier_name=appodeal&ts=1781505895&user_agent=TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDEyOyBQaXhlbCAzIEJ1aWxkL1NQMUEuMjEwODEyLjAxNi5DMTsgd3YpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIFZlcnNpb24vNC4wIENocm9tZS8xMTQuMC41NzM1LjEzMSBNb2JpbGUgU2FmYXJpLzUzNy4zNg&user_id=&w=320&zip=30600&cpm=${'$'}{AUCTION_PRICE}" style="display:none;"/></body></html>
        """.trimIndent()

        mraidInterstitialRenderer?.load(adm = mraidHtml, listener = object : AdRenderer.Listener {
            override fun onLoaded() { log("MRAID interstitial loaded"); isMraidInterstitialLoaded = true; btnShowMraidInterstitial.isEnabled = true
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=nurl&test=true", "nurl")
            }
            override fun onImpression() { log("MRAID interstitial impression")
                TrackingDispatcher.fire("https://httpbin.org/get?pixel=burl&test=true", "burl")
            }
            override fun onClick() { log("MRAID interstitial click") }
            override fun onError(error: Throwable) { log("MRAID interstitial error: ${error.message}") }
        })
    }

    private fun showMraidInterstitial() {
        log("Show MRAID interstitial")
        mraidInterstitialRenderer?.showFullscreen(this)
    }

    // ---------------- DESTROY ----------------

    private fun destroyAll() {
        log("Destroy all")

        bannerRenderer?.destroy()
        interstitialRenderer?.destroy()
        vastRenderer?.destroy()
        mraidRenderer?.destroy()
        mraidInterstitialRenderer?.destroy()

        bannerRenderer = null
        interstitialRenderer = null
        vastRenderer = null
        mraidRenderer = null
        mraidInterstitialRenderer = null

        bannerContainer.removeAllViews()

        isBannerLoaded = false
        isInterstitialLoaded = false
        isVastLoaded = false
        isMraidLoaded = false
        isMraidInterstitialLoaded = false

        btnShowBanner.isEnabled = false
        btnShowInterstitial.isEnabled = false
        btnShowVast.isEnabled = false
        btnShowMraid.isEnabled = false
        btnShowMraidInterstitial.isEnabled = false
    }

    private fun log(msg: String) {
        Log.d("BidonTest", msg)
        tvLog.append("\n$msg")
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyAll()
    }
}