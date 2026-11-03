package org.bidon.app.renderer

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.demo.tetris.R
import com.demo.tetris.databinding.FragmentRendererOnlyBinding
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.AdRenderingConfig
import org.json.JSONObject

/**
 * The renderer on its own, with hardcoded creatives pasted straight in.
 *
 * This is NOT the product path: no SDK, no auction, no adapter. Nothing here proves the real flow
 * works. It is for poking at the renderer in isolation - a new VAST tag, an MRAID command, a
 * creative that misbehaves - without needing a live auction to fill.
 */
class RendererOnlyFragment : Fragment(R.layout.fragment_renderer_only) {

    private var _binding: FragmentRendererOnlyBinding? = null
    private val binding get() = _binding!!

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

    // Stand-ins for the rendering config a DSP sends with its bid, so the close button options
    // can be seen without an auction.
    private val ringedButtonTopLeft = AdRenderingConfig.fromJson(
        JSONObject(
            """
            {
              "close_button": {
                "delay_seconds": 8, "style": "icon_circle", "size_dp": 36, "position": "top_left",
                "color": "#FFD600", "opacity": 1, "padding_dp": 16
              }
            }
            """
        )
    )

    private val textButtonBottomRight = AdRenderingConfig.fromJson(
        JSONObject("""{ "close_button": { "style": "text_close", "position": "bottom_right" } }""")
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRendererOnlyBinding.bind(view)

        with(binding) {
            btnLoadHtmlBanner.setOnClickListener { loadBanner() }
            btnShowHtmlBanner.setOnClickListener { showBanner() }

            btnLoadHtmlInterstitial.setOnClickListener { loadInterstitial() }
            btnShowHtmlInterstitial.setOnClickListener { showInterstitial() }

            btnLoadVast.setOnClickListener { loadVast() }
            btnShowVast.setOnClickListener { showVast() }

            btnLoadMraidBanner.setOnClickListener { loadMraid() }
            btnShowMraidBanner.setOnClickListener { showMraid() }

            btnLoadMraidInterstitial.setOnClickListener { loadMraidInterstitial() }
            btnShowMraidInterstitial.setOnClickListener { showMraidInterstitial() }

            btnDestroyAll.setOnClickListener { destroyAll() }
        }
    }

    override fun onDestroyView() {
        destroyAll()
        _binding = null
        super.onDestroyView()
    }

    private fun loadBanner() {
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
            override fun onLoaded() { isBannerLoaded = true; binding.btnShowHtmlBanner.isEnabled = true }

            override fun onImpression() { }
            override fun onClick() { }
            override fun onClose() { }
            override fun onReward() { }

            override fun onError(error: Throwable) { }
        })
    }

    private fun showBanner() {
        val view = bannerRenderer?.getView() ?: run { return }
        binding.rendererBannerSlot.removeAllViews()
        binding.rendererBannerSlot.addView(view)
    }

    // ---------------- INTERSTITIAL ----------------

    private fun loadInterstitial() {
        interstitialRenderer = BidonRenderer.createHtmlRenderer(ringedButtonTopLeft)




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
            override fun onLoaded() { isInterstitialLoaded = true; binding.btnShowHtmlInterstitial.isEnabled = true }
            override fun onImpression() { }
            override fun onClick() { }
            override fun onClose() { }
            override fun onReward() { }

            override fun onError(error: Throwable) { }
        })
    }

    private fun showInterstitial() {
        interstitialRenderer?.showFullscreen(requireActivity())
    }

    // ---------------- VAST ----------------

    private fun loadVast() {

        val vastXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <VAST version="2.0">
              <Ad id="demo">
                <InLine>
                  <AdSystem>Demo</AdSystem>
                  <AdTitle>Sample video</AdTitle>
                  <Creatives>
                    <Creative>
                      <Linear>
                        <Duration>00:01:00</Duration>
                        <MediaFiles>
                          <MediaFile delivery="progressive" type="video/mp4" width="854" height="480"><![CDATA[https://storage.googleapis.com/gvabox/media/samples/stock.mp4]]></MediaFile>
                        </MediaFiles>
                      </Linear>
                    </Creative>
                  </Creatives>
                </InLine>
              </Ad>
            </VAST>
        """.trimIndent()
        vastRenderer = BidonRenderer.createVastRenderer()
        vastRenderer?.load(adm = vastXml, listener = object : AdRenderer.Listener {
            override fun onLoaded() { isVastLoaded = true; binding.btnShowVast.isEnabled = true }
            override fun onImpression() { }
            override fun onClick() { }
            override fun onClose() { }
            override fun onReward() { }

            override fun onError(error: Throwable) { }
        })
    }

    private fun showVast() {
        vastRenderer?.showFullscreen(requireActivity())
    }

    // ---------------- MRAID BANNER ----------------

    private fun loadMraid() {
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
            override fun onLoaded() { isMraidLoaded = true; binding.btnShowMraidBanner.isEnabled = true }
            override fun onImpression() { }
            override fun onClick() { }
            override fun onClose() { }
            override fun onReward() { }

            override fun onError(error: Throwable) { }
        })
    }

    private fun showMraid() {
        val view = mraidRenderer?.getView() ?: run { return }
        binding.rendererBannerSlot.removeAllViews()
        binding.rendererBannerSlot.addView(view)
    }

    // ---------------- MRAID INTERSTITIAL ----------------

    private fun loadMraidInterstitial() {
        mraidInterstitialRenderer = BidonRenderer.createMraidRenderer(textButtonBottomRight)
        val mraidHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body { margin: 0; height: 100%; background: #1a1a2e; }
                body { display: flex; align-items: center; justify-content: center; }
                .text { color: #e94560; font-family: sans-serif; font-size: 20px; font-weight: bold; }
              </style>
            </head>
            <body>
              <span class="text">MRAID Interstitial</span>
              <script>
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

        mraidInterstitialRenderer?.load(adm = mraidHtml, listener = object : AdRenderer.Listener {
            override fun onLoaded() { isMraidInterstitialLoaded = true; binding.btnShowMraidInterstitial.isEnabled = true }
            override fun onImpression() { }
            override fun onClick() { }
            override fun onClose() { }
            override fun onReward() { }

            override fun onError(error: Throwable) { }
        })
    }

    private fun showMraidInterstitial() {
        mraidInterstitialRenderer?.showFullscreen(requireActivity())
    }

    // ---------------- DESTROY ----------------

    private fun destroyAll() {

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

        binding.rendererBannerSlot.removeAllViews()

        isBannerLoaded = false
        isInterstitialLoaded = false
        isVastLoaded = false
        isMraidLoaded = false
        isMraidInterstitialLoaded = false

        binding.btnShowHtmlBanner.isEnabled = false
        binding.btnShowHtmlInterstitial.isEnabled = false
        binding.btnShowVast.isEnabled = false
        binding.btnShowMraidBanner.isEnabled = false
        binding.btnShowMraidInterstitial.isEnabled = false
    }
}
