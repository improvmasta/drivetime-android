package org.jupiterns.drivetime

import android.net.Uri
import android.util.Base64
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google Drive for backups (BACKUP.md), with no Google SDK: OAuth 2.0 PKCE for installed
 * apps + the four REST calls we need (ensure folder, resumable upload, list, delete).
 *
 * The app ships no Google credential. The user creates an *Android-type* OAuth client in
 * their own (free) Google Cloud project — package name + the APK signing SHA-1 — and
 * pastes its client ID into Settings. Android clients have no secret; PKCE carries the
 * proof. Scope is `drive.file`: the app sees ONLY the files it created, and the backups
 * land in a visible "Drivetime Backups" folder the user can browse on drive.google.com.
 */
object DriveAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file openid email"

    /** The custom-scheme redirect for [clientId]. Google requires the scheme to be the
     *  REVERSED client id (`com.googleusercontent.apps.<id>:/oauth2redirect`) — a
     *  package-name scheme comes back as redirect_uri_mismatch. OAuthRedirectActivity
     *  must carry a matching intent-filter (the built-in client's is in the manifest;
     *  a fork with its own client id edits that filter too — BACKUP.md). */
    fun redirectFor(clientId: String): String =
        "com.googleusercontent.apps." +
            clientId.removeSuffix(".apps.googleusercontent.com") +
            ":/oauth2redirect"

    /** The verifier for the flow in flight. One flow at a time is plenty. */
    @Volatile private var verifier: String = ""

    /** Build the consent URL and remember the PKCE verifier for [finishAuth]. */
    fun beginAuthUrl(clientId: String): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        verifier = b64url(bytes)
        val challenge = b64url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=" + Uri.encode(clientId) +
            "&redirect_uri=" + Uri.encode(redirectFor(clientId)) +
            "&response_type=code" +
            "&scope=" + Uri.encode(SCOPE) +
            "&code_challenge=" + challenge +
            "&code_challenge_method=S256" +
            "&prompt=consent"   // always mint a refresh token, even on a re-connect
    }

    /** Exchange the redirect's code for tokens; fills [settings]. Null on success, else
     *  a short human-readable error. */
    fun finishAuth(settings: Settings, code: String): String? {
        if (verifier.isBlank()) return "no sign-in in progress — tap Connect again"
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", settings.driveClientId)
            .add("redirect_uri", redirectFor(settings.driveClientId))
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        return runCatching {
            Http.client.newCall(req).execute().use { rsp ->
                val text = rsp.body?.string() ?: ""
                if (!rsp.isSuccessful) return@runCatching "token exchange HTTP ${rsp.code}: ${errOf(text)}"
                val o = JSONObject(text)
                val refresh = o.optString("refresh_token")
                if (refresh.isBlank()) return@runCatching "Google returned no refresh token"
                settings.backupDriveRefreshToken = refresh
                settings.backupDriveAccessToken = o.optString("access_token")
                settings.backupDriveTokenExpiry =
                    System.currentTimeMillis() + o.optLong("expires_in", 0) * 1000L
                settings.backupDriveAccount = emailFromIdToken(o.optString("id_token"))
                settings.backupDriveFolderId = ""   // re-derive under the new account
                verifier = ""
                null
            }
        }.getOrElse { "token exchange failed: ${it.message}" }
    }

    /** The `email` claim out of an id_token JWT — payload is just base64url JSON. */
    fun emailFromIdToken(idToken: String?): String {
        val parts = idToken?.split(".") ?: return ""
        if (parts.size < 2) return ""
        return runCatching {
            JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE), Charsets.UTF_8))
                .optString("email")
        }.getOrDefault("")
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun errOf(text: String): String =
        runCatching { JSONObject(text).optString("error") }.getOrDefault("").ifBlank { text.take(120) }
}

class DriveClient(private val settings: Settings) {

    private val json = "application/json; charset=utf-8".toMediaType()

    /** A valid access token, refreshed when within 2 min of expiry. A revoked grant
     *  (`invalid_grant`) disconnects cleanly so the Settings card says so. */
    private fun token(): String {
        val cached = settings.backupDriveAccessToken
        if (cached.isNotBlank() &&
            System.currentTimeMillis() < settings.backupDriveTokenExpiry - 120_000L) return cached
        val refresh = settings.backupDriveRefreshToken
        if (refresh.isBlank()) throw IOException("Google Drive not connected")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", settings.driveClientId)
            .build()
        val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        return Http.client.newCall(req).execute().use { rsp ->
            val text = rsp.body?.string() ?: ""
            if (!rsp.isSuccessful) {
                if (text.contains("invalid_grant")) {
                    settings.backupDriveRefreshToken = ""
                    settings.backupDriveAccessToken = ""
                    throw IOException("Drive access was revoked — reconnect in Settings")
                }
                throw IOException("Drive token refresh HTTP ${rsp.code}")
            }
            val o = JSONObject(text)
            settings.backupDriveAccessToken = o.optString("access_token")
            settings.backupDriveTokenExpiry =
                System.currentTimeMillis() + o.optLong("expires_in", 0) * 1000L
            settings.backupDriveAccessToken
        }
    }

