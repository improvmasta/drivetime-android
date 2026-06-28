package org.jupiterns.drivetime

import android.content.Context
import android.content.Intent

/**
 * Outbound state broadcast — the other half of the Pillar-2 control API. Whenever
 * tracking state changes (logging on/off, tier flip, OBD link, queue drain), the
 * service or [Control] emits an explicit broadcast a Samsung Routine / HA / Tasker
 * can use as a *trigger*:
 *
 *   action: org.jupiterns.drivetime.event.STATE_CHANGED
 *   extras: logging              boolean   — service is alive and tracking
 *           tracking_mode        string    — auto | driving | light | off (desired)
 *           tier                 string    — DRIVING | LIGHT | OFF (actual)
 *           reason               string    — short why ("car BT" | "OBD" | …)
 *           queue_depth          int       — fixes waiting to upload
 *           obd_connected        boolean
 *           last_fix_at          long ms   — wall-clock of the last enqueued fix
 *           last_success_at      long ms   — wall-clock of the last successful upload
 *           last_command_source  string    — "user" | "shortcut" | "routine" | …
 *           source               string    — who triggered *this* broadcast (same set)
 *           ts                   long ms   — emission time
 *
 * The broadcast is sent without setPackage so Tasker/MacroDroid/HA and Samsung
 * Routine "Receive Intent" / "Intent received" hooks (which all register runtime
 * receivers) can consume it. Android 8+ allows implicit broadcasts to *runtime*
 * receivers, so this works for the intended consumers without leaking to manifest
 * receivers in random apps.
 */
object StateBroadcaster {
    const val ACTION_STATE_CHANGED = "org.jupiterns.drivetime.event.STATE_CHANGED"

    private const val EXTRA_LOGGING = "logging"
    private const val EXTRA_MODE = "tracking_mode"
    private const val EXTRA_TIER = "tier"
    private const val EXTRA_REASON = "reason"
    private const val EXTRA_QUEUE = "queue_depth"
    private const val EXTRA_OBD = "obd_connected"
    private const val EXTRA_LAST_FIX = "last_fix_at"
    private const val EXTRA_LAST_SUCCESS = "last_success_at"
    private const val EXTRA_LAST_CMD = "last_command_source"
    private const val EXTRA_SOURCE = "source"
    private const val EXTRA_TS = "ts"

    fun emit(context: Context, source: String) {
        val s = Settings(context)
        val health = runCatching { Uploader(context, s).health() }.getOrNull()
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(EXTRA_LOGGING, LiveState.logging)
            putExtra(EXTRA_MODE, s.trackingMode)
            putExtra(EXTRA_TIER, LiveState.tier ?: if (s.trackingMode == Settings.MODE_OFF) "OFF" else "")
            putExtra(EXTRA_REASON, LiveState.driveReason.orEmpty())
            putExtra(EXTRA_QUEUE, health?.queued ?: -1)
            putExtra(EXTRA_OBD, LiveState.obdConnected)
            putExtra(EXTRA_LAST_FIX, LiveState.updatedAt)
            putExtra(EXTRA_LAST_SUCCESS, health?.lastSuccessAt ?: 0L)
            putExtra(EXTRA_LAST_CMD, s.lastCommandSource)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_TS, System.currentTimeMillis())
        }
        runCatching { context.sendBroadcast(intent) }
    }
}
