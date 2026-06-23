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
        if (s.loggingEnabled && s.isConfigured && !LocationService.isRunning) {
            try {
                val svc = Intent(applicationContext, LocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    applicationContext.startForegroundService(svc)
                else
                    applicationContext.startService(svc)
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
