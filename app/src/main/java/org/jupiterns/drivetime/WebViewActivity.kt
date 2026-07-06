package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jupiterns.drivetime.databinding.ActivityWebBinding
import java.util.concurrent.TimeUnit

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
    private var lastLoginAt = 0L
    private var reloginInFlight = false

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

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)   // session cookie is first-party; harmless

        web.webViewClient = object : WebViewClient() {
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
        if (!loadedOnce) loadDashboard()
        else if (settings.isConfigured && System.currentTimeMillis() - lastLoginAt > RELOGIN_STALE_MS) {
            // Long-lived session: refresh the cookie in the background so live API calls
            // stay authed without reloading the page.
            silentLoginAsync()
        }
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---- loading + auth ----

    private fun loadDashboard() {
        if (!settings.isConfigured) { showSetup(); return }
        hideOverlay()
        silentLoginAsync { b.webview.loadUrl(settings.serverUrl) }
    }

    /** Log in on a background thread (seeding the session cookie), then run [then] on the
     *  UI thread regardless of outcome — offline still loads so the SW cache can serve. */
    private fun silentLoginAsync(then: (() -> Unit)? = null) {
        if (reloginInFlight) { then?.let { ui.post(it) }; return }
        reloginInFlight = true
        Thread {
            val result = runCatching { silentLogin() }.getOrDefault(LoginResult.OFFLINE)
            reloginInFlight = false
            ui.post {
                when (result) {
                    LoginResult.OK -> lastLoginAt = System.currentTimeMillis()
                    LoginResult.AUTH_FAILED ->
                        EventLog.warn("Dashboard login rejected — check username/password in Settings")
                    LoginResult.OFFLINE, LoginResult.ERROR -> { /* SW cache may still serve */ }
                }
                then?.invoke()
            }
        }.start()
    }

    /** POST the stored login; copy the Set-Cookie session into [CookieManager]. */
    private fun silentLogin(): LoginResult {
        if (!settings.isConfigured) return LoginResult.AUTH_FAILED
        val body = JSONObject()
            .put("username", settings.username)
            .put("password", settings.password)
            .toString()
        val req = Request.Builder()
            .url(settings.serverUrl + "/api/auth/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val cm = CookieManager.getInstance()
                        resp.headers("Set-Cookie").forEach { cm.setCookie(settings.serverUrl, it) }
                        cm.flush()
                        LoginResult.OK
                    }
                    resp.code == 401 -> LoginResult.AUTH_FAILED
                    else -> LoginResult.ERROR
                }
            }
        }.getOrDefault(LoginResult.OFFLINE)
    }

    private enum class LoginResult { OK, AUTH_FAILED, OFFLINE, ERROR }

    // ---- overlay states ----

    private fun showSetup() {
        b.overlayTitle.text = "Finish setup"
        b.overlayDetail.text = "Enter your server URL, username, and password to open the dashboard."
        b.overlayBtn.text = "Open Settings"
        b.overlayBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.overlay.visibility = View.VISIBLE
    }

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

    companion object {
        /** Re-seed the session cookie if the app has been open (loaded) this long, so a
         *  multi-day session never lets live API calls fall out of auth. */
        private const val RELOGIN_STALE_MS = 6L * 60 * 60_000L

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
