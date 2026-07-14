package org.jupiterns.drivetime

import android.content.Context
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
            // We were *meant* to be logging and the service is dead → record a suspected OEM kill
            // so the dashboard can name the specific setting to fix.
            //
            // The gap is measured from the last proof the process was ALIVE ([Settings.lifeBeatAt]),
            // not from the last GPS fix. That distinction is the whole point of [Health], and it
            // fixes a real bug: fixes stop arriving when the car is parked, so a phone killed after
            // sitting in a driveway for three hours used to be reported as a three-hour outage.
            // A beat is written whether or not the car is moving, so the gap now names the outage
            // and nothing else. (The old `expectedMaxGap` guard existed to stop a sparse LIGHT-tier
            // cadence from reading as a kill; against a beat that no longer means anything.)
            val gap = System.currentTimeMillis() - s.lifeBeatAt
            if (s.lifeBeatAt > 0 && gap > KILL_THRESHOLD_MS) {
                s.lastKillDetectedAt = System.currentTimeMillis()
                EventLog.warn("Logger was killed for ~${gap / 60_000}min — likely OEM battery manager")
                // An interruption the user never learns about becomes a mystery hole in the
                // trip log weeks later. Say it now, once per incident (the Settings banner
                // carries the fix instructions; the notification just gets them there).
                if (s.lastKillDetectedAt > s.lastKillNotifiedAt) {
                    s.lastKillNotifiedAt = s.lastKillDetectedAt
                    notifyKill(gap / 60_000)
                }
            }
            s.lastCommandSource = "watchdog"
            if (Control.startTrackingService(applicationContext)) {
                // The restart lands in LocationService.onCreate → Health.startLife, which writes
                // the durable `down` row for the outage we just measured. It won't double-notify:
                // `lastKillNotifiedAt` now sits inside the outage, which is how it knows we spoke.
                EventLog.warn("Watchdog restarted the logging service")
            } else {
                // Background FGS-start was refused (throttled / no exemption yet) —
                // try again next cycle rather than giving up.
                return Result.retry()
            }
        } else if (LocationService.isRunning) {
            // The service is alive. This periodic job is the one tick Doze cannot defer
            // indefinitely, so it is the FLOOR under the heartbeat: a phone asleep in a pocket
            // with no fixes and nothing to upload still proves it is alive every ~15 minutes,
            // which bounds how far wrong the death timestamp above can ever be.
            runCatching { Health.beat(applicationContext, s) }
        }
        // Drain any backlog regardless of whether we just restarted the service.
        runCatching { Uploader(applicationContext, s).flush() }
        return Result.success()
    }

    /**
     * Say it, once per episode, through [Notify] — which gives this the same channel group,
     * toggle ([Settings.notifyTrackingHealth]) and deep link every other notification has,
     * instead of the ad-hoc channel it used to build for itself.
     *
     * The route matters: the notification's whole job is to get the user to the setting that
     * stops it happening again, and the Settings → Tracking tab is where the OEM-battery
     * banner and its fix instructions live. Acknowledging it there cancels this (the bridge's
     * `dismissKillWarning`), so the shade agrees with the app.
     */
    private fun notifyKill(minutes: Long) {
        Notify.post(
            applicationContext, Notify.KIND_TRACKING_HEALTH, Notify.HEALTH_ID,
            "Drive tracking was interrupted",
            "No GPS was recorded for ~$minutes minutes — your phone likely stopped the " +
                "tracker to save battery, and any drives in that window are missing. " +
                "Open Settings → Access & battery to keep it running.",
            "/settings",
        )
    }

    companion object {
        private const val WORK = "drivetime-watchdog"

        /** Below this gap, a missing-service window is "OK, you swiped or rebooted"; above it,
         *  treat it as a silent kill that warrants the OEM warning. 20 min comfortably exceeds
         *  this job's own 15-min period, which is the slowest the heartbeat it reads can beat. */
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
