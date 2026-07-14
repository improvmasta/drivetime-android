package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject

/**
 * A bounded, on-disk ring of "battery used by a drive" events (Phase 5 — measure it). At drive
 * end the logger stamps one event: `{ts: <drive-start-sec>, kind:"battery", start, end}`, where
 * `start`/`end` are the whole-percent battery readings at the drive's start and end. The SPA
 * drains it idempotently via `DrivetimeNative.pullBattery(sinceTs)`, resolves each event to the
 * drive that began at `ts`, and writes the local `trip_battery` overlay so the drive-detail view
 * can show what the drive cost the battery.
 *
 * `ts` is epoch SECONDS (the drive's start), so an event and a segmented drive agree about when
 * the drive began. Phone-local only (a diagnostic); not synced to the server.
 *
 * The mechanics are [JsonlRing], and this is the class that most needed it: it never had a trim or
 * a select of its own — it reached across and called `WebVehicleBuffer.trimOldest` /
 * `.selectSince`, borrowing another buffer's internals because they happened to be identical. They
 * are shared on purpose now, rather than by coincidence.
 *
 * Like markers and vehicles, the drain is **at-or-after** (`>=`): the SPA's write is keyed by the
 * drive, hence idempotent, so re-delivery is free while a dropped event would be permanent.
 */
object WebBatteryBuffer {
    private const val MAX_EVENTS = 5_000

    private val ring = JsonlRing(
        fileName = "web_battery.jsonl",
        maxLines = MAX_EVENTS,
        inclusive = true,                 // `>=` — see above
        trimEvery = 1,                    // one event per drive
        label = "Battery buffer",
    )

    /** Append one battery event for the drive that started at [startSec]. */
    fun append(context: Context, startSec: Long, startPct: Int, endPct: Int) {
        ring.append(
            context,
            JSONObject()
                .put("ts", startSec)
                .put("kind", "battery")
                .put("start", startPct)
                .put("end", endPct),
        )
    }

    /** JSON array (string) of every buffered event at or after [sinceTs]. */
    fun pullSince(context: Context, sinceTs: Double): String = ring.pullSince(context, sinceTs)
}
