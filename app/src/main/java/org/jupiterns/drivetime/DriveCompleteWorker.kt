package org.jupiterns.drivetime

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The "drive completed — tag it" notification (NOTIFICATIONS.md P3a), native-armed and
 * SPA-informed. Armed at drive end ([LocationService] drive-end branch) with a 16-minute
 * delay — deliberately past the 15-minute gas-gap window, so a fuel stop's first leg never
 * prompts mid-chain (re-entering DRIVING cancels the pending work and the final leg re-arms).
 *
 * At fire time the SPA's last pending-attention push decides what to say:
 *  - SPA ticked after the drive ended and still lists it → post with the SPA's label
 *    ("Home → Depot · 12.3 mi") and the real drive route.
 *  - SPA ticked and does NOT list it → the drive was tagged, merged, or rule-tagged
 *    already; stay silent.
 *  - SPA hasn't ticked since the drive ended (app closed) → post the generic native
 *    version. A rule that would auto-tag can't run headless — accepted trade-off; the
 *    next attention push retracts the notification when the app opens.
 */
class DriveCompleteWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val s = Settings(ctx)
        // TWO independent prompts ride this one wake-up: the drive that just sealed ("tag it"),
        // and the backlog of drives sitting on a route default ("apply your usual tags"). So the
        // bail-out has to consider both — gating the whole worker on `notifyDriveComplete`, as it
        // did when it had only one prompt to post, would have made the route-default notification
        // dead code for anyone who wanted it *without* the per-drive nag.
        if (!s.notifyDriveComplete && !s.notifyApplyUsual) return Result.success()
        // Cancel-on-DRIVING should make this unreachable mid-drive; if the fire raced the
        // cancel anyway, stay silent — the chain's final leg re-arms.
        if (LocationService.isRunning && LiveState.tier == "DRIVING") return Result.success()
        val startMs = inputData.getLong(KEY_START_MS, 0L)
        val endMs = inputData.getLong(KEY_END_MS, 0L)
        val meters = inputData.getDouble(KEY_METERS, 0.0)
        if (startMs <= 0L) return Result.success()

        val att = Notify.attention(ctx)
        postDriveComplete(ctx, att, startMs / 1000, endMs, meters)
        postApplyUsual(ctx, att)
        return Result.success()
    }

    /** "Tag your drive" — the drive that just sealed, if the SPA still lists it as untagged. */
    private fun postDriveComplete(ctx: Context, att: Notify.Attention?, startTs: Long, endMs: Long, meters: Double) {
        val body: String
        val route: String
        if (att != null && att.tickAtMs >= endMs) {
            // The SPA has spoken since this drive ended, and it does NOT list the drive as
            // needing a tag (it was auto-tagged by a rule, or tagged in the HUD mid-drive).
            // Nothing to prompt for — but the backlog prompt below still stands on its own.
            val p = att.tagPrompts.firstOrNull { abs(it.startTs - startTs) <= Notify.MATCH_TOLERANCE_SEC }
                ?: return
            val miles = if (p.miles > 0) "%.1f mi".format(p.miles) else null
            body = listOfNotNull(p.label.ifBlank { null }, miles).joinToString(" · ")
                .ifBlank { "Your drive is ready to tag" }
            route = "/drive/L${p.startTs}"
        } else {
            body = "%.1f mi · tap to tag it".format(meters * 0.000621371)
            route = "/drive/L$startTs"
        }
        Notify.post(ctx, Notify.KIND_DRIVE_COMPLETE, startTs.toString(), "Tag your drive", body, route)
    }

    /**
     * "Apply your usual tags?" — the bell's route-default prompt, now available to the OS too.
     *
     * The phone has no idea what a route default IS: matching an origin→destination pair to the
     * tag you last used on it is the SPA's logic (localmileage's suggestion map). But the SPA
     * already hands the COUNT over in its attention payload, so the phone doesn't need to know —
     * it reports the number it was given, which is the same number the bell is showing.
     *
     * Fired from here rather than on a schedule of its own because this is the moment the backlog
     * can have grown: a drive just sealed. Keyed [Notify.HEALTH_ID] because it is a singleton
     * count like the digest — one "3 drives match a usual tag", replaced in place, never one
     * notification per drive — and retracted by the attention sweep the moment `suggested` hits 0.
     */
    private fun postApplyUsual(ctx: Context, att: Notify.Attention?) {
        val n = att?.suggested ?: 0
        if (n <= 0) return
        Notify.post(
            ctx, Notify.KIND_APPLY_USUAL, Notify.HEALTH_ID,
            "Apply your usual tags?",
            "$n drive${if (n == 1) "" else "s"} match${if (n == 1) "es" else ""} " +
                "a route you have tagged before.",
            "/mileage",
        )
    }

    companion object {
        private const val WORK = "drivetime-drive-complete"
        /** One minute past the 15-min gas-gap window: a stop short enough to be a gas-stop
         *  split re-enters DRIVING (cancelling this) before the prompt can fire. */
        private const val DELAY_MIN = 16L
        private const val KEY_START_MS = "start_ms"
        private const val KEY_END_MS = "end_ms"
        private const val KEY_METERS = "meters"

        /** A real drive just ended — arm the delayed prompt. REPLACE: only the newest
         *  drive's prompt is ever pending. */
        fun schedule(ctx: Context, startMs: Long, endMs: Long, meters: Double) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveCompleteWorker>()
                    .setInitialDelay(DELAY_MIN, TimeUnit.MINUTES)
                    .setInputData(
                        Data.Builder()
                            .putLong(KEY_START_MS, startMs)
                            .putLong(KEY_END_MS, endMs)
                            .putDouble(KEY_METERS, meters)
                            .build()
                    )
                    .build()
            )
        }

        /** Entering DRIVING invalidates the previous leg's pending prompt. */
        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        }
    }
}
