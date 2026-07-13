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
        if (!s.notifyDriveComplete) return Result.success()
        // Cancel-on-DRIVING should make this unreachable mid-drive; if the fire raced the
        // cancel anyway, stay silent — the chain's final leg re-arms.
        if (LocationService.isRunning && LiveState.tier == "DRIVING") return Result.success()
        val startMs = inputData.getLong(KEY_START_MS, 0L)
        val endMs = inputData.getLong(KEY_END_MS, 0L)
        val meters = inputData.getDouble(KEY_METERS, 0.0)
        if (startMs <= 0L) return Result.success()
        val startTs = startMs / 1000

        val att = Notify.attention(ctx)
        val body: String
        val route: String
        if (att != null && att.tickAtMs >= endMs) {
            val p = att.tagPrompts.firstOrNull { abs(it.startTs - startTs) <= Notify.MATCH_TOLERANCE_SEC }
                ?: return Result.success()
            val miles = if (p.miles > 0) "%.1f mi".format(p.miles) else null
            body = listOfNotNull(p.label.ifBlank { null }, miles).joinToString(" · ")
                .ifBlank { "Your drive is ready to tag" }
            route = "/drive/L${p.startTs}"
        } else {
            body = "%.1f mi · tap to tag it".format(meters * 0.000621371)
            route = "/drive/L$startTs"
        }
        Notify.post(ctx, Notify.KIND_DRIVE_COMPLETE, startTs.toString(), "Tag your drive", body, route)
        return Result.success()
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
