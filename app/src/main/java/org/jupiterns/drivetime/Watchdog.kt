package org.jupiterns.drivetime

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Self-healing safety net for the #1 rule: *never silently stop logging.*
 *
 * START_STICKY and the boot receiver cover the common cases, but an OEM battery
 * killer or a swiped-away task can take down the whole process without either
 * firing. This periodic job reconciles **should-be-logging** (`loggingEnabled`)
 * against **is-logging** ([LocationService.isRunning]) and relaunches the service
 * if the OS killed it. WorkManager re-spawns the process to run the job, so the
 * check happens even after a full process death.
 *
 * It also nudges the upload queue, so a backlog that built up while the service
 * was down starts draining as soon as the watchdog runs.
 *
 * 15 min is WorkManager's minimum period — fine for a backstop; the live paths
 * (STICKY, boot) handle the fast cases.
 */
class Watchdog(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val s = Settings(applicationContext)
        val ready = Permissions.snapshot(applicationContext, s).isReady
        // Server-optional (STANDALONE.md): reconcile against loggingEnabled alone, so a
        // standalone logger the OEM killer took down is restarted just like a synced one.
        if (s.loggingEnabled && ready && !LocationService.isRunning) {
            // We were *meant* to be logging and the service is dead → record a
            // suspected OEM kill so the dashboard can name the specific setting to
            // fix. We only count this if the gap exceeds the expected sample cadence
            // by a wide margin — a real cold start (no prior fix) doesn't count.
            val gap = System.currentTimeMillis() - s.lastFixAt
            val expectedMaxGap = (s.lightIntervalSec.coerceAtLeast(30) * 1000L) * 6
            if (s.lastFixAt > 0 && gap > KILL_THRESHOLD_MS && gap > expectedMaxGap) {
                s.lastKillDetectedAt = System.currentTimeMillis()
                EventLog.warn("Logger was killed for ~${gap / 60_000}min — likely OEM battery manager")
            }
            try {
                val svc = Intent(applicationContext, LocationService::class.java)
                s.lastCommandSource = "watchdog"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    applicationContext.startForegroundService(svc)
                else
                    applicationContext.startService(svc)
                EventLog.warn("Watchdog restarted the logging service")
            } catch (e: Exception) {
                // Background FGS-start was refused (throttled / no exemption yet) —
                // try again next cycle rather than giving up.
                return Result.retry()
            }
        }
        // Drain any backlog regardless of whether we just restarted the service.
        runCatching { Uploader(applicationContext, s).flush() }
        return Result.success()
    }

    companion object {
        private const val WORK = "drivetime-watchdog"

        /** Below this gap, a missing-service window is "OK, you swiped or rebooted";
         *  above it, treat it as a silent kill that warrants the OEM warning. 20 min
         *  comfortably exceeds the 15-min watchdog cadence + first-fix latency. */
        private const val KILL_THRESHOLD_MS = 20L * 60_000L

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Watchdog>(15, TimeUnit.MINUTES).build())
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        }
    }
}
