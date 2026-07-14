package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import org.jupiterns.drivetime.obd.Elm327Client
import java.io.InputStream
import java.io.OutputStream

/**
 * The device's own raw GPS archive: every recorded fix, as JSONL, bounded and drop-oldest.
 *
 * Standalone, this file IS the drive pipeline — the SPA drains it (`DrivetimeNative.pullFixes`)
 * and segments its own drives out of it with no server, and it is the durable archive the server
 * heals *from*. So the ring is sized well past any plausible gap between launches: a drive whose
 * fixes fall out before the SPA drains them is one nothing can ever re-derive or re-send.
 *
 * The mechanics are [JsonlRing]; what is specific to fixes lives here:
 *  - **Strictly-after (`>`) drains.** One fix per second, so re-delivering the boundary fix would
 *    be a duplicate. (The other three rings are at-or-after — see JsonlRing.)
 *  - **Amortised trim.** This appends once per fix, so re-reading the whole file on every append
 *    would be absurd; the small rings trim every time.
 */
object WebFixBuffer {
    private const val MAX_FIXES = 120_000         // ~a week of dense driving
    private const val TRIM_EVERY = 500            // amortize the rewrite: trim every N appends

    private val ring = JsonlRing(
        fileName = "web_fixes.jsonl",
        maxLines = MAX_FIXES,
        inclusive = false,                        // `>` — see above
        trimEvery = TRIM_EVERY,
        label = "Fix buffer",
    )

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
        ring.append(context, o)
    }

    /** JSON array (string) of every buffered fix newer than [sinceTs], for the SPA to append. */
    fun pullSince(context: Context, sinceTs: Double): String = ring.pullSince(context, sinceTs)

    /** Stream the whole buffer into [out] under the appender's lock, so a backup archive
     *  never carries a torn last line (BackupStore.writeArchive). */
    fun copyTo(context: Context, out: OutputStream) = ring.copyTo(context, out)

    /** Replace the buffer wholesale — a restore adopts the archive's fixes so the SPA's
     *  drain (idempotent on ts) can repopulate its replica from them. */
    fun replaceAll(context: Context, input: InputStream) = ring.replaceAll(context, input)

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    /** The integer `ts` of a fix line, or null if absent/garbled. */
    fun tsOf(line: String): Long? = JsonlRing.tsOf(line)

    /** A JSON-array string of the raw fix lines whose ts is **strictly greater** than [sinceTs]. */
    fun selectSince(lines: List<String>, sinceTs: Double): String =
        JsonlRing.selectSince(lines, sinceTs, inclusive = false)

    /** Keep only the newest [max] lines (drop the oldest), preserving order. */
    fun trimOldest(lines: List<String>, max: Int): List<String> = JsonlRing.trimOldest(lines, max)
}
