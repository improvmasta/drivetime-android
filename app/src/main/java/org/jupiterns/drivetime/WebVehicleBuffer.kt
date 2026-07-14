package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * A bounded, on-disk ring of vehicle events the logger stamped while the SPA was not on screen
 * (Phase 4). An event says "at ts, the car identified by `key` was in use" — `key` is a car
 * Bluetooth MAC, later upgraded to the VIN once OBD reads it. The service thread appends, the
 * JS-bridge thread drains it idempotently via `DrivetimeNative.pullVehicles(sinceTs)`, and the SPA
 * resolves each event to the drive that spans its ts and to a registered vehicle by key, then
 * writes the `trip_vehicle` overlay.
 *
 * A durable association by TIME, never a foreign key — the same discipline as markers, so a
 * merge/split/rebuild can't orphan a vehicle stamp. `ts` is epoch SECONDS (matching a buffered
 * fix/marker), so a vehicle event and a fix agree about when they happened.
 *
 * The mechanics are [JsonlRing]. Like markers, the drain is **at-or-after** (`>=`): several events
 * can share a second, and the SPA's stamp is idempotent (same drive + same vehicle), so
 * re-delivery is free while a dropped event would be permanent.
 */
object WebVehicleBuffer {
    private const val MAX_EVENTS = 5_000

    private val ring = JsonlRing(
        fileName = "web_vehicles.jsonl",
        maxLines = MAX_EVENTS,
        inclusive = true,                 // `>=` — see above
        trimEvery = 1,                    // a couple of events per drive
        label = "Vehicle buffer",
    )

    /**
     * Append one vehicle event. [key] is a BT MAC (or a VIN); [epochSec] is when it applied.
     *
     * [obdMac] rides along on a VIN event: it is the adapter that READ that VIN, and it's what
     * lets the SPA follow a dongle moved from one car to another. Without it, unplugging the
     * reader from the truck and plugging it into the van leaves the registry insisting the
     * adapter still belongs to the truck. Null on a Bluetooth event (nothing read a VIN).
     */
    fun append(context: Context, epochSec: Long, key: String, obdMac: String? = null) {
        val o = JSONObject()
            .put("ts", epochSec)
            .put("kind", "vehicle")
            .put("key", key)
        if (!obdMac.isNullOrBlank()) o.put("obd", obdMac.uppercase())
        ring.append(context, o)
    }

    /** JSON array (string) of every buffered event at or after [sinceTs]. */
    fun pullSince(context: Context, sinceTs: Double): String = ring.pullSince(context, sinceTs)

    /** Stream the whole buffer into [out] under the appender's lock (backup archives). */
    fun copyTo(context: Context, out: OutputStream) = ring.copyTo(context, out)

    /** Replace the buffer wholesale (restore). */
    fun replaceAll(context: Context, input: InputStream) = ring.replaceAll(context, input)

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    /** The integer `ts` of an event line, or null if absent/garbled. */
    fun tsOf(line: String): Long? = JsonlRing.tsOf(line)

    /** A JSON-array string of the raw event lines whose ts is **at or after** [sinceTs]. */
    fun selectSince(lines: List<String>, sinceTs: Double): String =
        JsonlRing.selectSince(lines, sinceTs, inclusive = true)

    /** Keep only the newest [max] lines (drop the oldest), preserving order. */
    fun trimOldest(lines: List<String>, max: Int): List<String> = JsonlRing.trimOldest(lines, max)
}
