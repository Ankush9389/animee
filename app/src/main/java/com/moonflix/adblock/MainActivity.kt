package com.moonflix.adblock

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var adView: AdView
    private lateinit var nativeAdWebView: WebView
    private lateinit var btnBack: ImageButton

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var appOpenAd: AppOpenAd? = null
    private var interstitialAd: InterstitialAd? = null
    private var isShowingAd = false
    private var pageLoadCount = 0

    private val appOpenAdUnitId = "ca-app-pub-5750308983692756/9676237432"
    private val interstitialAdUnitId = "ca-app-pub-5750308983692756/9484665742"
    private val smartlinkUrl = "https://asiafilm.org/4/c06e7c91ca54b103a6ef1e8c45e46d2f"

    private val startUrl = "https://kartoons.to/home"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MobileAds.initialize(this) {
            loadAndShowAppOpenAd()
            loadInterstitialAd()
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        mainContent = findViewById(R.id.mainContent)
        adView = findViewById(R.id.adView)
        nativeAdWebView = findViewById(R.id.nativeAdWebView)
        btnBack = findViewById(R.id.btnBack)

        setupNativeAd()

        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }

        adView.loadAd(AdRequest.Builder().build())

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (AdBlocker.isAdUrl(url)) {
                    return AdBlocker.createEmptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()
                return !AdBlocker.isAllowedNavigation(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                view?.evaluateJavascript(AdBlocker.COSMETIC_ADBLOCK_JS, null)

                pageLoadCount++
                if (pageLoadCount > 1 && pageLoadCount % 3 == 0) {
                    showInterstitialAd()
                }
                if (pageLoadCount == 2 || pageLoadCount % 5 == 0) {
                    openSmartlink()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback

                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                mainContent.visibility = View.GONE
                progressBar.visibility = View.GONE
                btnBack.visibility = View.GONE

                // Rotate to landscape and enable true immersive fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                setFullscreen(true)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                mainContent.visibility = View.VISIBLE
                btnBack.visibility = View.VISIBLE

                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null

                // Restore orientation and system bars
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                setFullscreen(false)
            }
        }

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(startUrl)
    }

    private fun setFullscreen(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun loadAndShowAppOpenAd() {
        if (isShowingAd || appOpenAd != null) return

        val request = AdRequest.Builder().build()
        AppOpenAd.load(
            this,
            appOpenAdUnitId,
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            appOpenAd = null
                            isShowingAd = false
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            appOpenAd = null
                            isShowingAd = false
                        }

                        override fun onAdShowedFullScreenContent() {
                            isShowingAd = true
                        }
                    }
                    if (!isFinishing && !isDestroyed) {
                        ad.show(this@MainActivity)
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    appOpenAd = null
                }
            }
        )
    }

    private fun loadInterstitialAd() {
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            interstitialAdUnitId,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    private fun showInterstitialAd() {
        val ad = interstitialAd ?: return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitialAd()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                interstitialAd = null
            }
        }
        ad.show(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupNativeAd() {
        nativeAdWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
        }
        nativeAdWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
                return true
            }
        }
        val nativeAdHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { margin: 0; padding: 0; background: #000000; display: flex; flex-direction: column; justify-content: center; align-items: center; overflow: hidden; }
                </style>
            </head>
            <body>
                <script async="async" data-cfasync="false" src="https://bauval.org/21/b45a39ce7653b015bcb56101c755adf1"></script>
                <div id="container-b45a39ce7653b015bcb56101c755adf1"></div>
                <script>
                  atOptions = {
                    'key' : 'e66e3084f4370be3ee40f727d0de9775',
                    'format' : 'iframe',
                    'height' : 90,
                    'width' : 728,
                    'params' : {}
                  };
                </script>
                <script src="https://bauval.org/22/e66e3084f4370be3ee40f727d0de9775"></script>
                <script data-cfasync="false" src="https://bauval.org/14/562771f3a4515f97bcd8949fed06f1e4"></script>
            </body>
            </html>
        """.trimIndent()
        nativeAdWebView.loadDataWithBaseURL("https://bauval.org", nativeAdHtml, "text/html", "UTF-8", null)
    }

    private fun openSmartlink() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(smartlinkUrl))
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
