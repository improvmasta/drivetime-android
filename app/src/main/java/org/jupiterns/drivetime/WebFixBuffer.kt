package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import org.jupiterns.drivetime.obd.Elm327Client
import java.io.File

/**
 * A bounded, on-disk ring of recent GPS fixes in the shape the SPA's on-device
 * segmenter consumes ({ts,lat,lon,speed_mph,…obd}), so the phone's own GPS can feed the
 * IndexedDB replica with no server (STANDALONE.md A2). Separate from [Uploader]'s
 * OwnTracks-shaped upload queue: that one is drained + deleted on upload, whereas this one
 * is *pulled* idempotently by the SPA (keyed by `ts`) and only trimmed by age/size — so a
 * fix reaches both the server (when configured) and the local replica.
 *
 * The SPA drains via the [WebViewActivity] `DrivetimeNative.pullFixes(sinceTs)` bridge.
 * File access is guarded by [LOCK] (the locator thread appends; the JS-bridge thread reads).
 */
object WebFixBuffer {
    private const val FILE = "web_fixes.jsonl"
    // Then drop oldest. The phone is the durable fix archive the server heals from, and a drive
    // whose fixes fall out of this ring before the SPA drains them is one the phone can never
    // re-derive or re-send — so the ring is sized well past any plausible gap between launches.
    private const val MAX_FIXES = 120_000         // ~a week of dense driving
    private const val TRIM_EVERY = 500            // amortize the rewrite: trim every N appends
    private val LOCK = Any()
    private var appendsSinceTrim = 0

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Append one fix (called for every recorded fix, in both local and server modes). */
    fun append(
        context: Context, lat: Double, lon: Double, epochSec: Long,
        speedMps: Float?, obd: Elm327Client.ObdSample? = null,
    ) {
        val o = JSONObject().put("ts", epochSec).put("lat", lat).put("lon", lon)
        if (speedMps != null) o.put("speed_mph", (speedMps * 2.2369362f).toDouble())
        if (obd != null) {
            obd.rpm?.let { o.put("rpm", it) }
            obd.engineLoad?.let { o.put("engine_load", it) }
            obd.coolantC?.let { o.put("coolant_c", it) }
            obd.voltage?.let { o.put("voltage", it) }
            obd.fuelLevel?.let { o.put("fuel_level", it) }
        }
        synchronized(LOCK) {
            val f = file(context)
            runCatching { f.appendText(o.toString() + "\n") }
            if (++appendsSinceTrim >= TRIM_EVERY) {
                appendsSinceTrim = 0
                val lines = f.readLinesOrEmpty()
                val trimmed = trimOldest(lines, MAX_FIXES)
                if (trimmed.size < lines.size) runCatching { f.writeText(trimmed.joinToString("\n", postfix = "\n")) }
            }
        }
    }

    /** JSON array (string) of every buffered fix newer than [sinceTs], for the SPA to append. */
    fun pullSince(context: Context, sinceTs: Double): String =
        synchronized(LOCK) { selectSince(file(context).readLinesOrEmpty(), sinceTs) }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    private val TS = Regex("\"ts\"\\s*:\\s*(\\d+)")

    /** The integer `ts` of a fix line, or null if absent/garbled. */
    fun tsOf(line: String): Long? = TS.find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** A JSON-array string of the raw fix lines whose ts > [sinceTs] (blank/garbled skipped).
     *  Each line is already a JSON object, so joining them in brackets is a valid JSON array. */
    fun selectSince(lines: List<String>, sinceTs: Double): String {
        val kept = lines.filter { it.isNotBlank() }.filter { (tsOf(it) ?: Long.MIN_VALUE) > sinceTs }
        return "[" + kept.joinToString(",") + "]"
    }

    /** Keep only the newest [max] lines (drop the oldest), preserving order. */
    fun trimOldest(lines: List<String>, max: Int): List<String> {
        val nonBlank = lines.filter { it.isNotBlank() }
        return if (nonBlank.size <= max) nonBlank else nonBlank.subList(nonBlank.size - max, nonBlank.size)
    }
}
