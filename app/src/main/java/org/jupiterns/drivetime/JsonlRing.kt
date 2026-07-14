package org.jupiterns.drivetime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One bounded, on-disk JSONL ring — the shared body of the four `Web*Buffer` objects
 * (HARDENING.md 5.3).
 *
 * Fixes, markers, vehicle events and battery events had each grown their own copy of the same
 * class: the same `readLinesOrEmpty`, the same `"ts"` regex, the same `tsOf`/`selectSince`/
 * `trimOldest`, the same append-then-trim under a lock, the same `copyTo`/`replaceAll`. Four
 * copies is four places for a fix to be applied to three of them — and it had already started:
 * `WebBatteryBuffer` didn't have its own trim/select at all, it reached across and called
 * `WebVehicleBuffer.trimOldest` / `.selectSince`, which is not a design, it is the last stage
 * before the copies drift.
 *
 * THE THREE THINGS THAT ACTUALLY DIFFER between the four, and are therefore parameters rather
 * than something to be unified away (compatibility contract #2 — every one of them is observable
 * on an installed phone):
 *
 *  1. **The filename.** `web_fixes.jsonl`, `web_markers.jsonl`, `web_vehicles.jsonl`,
 *     `web_battery.jsonl`. Renaming one orphans the data already on the phone, and orphans it
 *     inside every backup archive ever taken.
 *
 *  2. **The cap, and how often the trim runs.** Fixes are ~120k lines (a week of dense driving —
 *     the phone is the durable archive the server heals from, so a drive whose fixes fall out of
 *     the ring before the SPA drains them is one nothing can ever re-derive) and appending happens
 *     once per fix, so its trim is AMORTISED every [trimEvery] appends. The other three are
 *     ~5k lines and a handful of events per drive, so they trim on every append: the read costs
 *     nothing at that rate and an unbounded file is worse.
 *
 *  3. **`>` vs `>=` on the drain cursor**, which is a real semantic split and NOT an inconsistency
 *     to be tidied up. Fixes are strictly-after (`>`): they are keyed by second, one per second,
 *     and re-delivering the boundary fix would be a duplicate. Markers, vehicles and battery events
 *     are at-or-after (`>=`): two of them can share a second, so a strictly-after cursor that
 *     landed on the boundary would drop one **forever**, and re-delivery is free because every one
 *     of those is written idempotently by the SPA (on the marker's `id`, on the drive+vehicle, on
 *     the drive). Flip either and the bug is silent data loss on one side or duplicates on the
 *     other.
 *
 * Each ring owns its own lock, exactly as each object used to — the service thread appends while
 * the JS-bridge thread drains, and they must not serialise against a ring they never touch.
 */
class JsonlRing(
    private val fileName: String,
    private val maxLines: Int,
    /** true = drain at-or-after the cursor (`>=`); false = strictly after (`>`). See #3 above. */
    private val inclusive: Boolean,
    /** Trim after this many appends. 1 = every append (the small rings); N = amortised (fixes). */
    private val trimEvery: Int = 1,
    /** What this ring is called in the log ("Fix buffer", "Marker buffer", …). */
    private val label: String,
) {
    private val lock = Any()
    private var appendsSinceTrim = 0
    private var appendFailing = false

    private fun file(context: Context) = File(context.filesDir, fileName)

    /**
     * Append one event, then trim if this ring is due.
     *
     * A silently failing append is the one failure here that matters: standalone, these buffers
     * ARE the drive pipeline, so a full disk means drives simply stop appearing with nothing said.
     * The incident EDGES are logged (started failing / recovered), never one line per append —
     * that would put a log line on every GPS fix.
     */
    fun append(context: Context, o: JSONObject) {
        synchronized(lock) {
            val f = file(context)
            runCatching { f.appendText(o.toString() + "\n") }
                .onFailure {
                    if (!appendFailing) {
                        appendFailing = true
                        EventLog.warn("$label append failed: ${it.message}")
                    }
                }
                .onSuccess {
                    if (appendFailing) {
                        appendFailing = false
                        EventLog.info("$label append recovered")
                    }
                }
            if (++appendsSinceTrim >= trimEvery) {
                appendsSinceTrim = 0
                val lines = f.readLinesOrEmpty()
                val trimmed = trimOldest(lines, maxLines)
                if (trimmed.size < lines.size) {
                    EventLog.debug("$label trimmed ${lines.size - trimmed.size} oldest (cap $maxLines)")
                    runCatching { f.writeText(trimmed.joinToString("\n", postfix = "\n")) }
                }
            }
        }
    }

    /** JSON array (string) of every buffered line past [sinceTs] — see [inclusive]. */
    fun pullSince(context: Context, sinceTs: Double): String =
        synchronized(lock) { selectSince(file(context).readLinesOrEmpty(), sinceTs, inclusive) }

    /** How many lines sit at or after [sinceTs]. */
    fun countSince(context: Context, sinceTs: Long): Int =
        synchronized(lock) {
            file(context).readLinesOrEmpty().count { (tsOf(it) ?: Long.MIN_VALUE) >= sinceTs }
        }

    /** The newest buffered line's ts, or null when there are none. */
    fun latestTs(context: Context): Long? =
        synchronized(lock) { file(context).readLinesOrEmpty().mapNotNull { tsOf(it) }.maxOrNull() }

    /** Stream the whole buffer into [out] under the appender's lock, so a backup archive never
     *  carries a torn last line (BackupStore.writeArchive). */
    fun copyTo(context: Context, out: OutputStream) {
        synchronized(lock) {
            val f = file(context)
            if (f.exists()) f.inputStream().use { it.copyTo(out) }
        }
    }

    /** Replace the buffer wholesale — a restore adopts the archive's lines so the SPA's drain
     *  (idempotent) can repopulate its replica from them. */
    fun replaceAll(context: Context, input: InputStream) {
        synchronized(lock) {
            runCatching { file(context).outputStream().use { input.copyTo(it) } }
                .onFailure { EventLog.warn("$label restore failed: ${it.message}") }
        }
    }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----
    companion object {
        private val TS = Regex("\"ts\"\\s*:\\s*(\\d+)")

        /** The integer `ts` of an event line, or null if absent/garbled. */
        fun tsOf(line: String): Long? = TS.find(line)?.groupValues?.get(1)?.toLongOrNull()

        /**
         * A JSON-array string of the raw lines past [sinceTs]. Each line is already a JSON object,
         * so joining them in brackets is a valid JSON array.
         *
         * [inclusive] is the `>=` / `>` split described at the top of this file. It has no default
         * on purpose: a caller that doesn't state which one it wants is a caller that hasn't
         * thought about whether its events can share a second, and either answer is silently wrong
         * for one of the four rings.
         */
        fun selectSince(lines: List<String>, sinceTs: Double, inclusive: Boolean): String {
            val kept = lines
                .filter { it.isNotBlank() }
                .filter {
                    val ts = tsOf(it) ?: Long.MIN_VALUE
                    if (inclusive) ts >= sinceTs else ts > sinceTs
                }
            return "[" + kept.joinToString(",") + "]"
        }

        /** Keep only the newest [max] lines (drop the oldest), preserving order. */
        fun trimOldest(lines: List<String>, max: Int): List<String> {
            val nonBlank = lines.filter { it.isNotBlank() }
            return if (nonBlank.size <= max) nonBlank
            else nonBlank.subList(nonBlank.size - max, nonBlank.size)
        }
    }
}
