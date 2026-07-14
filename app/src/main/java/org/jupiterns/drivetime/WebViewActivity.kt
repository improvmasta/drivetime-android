package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jupiterns.drivetime.databinding.ActivityWebBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where Settings → "Report a problem" sends the diagnostic bundle (no server required). */
private const val REPORT_EMAIL = "lindsay@jupiterns.org"

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

    companion object {
        /** Intent extra carrying an SPA route ("/drive/L1720000000") — how a tapped event
         *  notification deep-links into the app (NOTIFICATIONS.md P3). */
        const val EXTRA_ROUTE = "dt_route"
    }

    private lateinit var b: ActivityWebBinding
    private lateinit var settings: Settings
    private val uploader by lazy { Uploader(this, settings) }
    private val ui = Handler(Looper.getMainLooper())

    private var loadedOnce = false

    // Double-tap-to-exit guard: timestamp of the last unhandled BACK on the home tab. A
    // second press within EXIT_CONFIRM_MS backgrounds the app; otherwise we just toast.
    private var lastBackAt = 0L
    private val EXIT_CONFIRM_MS = 2000L

    // Native-action plumbing (moved here from the retired LoggerActivity). These need an
    // Activity + the ActivityResult APIs, so they can't live in the WebView — the Settings
    // tabs trigger them over the bridge and re-poll getSettings()/getStatus() for the result.
    private var pendingStartAfterGrant = false
    private lateinit var btPicker: BtDevicePicker
    private val syncStatus by lazy { SyncStatus(settings, uploader) }
    private lateinit var fgLocationLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var bgLocationLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var miscPermLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>
    // Backup & restore (BACKUP.md): destination folder, one-off archive save, restore pick.
    private lateinit var folderLauncher: ActivityResultLauncher<Uri?>
    private lateinit var archiveExportLauncher: ActivityResultLauncher<String>
    private lateinit var restoreLauncher: ActivityResultLauncher<Array<String>>
    // Mileage export "Save to…": the CSV waiting on the create-document picker.
    private lateinit var csvSaveLauncher: ActivityResultLauncher<String>
    @Volatile private var pendingSaveAs: ByteArray? = null

    // Last "Test connection" result, surfaced back to the SPA's Sync tab via getStatus().
    @Volatile private var lastTestMsg: String = ""
    @Volatile private var lastTestKind: String = ""   // "good" | "bad" | "warn" | ""

    // Deep-link route waiting for the SPA (NOTIFICATIONS.md P3): stashed from the launch
    // intent (cold start) or an onNewIntent the page wasn't ready for; the SPA pulls it via
    // consumePendingRoute() once mounted. Volatile: UI thread writes, JS-bridge thread reads.
    @Volatile private var pendingRoute: String? = null

    // Serves the APK-bundled SPA (assets/web/…) at https://appassets.androidplatform.net/
    // assets/web/ — a secure origin, so the SPA's service worker + IndexedDB replica work
    // in standalone mode (STANDALONE.md A1).
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            // Restore staging (BACKUP.md): a backup's app-data JSON is served to the SPA at
            // /restore/staged.json — a 40 MB document can't ride a bridge return value.
            .addPathHandler("/restore/", WebViewAssetLoader.InternalStoragePathHandler(
                this, BackupStore.restoreDir(this)))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        b = ActivityWebBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        registerLaunchers()
        btPicker = BtDevicePicker(this) { toast(it) }

        val web = b.webview
        val ws: WebSettings = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true          // SPA localStorage (theme) + IndexedDB replica
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW   // site is https-only
        ws.mediaPlaybackRequiresUserGesture = true
        // The SPA ships its OWN complete light+dark themes and toggles them itself (Settings →
        // Theme), so the WebView must NOT algorithmically darken content. With darkening allowed
        // it re-darkened an explicit LIGHT theme whenever the OS was in night mode, so "light mode
        // looked like dark mode". Off = the page's own CSS fully decides the appearance.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, false)
        }
        // Chromium starts its text selection from the container view's performLongClick(), before
        // the page's `user-select: none` or selectstart guard can weigh in — so holding a drive row
        // to select it highlighted the row's text and raised the magnifier. Consume the long-click
        // here, except over an editable field where the hold is how you reach the paste menu.
        web.setOnLongClickListener { v ->
            (v as WebView).hitTestResult.type != WebView.HitTestResult.EDIT_TEXT_TYPE
        }

        // Bridge for the SPA: drain the phone's own GPS into the on-device replica (A2), learn
        // whether we're standalone (A3), and drive/read every native tracker setting + action
        // from the Settings tabs. Only our own SPA is ever loaded in this WebView (external
        // links hand off to the system browser), so the surface is trusted.
        web.addJavascriptInterface(NativeBridge(), "DrivetimeNative")

        CookieManager.getInstance().setAcceptCookie(true)

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
                if (request.isForMainFrame) {
                    EventLog.warn("Web main-frame load error: ${error.errorCode} ${error.description}")
                    if (!loadedOnce) showOffline()
                }
            }
        }

        // The SPA's console errors are invisible on a phone — capture them into the activity
        // log so a Report-a-problem email carries the web side of a failure too. Errors are
        // WARN (shown in the Log screen); everything else is ignored to keep the trail coarse.
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val src = msg.sourceId()?.substringAfterLast('/') ?: ""
                    val text = msg.message()?.take(300) ?: ""
                    EventLog.warn("web console: $text ($src:${msg.lineNumber()})")
                }
                return false // let it reach logcat too
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

        // Hardware back is owned by the SPA: it closes an open sheet/dialog, then exits a
        // multi-select, then returns to the first tab from anywhere else. Only when the SPA
        // has nothing left to handle (home tab, nothing open) do we act — and even then we
        // never leave on a single press: the first back shows a "press again to exit" toast,
        // and only a second press within the window backgrounds the app. So a stray BACK on
        // the home tab can't make drivetime vanish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                b.webview.evaluateJavascript(
                    "(typeof window.__dtHandleBack==='function') ? window.__dtHandleBack() : false"
                ) { res ->
                    if (res == "true") return@evaluateJavascript
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBackAt < EXIT_CONFIRM_MS) {
                        moveTaskToBack(true)
                    } else {
                        lastBackAt = now
                        Toast.makeText(this@WebViewActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // A notification tap on a cold start: stash the route; the SPA pulls it once mounted.
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)

        loadDashboard()
    }

    /** A notification tap while the activity exists (launchMode="singleTask"): hand the
     *  route straight to the running SPA, or stash it if the page isn't ready yet. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return
        val js = "(typeof window.__dtNavigate==='function') " +
            "? (window.__dtNavigate(${JSONObject.quote(route)}), true) : false"
        b.webview.evaluateJavascript(js) { res ->
            if (res != "true") pendingRoute = route
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to the app: drain any upload backlog, and if the dashboard never came
        // up (e.g. setup was just finished, or we were offline), try again.
        Thread { runCatching { uploader.flush() } }.start()
        // The SPA authenticates to the server per-request with the device token (no cookie
        // session to keep alive), so a resume just retries the load if it never came up.
        if (!loadedOnce) loadDashboard()
    }


    override fun onDestroy() {
        // tear down an open scan (dialog/receiver/discovery); lateinit-guarded in case
        // onCreate never completed
        if (::btPicker.isInitialized) btPicker.close()
        super.onDestroy()
    }

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
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { exportTo(it) } }
        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { importFrom(it) } }
        scanLauncher = registerForActivityResult(ScanContract()) { result ->
            result?.contents?.let { applyPairing(it) }
        }
        folderLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri -> uri?.let { adoptBackupFolder(it) } }
        archiveExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument(BackupStore.ARCHIVE_MIME)
        ) { uri -> uri?.let { exportArchiveTo(it) } }
        restoreLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { restoreFrom(it) } }
        csvSaveLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            val bytes = pendingSaveAs
            pendingSaveAs = null
            if (uri != null && bytes != null) writeExportTo(uri, bytes)
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
        finishStart()
    }

    /** Complete a start: fine location is enough for the foreground service — background
     *  location only extends logging past the screen turning off, so declining it must
     *  degrade to foreground-only tracking, never block the start ("Not now" used to
     *  strand the toggle in the off state). The Tracking tab's checklist keeps nagging. */
    private fun finishStart() {
        pendingStartAfterGrant = false
        if (!Permissions.snapshot(this, settings).hasBatteryExempt) Battery.requestExemption(this)
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
     *  complete the start. Fine location alone is sufficient (see [finishStart]). */
    private fun tryResumeStart() {
        if (!Permissions.snapshot(this, settings).hasFineLocation) return
        finishStart()
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
            .setNegativeButton("Not now") { _, _ ->
                // Declining background access degrades to foreground-only logging —
                // it must not cancel a queued start.
                if (pendingStartAfterGrant) finishStart()
            }
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
        // Pairing is the clearest "I want a server" signal — re-enable sync if it was
        // toggled off, or the fresh pairing would look dead.
        settings.serverEnabled = true
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
                Http.client.newCall(req).execute().use {
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

    // ---- full-data backup & restore (BACKUP.md) ----

    /** Keep the picked folder across reboots; one persisted grant is plenty, so a
     *  previous folder's grant is released. */
    private fun adoptBackupFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val took = runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        if (took.isFailure) { snack("Couldn't keep access to that folder — pick another"); return }
        val old = settings.backupFolderUri
        if (old.isNotBlank() && old != uri.toString()) {
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        settings.backupFolderUri = uri.toString()
        settings.backupFolderName = runCatching {
            DocumentFile.fromTreeUri(this, uri)?.name ?: ""
        }.getOrDefault("")
        // The scheduled work's network constraint is derived from the destination set, so
        // changing that set has to re-arm it (BackupWorker.constraints).
        BackupWorker.reschedule(this, settings)
        EventLog.info("Backup folder set: ${settings.backupFolderName.ifBlank { uri.lastPathSegment ?: "?" }}")
        toast("Backup folder set")
    }

    /** One-off "Save to file…": stream a fresh archive to the user-picked document. */
    private fun exportArchiveTo(uri: Uri) {
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "w")
                    ?.let { BackupStore.writeArchive(this, settings, it); true } ?: false
            }.getOrElse { EventLog.warn("Backup export failed: ${it.message}"); false }
            ui.post { if (ok) toast("Backup saved") else snack("Backup export failed") }
            if (ok) EventLog.info("Backup exported to a picked file")
        }.start()
    }

    /** Restore a picked file — full archive, bare data snapshot, or legacy settings JSON;
     *  BackupStore sniffs the bytes. Native state applies here; staged app data is handed
     *  to the SPA, which imports it and reloads itself. */
    private fun restoreFrom(uri: Uri) {
        toast("Restoring…")
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { BackupStore.restore(this, settings, it) }
                    ?: BackupStore.RestoreResult("unknown", 0, false, "couldn't open the file")
            }.getOrElse { BackupStore.RestoreResult("unknown", 0, false, it.message ?: "read error") }
            ui.post {
                when {
                    result.error != null -> snack("Restore failed: ${result.error}")
                    result.stagedAppData -> {
                        b.webview.evaluateJavascript(
                            "(typeof window.__dtRestoreStaged==='function') && window.__dtRestoreStaged()",
                            null)
                        snack("Restoring your data…")
                    }
                    else -> toast("Imported ${result.settingsApplied} settings")
                }
            }
        }.start()
    }

    // ---- mileage export destinations ----

    /** Write a "Save to…" export to the document the user just picked. */
    private fun writeExportTo(uri: Uri, bytes: ByteArray) {
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            ui.post { if (ok) toast("Exported") else snack("Export failed — couldn't write the file") }
            EventLog.info(if (ok) "Export saved to a picked file" else "Export to picked file failed")
        }.start()
    }

    /** Hand a generated export to the system share sheet (email, messaging, cloud apps).
     *  The file is staged under our FileProvider `exports/` root. */
    private fun shareExport(name: String, mime: String, bytes: ByteArray) {
        val intent = runCatching {
            val dir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            Intent(Intent.ACTION_SEND)
                .setType(mime.ifBlank { "text/csv" })
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.getOrNull()
        if (intent == null) { snack("Couldn't prepare the file to share"); return }
        runCatching { startActivity(Intent.createChooser(intent, "Share $name")) }
            .onFailure { snack("Nothing available to share with") }
    }

    /** Upload an export to Google Drive — optionally converted to a real Google Sheet —
     *  using the backup feature's Drive connection, then offer to open the result. */
    private fun uploadExportToDrive(name: String, mime: String, bytes: ByteArray, asSheet: Boolean) {
        toast(if (asSheet) "Creating Google Sheet…" else "Uploading to Google Drive…")
        Thread {
            val link = runCatching { DriveClient(settings).uploadExport(name, mime, bytes, asSheet) }
            ui.post {
                link.fold({ url ->
                    Snackbar.make(b.root,
                        if (asSheet) "Google Sheet created" else "Uploaded to Google Drive",
                        Snackbar.LENGTH_LONG)
                        .setAction("Open") {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        .show()
                }, { e ->
                    snack("Drive export failed: ${e.message ?: "error"}")
                })
            }
            link.fold(
                { EventLog.info("Export uploaded to Drive: $name (sheet=$asSheet)") },
                { EventLog.warn("Drive export failed: ${it.message}") })
        }.start()
    }

    /** Kick the Drive OAuth consent flow in the system browser (redirect returns via
     *  [OAuthRedirectActivity]). */
    private fun startDriveAuth() {
        val cid = settings.driveClientId
        if (cid.isBlank()) { snack("Paste your OAuth client ID first (Google Drive setup)"); return }
        val url = DriveAuth.beginAuthUrl(cid)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { snack("No browser available for Google sign-in") }
    }

    // ---- problem report ----

    /** Build the diagnostic bundle — app/device info, a secrets-free settings summary, the
     *  permission checklist, and the FULL activity log (DEBUG included) — into
     *  `reports/drivetime-report.txt`, then open a chooser for an email pre-addressed to
     *  [REPORT_EMAIL]. Nothing sends until the user hits Send in their own mail app. */
    private fun sendProblemReport(note: String) {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val report = buildString {
            append("drivetime problem report\n")
            append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("Server configured: ${settings.isConfigured}\n")
            append("Tracking mode: ${settings.trackingMode}\n")
            append("Battery exempt: ${Battery.isExempt(this@WebViewActivity)}\n")
            for (c in Permissions.checklist(this@WebViewActivity, settings)) {
                append("Permission — ${c.label}: ${if (c.granted) "granted" else "MISSING"}\n")
            }
            if (note.isNotBlank()) append("\nUser note: $note\n")
            append("\n--- activity log (newest first, diagnostics included) ---\n")
            for (e in EventLog.recent()) {
                append("${fmt.format(Date(e.ts))} [${e.level}] ${e.msg}\n")
            }
        }
        val subject = "drivetime problem report (v${BuildConfig.VERSION_NAME})"
        val intent = runCatching {
            val dir = File(getExternalFilesDir(null), "reports").apply { mkdirs() }
            val f = File(dir, "drivetime-report.txt")
            f.writeText(report)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT,
                    if (note.isNotBlank()) note
                    else "(describe what went wrong here — the diagnostic log is attached)")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.getOrElse {
            // Couldn't write the attachment (no external storage?) — inline a trimmed
            // report in the body instead; a long email beats no report.
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, report.take(50_000))
        }
        runCatching { startActivity(Intent.createChooser(intent, "Send problem report")) }
            .onFailure { snack("No email app available") }
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

        /** JSON array of markers stamped natively (the notification's Mark button, Android
         *  Auto) at or after [sinceTs]. Native minted each `id`, so the SPA's drain is
         *  idempotent: it ignores an id it already holds (MARKERS.md §6). */
        @JavascriptInterface
        fun pullMarkers(sinceTs: Double): String = WebMarkerBuffer.pullSince(this@WebViewActivity, sinceTs)

        /** JSON array of vehicle events (car BT connected, or OBD read a VIN) at or after
         *  [sinceTs] (Phase 4). The SPA resolves each to the drive spanning its ts and to a
         *  registered vehicle by key, then writes the trip_vehicle overlay — idempotent, so a
         *  cursor overlap re-delivering an event is harmless. */
        @JavascriptInterface
        fun pullVehicles(sinceTs: Double): String = WebVehicleBuffer.pullSince(this@WebViewActivity, sinceTs)

        /** JSON array of per-drive "battery used" events (start/end %) the logger stamped at
         *  drive end, at or after [sinceTs] (Phase 5). The SPA resolves each to the drive that
         *  began at its ts and writes the local `trip_battery` overlay — a phone-local diagnostic,
         *  never synced. Re-delivery is idempotent (keyed by the drive). */
        @JavascriptInterface
        fun pullBattery(sinceTs: Double): String = WebBatteryBuffer.pullSince(this@WebViewActivity, sinceTs)

        /** JSON array of tracker-liveness rows at or after [sinceTs] — the `down` windows the
         *  logger was NOT running (with why), and the `cond` transitions in what it needs to work
         *  (location services, permissions, power saver). See [Health].
         *
         *  This is the row stream that lets the app state "you lost 14:02–15:10" as a FACT. Every
         *  other signal it has is an absence of GPS fixes, and an absence proves nothing: a parked
         *  car and a dead tracker look identical in the fix stream. Keyed `kind|ts` on the SPA
         *  side, so a cursor overlap re-delivering a row is a no-op. */
        @JavascriptInterface
        fun pullHealth(sinceTs: Double): String = Health.pullSince(this@WebViewActivity, sinceTs)

        /**
         * Stamp a marker from the SPA's own Mark button, through the SAME service path the
         * notification uses. One writer: otherwise an in-app mark would land
         * in IndexedDB without ever reaching [LiveState.markerCount], and the notification's
         * "since #N" would disagree with the live bar's — the two surfaces the driver checks
         * against each other. The SPA then drains it back out of the buffer like any other.
         *
         * False when no drive is in progress (nothing to mark) — the caller falls back.
         */
        @JavascriptInterface
        fun mark(): Boolean {
            if (!LocationService.isRunning || LiveState.tier != "DRIVING") return false
            val i = Intent(this@WebViewActivity, LocationService::class.java).setAction(Control.ACTION_MARK)
            return runCatching { startService(i) }.isSuccess
        }

        /** The live drive dashboard was opened/closed in the SPA. While open, the logger boosts
         *  its GPS + OBD sample rate so the gauges read ~1s-live; on close it drops back to the
         *  battery-saving cadence. A no-op when not driving. */
        @JavascriptInterface
        fun setDashboardActive(active: Boolean) {
            LocationService.setDashboardBoost(this@WebViewActivity, active)
        }

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
                .put("server_enabled", settings.serverEnabled)
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
                .put("notif_driving_only", settings.notifDrivingOnly)
                .put("notify_drive_complete", settings.notifyDriveComplete)
                .put("notify_gas_stop", settings.notifyGasStop)
                .put("notify_digest", settings.notifyDigest)
                .put("notify_tracking_health", settings.notifyTrackingHealth)
                .put("notify_backup_health", settings.notifyBackupHealth)
                .put("digest_day", settings.digestDay)
                .put("digest_time", settings.digestTime)
                .put("control_token", settings.controlToken)
                .put("updates_enabled", settings.updatesEnabled)
                // No build self-updates any more (Play policy — the updater is deleted, not
                // disabled), so the SPA hides the whole "check for updates" affordance rather
                // than offering a dead button. Reported as a constant `false` rather than
                // dropped: the SPA reads `updates_supported !== false`, so an ABSENT key means
                // "supported" and would put the dead button back.
                .put("updates_supported", false)
                // The legacy standalone car/OBD devices. Nothing in the SPA *configures* these
                // any more — the vehicle that owns the device does — but they're reported so the
                // registry can adopt a pre-registry install's devices into a real vehicle
                // exactly once (vehicles.js adoptLegacyDevices), instead of stranding them in a
                // settings screen that no longer shows them.
                .put("carBtName", settings.carBtName)
                .put("carBtMac", settings.carBtMac)
                .put("obdName", settings.obdName)
                .put("obdMac", settings.obdMac)
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
                    .put("action", Permissions.keyOf(c.action)))
            }
            val oem = OemBatteryLinks.help()
            JSONObject()
                .put("perms", perms)
                .put("batteryExempt", Battery.isExempt(this@WebViewActivity))
                .put("oemLabel", oem.label)
                .put("oemAdvice", oem.advice)
                .put("conn", syncStatus.connStatus())
                .put("killWarning", syncStatus.killWarning() ?: JSONObject.NULL)
                // Standing trouble codes (NOTIFICATIONS.md P5). The dongle is the only thing
                // that knows these and nothing replicates them, so the notification centre
                // gets its check-engine row from here rather than from the replica.
                .put("dtcs", JSONArray(settings.knownDtcs.sorted()))
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
                    "server_enabled" -> settings.serverEnabled = value.toBooleanStrictOrNull() ?: settings.serverEnabled
                    "control_token" -> settings.controlToken = value
                    "updates_enabled" -> settings.updatesEnabled = value.toBooleanStrictOrNull() ?: settings.updatesEnabled
                    "backup_schedule" -> {
                        settings.backupSchedule = value
                        BackupWorker.reschedule(this@WebViewActivity, settings)
                    }
                    "backup_keep" -> settings.backupKeep = value.toIntOrNull() ?: settings.backupKeep
                    "backup_drive_client_id" -> settings.backupDriveClientId = value
                    // Event notifications (NOTIFICATIONS.md P3): UI-only keys, not in
                    // Control.SET_KEYS for v1.
                    "notify_drive_complete" -> settings.notifyDriveComplete =
                        value.toBooleanStrictOrNull() ?: settings.notifyDriveComplete
                    "notify_gas_stop" -> settings.notifyGasStop =
                        value.toBooleanStrictOrNull() ?: settings.notifyGasStop
                    // Health alerts (P5). Turning the kill warning OFF also clears one that
                    // is already in the shade — a toggle that leaves its own notification
                    // sitting there reads as broken.
                    "notify_tracking_health" -> {
                        settings.notifyTrackingHealth =
                            value.toBooleanStrictOrNull() ?: settings.notifyTrackingHealth
                        if (!settings.notifyTrackingHealth) {
                            Notify.cancel(
                                this@WebViewActivity, Notify.KIND_TRACKING_HEALTH, Notify.HEALTH_ID)
                        }
                    }
                    "notify_backup_health" -> {
                        settings.notifyBackupHealth =
                            value.toBooleanStrictOrNull() ?: settings.notifyBackupHealth
                        if (!settings.notifyBackupHealth) {
                            Notify.cancel(
                                this@WebViewActivity, Notify.KIND_BACKUP_HEALTH, Notify.HEALTH_ID)
                        }
                    }
                    // The digest keys own a scheduled worker, so each write re-arms it (the
                    // backup_schedule precedent above).
                    "notify_digest" -> {
                        settings.notifyDigest = value.toBooleanStrictOrNull() ?: settings.notifyDigest
                        DigestWorker.reschedule(this@WebViewActivity, settings)
                    }
                    "digest_day" -> {
                        settings.digestDay = value.toIntOrNull() ?: settings.digestDay
                        DigestWorker.reschedule(this@WebViewActivity, settings)
                    }
                    "digest_time" -> {
                        settings.digestTime = value
                        DigestWorker.reschedule(this@WebViewActivity, settings)
                    }
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

        /** Turn tracking off now and auto-resume after [minutes] (header "Turn off for…"). */
        @JavascriptInterface
        fun snoozeTracking(minutes: String) {
            val m = minutes.toIntOrNull() ?: return
            ui.post {
                Control.snooze(this@WebViewActivity, m)
                val label = when {
                    m % 1440 == 0 -> "${m / 1440}d"
                    m % 60 == 0 -> "${m / 60}h"
                    else -> "${m}m"
                }
                toast("Tracking off — back on in $label")
            }
        }

        /** Run the grant flow for one checklist item, keyed by its [getStatus] `action`. */
        @JavascriptInterface
        fun requestPermission(action: String) {
            val a = Permissions.fromKey(action) ?: return
            ui.post { runPermissionAction(a) }
        }

        /** Request the battery-optimisation exemption (Tracking tab). */
        @JavascriptInterface
        fun requestBatteryExemption() { ui.post { Battery.requestExemption(this@WebViewActivity) } }

        /** Open this OEM's protected-apps / auto-start page (Tracking tab). */
        @JavascriptInterface
        fun openOemPage() { ui.post { OemBatteryLinks.openProtectedAppsPage(this@WebViewActivity) } }

        /**
         * Acknowledge the last suspected OEM kill — the Tracking-tab dismiss, and (P5) the
         * notification centre's X on the `tracking_interrupted` notice.
         *
         * This is the kill warning's ONE dismissal, and it is deliberately per-episode: the
         * ack is a timestamp, so the *next* kill (a later `lastKillDetectedAt`) raises the
         * warning again. It also cancels the posted notification, so acknowledging in the app
         * clears the shade — the two halves must never disagree about whether the user has
         * seen this.
         */
        @JavascriptInterface
        fun dismissKillWarning() {
            settings.killAcknowledgedAt = System.currentTimeMillis()
            Notify.cancel(this@WebViewActivity, Notify.KIND_TRACKING_HEALTH, Notify.HEALTH_ID)
        }

        // ---- event notifications + deep-linking (NOTIFICATIONS.md P3) ----

        /** Post one event notification through [Notify] — same gate native firing uses
         *  (kind toggle + permission), so a new SPA-driven kind is one bridge call. */
        @JavascriptInterface
        fun postNotification(kind: String, id: String, title: String, body: String, route: String): Boolean =
            Notify.post(this@WebViewActivity, kind, id, title, body, route)

        /** Cancel a posted event notification by its (kind, id). */
        @JavascriptInterface
        fun cancelNotification(kind: String, id: String) {
            Notify.cancel(this@WebViewActivity, kind, id)
        }

        /** The SPA's pending-attention snapshot, pushed once per sync tick:
         *  `{tagPrompts:[{start_ts,label,miles}], gasPairs:[right_ts], untagged, suggested}`.
         *  Native stores it (informing [DriveCompleteWorker] and the P4 digest) and retracts
         *  any posted notification whose item is no longer pending. */
        @JavascriptInterface
        fun setPendingAttention(json: String) {
            Notify.setPendingAttention(this@WebViewActivity, json)
        }

        /** Cold-start deep-link pull: the route a tapped notification carried, once ("" when
         *  none). The SPA calls this on mount; a warm tap goes straight to __dtNavigate. */
        @JavascriptInterface
        fun consumePendingRoute(): String {
            val r = pendingRoute ?: ""
            pendingRoute = null
            return r
        }

        /**
         * Open the Bluetooth device picker.
         *
         * The vehicle registry owns every car device now, so the two live kinds — "vehicle" (a
         * car's stereo/head unit) and "vehicle-obd" (that car's OBD dongle) — write nothing
         * natively: they hand the chosen MAC + name back to whichever editor is open (Settings'
         * vehicle sheet, or the first-run wizard's car step) through a JS callback, and the SPA
         * saves it on the vehicle. The legacy "car"/"obd" kinds still set the old standalone
         * settings; nothing in the SPA calls them any more, and they go when the fallback in
         * [Settings.obdTarget] does.
         */
        @JavascriptInterface
        fun pickBluetooth(kind: String) {
            ui.post {
                when (kind) {
                    "car" -> btPicker.pick("Select car Bluetooth",
                        onPick = { mac, name -> settings.carBtMac = mac; settings.carBtName = name },
                        onClear = { settings.carBtMac = ""; settings.carBtName = "" })
                    "obd" -> btPicker.pick("Select OBD dongle",
                        onPick = { mac, name -> settings.obdMac = mac; settings.obdName = name },
                        onClear = { settings.obdMac = ""; settings.obdName = "" })
                    "vehicle" -> btPicker.pick("Select car Bluetooth",
                        onPick = { mac, name -> pickedBack("__dtVehicleBtPicked", mac, name) },
                        onClear = {})
                    "vehicle-obd" -> btPicker.pick("Select OBD adapter",
                        onPick = { mac, name -> pickedBack("__dtVehicleObdPicked", mac, name) },
                        onClear = {})
                }
            }
        }

        /** Hand a picked device back to the SPA editor waiting on it. */
        private fun pickedBack(fn: String, mac: String, name: String) {
            val js = "window.$fn && window.$fn(" +
                JSONObject.quote(mac) + "," + JSONObject.quote(name) + ")"
            ui.post { b.webview.evaluateJavascript(js, null) }
        }

        /** The SPA's vehicles registry pushes the UNION of every vehicle's Bluetooth MACs here
         *  (Phase 4), so the logger's [btReceiver] treats any registered car's BT as a driving
         *  signal — not just the legacy single [Settings.carBtMac], which the getter still folds
         *  in. [json] is a JSON array of MAC strings; a parse failure leaves the set unchanged. */
        @JavascriptInterface
        fun setVehicleBtMacs(json: String) {
            runCatching {
                val arr = org.json.JSONArray(json)
                val macs = (0 until arr.length()).map { arr.getString(it) }.toSet()
                settings.carBtMacs = macs
            }.onFailure { EventLog.warn("setVehicleBtMacs failed: ${it.message}") }
        }

        /** The SPA's vehicles registry pushes the cars here — each with its OBD adapter (blank if
         *  it has none) and the car Bluetooth that identifies it — so the OBD loop can dial the
         *  adapter of the car you are actually in instead of assuming a one-car household
         *  ([Settings.obdTarget]). [json] is `[{obd, name, bt:[mac,…]}, …]`, one entry per
         *  vehicle; adapter-less cars are included on purpose, because knowing a car has no
         *  dongle is what stops the logger probing for one. An empty array clears the bindings,
         *  dropping back to the legacy [Settings.obdMac]. A parse failure changes nothing. */
        @JavascriptInterface
        fun setVehicleObd(json: String) {
            runCatching {
                val arr = org.json.JSONArray(json)
                settings.vehicleObd = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val bts = o.optJSONArray("bt")
                    val list: List<String> = if (bts == null) emptyList()
                        else (0 until bts.length()).map { bts.getString(it) }
                    Settings.ObdBinding(o.optString("obd").trim(), o.optString("name"), list)
                }
            }.onFailure { EventLog.warn("setVehicleObd failed: ${it.message}") }
        }

        /** Hold the screen awake while the live-drive HUD asks for it (its "Keep screen on"
         *  toggle) — a phone mounted on the dash is useless if it sleeps mid-drive.
         *  FLAG_KEEP_SCREEN_ON is scoped to this window: it needs no wake-lock permission, and
         *  it lapses the moment the activity leaves the foreground, so a forgotten toggle can
         *  never strand the screen on behind another app. The SPA drops it when the drive ends. */
        @JavascriptInterface
        fun setKeepScreenOn(on: Boolean) {
            ui.post {
                val w = this@WebViewActivity.window
                if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        /**
         * Formerly the interactive check-for-update (More tab). The app no longer updates
         * itself at all, and the SPA hides the affordance (`updates_supported=false`) — but
         * the method stays on the bridge, because a WebView holding an older cached snapshot
         * still has the button and calls this by name. Kept as an honest no-op rather than
         * removed, so that stale button says something true instead of throwing.
         */
        @JavascriptInterface
        fun checkForUpdate() {
            ui.post { toast("Updates for this build come from Google Play") }
        }

        /** Export settings to a file via the system picker (Sync/Backup). */
        @JavascriptInterface
        fun exportBackup() { ui.post { exportLauncher.launch("drivetime-settings.json") } }

        /** Import settings from a file via the system picker (Sync/Backup). */
        @JavascriptInterface
        fun importBackup() { ui.post { importLauncher.launch(arrayOf("application/json", "*/*")) } }

        // ---- full-data backup & restore (BACKUP.md) ----

        /** The SPA streams its data snapshot over these three (begin → chunk* → end);
         *  chunked because one bridge string holding the whole history risks an OOM. */
        @JavascriptInterface
        fun backupSnapshotBegin(): Boolean = BackupStore.beginSnapshot(this@WebViewActivity)

        @JavascriptInterface
        fun backupSnapshotChunk(chunk: String): Boolean = BackupStore.appendChunk(chunk)

        @JavascriptInterface
        fun backupSnapshotEnd(createdAtMs: Double): Boolean =
            BackupStore.endSnapshot(this@WebViewActivity, settings, createdAtMs.toLong())

        /** Backup config + last-run status for the Settings card. `supported` tells the SPA
         *  this shell speaks the full-data protocol at all. */
        @JavascriptInterface
        fun getBackupStatus(): String = runCatching {
            JSONObject()
                .put("supported", true)
                .put("schedule", settings.backupSchedule)
                .put("keep", settings.backupKeep)
                .put("folderSet", settings.backupFolderUri.isNotBlank())
                .put("folderName", settings.backupFolderName)
                .put("driveClientIdSet", settings.driveClientId.isNotBlank())
                .put("driveDefaultClient", Settings.DEFAULT_DRIVE_CLIENT_ID.isNotBlank())
                .put("driveConnected", settings.backupDriveRefreshToken.isNotBlank())
                .put("driveAccount", settings.backupDriveAccount)
                .put("lastBackupAt", settings.backupLastAt)
                .put("lastBackupOk", settings.backupLastOk)
                .put("lastBackupResult", settings.backupLastResult)
                .put("snapshotAt", settings.backupSnapshotAt)
                .toString()
        }.getOrDefault("{}")

        /** Pick (or change) the backup destination folder via the system tree picker. */
        @JavascriptInterface
        fun pickBackupFolder() { ui.post { folderLauncher.launch(null) } }

        /** Forget the picked folder — releases the grant, deletes nothing. */
        @JavascriptInterface
        fun clearBackupFolder() {
            val old = settings.backupFolderUri
            settings.backupFolderUri = ""
            settings.backupFolderName = ""
            if (old.isNotBlank()) runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            BackupWorker.reschedule(this@WebViewActivity, settings)
            ui.post { toast("Backup folder forgotten") }
        }

        /** Start the Google Drive consent flow (needs the pasted client ID). */
        @JavascriptInterface
        fun connectDrive() { ui.post { startDriveAuth() } }

        /** Drop the Drive tokens; the files already uploaded stay in the user's Drive. */
        @JavascriptInterface
        fun disconnectDrive() {
            settings.backupDriveRefreshToken = ""
            settings.backupDriveAccessToken = ""
            settings.backupDriveAccount = ""
            settings.backupDriveFolderId = ""
            settings.backupDriveExportFolderId = ""
            BackupWorker.reschedule(this@WebViewActivity, settings)
            EventLog.info("Google Drive disconnected")
            ui.post { toast("Google Drive disconnected") }
        }

        /** Back up to the configured destination(s) right now (the SPA has just pushed a
         *  fresh snapshot). Result surfaces via [getBackupStatus]. */
        @JavascriptInterface
        fun backupNow() {
            ui.post {
                if (settings.backupFolderUri.isBlank() && settings.backupDriveRefreshToken.isBlank()) {
                    snack("Pick a backup folder or connect Google Drive first")
                } else {
                    BackupWorker.runNow(this@WebViewActivity)
                    toast("Backing up…")
                }
            }
        }

        /** One-off archive to a user-picked file (system save dialog). */
        @JavascriptInterface
        fun exportBackupFile() {
            ui.post { archiveExportLauncher.launch(BackupStore.archiveName(System.currentTimeMillis())) }
        }

        /** Pick a backup file and restore it (archive / data snapshot / legacy settings). */
        @JavascriptInterface
        fun restoreBackup() {
            ui.post {
                restoreLauncher.launch(arrayOf(
                    "application/zip", "application/json", "application/gzip",
                    "application/octet-stream", "*/*"))
            }
        }

        /** The SPA finished (or failed) importing staged restore data; it reloads itself
         *  on success, so we only clean up and surface a failure. */
        @JavascriptInterface
        fun restoreStagedDone(ok: Boolean, detail: String) {
            BackupStore.clearStaged(this@WebViewActivity)
            EventLog.info(if (ok) "Data restore imported ($detail)" else "Data restore failed: $detail")
            if (!ok) ui.post { snack("Restore failed: $detail") }
        }

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

        /** "Save to…": export via the system create-document picker, so the user picks
         *  the location and final name. The pending bytes wait on the picker result. */
        @JavascriptInterface
        fun saveFileAs(name: String, mime: String, content: String) {
            val safe = if (name.isBlank()) "drivetime.csv" else name.substringAfterLast('/')
            pendingSaveAs = content.toByteArray(Charsets.UTF_8)
            ui.post { csvSaveLauncher.launch(safe) }
        }

        /** Share an export through the system share sheet (email, messaging, any app). */
        @JavascriptInterface
        fun shareFile(name: String, mime: String, content: String) {
            val safe = if (name.isBlank()) "drivetime.csv" else name.substringAfterLast('/')
            val bytes = content.toByteArray(Charsets.UTF_8)
            ui.post { shareExport(safe, mime, bytes) }
        }

        /** Upload an export to Google Drive; [asSheet] converts the CSV into a real
         *  Google Sheet. False when Drive isn't connected — the SPA shows its connect
         *  hint instead of a doomed upload. */
        @JavascriptInterface
        fun exportToDrive(name: String, mime: String, content: String, asSheet: Boolean): Boolean {
            if (settings.backupDriveRefreshToken.isBlank()) return false
            val safe = if (name.isBlank()) "drivetime.csv" else name.substringAfterLast('/')
            val bytes = content.toByteArray(Charsets.UTF_8)
            ui.post { uploadExportToDrive(safe, mime.ifBlank { "text/csv" }, bytes, asSheet) }
            return true
        }

        /** Open the native event log (diagnostics — service starts/stops, OBD, uploads). */
        @JavascriptInterface
        fun openLog() {
            ui.post { startActivity(Intent(this@WebViewActivity, LogActivity::class.java)) }
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
                // The drive's signal light. `driving` means "a drive is in progress" (it survives
                // a red light and a gas pump); `moving` is whether the wheels are turning right
                // now, and `stopped_since` (epoch seconds, 0 while moving) lets the HUD count the
                // current stop up live rather than guess at it.
                .put("moving", s.moving)
                .put("stopped_since", if (s.stoppedSince > 0L) s.stoppedSince / 1000 else 0L)
                .put("reason", s.driveReason ?: JSONObject.NULL)
                // Epoch *seconds* (0 = not driving), the same unit as a buffered fix's `ts`, so
                // the SPA can anchor the live bar to the real drive start with no unit juggling.
                .put("drive_started_at", if (s.driveStartedAt > 0L) s.driveStartedAt / 1000 else 0L)
                .put("lat", s.lat ?: JSONObject.NULL)
                .put("lon", s.lon ?: JSONObject.NULL)
                // The drive's own running totals. LiveState is the single source of truth for
                // the drive in progress (MARKERS.md §6): the service sees every fix and every
                // mark, including while the WebView is dead, so the bar READS these rather
                // than rescanning the fix buffer and re-summing haversine on every tick.
                .put("drive_meters", s.driveMeters)
                .put("markers", s.markerCount)
                .put("last_marker_ts", s.lastMarkerTs ?: JSONObject.NULL)
                .put("speed_mph", s.speedMph ?: JSONObject.NULL)
                .put("obd_connected", s.obdConnected)
                .put("rpm", s.rpm ?: JSONObject.NULL)
                .put("throttle", s.throttle ?: JSONObject.NULL)
                .put("coolant_c", s.coolantC ?: JSONObject.NULL)
                .put("voltage", s.voltage ?: JSONObject.NULL)
                .put("updated_at", s.updatedAt)
                .toString()
        }.getOrDefault("{}")

        /** SPA-side diagnostics (uncaught JS errors, unhandled rejections) land in the same
         *  activity log a problem report attaches. ERROR/WARN show in the Log screen;
         *  anything else files as DEBUG so the visible trail stays coarse. */
        @JavascriptInterface
        fun logEvent(level: String, msg: String) {
            val lvl = when (level.uppercase(Locale.US)) {
                "ERROR" -> EventLog.Level.ERROR
                "WARN" -> EventLog.Level.WARN
                else -> EventLog.Level.DEBUG
            }
            EventLog.add(lvl, msg.take(600))
        }

        /** Settings → Report a problem: compose a pre-addressed email carrying the full
         *  diagnostic bundle. The user reviews and sends it themselves. */
        @JavascriptInterface
        fun reportProblem(note: String) {
            ui.post { sendProblemReport(note) }
        }
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

    // ---- misc ----

    private fun toast(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

}
