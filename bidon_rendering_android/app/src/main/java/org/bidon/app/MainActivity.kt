/*package org.bidon.app

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.html.HtmlRenderer

class MainActivity : AppCompatActivity() {

    private lateinit var bannerContainer: FrameLayout
    private lateinit var tvLog: TextView
    private lateinit var btnLoadBanner: Button
    private lateinit var btnShowBanner: Button
    private lateinit var btnLoadInterstitial: Button
    private lateinit var btnShowInterstitial: Button
    private lateinit var btnDestroy: Button

    private var bannerRenderer: HtmlRenderer? = null
    private var interstitialRenderer: HtmlRenderer? = null
    private var isBannerLoaded = false
    private var isInterstitialLoaded = false

    private fun getTime() = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
    private fun log(msg: String) {
        Log.d("BidonTest", msg)
        tvLog.append("\n[${getTime()}] $msg")
        tvLog.post { tvLog.scrollTo(0, tvLog.bottom) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 500, 16, 16)
            setBackgroundColor(Color.WHITE)
        }

        // 🔘 Кнопки банера
        val bannerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnLoadBanner = Button(this).apply {
            text = "📊 Load Banner"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
            setOnClickListener { loadBanner() }
        }

        btnShowBanner = Button(this).apply {
            text = "▶️ Show Banner"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4; marginEnd = 4 }
            isEnabled = false
            setOnClickListener { showBanner() }
        }

        bannerControls.addView(btnLoadBanner)
        bannerControls.addView(btnShowBanner)

        // 🔘 Кнопки інтерстішала
        val interstitialControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        btnLoadInterstitial = Button(this).apply {
            text = "🖼️ Load Interstitial"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
            setOnClickListener { loadInterstitial() }
        }

        btnShowInterstitial = Button(this).apply {
            text = "▶️ Show Interstitial"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4 }
            isEnabled = false
            setOnClickListener { showInterstitial() }
        }

        interstitialControls.addView(btnLoadInterstitial)
        interstitialControls.addView(btnShowInterstitial)

        // 🔘 Кнопка Destroy
        btnDestroy = Button(this).apply {
            text = "🗑️ Destroy All"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnClickListener { destroyAll() }
        }

        // 📦 Контейнер для банера
        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(Color.LTGRAY)
        }

        // 📋 Лог
        tvLog = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(8, 8, 8, 8)
        }

        root.addView(bannerControls)
        root.addView(interstitialControls)
        root.addView(btnDestroy)
        root.addView(bannerContainer)
        root.addView(tvLog)
        setContentView(root)

        log("🚀 App started")
        BidonRenderer.init(this)
    }

    private fun loadBanner() {
        log("🔄 Loading banner...")
        bannerRenderer = BidonRenderer.createHtmlRenderer(this)

        val html = """
    <html><body style="margin:0;">
      <a href="https://google.com">
        <img src="https://picsum.photos/320/50" style="width:100%;height:100%;" />
      </a>
    </body></html>
""".trimIndent()

        bannerRenderer?.load(
            adm = html,
            nurl = "https://httpbin.org/get?banner_nurl=1",
            onLoaded = {
                log("✅ Banner LOADED")
                isBannerLoaded = true
                btnShowBanner.isEnabled = true
            },
            onClick = { log("🖱️ Banner clicked") },
            onError = { log("❌ Banner error: ${it.message}") }
        )
    }

    private fun showBanner() {
        if (!isBannerLoaded) return
        log("▶️ Showing banner...")
        bannerRenderer?.show(
            container = bannerContainer,
            burl = "https://httpbin.org/get?banner_burl=1",
            isFullscreen = false
        )
        log("🔍 Banner container children: ${bannerContainer.childCount}")
    }

    private fun loadInterstitial() {
        log("🔄 Loading interstitial...")
        interstitialRenderer = BidonRenderer.createHtmlRenderer(this)

        val html = """
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <style>
              body { 
                margin: 0; 
                padding: 0; 
                overflow: hidden; 
                background: #000; /* Чорний фон, якщо картинка не заповнить екран */
              }
              img { 
                display: block; 
                width: 100vw; 
                height: 100vh; 
                object-fit: cover; /* Картинка заповнить екран, обрізаючи краї */
              }
            </style>
          </head>
          <body>
            <a href="https://www.google.com" style="display:block; width:100%; height:100%;">
              <img src="https://media.giphy.com/media/3o7TKs3845172696/giphy.gif" alt="Ad" />
            </a>
          </body>
        </html>
    """.trimIndent()

        interstitialRenderer?.load(
            adm = html,
            nurl = "https://httpbin.org/get?interstitial_nurl=1",
            onLoaded = {
                log("✅ Interstitial LOADED")
                isInterstitialLoaded = true
                btnShowInterstitial.isEnabled = true
            },
            onClick = { log("🖱️ Interstitial clicked") },
            onError = { log("❌ Interstitial error: ${it.message}") }
        )
    }

    private fun showInterstitial() {
        if (!isInterstitialLoaded) return
        log("▶️ Showing interstitial (fullscreen)...")
        interstitialRenderer?.show(
            container = null,
            burl = "https://httpbin.org/get?interstitial_burl=1",
            isFullscreen = true
        )
    }

    private fun destroyAll() {
        log("🗑️ Destroying all...")
        bannerRenderer?.destroy()
        interstitialRenderer?.destroy()
        bannerRenderer = null
        interstitialRenderer = null
        bannerContainer.removeAllViews()
        isBannerLoaded = false
        isInterstitialLoaded = false
        btnShowBanner.isEnabled = false
        btnShowInterstitial.isEnabled = false
        log("✅ All destroyed")
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyAll()
    }
}*/



package org.bidon.app

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.AdRenderer

class MainActivity : AppCompatActivity() {

    private lateinit var bannerContainer: FrameLayout
    private lateinit var tvLog: TextView

    private lateinit var btnLoadBanner: Button
    private lateinit var btnShowBanner: Button
    private lateinit var btnLoadInterstitial: Button
    private lateinit var btnShowInterstitial: Button
    private lateinit var btnDestroy: Button

    private var bannerRenderer: AdRenderer? = null
    private var interstitialRenderer: AdRenderer? = null

    private var isBannerLoaded = false
    private var isInterstitialLoaded = false

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
        root.addView(btnDestroy)
        root.addView(bannerContainer)
        root.addView(tvLog)

        setContentView(root)

        log("App started")
    }

    // ---------------- BANNER ----------------

    private fun loadBanner() {
        log("Loading banner...")

        bannerRenderer = BidonRenderer.createHtmlRenderer()

        val html = """
            <html>
              <body style="margin:0;">
                <a href="https://google.com">
                  <img src="https://picsum.photos/320/50"
                       style="width:100%;height:100%;object-fit:cover;" />
                </a>
              </body>
            </html>
        """.trimIndent()

        bannerRenderer?.load(
            adm = html,
            listener = object : AdRenderer.Listener {

                override fun onLoaded() {
                    log("Banner loaded")
                    isBannerLoaded = true
                    btnShowBanner.isEnabled = true
                }

                override fun onImpression() {
                    log("Banner impression")
                }

                override fun onClick() {
                    log("Banner click")
                }

                override fun onError(error: Throwable) {
                    log("Banner error: ${error.message}")
                }
            }
        )
    }

    private fun showBanner() {
        log("Show banner")

        val view = bannerRenderer?.getView()

        if (view == null) {
            log("Banner view is null")
            return
        }

        bannerContainer.removeAllViews()
        bannerContainer.addView(view)
    }

    // ---------------- INTERSTITIAL ----------------

    private fun loadInterstitial() {
        log("Loading interstitial...")

        interstitialRenderer = BidonRenderer.createHtmlRenderer()

        val html = """
            <html>
              <body style="margin:0;background:black;">
                <a href="https://google.com">
                  <img src="https://media.giphy.com/media/3o7TKs3845172696/giphy.gif"
                       style="width:100vw;height:100vh;object-fit:cover;" />
                </a>
              </body>
            </html>
        """.trimIndent()

        interstitialRenderer?.load(
            adm = html,
            listener = object : AdRenderer.Listener {

                override fun onLoaded() {
                    log("Interstitial loaded")
                    isInterstitialLoaded = true
                    btnShowInterstitial.isEnabled = true
                }

                override fun onImpression() {
                    log("Interstitial impression")
                }

                override fun onClick() {
                    log("Interstitial click")
                }

                override fun onError(error: Throwable) {
                    log("Interstitial error: ${error.message}")
                }
            }
        )
    }

    private fun showInterstitial() {
        log("Show interstitial")

        interstitialRenderer?.showFullscreen(this)
    }

    // ---------------- DESTROY ----------------

    private fun destroyAll() {
        log("Destroy all")

        bannerRenderer?.destroy()
        interstitialRenderer?.destroy()

        bannerRenderer = null
        interstitialRenderer = null

        bannerContainer.removeAllViews()

        isBannerLoaded = false
        isInterstitialLoaded = false

        btnShowBanner.isEnabled = false
        btnShowInterstitial.isEnabled = false
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









