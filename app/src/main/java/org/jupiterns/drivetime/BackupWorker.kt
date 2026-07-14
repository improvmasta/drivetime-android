package org.jupiterns.drivetime

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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

        val attempted = results.isNotEmpty()
        if (!attempted) {
            allOk = false
            results += "no destination configured"
        }
        s.backupLastAt = System.currentTimeMillis()
        s.backupLastOk = allOk
        s.backupLastResult = results.joinToString(" · ")
        EventLog.info("Backup: ${s.backupLastResult}")

        // A backup that fails keeps failing — the folder grant was revoked, the Drive token was
        // pulled — and it fails *invisibly*: nothing but the Settings card knows, and the user
        // has no reason to open it. Weeks of "backed up" that never happened is exactly the
        // state a backup exists to prevent, so after [FAIL_STREAK] consecutive automatic runs,
        // say so. Not on the first failure: a folder on an unmounted SD card or a token
        // mid-refresh recovers on its own, and a notification for that is a false alarm.
        // "no destination configured" is not a failure — nothing was promised.
        if (attempted) {
            val manual = inputData.getBoolean(KEY_MANUAL, false)
            if (allOk) {
                if (s.backupFailStreak != 0) {
                    EventLog.info("Backup recovered after ${s.backupFailStreak} failure(s)")
                    s.backupFailStreak = 0
                }
                // Producer-retracted (NOTIFICATIONS.md #6): the SPA's attention push knows
                // nothing about a backup destination, so the only thing that can clear this
                // warning is a run that worked.
                Notify.cancel(ctx, Notify.KIND_BACKUP_HEALTH, Notify.HEALTH_ID)
            } else if (!manual) {
                // A manual run reports itself on the Settings card the user is looking at, so
                // it neither nags nor counts — but its *success* above still clears the streak.
                s.backupFailStreak += 1
                if (s.backupFailStreak >= FAIL_STREAK) notifyFailing(ctx, s)
            }
        }
        return Result.success()
    }

    private fun notifyFailing(ctx: Context, s: Settings) {
        val n = s.backupFailStreak
        Notify.post(
            ctx, Notify.KIND_BACKUP_HEALTH, Notify.HEALTH_ID,
            "Backups are failing",
            "The last $n backups didn't finish (${s.backupLastResult}). Your drives are not " +
                "being backed up — open Settings to re-pick the folder or reconnect Drive.",
            "/settings",
        )
    }

    /**
     * Write the archive under a temp name and rename it into place only once the last byte is
     * out. A backup is a stream of tens of MB to removable/networked storage: the write dies
     * partway (card pulled, provider killed, disk full) often enough to matter, and the
     * truncated file it left behind was *named like a good archive and sorted newest* — so
     * retention counted it as one of the N kept and deleted a real archive to make room. Enough
     * failures in a row and every archive in the folder is a corpse. See [BackupStore.tempName]
     * for why the temp-ness lives in the prefix.
     */
    private fun writeToFolder(ctx: Context, s: Settings, tree: Uri, name: String) {
        val dir = DocumentFile.fromTreeUri(ctx, tree)
            ?: throw IllegalStateException("folder unavailable")
        if (!dir.canWrite()) throw IllegalStateException("folder permission lost — re-pick it")
        val tmp = dir.createFile(BackupStore.ARCHIVE_MIME, BackupStore.tempName(name))
            ?: throw IllegalStateException("couldn't create file")
        try {
            ctx.contentResolver.openOutputStream(tmp.uri, "w")?.use { out ->
                BackupStore.writeArchive(ctx, s, out)
            } ?: throw IllegalStateException("couldn't open file")
            if (!tmp.renameTo(name)) throw IllegalStateException("couldn't finalize the archive")
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
        // Retention: newest N by name (names sort chronologically), plus any temp file a
        // previous run died holding.
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
        /** Set on the run the user asked for by hand (Settings → Back up now): it reports
         *  itself on screen, so it neither nags nor feeds the failure streak. */
        private const val KEY_MANUAL = "manual"
        /** Consecutive failed automatic runs before we say so. */
        const val FAIL_STREAK = 3

        /** An automatic run needs the network **only when Drive is a destination** — a SAF
         *  folder is local and backs up fine on a plane. Constraining it unconditionally would
         *  mean a folder-only user's weekly backup silently waits for a network it doesn't use;
         *  not constraining a Drive run means a momentary-offline weekly fire *fails* and skips
         *  the whole cycle, instead of deferring by an hour and succeeding. */
        private fun constraints(s: Settings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (s.backupDriveRefreshToken.isNotBlank()) NetworkType.CONNECTED
                    else NetworkType.NOT_REQUIRED
                )
                .build()

        /** (Re)arm the periodic work to match the schedule setting. UPDATE keeps the
         *  existing cadence anchor when only unrelated settings changed. Call it whenever the
         *  **destination set** changes too, not just the cadence: the network constraint above
         *  is derived from it, and UPDATE is what carries a changed constraint onto the
         *  already-enqueued work. */
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
                PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                    .setConstraints(constraints(s))
                    .build()
            )
        }

        /** Settings → Back up now. Deliberately unconstrained: the user is looking at the
         *  button, and "nothing happened, come back when you have signal" is worse than a
         *  Drive failure it can read on the card a second later. */
        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setInputData(Data.Builder().putBoolean(KEY_MANUAL, true).build())
                    .build()
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
                    .setConstraints(constraints(s))
                    .build()
            )
        }
    }
}
