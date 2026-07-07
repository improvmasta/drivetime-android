package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.jupiterns.drivetime.databinding.ActivityWebBinding

/**
 * The hybrid shell — this is the launcher. It hosts the drivetime SPA (the full web
 * dashboard) so the app *is* the web app, while the logging/OBD/automation pipeline
 * runs untouched underneath. Because the app already stores the dashboard login, it
 * silently authenticates (POST /api/auth/login → session cookie in [CookieManager])
 * before loading, so the SPA opens already-logged-in.
 *
 * A floating status pill reports the logger and taps through to the native
 * [LoggerActivity]; offline, the SPA's own service-worker cache serves the last-seen
 * dashboard (see LOCAL_FIRST.md), and this shell only shows its overlay when even that
 * fails or setup is missing.
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebBinding
    private lateinit var settings: Settings
    private val uploader by lazy { Uploader(this, settings) }
    private val ui = Handler(Looper.getMainLooper())

    private var loadedOnce = false

    // Serves the APK-bundled SPA (assets/web/…) at https://appassets.androidplatform.net/
    // assets/web/ — a secure origin, so the SPA's service worker + IndexedDB replica work
    // in standalone mode (STANDALONE.md A1).
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val ticker = object : Runnable {
        override fun run() { refreshPill(); ui.postDelayed(this, 1000) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        b = ActivityWebBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        val web = b.webview
        val ws: WebSettings = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true          // SPA localStorage (theme) + IndexedDB replica
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW   // site is https-only
        ws.mediaPlaybackRequiresUserGesture = true
        // Let the WebView honour the app's night mode → the SPA's prefers-color-scheme CSS.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, true)
        }

        // Bridge for the SPA: drain the phone's own GPS into the on-device replica (A2) and
        // learn whether we're standalone (A3). Only our own SPA is ever loaded in this
        // WebView (external links hand off to the system browser), so the surface is trusted.
        web.addJavascriptInterface(NativeBridge(), "DrivetimeNative")

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)   // session cookie is first-party; harmless

        // Route bundled-SPA requests (appassets origin) through the asset loader, for both
        // page loads and the service worker's own precache fetches.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)
                })
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (WebAuth.isInAppUrl(settings.serverUrl, url)) return false
                // External link (other host, mailto:, tel:, …) → hand off to the system.
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && WebAuth.isInAppUrl(settings.serverUrl, url)) {
                    loadedOnce = true
                    hideOverlay()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Only the top-level dashboard failing to load warrants the cover; a stray
                // sub-resource (a map tile offline) must not blank the whole app.
                if (request.isForMainFrame && !loadedOnce) showOffline()
            }
        }

        // CSV export / APK links → the system download manager, carrying our session cookie.
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setMimeType(mimeType)
                    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                EventLog.info("Download started: $url")
            }
        }

        b.pillCard.setOnClickListener { startActivity(Intent(this, LoggerActivity::class.java)) }

        // Hardware back walks the SPA's history first, then exits.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.webview.canGoBack()) b.webview.goBack() else finish()
            }
        })

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        // Returning to the app: drain any upload backlog, and if the dashboard never came
        // up (e.g. setup was just finished, or we were offline), try again.
        Thread { runCatching { uploader.flush() } }.start()
        // The SPA authenticates to the server per-request with Basic auth (no cookie session
        // to keep alive), so a resume just retries the load if it never came up.
        if (!loadedOnce) loadDashboard()
        // Offer a newer build if one's been published (throttled; silent when up to date).
        if (settings.updatesEnabled) Updater.checkFromUi(this, interactive = false)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---- loading + auth ----

    private fun loadDashboard() {
        // The app ALWAYS runs its own bundled SPA on a secure local origin (STANDALONE.md
        // A1): one storage origin, usable offline from first launch. When a server is
        // configured the SPA reaches it as a cross-origin *sync target* (absolute URL +
        // Basic auth handed over the bridge, see [NativeBridge]) — the server is never
        // loaded as the page. So the dashboard is identical with or without a server, and
        // turning sync on/off never moves where the on-device replica lives.
        hideOverlay()
        b.webview.loadUrl(Shell.LOCAL_URL)
    }

    // ---- JS bridge (A2/A3) ----

    /**
     * Exposed to the SPA as `window.DrivetimeNative`. Methods run on a WebView-owned binder
     * thread, not the UI thread — keep them cheap + thread-safe. Read-only: the SPA pulls
     * buffered GPS fixes to segment its own drives offline, learns whether a server is set,
     * and — when one is — gets the absolute server URL + Basic auth to reach it cross-origin.
     */
    inner class NativeBridge {
        /** JSON array of buffered native fixes newer than [sinceTs] (epoch seconds). The SPA
         *  feeds these to `appendFixes` and advances its own cursor; idempotent on `ts`. */
        @JavascriptInterface
        fun pullFixes(sinceTs: Double): String = WebFixBuffer.pullSince(this@WebViewActivity, sinceTs)

        /** True when no *usable* server is configured (missing URL or creds) — the SPA runs
         *  purely local against its replica. Flips to false only once sync can authenticate. */
        @JavascriptInterface
        fun standalone(): Boolean = !settings.isConfigured

        /** Open the native Tracker screen (permissions, devices, server pairing, updates)
         *  from the SPA's Settings → "Tracking & devices" row (AUTH.md). Runs on a binder
         *  thread, so hop to the UI thread to start the Activity. */
        @JavascriptInterface
        fun openTracker() {
            ui.post { startActivity(Intent(this@WebViewActivity, LoggerActivity::class.java)) }
        }

        /** Like [openTracker] but deep-links straight to a section of the native Tracker
         *  screen ('tracking' | 'devices' | 'sync' | 'backup' | 'updates' | 'advanced'), so
         *  the SPA Settings tabs land the user in the matching area. Runs on a binder thread,
         *  so hop to the UI thread to start the Activity. */
        @JavascriptInterface
        fun openTrackerSection(section: String) {
            ui.post {
                startActivity(Intent(this@WebViewActivity, LoggerActivity::class.java)
                    .putExtra(LoggerActivity.EXTRA_SECTION, section))
            }
        }

        /** The absolute base URL the SPA prepends to its `/api` calls so they reach the server
         *  cross-origin (the SPA itself is served from the bundled local origin). Blank until
         *  a server + creds are set, which keeps the app in local mode. No trailing slash. */
        @JavascriptInterface
        fun serverUrl(): String = if (settings.isConfigured) settings.serverUrl else ""

        /** The HTTP Basic header (from the dashboard login) the SPA attaches to every
         *  cross-origin API call — the same stateless credential the uploader uses for
         *  `/api/ingest`. Blank when unconfigured. */
        @JavascriptInterface
        fun authHeader(): String = if (settings.isConfigured) settings.authHeader else ""

        /** Save a text file (e.g. the mileage CSV export) to the device's public Downloads.
         *  The SPA calls this because a WebView can't download a `blob:` URL through the
         *  DownloadManager. MediaStore on Q+, a direct Downloads write on older builds; a
         *  toast confirms so the export is visibly *done*. Runs on a binder thread. */
        @JavascriptInterface
        fun saveFile(name: String, mime: String, content: String) {
            val safe = if (name.isBlank()) "drivetime.csv" else name.substringAfterLast('/')
            val ok = runCatching {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, if (mime.isBlank()) "text/plain" else mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    java.io.File(dir, safe).outputStream().use { it.write(bytes) }
                    true
                }
            }.getOrDefault(false)
            ui.post {
                Toast.makeText(this@WebViewActivity,
                    if (ok) "Saved $safe to Downloads" else "Couldn't save $safe",
                    Toast.LENGTH_LONG).show()
            }
            EventLog.info(if (ok) "Saved export: $safe" else "Export save failed: $safe")
        }

        /** The logger's live snapshot (LiveState) as JSON, for the SPA's active-drive bar:
         *  whether we're driving now, the current speed, and the OBD basics. Cheap, volatile,
         *  same-process read; the SPA polls it every few seconds and computes elapsed/distance
         *  itself from the buffered fixes. `{}` if anything goes sideways. */
        @JavascriptInterface
        fun liveState(): String = runCatching {
            val s = LiveState
            JSONObject()
                .put("logging", s.logging)
                .put("tier", s.tier ?: JSONObject.NULL)
                .put("driving", s.tier == "DRIVING")
                .put("reason", s.driveReason ?: JSONObject.NULL)
                .put("speed_mph", s.speedMph ?: JSONObject.NULL)
                .put("obd_connected", s.obdConnected)
                .put("rpm", s.rpm ?: JSONObject.NULL)
                .put("coolant_c", s.coolantC ?: JSONObject.NULL)
                .put("voltage", s.voltage ?: JSONObject.NULL)
                .put("updated_at", s.updatedAt)
                .toString()
        }.getOrDefault("{}")
    }

    // ---- overlay states ----

    private fun showOffline() {
        b.overlayTitle.text = "No connection"
        b.overlayDetail.text = "Can't reach the dashboard. Logging is still running — your drives are saved and will upload when you're back online."
        b.overlayBtn.text = "Retry"
        b.overlayBtn.setOnClickListener { loadDashboard() }
        b.overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() { b.overlay.visibility = View.GONE }

    // ---- status pill ----

    private fun refreshPill() {
        val on = settings.trackingMode != Settings.MODE_OFF
        val (label, colorRes) = when {
            !on -> "○ Off" to R.color.status_grey
            LiveState.tier == "DRIVING" -> "● Driving" to R.color.status_green
            LiveState.tier == "LIGHT" -> "● Light" to R.color.status_blue
            else -> "● Starting" to R.color.status_amber
        }
        val queued = runCatching { uploader.health().queued }.getOrDefault(0)
        b.pill.text = if (queued > 0) "$label · $queued queued" else label
        b.pill.setTextColor(ContextCompat.getColor(this, colorRes))
    }
}
