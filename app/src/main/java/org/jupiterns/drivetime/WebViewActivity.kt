package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jupiterns.drivetime.databinding.ActivityWebBinding
import java.util.concurrent.TimeUnit

/**
 * The hybrid shell — this is the launcher AND the app's one native surface. It hosts the
 * drivetime SPA (the full web dashboard, including the tabbed Settings screen) so the app
 * *is* the web app, while the logging/OBD/automation pipeline runs untouched underneath.
 *
 * The Settings tabs render every tracker knob as normal web controls and read/write them
 * over the `DrivetimeNative` bridge; the handful of things a WebView genuinely can't do —
 * runtime permission prompts, Bluetooth/OBD pairing, backup file-pickers, the QR pairing
 * scanner, and the APK self-updater — are hosted *here* and fired in-place by the tabs
 * (there is no longer a separate native Tracker screen to jump to). Because the app already
 * stores the server credential it hands it to the SPA over the bridge, so cross-origin sync
 * works with no in-WebView login.
 *
 * A floating status pill reports the logger and taps into the SPA's Settings route; offline,
 * the SPA's own service-worker cache serves the last-seen dashboard (see LOCAL_FIRST.md),
 * and this shell only shows its overlay when even that fails.
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebBinding
    private lateinit var settings: Settings
    private val uploader by lazy { Uploader(this, settings) }
    private val ui = Handler(Looper.getMainLooper())

    private var loadedOnce = false

    // Native-action plumbing (moved here from the retired LoggerActivity). These need an
    // Activity + the ActivityResult APIs, so they can't live in the WebView — the Settings
    // tabs trigger them over the bridge and re-poll getSettings()/getStatus() for the result.
    private var pendingStartAfterGrant = false
    private var pendingBtPick: (() -> Unit)? = null
    private lateinit var fgLocationLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var bgLocationLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var miscPermLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var btPermLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>

    // Last "Test connection" result, surfaced back to the SPA's Sync tab via getStatus().
    @Volatile private var lastTestMsg: String = ""
    @Volatile private var lastTestKind: String = ""   // "good" | "bad" | "warn" | ""

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

        registerLaunchers()

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

        // Bridge for the SPA: drain the phone's own GPS into the on-device replica (A2), learn
        // whether we're standalone (A3), and drive/read every native tracker setting + action
        // from the Settings tabs. Only our own SPA is ever loaded in this WebView (external
        // links hand off to the system browser), so the surface is trusted.
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

        // The pill now drops the user into the SPA's own Settings route rather than a separate
        // native screen — the tracker settings live there as tabs.
        b.pillCard.setOnClickListener {
            b.webview.evaluateJavascript("location.hash = '#/settings'", null)
        }

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
        // The SPA authenticates to the server per-request with the device token (no cookie
        // session to keep alive), so a resume just retries the load if it never came up.
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
        // Bearer token handed over the bridge, see [NativeBridge]) — the server is never
        // loaded as the page.
        hideOverlay()
        b.webview.loadUrl(Shell.LOCAL_URL)
    }

    // ---- native action launchers (moved from LoggerActivity) ----

    private fun registerLaunchers() {
        fgLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (fineGranted) maybeRequestBackgroundLocation()
            else snack("Location permission denied — drivetime can't log without it.")
            if (pendingStartAfterGrant && fineGranted) tryResumeStart()
        }
        bgLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> if (pendingStartAfterGrant) tryResumeStart() }
        miscPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> }
        btPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val resume = pendingBtPick; pendingBtPick = null
            if (grants.values.all { it }) resume?.invoke()
            else toast("Bluetooth permission is needed to find your dongle")
        }
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { exportTo(it) } }
        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { importFrom(it) } }
        scanLauncher = registerForActivityResult(ScanContract()) { result ->
            result?.contents?.let { applyPairing(it) }
        }
    }

    private fun startTracking() {
        // No server gate — tracking is server-optional (STANDALONE.md). Two-step flow: if
        // fine-location is missing, kick that off and remember we wanted to start.
        val snap = Permissions.snapshot(this, settings)
        if (!snap.hasFineLocation) {
            pendingStartAfterGrant = true
            fgLocationLauncher.launch(Permissions.requestArgsFor(Permissions.Action.REQUEST_FOREGROUND_LOCATION))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !snap.hasBackgroundLocation) {
            pendingStartAfterGrant = true
            maybeRequestBackgroundLocation()
            return
        }
        if (!snap.hasBatteryExempt) Battery.requestExemption(this)
        pendingStartAfterGrant = false
        Control.apply(this, Control.ACTION_MODE_AUTO, "user")
        toast("Tracking on — Auto mode")
    }

    private fun forceMode(action: String, label: String) {
        if (!Permissions.snapshot(this, settings).hasFineLocation) {
            fgLocationLauncher.launch(Permissions.requestArgsFor(Permissions.Action.REQUEST_FOREGROUND_LOCATION))
            return
        }
        Control.apply(this, action, "user")
        toast("Mode: $label")
    }

    /** If logging was queued behind a permission prompt and the prompt has cleared,
     *  complete the start. The fine→background steps each call this on grant. */
    private fun tryResumeStart() {
        val snap = Permissions.snapshot(this, settings)
        if (!snap.hasFineLocation) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !snap.hasBackgroundLocation) return
        pendingStartAfterGrant = false
        if (!snap.hasBatteryExempt) Battery.requestExemption(this)
        Control.apply(this, Control.ACTION_MODE_AUTO, "user")
        toast("Tracking on — Auto mode")
    }

    /** Step 2 of the location flow: explain that background access is what lets logging
     *  continue when the phone is locked, then route to the system prompt. */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("Keep logging when the phone is locked")
            .setMessage(
                "drivetime records GPS the whole time you're driving — even with the screen off. " +
                "On the next screen, pick \"Allow all the time\".\n\n" +
                "Without this, fixes stop the moment the phone sleeps."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue") { _, _ ->
                bgLocationLauncher.launch(
                    Permissions.requestArgsFor(Permissions.Action.REQUEST_BACKGROUND_LOCATION)
                )
            }
            .show()
    }

    /** Run the fix flow for a permission checklist [Action] — the same dispatch the old
     *  native warning banner used, now driven by the SPA's "Grant" buttons. */
    private fun runPermissionAction(action: Permissions.Action) {
        when (action) {
            Permissions.Action.REQUEST_FOREGROUND_LOCATION ->
                fgLocationLauncher.launch(Permissions.requestArgsFor(action))
            Permissions.Action.REQUEST_BACKGROUND_LOCATION -> maybeRequestBackgroundLocation()
            Permissions.Action.REQUEST_NOTIFICATIONS,
            Permissions.Action.REQUEST_BLUETOOTH,
            Permissions.Action.REQUEST_ACTIVITY_RECOGNITION ->
                miscPermLauncher.launch(Permissions.requestArgsFor(action))
            Permissions.Action.REQUEST_BATTERY_EXEMPT -> Battery.requestExemption(this)
            Permissions.Action.OPEN_LOCATION_SETTINGS ->
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Permissions.Action.OPEN_APP_SETTINGS -> Battery.openAppSettings(this)
        }
    }

    // ---- pairing (AUTH.md) ----

    private fun startPairScan() {
        val opts = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the pairing QR from the server's Settings → Pair a device")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(opts)
    }

    /** Apply a scanned or pasted pairing payload: set the server URL (if the payload carries
     *  one) + the device token, persist, then test. The SPA re-polls getSettings() to reflect. */
    private fun applyPairing(raw: String) {
        val p = Pairing.parse(raw)
        if (!p.hasToken) { toast("That didn't contain a device token"); return }
        p.url?.let { settings.serverUrl = it }
        settings.deviceToken = p.token!!
        // A device token supersedes any legacy username/password login on this device.
        settings.username = ""; settings.password = ""
        toast("Paired — testing connection…")
        testConnection()
    }

    private fun testConnection() {
        if (!settings.isConfigured) {
            lastTestMsg = "Enter a server URL, then scan or paste the device token"
            lastTestKind = "warn"
            toast(lastTestMsg)
            return
        }
        lastTestMsg = "… testing"; lastTestKind = ""
        Thread {
            val (msg, kind) = runCatching {
                val req = Request.Builder()
                    .url(settings.ingestUrl)
                    .header("Authorization", settings.authHeader)
                    .post("[]".toRequestBody("application/json".toMediaType()))
                    .build()
                testClient.newCall(req).execute().use {
                    when {
                        it.isSuccessful -> "✓ Connection OK" to "good"
                        it.code == 401 -> "✕ Auth failed — re-scan the pairing QR (token may have rotated)" to "bad"
                        else -> "⚠ Server error: HTTP ${it.code}" to "warn"
                    }
                }
            }.getOrElse { ("✕ Can't reach server: ${it.message ?: "network error"}") to "bad" }
            lastTestMsg = msg; lastTestKind = kind
            ui.post { snack(msg) }
            EventLog.info("Test connection: $msg")
        }.start()
    }

    // ---- Bluetooth device pickers ----

    private fun pickBt(title: String, onPick: (mac: String, name: String) -> Unit, onClear: () -> Unit) {
        if (!hasBtPerms()) {
            // Request inline — the picker must self-grant CONNECT + SCAN the first time.
            pendingBtPick = { pickBt(title, onPick, onClear) }
            btPermLauncher.launch(btPerms())
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("No Bluetooth on this device"); return }
        showScanningPicker(title, adapter, onPick, onClear)
    }

    /**
     * Live device picker: lists bonded devices *and* actively discovers nearby ones — how
     * Torque finds a dongle that never appears in system Bluetooth settings — so an unpaired
     * ELM327 can simply be tapped (we capture its MAC). Discovery + the receiver are torn
     * down when the dialog closes.
     */
    @SuppressLint("MissingPermission")
    private fun showScanningPicker(
        title: String,
        adapter: BluetoothAdapter,
        onPick: (mac: String, name: String) -> Unit,
        onClear: () -> Unit,
    ) {
        val seen = HashSet<String>()
        val picks = ArrayList<Pair<String, String>>()       // (mac, name), parallel to rows
        val rows = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        var nearbyCount = 0
        var dialog: AlertDialog? = null

        fun setStatus(scanning: Boolean) {
            dialog?.setTitle(when {
                scanning -> "$title — scanning…"
                picks.isEmpty() -> "$title — none found. Rescan, or Enter MAC."
                nearbyCount == 0 -> "$title — tap a paired device, or Rescan / Enter MAC"
                else -> "$title — tap your device"
            })
        }

        fun add(mac: String?, name: String?, nearby: Boolean) {
            if (mac == null || !seen.add(mac)) return
            val nm = name?.takeIf { it.isNotBlank() } ?: mac
            picks.add(mac to nm)
            rows.add("$nm\n$mac · ${if (nearby) "nearby" else "paired"}")   // auto-refreshes
            if (nearby) nearbyCount++
        }

        adapter.bondedDevices?.forEach { add(it.address, it.name, nearby = false) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?.let { add(it.address, it.name, nearby = true) }
                        setStatus(scanning = true)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> setStatus(scanning = false)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            .apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        fun startScan() {
            runCatching { adapter.cancelDiscovery() }
            runCatching { adapter.startDiscovery() }
            setStatus(scanning = true)
        }

        val d = AlertDialog.Builder(this)
            .setTitle("$title — scanning…")
            .setAdapter(rows) { _, i -> onPick(picks[i].first, picks[i].second) }
            .setPositiveButton("Rescan", null)              // overridden below so it doesn't dismiss
            .setNeutralButton("Clear") { _, _ -> onClear() }
            .setNegativeButton("Enter MAC") { _, _ -> promptForMac(title, onPick) }
            .setOnDismissListener {
                runCatching { adapter.cancelDiscovery() }
                runCatching { unregisterReceiver(receiver) }
            }
            .create()
        dialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { startScan() }
        startScan()
    }

    private fun hasBtPerms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else emptyArray()

    /** Manual MAC entry — the path for an unbonded dongle that never appears in the paired
     *  list. LocationService connects by MAC via the insecure-socket fallback, no bonding. */
    private fun promptForMac(title: String, onPick: (mac: String, name: String) -> Unit) {
        val input = EditText(this).apply { hint = "AA:BB:CC:DD:EE:FF"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Enter the adapter's Bluetooth MAC (from Torque, the dongle's label, or a BT scanner app).")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val mac = input.text.toString().trim().uppercase()
                if (BluetoothAdapter.checkBluetoothAddress(mac)) onPick(mac, mac)
                else toast("Invalid MAC — expected AA:BB:CC:DD:EE:FF")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- backup ----

    private fun exportTo(uri: Uri) {
        val json = SettingsExport.toJson(settings).toString(2)
        runCatching {
            contentResolver.openOutputStream(uri, "w").use { it?.write(json.toByteArray()) }
            toast("Exported")
        }.getOrElse { toast("Export failed: ${it.message}") }
    }

    private fun importFrom(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri).use { it?.bufferedReader()?.readText() } }
            .getOrNull()
        if (text.isNullOrBlank()) { toast("Import failed: empty file"); return }
        val applied = SettingsExport.fromJson(this, settings, text)
        if (applied == 0) { toast("Import failed: no recognised keys"); return }
        toast("Imported $applied settings")
    }

    // ---- JS bridge ----

    /**
     * Exposed to the SPA as `window.DrivetimeNative`. Read methods (getSettings, getStatus,
     * pullFixes, liveState, …) run on a WebView-owned binder thread — they only read
     * `Settings`/`Permissions`/`Uploader`/`LiveState`, all safe off the UI thread. Action
     * methods (setSetting, requestPermission, pickBluetooth, …) touch Activity UI, so they
     * hop to the UI thread. The SPA polls getSettings + getStatus to reflect any action.
     */
    inner class NativeBridge {
        /** JSON array of buffered native fixes newer than [sinceTs] (epoch seconds). The SPA
         *  feeds these to `appendFixes` and advances its own cursor; idempotent on `ts`. */
        @JavascriptInterface
        fun pullFixes(sinceTs: Double): String = WebFixBuffer.pullSince(this@WebViewActivity, sinceTs)

        /** True when no *usable* server is configured — the SPA runs purely local. */
        @JavascriptInterface
        fun standalone(): Boolean = !settings.isConfigured

        /** The absolute base URL the SPA prepends to its `/api` calls (cross-origin sync). */
        @JavascriptInterface
        fun serverUrl(): String = if (settings.isConfigured) settings.serverUrl else ""

        /** The `Authorization` header the SPA attaches to every cross-origin API call. */
        @JavascriptInterface
        fun authHeader(): String = if (settings.isConfigured) settings.authHeader else ""

        // ---- Settings tabs: read ----

        /** Every editable tracker setting + the derived flags the Settings tabs render.
         *  The device token itself is never surfaced in the WebView (AUTH.md) — only whether
         *  one is set — so pairing stays a native-only flow. */
        @JavascriptInterface
        fun getSettings(): String = runCatching {
            JSONObject()
                .put("serverUrl", settings.serverUrl)
                .put("hasServer", settings.hasServer)
                .put("isConfigured", settings.isConfigured)
                .put("standalone", !settings.isConfigured)
                .put("deviceTokenSet", settings.deviceToken.isNotBlank())
                .put("trackingMode", settings.trackingMode)
                .put("interval_sec", settings.intervalSec)
                .put("idle_interval_sec", settings.idleIntervalSec)
                .put("light_interval_sec", settings.lightIntervalSec)
                .put("upload_interval_sec", settings.uploadIntervalSec)
                .put("driving_upload_interval_sec", settings.drivingUploadIntervalSec)
                .put("stationary_stop_min", settings.stationaryStopMin)
                .put("drive_by_speed", settings.driveBySpeed)
                .put("motion_onset", settings.motionOnset)
                .put("auto_trip", settings.autoTrip)
                .put("alerts_enabled", settings.alertsEnabled)
                .put("control_token", settings.controlToken)
                .put("updates_enabled", settings.updatesEnabled)
                .put("carBtName", settings.carBtName)
                .put("obdName", settings.obdName)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .toString()
        }.getOrDefault("{}")

        /** Live health for the tabs: the permission checklist (each with a fixable [action]
         *  key), battery + OEM state, the connection/upload summary, any OEM-kill warning,
         *  and the last Test-connection result. Recomputed each poll. */
        @JavascriptInterface
        fun getStatus(): String = runCatching {
            val perms = JSONArray()
            for (c in Permissions.checklist(this@WebViewActivity, settings)) {
                perms.put(JSONObject()
                    .put("label", c.label)
                    .put("granted", c.granted)
                    .put("action", actionKey(c.action)))
            }
            val oem = OemBatteryLinks.help()
            JSONObject()
                .put("perms", perms)
                .put("batteryExempt", Battery.isExempt(this@WebViewActivity))
                .put("oemLabel", oem.label)
                .put("oemAdvice", oem.advice)
                .put("conn", connStatus())
                .put("killWarning", killWarning() ?: JSONObject.NULL)
                .put("test", JSONObject().put("msg", lastTestMsg).put("kind", lastTestKind))
                .toString()
        }.getOrDefault("{}")

        /** The automation cheat-sheet shown verbatim in the More tab (kept in sync with the
         *  control API by AutomationHelpTest). */
        @JavascriptInterface
        fun automationHelp(): String = runCatching { AutomationHelp.cheatSheet() }.getOrDefault("")

        // ---- Settings tabs: write ----

        /** Change one setting. Routine-controllable keys go through [Control.set] so the UI
         *  and the routine SET API share the same side effects (workers, mode); the few
         *  UI-only keys (server URL, control token, updates) are written directly. */
        @JavascriptInterface
        fun setSetting(key: String, value: String) {
            ui.post {
                when (key) {
                    "server_url" -> settings.serverUrl = value
                    "control_token" -> settings.controlToken = value
                    "updates_enabled" -> settings.updatesEnabled = value.toBooleanStrictOrNull() ?: settings.updatesEnabled
                    else -> Control.set(this@WebViewActivity, key, value, "user")
                }
            }
        }

        /** Master on/off from the Tracking tab (runs the permission two-step on start). */
        @JavascriptInterface
        fun setTracking(on: Boolean) {
            ui.post { if (on) startTracking() else { Control.apply(this@WebViewActivity, Control.ACTION_STOP, "user"); toast("Tracking off") } }
        }

        /** Force a tracking mode from the Tracking tab: "auto" | "driving" | "eco". */
        @JavascriptInterface
        fun setMode(mode: String) {
            ui.post {
                when (mode) {
                    "auto" -> forceMode(Control.ACTION_MODE_AUTO, "Auto")
                    "driving" -> forceMode(Control.ACTION_MODE_DRIVING, "Driving (forced)")
                    "eco" -> forceMode(Control.ACTION_MODE_ECO, "Eco (light only)")
                }
            }
        }

        /** Run the grant flow for one checklist item, keyed by its [getStatus] `action`. */
        @JavascriptInterface
        fun requestPermission(action: String) {
            val a = actionFromKey(action) ?: return
            ui.post { runPermissionAction(a) }
        }

        /** Request the battery-optimisation exemption (Tracking tab). */
        @JavascriptInterface
        fun requestBatteryExemption() { ui.post { Battery.requestExemption(this@WebViewActivity) } }

        /** Open this OEM's protected-apps / auto-start page (Tracking tab). */
        @JavascriptInterface
        fun openOemPage() { ui.post { OemBatteryLinks.openProtectedAppsPage(this@WebViewActivity) } }

        /** Stop nagging about the last suspected OEM kill (Tracking tab dismiss). */
        @JavascriptInterface
        fun dismissKillWarning() { settings.killAcknowledgedAt = System.currentTimeMillis() }

        /** Open the Bluetooth device picker for "car" or "obd" (Devices tab). */
        @JavascriptInterface
        fun pickBluetooth(kind: String) {
            ui.post {
                when (kind) {
                    "car" -> pickBt("Select car Bluetooth",
                        onPick = { mac, name -> settings.carBtMac = mac; settings.carBtName = name },
                        onClear = { settings.carBtMac = ""; settings.carBtName = "" })
                    "obd" -> pickBt("Select OBD dongle",
                        onPick = { mac, name -> settings.obdMac = mac; settings.obdName = name },
                        onClear = { settings.obdMac = ""; settings.obdName = "" })
                }
            }
        }

        /** Launch the QR pairing scanner (Sync tab). */
        @JavascriptInterface
        fun scanPairingQr() { ui.post { startPairScan() } }

        /** Apply a pasted pairing payload — a `drivetime://pair?…` link or a bare token
         *  (Sync tab). Keeps any server URL the user already set. */
        @JavascriptInterface
        fun pastePairing(payload: String) { ui.post { applyPairing(payload) } }

        /** Probe the configured server and stash the result for [getStatus] (Sync tab). */
        @JavascriptInterface
        fun testConnection() { ui.post { this@WebViewActivity.testConnection() } }

        /** Interactive check-for-update (More tab). */
        @JavascriptInterface
        fun checkForUpdate() {
            ui.post { toast("Checking for updates…"); Updater.checkFromUi(this@WebViewActivity, interactive = true) }
        }

        /** Export settings to a file via the system picker (Sync/Backup). */
        @JavascriptInterface
        fun exportBackup() { ui.post { exportLauncher.launch("drivetime-settings.json") } }

        /** Import settings from a file via the system picker (Sync/Backup). */
        @JavascriptInterface
        fun importBackup() { ui.post { importLauncher.launch(arrayOf("application/json", "*/*")) } }

        /** Save a text file (e.g. the mileage CSV export) to the device's public Downloads.
         *  The SPA calls this because a WebView can't download a `blob:` URL. */
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

        /** The logger's live snapshot (LiveState) as JSON, for the SPA's active-drive bar and
         *  the Tracking/Devices tab status lines. `{}` if anything goes sideways. */
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

    // ---- status helpers shared by the bridge ----

    /** The connection/upload summary the Sync tab shows — mirrors what the old native screen
     *  rendered: standalone, connected, reconnecting/auth-failed, or not-connected-yet. */
    private fun connStatus(): JSONObject {
        val o = JSONObject()
        if (!settings.hasServer) {
            return o.put("state", "standalone").put("detail", "No server — drives are saved on your phone.")
        }
        val h = uploader.health()
        val now = System.currentTimeMillis()
        when {
            h.lastError != null && h.failures > 0 -> {
                val auth = h.lastError!!.contains("Auth", ignoreCase = true)
                val retry = if (h.backoffUntil > now) " · retry in ${(h.backoffUntil - now) / 1000}s" else ""
                o.put("state", if (auth) "auth_failed" else "reconnecting")
                o.put("detail", "${h.queued} fix(es) waiting$retry")
                o.put("error", if (auth) "Uploads are being rejected — re-scan the pairing QR (the token may have rotated)."
                    else "Last error: ${h.lastError}")
            }
            h.lastSuccessAt > 0 -> {
                o.put("state", "connected")
                o.put("detail", "Last upload ${rel(h.lastSuccessAt)} · ${h.queued} queued")
            }
            else -> {
                o.put("state", "idle")
                o.put("detail", "${h.queued} queued · no upload yet — try Test connection")
            }
        }
        return o
    }

    /** The OEM-kill warning string, or null — only while an incident is newer than the last
     *  acknowledgement and within the nag window. */
    private fun killWarning(): String? {
        val killAt = settings.lastKillDetectedAt
        if (killAt > settings.killAcknowledgedAt &&
            System.currentTimeMillis() - killAt < KILL_WARNING_TTL_MS) {
            return "Your phone killed logging for a while. ${OemBatteryLinks.help().advice}"
        }
        return null
    }

    private fun rel(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return if (d < 60_000) "${d / 1000}s ago"
        else android.text.format.DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
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

    // ---- misc ----

    private fun toast(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

    companion object {
        /** Stable string keys for the permission-checklist actions, so the SPA can pass one
         *  back to [NativeBridge.requestPermission] without knowing the Kotlin enum. */
        private fun actionKey(a: Permissions.Action): String = when (a) {
            Permissions.Action.REQUEST_FOREGROUND_LOCATION -> "foreground_location"
            Permissions.Action.REQUEST_BACKGROUND_LOCATION -> "background_location"
            Permissions.Action.REQUEST_NOTIFICATIONS -> "notifications"
            Permissions.Action.REQUEST_BLUETOOTH -> "bluetooth"
            Permissions.Action.REQUEST_ACTIVITY_RECOGNITION -> "activity_recognition"
            Permissions.Action.REQUEST_BATTERY_EXEMPT -> "battery"
            Permissions.Action.OPEN_LOCATION_SETTINGS -> "location_settings"
            Permissions.Action.OPEN_APP_SETTINGS -> "app_settings"
        }

        private fun actionFromKey(k: String): Permissions.Action? = when (k) {
            "foreground_location" -> Permissions.Action.REQUEST_FOREGROUND_LOCATION
            "background_location" -> Permissions.Action.REQUEST_BACKGROUND_LOCATION
            "notifications" -> Permissions.Action.REQUEST_NOTIFICATIONS
            "bluetooth" -> Permissions.Action.REQUEST_BLUETOOTH
            "activity_recognition" -> Permissions.Action.REQUEST_ACTIVITY_RECOGNITION
            "battery" -> Permissions.Action.REQUEST_BATTERY_EXEMPT
            "location_settings" -> Permissions.Action.OPEN_LOCATION_SETTINGS
            "app_settings" -> Permissions.Action.OPEN_APP_SETTINGS
            else -> null
        }

        /** How long after a suspected OEM kill we keep nagging — a week is enough to notice
         *  and fix; after that there's nothing actionable left and it becomes noise. */
        private const val KILL_WARNING_TTL_MS = 7L * 24 * 60 * 60_000L

        private val testClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
