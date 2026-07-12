package org.jupiterns.drivetime

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Writes one backup archive to every configured destination — the SAF folder and/or
 * Google Drive — then prunes each to the newest [Settings.backupKeep] (BACKUP.md).
 *
 * Runs three ways, all funnelling through the same doWork():
 *  - periodic, daily or weekly (schedule setting; WorkManager persists it across reboots)
 *  - once, a few minutes after a drive ends ("after each drive" schedule)
 *  - once, immediately (Settings → Back up now — the SPA pushes a fresh snapshot first)
 *
 * The archive's app data is the snapshot the SPA last pushed, but the shell's own fix
 * ring rides along at full freshness — so a drive logged after the app was last opened
 * is still in the backup (BackupStore.writeArchive). Failures never Result.retry():
 * a retried run would mint a second same-day archive on the destination that DID
 * succeed, and the next scheduled run is the retry. The result lands in
 * [Settings.backupLastResult] for the Settings card.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val s = Settings(ctx)
        val name = BackupStore.archiveName(System.currentTimeMillis())
        val results = mutableListOf<String>()
        var allOk = true

        if (!BackupStore.hasSnapshot(ctx)) {
            // First run before the SPA ever pushed: the archive would hold settings +
            // raw fixes but no drive history. Still worth writing — say so instead.
            EventLog.warn("Backup running with no app-data snapshot yet (open the app once)")
        }

        val folderUri = s.backupFolderUri
        if (folderUri.isNotBlank()) {
            runCatching { writeToFolder(ctx, s, Uri.parse(folderUri), name) }
                .onSuccess { results += "folder ✓" }
                .onFailure {
                    allOk = false
                    results += "folder ✕ (${it.message?.take(80)})"
                    EventLog.warn("Backup to folder failed: ${it.message}")
                }
        }

        if (s.backupDriveRefreshToken.isNotBlank()) {
            runCatching { writeToDrive(ctx, s, name) }
                .onSuccess { results += "Drive ✓" }
                .onFailure {
                    allOk = false
                    results += "Drive ✕ (${it.message?.take(80)})"
                    EventLog.warn("Backup to Drive failed: ${it.message}")
                }
        }

        if (results.isEmpty()) {
            allOk = false
            results += "no destination configured"
        }
        s.backupLastAt = System.currentTimeMillis()
        s.backupLastOk = allOk
        s.backupLastResult = results.joinToString(" · ")
        EventLog.info("Backup: ${s.backupLastResult}")
        return Result.success()
    }

    private fun writeToFolder(ctx: Context, s: Settings, tree: Uri, name: String) {
        val dir = DocumentFile.fromTreeUri(ctx, tree)
            ?: throw IllegalStateException("folder unavailable")
        if (!dir.canWrite()) throw IllegalStateException("folder permission lost — re-pick it")
        val f = dir.createFile(BackupStore.ARCHIVE_MIME, name)
            ?: throw IllegalStateException("couldn't create file")
        ctx.contentResolver.openOutputStream(f.uri, "w")?.use { out ->
            BackupStore.writeArchive(ctx, s, out)
        } ?: throw IllegalStateException("couldn't open file")
        // Retention: newest N by name (names sort chronologically).
        val children = dir.listFiles().mapNotNull { it.name }
        for (doomed in BackupStore.namesToPrune(children, s.backupKeep)) {
            dir.listFiles().firstOrNull { it.name == doomed }?.delete()
        }
    }

    private fun writeToDrive(ctx: Context, s: Settings, name: String) {
        // Drive's resumable upload wants a sized, replayable body — build to cache first.
        val tmp = File(ctx.cacheDir, name)
        try {
            tmp.outputStream().use { BackupStore.writeArchive(ctx, s, it) }
            val drive = DriveClient(s)
            drive.upload(tmp, name)
            drive.prune(s.backupKeep)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val WORK_PERIODIC = "drivetime-backup"
        private const val WORK_ONCE = "drivetime-backup-once"
        /** Post-drive debounce: past the 5-min stationary-stop window, so a light-to-red
         *  errand resume has already re-entered DRIVING instead of double-firing. */
        private const val AFTER_DRIVE_DELAY_MIN = 10L

        /** (Re)arm the periodic work to match the schedule setting. UPDATE keeps the
         *  existing cadence anchor when only unrelated settings changed. */
        fun reschedule(ctx: Context, s: Settings) {
            val wm = WorkManager.getInstance(ctx)
            val days = when (s.backupSchedule) {
                "daily" -> 1L
                "weekly" -> 7L
                else -> {
                    wm.cancelUniqueWork(WORK_PERIODIC)
                    return
                }
            }
            wm.enqueueUniquePeriodicWork(
                WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS).build()
            )
        }

        /** Settings → Back up now. */
        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().build()
            )
        }

        /** A drive just ended — on the "after each drive" schedule, back up shortly.
         *  REPLACE restarts the countdown if another drive ends inside the delay. */
        fun afterDrive(ctx: Context, s: Settings) {
            if (s.backupSchedule != "drive") return
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setInitialDelay(AFTER_DRIVE_DELAY_MIN, TimeUnit.MINUTES)
                    .build()
            )
        }
    }
}
