package org.jupiterns.drivetime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app self-update. The sideload loop (push code → CI builds a signed APK → put it on
 * the server → install on the phone) loses its slowest step here: the app itself checks
 * the server for a newer build and hands it to the system installer, so a new version is
 * a two-tap "Update" instead of a browser download + file hunt.
 *
 * How it hangs together:
 *  - The server publishes `GET {serverUrl}/dl/version.json` — `{versionCode, versionName,
 *    apk, notes}` — next to the APK (both under the public `/dl` path, no auth). The host
 *    helper `publish-apk.sh` writes it from the latest CI build (see the android README).
 *  - This build knows its own [BuildConfig.VERSION_CODE] (= the CI run number). If the
 *    server advertises a higher one, we prompt, download the APK to app-private external
 *    storage, and fire an `ACTION_VIEW` install intent via [FileProvider].
 *  - Every build is signed with the one committed key, so the install lands *in place* and
 *    keeps settings — the whole reason this is safe to automate.
 *
 * Because every APK is signed with the same key, this only ever offers a same-signature
 * upgrade; the OS still shows its own install confirmation.
 */
object Updater {

    /** Parsed `version.json`. [apk] is a filename or URL resolved against `/dl/`. */
    data class Release(val versionCode: Int, val versionName: String, val apk: String, val notes: String?)

    /** Don't re-check on every foreground; a few hours is plenty for a sideload app. */
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Pure decision — is [latest] newer than what's installed? Kept side-effect-free so
     *  it's unit-testable without Android. A missing/older manifest never offers a
     *  "downgrade". */
    fun isNewer(installedCode: Int, latest: Release): Boolean = latest.versionCode > installedCode

    /** Parse a `version.json` body; null if it's malformed or missing the version code. */
    fun parse(body: String): Release? = runCatching {
        val o = JSONObject(body)
        val code = o.optInt("versionCode", -1)
        if (code < 0) return null
        Release(
            versionCode = code,
            versionName = o.optString("versionName", code.toString()),
            apk = o.optString("apk", "drivetime.apk"),
            notes = o.optString("notes", "").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Absolute download URL for [r]'s APK — its `apk` field may already be absolute. */
    private fun apkUrl(serverUrl: String, r: Release): String =
        if (r.apk.startsWith("http")) r.apk else "$serverUrl/dl/${r.apk.trimStart('/')}"

    /** Foreground-triggered check. [interactive] = the user tapped "Check for updates", so
     *  we also report "up to date"/errors; the automatic path stays silent unless there's
     *  actually an update. Throttled unless [force]. */
    fun checkFromUi(activity: Activity, interactive: Boolean) {
        val settings = Settings(activity)
        val serverUrl = settings.serverUrl
        if (serverUrl.isBlank()) { if (interactive) toast(activity, "Set a server URL first"); return }
        if (!interactive &&
            System.currentTimeMillis() - settings.lastUpdateCheckAt < CHECK_INTERVAL_MS) return

        Thread {
            val release = runCatching { fetch(serverUrl) }.getOrNull()
            settings.lastUpdateCheckAt = System.currentTimeMillis()
            if (activity.isFinishing) return@Thread
            activity.runOnUiThread {
                when {
                    release == null ->
                        if (interactive) toast(activity, "Couldn't reach the update server")
                    isNewer(BuildConfig.VERSION_CODE, release) ->
                        promptInstall(activity, serverUrl, release)
                    interactive ->
                        toast(activity, "You're on the latest version (${BuildConfig.VERSION_NAME})")
                }
            }
        }.start()
    }

    private fun fetch(serverUrl: String): Release? {
        val req = Request.Builder().url("$serverUrl/dl/version.json").build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else parse(resp.body?.string().orEmpty())
        }
    }

    private fun promptInstall(activity: Activity, serverUrl: String, r: Release) {
        val body = buildString {
            append("Version ${r.versionName} is available (you have ${BuildConfig.VERSION_NAME}).")
            r.notes?.let { append("\n\n").append(it) }
        }
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(body)
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(activity, serverUrl, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, serverUrl: String, r: Release) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading update")
            .setMessage("Starting…")
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            val result = runCatching { download(activity, apkUrl(serverUrl, r)) { pct ->
                if (!activity.isFinishing) activity.runOnUiThread {
                    progress.setMessage(if (pct >= 0) "$pct%" else "Downloading…")
                }
            } }
            if (activity.isFinishing) return@Thread
            activity.runOnUiThread {
                progress.dismiss()
                result.onSuccess { file -> install(activity, file) }
                    .onFailure {
                        EventLog.warn("Update download failed: ${it.message}")
                        toast(activity, "Update download failed: ${it.message ?: "network error"}")
                    }
            }
        }.start()
    }

    /** Stream the APK to app-private external storage (shareable with the installer via
     *  [FileProvider]), reporting integer percent when the server sends a length. */
    private fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val out = File(dir, "drivetime.apk")
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val bodyStream = resp.body?.byteStream() ?: throw java.io.IOException("empty body")
            val total = resp.body?.contentLength() ?: -1L
            bodyStream.use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } >= 0) {
                        sink.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        } else onProgress(-1)
                    }
                }
            }
        }
        return out
    }

    private fun install(activity: Activity, file: File) {
        // API 26+ gates sideloading behind a per-app "install unknown apps" grant; bounce
        // the user there once if it isn't granted, then they retry Update.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "Allow drivetime to install apps, then tap Update again")
            runCatching {
                activity.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, "Couldn't open the installer: ${it.message}") }
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
