package org.jupiterns.drivetime

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The weekly untagged digest (NOTIFICATIONS.md P4): one nudge a week — "6 drives need tags
 * (4 have suggestions)" — at the day + time the user picked, deep-linking to Mileage.
 * Default OFF like every event notification.
 *
 * **Counts come from the SPA's last attention push** ([Notify.attention]), not from a headless
 * re-segmentation: nothing on the native side knows what a tag is. So the numbers are a
 * *floor* — drives logged after the app was last open aren't in them yet — which is the right
 * error to make: the digest may under-count, but it can never invent work that isn't there.
 * No push ever (a phone that has logged drives but never opened the app) ⇒ stay silent.
 *
 * Self-rescheduling: each run arms the next week from the wall clock ([DigestSchedule]), so
 * the slot can't drift across DST the way 7-day periodic work would. WorkManager persists the
 * pending work across reboots on its own; [BootReceiver] re-arms anyway as a backstop.
 */
class DigestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val s = Settings(ctx)
        // Toggled off while this run was pending — nothing to post, and nothing to re-arm.
        if (!s.notifyDigest) return Result.success()

        val att = Notify.attention(ctx)
        val untagged = att?.untagged ?: 0
        if (untagged > 0) {
            val suggested = att?.suggested ?: 0
            val title = if (untagged == 1) "1 drive needs a tag" else "$untagged drives need tags"
            val body = when {
                suggested >= untagged -> "Every one has a suggested tag — apply them in a tap."
                suggested > 0 -> "$suggested of them have a suggested tag."
                else -> "Open Mileage to tag them and keep the log claimable."
            }
            Notify.post(ctx, Notify.KIND_WEEKLY_DIGEST, DIGEST_ID, title, body, "/mileage")
        } else {
            // Nothing to tag (or the SPA has never pushed): a "0 drives need tags" notification
            // is pure noise — the whole point of the digest is that it only speaks up with work.
            EventLog.info("Weekly digest: nothing to report")
        }

        reschedule(ctx, s)
        return Result.success()
    }

    companion object {
        private const val WORK = "drivetime-weekly-digest"

        /** One digest at a time: a fixed id means next week's post *replaces* last week's in
         *  the shade instead of stacking a pile of stale counts. */
        const val DIGEST_ID = "weekly"

        /** (Re)arm the next digest — on toggle/day/time change, at boot, and after each fire.
         *  REPLACE, so changing the day never leaves the old slot armed too. Off ⇒ cancelled. */
        fun reschedule(ctx: Context, s: Settings) {
            val wm = WorkManager.getInstance(ctx)
            if (!s.notifyDigest) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val delay = DigestSchedule.nextDelayMs(
                System.currentTimeMillis(), s.digestDay, s.digestTime, ZoneId.systemDefault()
            )
            wm.enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DigestWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
            )
        }
    }
}
