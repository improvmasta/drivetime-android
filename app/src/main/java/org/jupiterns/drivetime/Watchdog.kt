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

    /** All of the work is in [reconcile]; this shell only turns its answer into a [Result]. The
     *  split is what makes the net testable: a [Worker] can only be constructed with a
     *  `WorkerParameters`, which is `@RestrictTo` — so as long as the logic lived *here*, the one
     *  piece of code whose entire job is to notice that logging has silently stopped was itself
     *  the least reachable code in the app. */
    override fun doWork(): Result =
        if (reconcile(applicationContext, LocationService.isRunning)) Result.success()
        else Result.retry()

    companion object {
        private const val WORK = "drivetime-watchdog"

        /** Below this gap, a missing-service window is "OK, you swiped or rebooted"; above it,
         *  treat it as a silent kill that warrants the OEM warning. 20 min comfortably exceeds
         *  this job's own 15-min period, which is the slowest the heartbeat it reads can beat. */
        private const val KILL_THRESHOLD_MS = 20L * 60_000L

        /**
         * The watchdog's actual job: reconcile "we are meant to be logging" against [running],
         * and put the two back in agreement.
         *
         * Returns false **only** when an FGS start was attempted and *refused* (throttled, no
         * battery exemption yet) — which the worker turns into a retry so we try again next
         * cycle instead of giving up. Everything else, "there was nothing to do" included, is a
         * success.
         */
        fun reconcile(context: Context, running: Boolean): Boolean {
            val s = Settings(context)
            val ready = Permissions.snapshot(context, s).isReady
            // Server-optional (STANDALONE.md): reconcile against loggingEnabled alone, so a
            // standalone logger the OEM killer took down is restarted just like a synced one.
            //
            // Note the three ways this deliberately does nothing. Logging is OFF — the user
            // stopped it, and a watchdog that resurrects a tracker the user switched off is a
            // worse bug than the one this class exists to fix. The prerequisites aren't met — a
            // revoked location permission is not something a background restart can repair, and
            // retrying into it would pin WorkManager's backoff and burn the wake-ups we need for
            // the real case. Or the service is simply alive, which is the overwhelmingly common
            // outcome and must stay cheap.
            if (s.loggingEnabled && ready && !running) {
                // We were *meant* to be logging and the service is dead → record a suspected OEM
                // kill so the dashboard can name the specific setting to fix.
                //
                // The gap is measured from the last proof the process was ALIVE
                // ([Settings.lifeBeatAt]), not from the last GPS fix. That distinction is the whole
                // point of [Health], and it fixes a real bug: fixes stop arriving when the car is
                // parked, so a phone killed after sitting in a driveway for three hours used to be
                // reported as a three-hour outage. A beat is written whether or not the car is
                // moving, so the gap now names the outage and nothing else. (The old
                // `expectedMaxGap` guard existed to stop a sparse LIGHT-tier cadence from reading
                // as a kill; against a beat that no longer means anything.)
                val now = Clock.now()
                val gap = now - s.lifeBeatAt
                if (isKill(s.lifeBeatAt, now)) {
                    s.lastKillDetectedAt = now
                    EventLog.warn("Logger was killed for ~${gap / 60_000}min — likely OEM battery manager")
                    // An interruption the user never learns about becomes a mystery hole in the
                    // trip log weeks later. Say it now, once per incident (the Settings banner
                    // carries the fix instructions; the notification just gets them there).
                    if (s.lastKillDetectedAt > s.lastKillNotifiedAt) {
                        s.lastKillNotifiedAt = s.lastKillDetectedAt
                        notifyKill(context, gap / 60_000)
                    }
                }
                s.lastCommandSource = "watchdog"
                if (!Control.startTrackingService(context)) return false
                // The restart lands in LocationService.onCreate → Health.startLife, which writes
                // the durable `down` row for the outage we just measured. It won't double-notify:
                // `lastKillNotifiedAt` now sits inside the outage, which is how it knows we spoke.
                EventLog.warn("Watchdog restarted the logging service")
            } else if (running) {
                // The service is alive. This periodic job is the one tick Doze cannot defer
                // indefinitely, so it is the FLOOR under the heartbeat: a phone asleep in a pocket
                // with no fixes and nothing to upload still proves it is alive every ~15 minutes,
                // which bounds how far wrong the death timestamp above can ever be.
                runCatching { Health.beat(context, s) }
            }
            // Drain any backlog regardless of whether we just restarted the service.
            runCatching { Uploader(context, s).flush() }
            return true
        }

        /**
         * Is a service-is-dead window long enough to call a kill? Pure, so the one judgement that
         * can send the user to their battery settings is pinned by a test rather than inferred
         * from a 20-minute wait.
         *
         * `lifeBeatAt == 0` — a phone that has never beaten, i.e. a fresh install — is **not** a
         * kill. There is no interval to measure, and an install whose first watchdog pass accuses
         * the phone of killing a tracker it has never once run is the cry-wolf bug in its purest
         * form.
         */
        fun isKill(lifeBeatAt: Long, now: Long): Boolean =
            lifeBeatAt > 0 && now - lifeBeatAt > KILL_THRESHOLD_MS

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
        private fun notifyKill(context: Context, minutes: Long) {
            Notify.post(
                context, Notify.KIND_TRACKING_HEALTH, Notify.HEALTH_ID,
                "Drive tracking was interrupted",
                "No GPS was recorded for ~$minutes minutes — your phone likely stopped the " +
                    "tracker to save battery, and any drives in that window are missing. " +
                    "Open Settings → Access & battery to keep it running.",
                "/settings",
            )
        }

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
