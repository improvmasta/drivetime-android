package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A bounded, on-disk ring of vehicle events the logger stamped while the SPA was not on screen
 * (Phase 4). An event says "at ts, the car identified by `key` was in use" — `key` is a car
 * Bluetooth MAC, later upgraded to the VIN once OBD reads it. Mirrors [WebMarkerBuffer]: the
 * service thread appends, the JS-bridge thread drains it idempotently via
 * `DrivetimeNative.pullVehicles(sinceTs)`, and the SPA resolves each event to the drive that
 * spans its ts and to a registered vehicle by key, then writes the `trip_vehicle` overlay.
 *
 * A durable association by TIME, never a foreign key — the same discipline as markers, so a
 * merge/split/rebuild can't orphan a vehicle stamp. Events are sparse (a couple per drive), so
 * like markers this trims on every append and MAX_EVENTS is years of driving.
 *
 * `ts` is epoch SECONDS (matching a buffered fix/marker), so a vehicle event and a fix agree
 * about when they happened.
 */
object WebVehicleBuffer {
    private const val FILE = "web_vehicles.jsonl"
    private const val MAX_EVENTS = 5_000
    private val LOCK = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

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
        synchronized(LOCK) {
            val f = file(context)
            runCatching { f.appendText(o.toString() + "\n") }
            val lines = f.readLinesOrEmpty()
            val trimmed = trimOldest(lines, MAX_EVENTS)
            if (trimmed.size < lines.size) {
                runCatching { f.writeText(trimmed.joinToString("\n", postfix = "\n")) }
            }
        }
    }

    /** JSON array (string) of every buffered event at or after [sinceTs].
     *
     *  At-or-after, not strictly-after: a cursor that skipped the boundary could drop an event
     *  forever. Re-delivery is harmless — the SPA's stamp is idempotent (same drive+vehicle). */
    fun pullSince(context: Context, sinceTs: Double): String =
        synchronized(LOCK) { selectSince(file(context).readLinesOrEmpty(), sinceTs) }

    /** Stream the whole buffer into [out] under the appender's lock (backup archives). */
    fun copyTo(context: Context, out: java.io.OutputStream) {
        synchronized(LOCK) {
            val f = file(context)
            if (f.exists()) f.inputStream().use { it.copyTo(out) }
        }
    }

    /** Replace the buffer wholesale (restore). */
    fun replaceAll(context: Context, input: java.io.InputStream) {
        synchronized(LOCK) {
            runCatching { file(context).outputStream().use { input.copyTo(it) } }
                .onFailure { EventLog.warn("Vehicle buffer restore failed: ${it.message}") }
        }
    }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    private val TS = Regex("\"ts\"\\s*:\\s*(\\d+)")

    /** The integer `ts` of an event line, or null if absent/garbled. */
    fun tsOf(line: String): Long? = TS.find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** A JSON-array string of the raw event lines whose ts >= [sinceTs]. */
    fun selectSince(lines: List<String>, sinceTs: Double): String {
        val kept = lines.filter { it.isNotBlank() }.filter { (tsOf(it) ?: Long.MIN_VALUE) >= sinceTs }
        return "[" + kept.joinToString(",") + "]"
    }

    /** Keep only the newest [max] lines (drop the oldest), preserving order. */
    fun trimOldest(lines: List<String>, max: Int): List<String> {
        val nonBlank = lines.filter { it.isNotBlank() }
        return if (nonBlank.size <= max) nonBlank else nonBlank.subList(nonBlank.size - max, nonBlank.size)
    }
}
