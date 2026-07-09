package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A bounded, on-disk ring of markers the driver stamped from the notification (or Android
 * Auto) while the SPA was not on screen. Mirrors [WebFixBuffer]: the service thread appends,
 * the JS-bridge thread drains it idempotently via `DrivetimeNative.pullMarkers(sinceTs)`.
 *
 * Native must never write IndexedDB, so this is how a marker crosses the boundary — exactly
 * the way GPS fixes already do (MARKERS.md §6).
 *
 * Deliberately a SEPARATE file from `web_fixes.jsonl`. Fixes are dense and disposable; a
 * marker is sparse and precious — the driver pressed a button to make it — so the two must
 * never share a trim policy. At a handful per drive, MAX_MARKERS is years of driving.
 *
 * **Native mints the uuid**, which is what makes the pull idempotent: a crash between append
 * and drain re-delivers the marker rather than duplicating it, since the SPA keys on `id`.
 * `ts` is epoch SECONDS, the same unit a buffered fix carries, so a native marker and a
 * native fix agree about when they happened.
 */
object WebMarkerBuffer {
    private const val FILE = "web_markers.jsonl"
    private const val MAX_MARKERS = 5_000
    private val LOCK = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Append one marker. [id] is a client-minted uuid; [epochSec] is where it sits on the route. */
    fun append(context: Context, id: String, epochSec: Long, lat: Double, lon: Double) {
        val o = JSONObject()
            .put("id", id)
            .put("ts", epochSec)
            .put("lat", lat)
            .put("lon", lon)
            .put("origin", "live")
        synchronized(LOCK) {
            val f = file(context)
            runCatching { f.appendText(o.toString() + "\n") }
            // Markers are rare, so trim on every append rather than amortising: the cost is
            // a file read no one will ever notice, and an unbounded file is worse.
            val lines = f.readLinesOrEmpty()
            val trimmed = trimOldest(lines, MAX_MARKERS)
            if (trimmed.size < lines.size) {
                runCatching { f.writeText(trimmed.joinToString("\n", postfix = "\n")) }
            }
        }
    }

    /** JSON array (string) of every buffered marker at or after [sinceTs].
     *
     *  At-or-after, not strictly-after: two marks can share a second, and a cursor that
     *  skipped the boundary would drop one forever. Re-delivery is harmless — the SPA
     *  ignores an `id` it already holds. */
    fun pullSince(context: Context, sinceTs: Double): String =
        synchronized(LOCK) { selectSince(file(context).readLinesOrEmpty(), sinceTs) }

    /** How many markers sit at or after [sinceTs] — the drive's marker count for the
     *  notification, without the SPA having to be alive to compute it. */
    fun countSince(context: Context, sinceTs: Long): Int =
        synchronized(LOCK) {
            file(context).readLinesOrEmpty().count { (tsOf(it) ?: Long.MIN_VALUE) >= sinceTs }
        }

    /** The newest buffered marker's ts, or null when there are none. */
    fun latestTs(context: Context): Long? =
        synchronized(LOCK) { file(context).readLinesOrEmpty().mapNotNull { tsOf(it) }.maxOrNull() }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    private val TS = Regex("\"ts\"\\s*:\\s*(\\d+)")

    /** The integer `ts` of a marker line, or null if absent/garbled. */
    fun tsOf(line: String): Long? = TS.find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** A JSON-array string of the raw marker lines whose ts >= [sinceTs]. Each line is
     *  already a JSON object, so joining them in brackets is a valid JSON array. */
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
