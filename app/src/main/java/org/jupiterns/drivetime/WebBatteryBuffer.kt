package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A bounded, on-disk ring of "battery used by a drive" events (Phase 5 — measure it). At drive
 * end the logger stamps one event: `{ts: <drive-start-sec>, kind:"battery", start, end}`, where
 * `start`/`end` are the whole-percent battery readings at the drive's start and end. The SPA
 * drains it idempotently via `DrivetimeNative.pullBattery(sinceTs)`, resolves each event to the
 * drive that began at `ts`, and writes the local `trip_battery` overlay so the drive-detail view
 * can show what the drive cost the battery.
 *
 * Mirrors [WebVehicleBuffer]: the service thread appends, the JS-bridge thread drains, and `ts`
 * is epoch SECONDS (the drive's start), so an event and a segmented drive agree about when the
 * drive began. Events are one per drive, so — like markers/vehicles — this trims on every append
 * and MAX_EVENTS is years of driving. Phone-local only (a diagnostic); not synced to the server.
 */
object WebBatteryBuffer {
    private const val FILE = "web_battery.jsonl"
    private const val MAX_EVENTS = 5_000
    private val LOCK = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Append one battery event for the drive that started at [startSec]. */
    fun append(context: Context, startSec: Long, startPct: Int, endPct: Int) {
        val o = JSONObject()
            .put("ts", startSec)
            .put("kind", "battery")
            .put("start", startPct)
            .put("end", endPct)
        synchronized(LOCK) {
            val f = file(context)
            runCatching { f.appendText(o.toString() + "\n") }
            val lines = f.readLinesOrEmpty()
            val trimmed = WebVehicleBuffer.trimOldest(lines, MAX_EVENTS)
            if (trimmed.size < lines.size) {
                runCatching { f.writeText(trimmed.joinToString("\n", postfix = "\n")) }
            }
        }
    }

    /** JSON array (string) of every buffered event at or after [sinceTs]. At-or-after (not
     *  strictly-after) so a cursor that skipped the boundary can't drop an event forever;
     *  re-delivery is harmless — the SPA's write is keyed by the drive, hence idempotent. */
    fun pullSince(context: Context, sinceTs: Double): String =
        synchronized(LOCK) { WebVehicleBuffer.selectSince(file(context).readLinesOrEmpty(), sinceTs) }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()
}
