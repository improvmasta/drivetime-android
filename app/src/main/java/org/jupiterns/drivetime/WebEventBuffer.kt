package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject

/**
 * Hard-brake / hard-launch events from the accel extractor (INSIGHTS P3a), as JSONL for the
 * SPA to drain (`DrivetimeNative.pullEvents` → the local `accel_events` store → merged into
 * `drive_stats` at seal). Raw sensor streams never cross the bridge — the accelerometer is
 * consumed at the edge in [AccelExtractor]; what lands here is a handful of events per drive.
 *
 * At-or-after (`>=`) drains like the marker/vehicle/battery rings: two events can share a
 * second, and the SPA's write is idempotent (keyed `kind|ts`), so re-delivery is free and a
 * strictly-after cursor landing on the boundary would drop one forever.
 */
object WebEventBuffer {
    private const val MAX_EVENTS = 5_000

    private val ring = JsonlRing(
        fileName = "web_events.jsonl",
        maxLines = MAX_EVENTS,
        inclusive = true,
        label = "Event buffer",
    )

    /** Append one event. [magMs2] is the accel-window peak (gravity-removed, m/s²) when the
     *  sensor saw it; [mphs] the GPS-delta rate (signed, mph per second). */
    fun append(
        context: Context, epochSec: Long, kind: String,
        magMs2: Double?, mphs: Double, fromMph: Double, toMph: Double,
    ) {
        val o = JSONObject()
            .put("ts", epochSec)
            .put("kind", kind)
            .put("mphs", Math.round(mphs * 10.0) / 10.0)
            .put("from", Math.round(fromMph * 10.0) / 10.0)
            .put("to", Math.round(toMph * 10.0) / 10.0)
        if (magMs2 != null) o.put("mag", Math.round(magMs2 * 100.0) / 100.0)
        ring.append(context, o)
    }

    /** JSON array (string) of every buffered event at or after [sinceTs]. */
    fun pullSince(context: Context, sinceTs: Double): String = ring.pullSince(context, sinceTs)
}
