package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * A bounded, on-disk ring of markers the driver stamped from the notification (or Android Auto)
 * while the SPA was not on screen. The service thread appends; the JS-bridge thread drains it
 * idempotently via `DrivetimeNative.pullMarkers(sinceTs)`.
 *
 * Native must never write IndexedDB, so this is how a marker crosses the boundary — exactly the
 * way GPS fixes already do (MARKERS.md §6).
 *
 * Deliberately a SEPARATE file from `web_fixes.jsonl`. Fixes are dense and disposable; a marker is
 * sparse and precious — the driver pressed a button to make it — so the two must never share a
 * trim policy. At a handful per drive, MAX_MARKERS is years of driving.
 *
 * **Native mints the uuid**, which is what makes the pull idempotent: a crash between append and
 * drain re-delivers the marker rather than duplicating it, since the SPA keys on `id`. `ts` is
 * epoch SECONDS, the same unit a buffered fix carries, so a native marker and a native fix agree
 * about when they happened.
 *
 * The mechanics are [JsonlRing]. What is specific to markers: the drain is **at-or-after** (`>=`),
 * because two marks can share a second and a strictly-after cursor landing on the boundary would
 * drop one forever. Re-delivery is free — the SPA ignores an `id` it already holds.
 */
object WebMarkerBuffer {
    private const val MAX_MARKERS = 5_000

    private val ring = JsonlRing(
        fileName = "web_markers.jsonl",
        maxLines = MAX_MARKERS,
        inclusive = true,                 // `>=` — see above
        trimEvery = 1,                    // markers are rare; the read costs nothing at this rate
        label = "Marker buffer",
    )

    /** Append one marker. [id] is a client-minted uuid; [epochSec] is where it sits on the route. */
    fun append(context: Context, id: String, epochSec: Long, lat: Double, lon: Double) {
        ring.append(
            context,
            JSONObject()
                .put("id", id)
                .put("ts", epochSec)
                .put("lat", lat)
                .put("lon", lon)
                .put("origin", "live"),
        )
    }

    /** JSON array (string) of every buffered marker at or after [sinceTs]. */
    fun pullSince(context: Context, sinceTs: Double): String = ring.pullSince(context, sinceTs)

    /** How many markers sit at or after [sinceTs] — the drive's marker count for the
     *  notification, without the SPA having to be alive to compute it. */
    fun countSince(context: Context, sinceTs: Long): Int = ring.countSince(context, sinceTs)

    /** The newest buffered marker's ts, or null when there are none. */
    fun latestTs(context: Context): Long? = ring.latestTs(context)

    /** Stream the whole buffer into [out] under the appender's lock (backup archives). */
    fun copyTo(context: Context, out: OutputStream) = ring.copyTo(context, out)

    /** Replace the buffer wholesale (restore). */
    fun replaceAll(context: Context, input: InputStream) = ring.replaceAll(context, input)

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    /** The integer `ts` of a marker line, or null if absent/garbled. */
    fun tsOf(line: String): Long? = JsonlRing.tsOf(line)

    /** A JSON-array string of the raw marker lines whose ts is **at or after** [sinceTs]. */
    fun selectSince(lines: List<String>, sinceTs: Double): String =
        JsonlRing.selectSince(lines, sinceTs, inclusive = true)

    /** Keep only the newest [max] lines (drop the oldest), preserving order. */
    fun trimOldest(lines: List<String>, max: Int): List<String> = JsonlRing.trimOldest(lines, max)
}
