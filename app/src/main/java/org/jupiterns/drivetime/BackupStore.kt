package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The full-data backup pipeline's storage half (BACKUP.md).
 *
 * The SPA owns the app data (IndexedDB — a LevelDB directory nothing outside the WebView
 * can safely read), so it pushes a complete JSON *snapshot* over the bridge in chunks;
 * we keep the latest one gzipped in filesDir. An *archive* is one portable .zip wrapping
 * that snapshot + the tracker settings + the shell's own durable fix/marker buffers — so
 * a backup taken by a background worker still carries any drive logged since the app was
 * last opened, even though the snapshot itself is only as fresh as that last open.
 *
 * Restore is the reverse: settings + buffers are applied natively, and the app-data JSON
 * is *staged* at filesDir/restore/staged.json — served to the SPA at /restore/staged.json
 * by an InternalStoragePathHandler (a 40 MB document can't ride a bridge return) — then
 * the SPA imports it and reloads.
 */
object BackupStore {

    const val ARCHIVE_PREFIX = "drivetime-backup-"
    const val ARCHIVE_SUFFIX = ".zip"
    const val ARCHIVE_MIME = "application/zip"

    private fun dir(context: Context) = File(context.filesDir, "backup").apply { mkdirs() }
    fun snapshotFile(context: Context) = File(dir(context), "snapshot.json.gz")
    private fun snapshotTmp(context: Context) = File(dir(context), "snapshot.tmp.gz")
    fun restoreDir(context: Context) = File(context.filesDir, "restore").apply { mkdirs() }
    fun stagedFile(context: Context) = File(restoreDir(context), "staged.json")

    // ---- snapshot sink (bridge-chunked; single writer, calls arrive on a binder thread) ----

    private var writer: Writer? = null

    @Synchronized
    fun beginSnapshot(context: Context): Boolean = runCatching {
        writer?.close()
        val tmp = snapshotTmp(context)
        tmp.delete()
        writer = OutputStreamWriter(GZIPOutputStream(tmp.outputStream()), Charsets.UTF_8)
        true
    }.getOrElse { EventLog.warn("Backup snapshot begin failed: ${it.message}"); false }

    @Synchronized
    fun appendChunk(chunk: String): Boolean = runCatching {
        (writer ?: return false).write(chunk)
        true
    }.getOrElse { EventLog.warn("Backup snapshot chunk failed: ${it.message}"); false }

    /** Close + atomically publish the snapshot; stamps [Settings.backupSnapshotAt]. */
    @Synchronized
    fun endSnapshot(context: Context, settings: Settings, createdAtMs: Long): Boolean = runCatching {
        val w = writer ?: return false
        writer = null
        w.close()
        val tmp = snapshotTmp(context)
        if (!tmp.exists() || tmp.length() == 0L) return false
        if (!tmp.renameTo(snapshotFile(context))) return false
        settings.backupSnapshotAt = if (createdAtMs > 0) createdAtMs else System.currentTimeMillis()
        EventLog.debug("Backup snapshot refreshed (${snapshotFile(context).length() / 1024} KB gz)")
        true
    }.getOrElse { EventLog.warn("Backup snapshot end failed: ${it.message}"); false }

    fun hasSnapshot(context: Context) = snapshotFile(context).exists()

    // ---- archive naming / retention (pure where possible; see BackupStoreTest) ----

    /** Sortable local-time name — lexicographic order IS chronological, so retention
     *  prunes by name alone. */
    fun archiveName(atMs: Long): String =
        ARCHIVE_PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(atMs)) + ARCHIVE_SUFFIX

    fun isArchiveName(name: String?): Boolean =
        name != null && name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX)

    /** Which of [names] retention deletes when keeping the newest [keep] archives.
     *  Non-archive names are never candidates — a stray user file in the folder is safe. */
    fun namesToPrune(names: List<String>, keep: Int): List<String> =
        names.filter { isArchiveName(it) }.sortedDescending().drop(keep.coerceAtLeast(1))

    // ---- archive build ----

    /**
     * Stream a complete archive to [out] (closes it). Entries:
     *   manifest.json         — schema/kind/times/app version
     *   app.json.gz           — the SPA data snapshot, as pushed (absent if never pushed)
     *   settings.json         — SettingsExport (tracker config + pairing)
     *   pending_fixes.jsonl   — the shell's durable GPS ring (fresher than the snapshot)
     *   pending_markers.jsonl — natively-stamped markers awaiting the SPA's drain
     */
    fun writeArchive(context: Context, settings: Settings, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                JSONObject()
                    .put("schema", 1)
                    .put("kind", "drivetime-backup")
                    .put("created_at", System.currentTimeMillis())
                    .put("snapshot_at", settings.backupSnapshotAt)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("app_version_code", BuildConfig.VERSION_CODE)
                    .toString(2).toByteArray()
            )
            zip.closeEntry()

            val snap = snapshotFile(context)
            if (snap.exists()) {
                zip.putNextEntry(ZipEntry("app.json.gz"))
                snap.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(SettingsExport.toJson(settings).toString(2).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("pending_fixes.jsonl"))
            WebFixBuffer.copyTo(context, zip)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("pending_markers.jsonl"))
            WebMarkerBuffer.copyTo(context, zip)
            zip.closeEntry()
        }
    }

    // ---- restore ----

    /** What a restore file turned out to hold. */
    data class RestoreResult(
        val kind: String,            // "archive" | "data" | "settings" | "unknown"
        val settingsApplied: Int,    // recognised settings keys applied
        val stagedAppData: Boolean,  // app data staged; caller must invoke the SPA hook
        val error: String? = null,
    )

    /** First bytes decide the format — extensions lie once a file has been mailed around. */
    fun sniff(head: ByteArray): String = when {
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> "zip"
        head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> "gzip"
        head.isNotEmpty() && (head[0] == '{'.code.toByte() || head[0] == 0xef.toByte()) -> "json"
        else -> "unknown"
    }

    /**
     * Restore from any supported file: a full .zip archive, a bare data snapshot
     * (.json / .json.gz), or a legacy settings-only .json. Native state (settings,
     * fix/marker buffers) is applied here; app data is staged for the SPA.
     */
    fun restore(context: Context, settings: Settings, input: InputStream): RestoreResult {
        val buf = BufferedInputStream(input)
        buf.mark(4)
        val head = ByteArray(2)
        val n = buf.read(head)
        buf.reset()
        return when (sniff(if (n > 0) head.copyOf(n) else ByteArray(0))) {
            "zip" -> restoreArchive(context, settings, buf)
            "gzip" -> restoreJson(context, settings, GZIPInputStream(buf))
            "json" -> restoreJson(context, settings, buf)
            else -> RestoreResult("unknown", 0, false, "not a drivetime backup file")
        }
    }

    private fun restoreArchive(context: Context, settings: Settings, input: InputStream): RestoreResult {
        var applied = 0
        var staged = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    // ZipInputStream EOFs at the entry boundary, so entry-scoped reads are safe.
                    "settings.json" ->
                        applied = SettingsExport.fromJson(context, settings, zip.readBytes().toString(Charsets.UTF_8))
                    "app.json.gz" -> { stage(context, GZIPInputStream(zip)); staged = true }
                    "app.json" -> { stage(context, zip); staged = true }
                    "pending_fixes.jsonl" -> WebFixBuffer.replaceAll(context, zip)
                    "pending_markers.jsonl" -> WebMarkerBuffer.replaceAll(context, zip)
                }
            }
        }
        // The archive may have changed the backup schedule itself — re-arm the worker.
        runCatching { BackupWorker.reschedule(context, settings) }
        EventLog.info("Backup archive restored: $applied settings, appData=$staged")
        return RestoreResult("archive", applied, staged)
    }

    /** A bare JSON file: the SPA data snapshot (kind=drivetime-data) or a legacy
     *  settings export. Peeking at the head keeps a 40 MB snapshot off the heap. */
    private fun restoreJson(context: Context, settings: Settings, input: InputStream): RestoreResult {
        val buf = BufferedInputStream(input)
        buf.mark(512)
        val head = ByteArray(256)
        val n = buf.read(head)
        buf.reset()
        val headText = String(head, 0, n.coerceAtLeast(0), Charsets.UTF_8)
        return if (headText.contains("\"drivetime-data\"")) {
            stage(context, buf)
            EventLog.info("Backup data snapshot staged for restore")
            RestoreResult("data", 0, true)
        } else {
            val applied = SettingsExport.fromJson(context, settings, buf.readBytes().toString(Charsets.UTF_8))
            runCatching { BackupWorker.reschedule(context, settings) }
            EventLog.info("Settings file restored: $applied keys")
            if (applied == 0) RestoreResult("unknown", 0, false, "no recognised keys")
            else RestoreResult("settings", applied, false)
        }
    }

    private fun stage(context: Context, input: InputStream) {
        val f = stagedFile(context)
        f.outputStream().use { input.copyTo(it) }
    }

    fun clearStaged(context: Context) {
        runCatching { stagedFile(context).delete() }
    }
}