    private fun authed(b: Request.Builder) = b.header("Authorization", "Bearer ${token()}").build()

    /** The "Drivetime Backups" folder id — cached, found, or created. */
    fun ensureFolder(): String {
        settings.backupDriveFolderId.takeIf { it.isNotBlank() }?.let { return it }
        val q = Uri.encode(
            "name = 'Drivetime Backups' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
        val list = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name)&pageSize=1")
            .get()
        Http.client.newCall(authed(list)).execute().use { rsp ->
            if (rsp.isSuccessful) {
                val files = JSONObject(rsp.body?.string() ?: "{}").optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val id = files.getJSONObject(0).optString("id")
                    if (id.isNotBlank()) { settings.backupDriveFolderId = id; return id }
                }
            } else if (rsp.code == 401) throw IOException("Drive auth failed")
        }
        val create = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .post(JSONObject()
                .put("name", "Drivetime Backups")
                .put("mimeType", "application/vnd.google-apps.folder")
                .toString().toRequestBody(json))
        return Http.client.newCall(authed(create)).execute().use { rsp ->
            if (!rsp.isSuccessful) throw IOException("Drive folder create HTTP ${rsp.code}")
            val id = JSONObject(rsp.body?.string() ?: "{}").optString("id")
            if (id.isBlank()) throw IOException("Drive folder create returned no id")
            settings.backupDriveFolderId = id
            id
        }
    }

    /** Resumable upload (init → single PUT). Multipart caps at 5 MB; archives run bigger. */
    fun upload(file: File, name: String) {
        var folder = ensureFolder()
        var location = initUpload(folder, name, file.length())
        if (location == null) {
            // Folder id can go stale (user deleted it on drive.google.com) — re-derive once.
            settings.backupDriveFolderId = ""
            folder = ensureFolder()
            location = initUpload(folder, name, file.length())
                ?: throw IOException("Drive upload init failed")
        }
        val put = Request.Builder()
            .url(location)
            .put(file.asRequestBody(BackupStore.ARCHIVE_MIME.toMediaType()))
            .build()
        Http.client.newCall(put).execute().use { rsp ->
            if (!rsp.isSuccessful) throw IOException("Drive upload HTTP ${rsp.code}")
        }
    }

    /** The resumable-session URL, or null when the parent folder id was rejected. */
    private fun initUpload(folderId: String, name: String, length: Long): String? {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId))
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            .header("X-Upload-Content-Type", BackupStore.ARCHIVE_MIME)
            .header("X-Upload-Content-Length", length.toString())
            .post(meta.toString().toRequestBody(json))
        return Http.client.newCall(authed(req)).execute().use { rsp ->
            if (rsp.code == 404) return@use null
            if (!rsp.isSuccessful) throw IOException("Drive upload init HTTP ${rsp.code}")
            rsp.header("Location") ?: throw IOException("Drive upload init: no session URL")
        }
    }

    /** Backup archives in the folder as (id, name), newest name first. */
    fun listBackups(): List<Pair<String, String>> {
        val folder = ensureFolder()
        val q = Uri.encode("'$folder' in parents and trashed = false")
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name)&pageSize=200")
            .get()
        return Http.client.newCall(authed(req)).execute().use { rsp ->
            if (!rsp.isSuccessful) throw IOException("Drive list HTTP ${rsp.code}")
            val files = JSONObject(rsp.body?.string() ?: "{}").optJSONArray("files")
                ?: return@use emptyList()
            val out = ArrayList<Pair<String, String>>(files.length())
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += f.optString("id") to f.optString("name")
            }
            out.sortedByDescending { it.second }
        }
    }

    /** Apply retention: keep the newest [keep] archives, trash-free delete the rest. */
    fun prune(keep: Int) {
        val byName = listBackups().associate { it.second to it.first }
        for (doomed in BackupStore.namesToPrune(byName.keys.toList(), keep)) {
            val id = byName[doomed] ?: continue
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$id")
                .delete()
            Http.client.newCall(authed(req)).execute().use { rsp ->
                if (!rsp.isSuccessful && rsp.code != 404) {
                    EventLog.warn("Drive prune of $doomed failed: HTTP ${rsp.code}")
                }
            }
        }
    }
}
