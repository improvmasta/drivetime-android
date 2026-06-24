package org.jupiterns.drivetime

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jupiterns.drivetime.obd.Elm327Client
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Batches GPS fixes and POSTs them to drivetime's /api/ingest. Built for the #1
 * robustness rule — never lose a fix the phone managed to record:
 *
 *  - **Durable queue** on disk (`queue.jsonl`); survives crash/kill.
 *  - **Verify-before-delete** — a batch is removed from the queue only after the
 *    server acks it, and only the exact lines that were sent are dropped, so fixes
 *    appended during an in-flight POST are never discarded.
 *  - **Size cap** — the queue is bounded; on overflow the *oldest* fixes are dropped
 *    (a live fix matters more than a week-old one) rather than growing until OOM.
 *  - **Backoff + jitter** — repeated failures back off exponentially so a dead
 *    server doesn't get hammered and the radio isn't kept hot.
 *  - **Atomic rewrites** — trimming the queue writes a temp file and renames it, so
 *    a kill mid-rewrite can't corrupt or truncate the queue.
 *
 * The queue file is process-global state: the logging service, the watchdog, and
 * the UI may each hold their own Uploader. All file access therefore takes a
 * process-wide [LOCK], and flushing is single-flight via [flushing] so two callers
 * can't send-and-trim the same head of the queue and drop newer fixes.
 *
 * Payload is OwnTracks-shaped so it works with the existing server endpoint:
 *   {"_type":"location","lat":..,"lon":..,"tst":<epoch_s>,"vel":<km/h>,"acc":..,"cog":..}
 */
class Uploader(context: Context, private val settings: Settings) {
    private val queueFile = File(context.filesDir, QUEUE)
    private val tmpFile = File(context.filesDir, QUEUE_TMP)

    @Synchronized
    fun enqueue(lat: Double, lon: Double, epochSec: Long, speedMps: Float?, accuracyM: Float?,
                courseDeg: Float?, obd: Elm327Client.ObdSample? = null) {
        val o = JSONObject()
            .put("_type", "location")
            .put("lat", lat).put("lon", lon).put("tst", epochSec)
        if (speedMps != null) o.put("vel", Math.round(speedMps * 3.6))   // km/h, OwnTracks units
        if (accuracyM != null) o.put("acc", Math.round(accuracyM))
        if (courseDeg != null) o.put("cog", Math.round(courseDeg))
        if (obd != null) {
            obd.rpm?.let { o.put("rpm", it) }
            obd.obdKph?.let { o.put("obd_kph", it) }
            obd.engineLoad?.let { o.put("engine_load", it) }
            obd.coolantC?.let { o.put("coolant_c", it) }
            obd.throttle?.let { o.put("throttle", it) }
            obd.maf?.let { o.put("maf", it) }
            obd.voltage?.let { o.put("voltage", it) }
            if (obd.dtcs.isNotEmpty()) o.put("dtc", JSONArray(obd.dtcs))
        }
        synchronized(LOCK) {
            queueFile.appendText(o.toString() + "\n")
            enforceCapLocked()
        }
    }

    /**
     * Drain the queue, sending it in [MAX_BATCH]-sized POSTs until it's empty (so a
     * backlog from a dead-zone clears in one call, not one batch per flush). The
     * network call happens outside [LOCK] (a slow POST never blocks the locator
     * thread appending fixes) and is guarded by [flushing] so only one flush runs at
     * a time. Returns true when the queue is empty afterwards.
     */
    fun flush(): Boolean {
        if (!settings.isConfigured) return false
        if (System.currentTimeMillis() < nextAttemptAt) return false   // in backoff window
        if (!flushing.compareAndSet(false, true)) return false          // another flush in flight
        try {
            var sweeps = 0
            while (sweeps++ < MAX_FLUSH_BATCHES) {
                val batch = synchronized(LOCK) {
                    if (!queueFile.exists()) return true
                    queueFile.readLines().filter { it.isNotBlank() }.take(MAX_BATCH)
                }
                if (batch.isEmpty()) {
                    synchronized(LOCK) { if (queuedCountLocked() == 0) queueFile.delete() }
                    return true
                }
                val body = JSONArray()
                batch.forEach { runCatching { body.put(JSONObject(it)) } }  // skip any corrupt line
                val req = Request.Builder()
                    .url(settings.ingestUrl)
                    .header("Authorization", settings.authHeader)
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val ok = try {
                    client.newCall(req).execute().use { it.isSuccessful }
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    // Drop exactly the lines we sent. Only enqueue (append-only) can have
                    // touched the file meanwhile, so the head is still our batch — even if
                    // one or more lines were unparseable, they're consumed and won't wedge.
                    val empty = synchronized(LOCK) { dropHeadLocked(batch.size) }
                    failures = 0
                    nextAttemptAt = 0L
                    if (empty) return true   // else loop to send the next batch
                } else {
                    failures++
                    nextAttemptAt = System.currentTimeMillis() + backoffMs(failures)
                    return false
                }
            }
            return false   // hit the per-call sweep cap; the next flush continues draining
        } finally {
            flushing.set(false)
        }
    }

    fun queuedCount(): Int = synchronized(LOCK) { queuedCountLocked() }

    // --- internals (all callers hold LOCK) ---

    private fun queuedCountLocked(): Int =
        if (queueFile.exists()) queueFile.readLines().count { it.isNotBlank() } else 0

    /** Remove the first [n] non-blank lines; returns true if the queue is now empty. */
    private fun dropHeadLocked(n: Int): Boolean {
        val remaining = queueFile.readLines().filter { it.isNotBlank() }.drop(n)
        if (remaining.isEmpty()) { queueFile.delete(); tmpFile.delete(); return true }
        atomicWriteLocked(remaining)
        return false
    }

    /** Keep the queue bounded: once it exceeds the byte cap, drop the oldest quarter
     *  in one rewrite (cheaper than trimming a line per fix). A live fix beats a stale
     *  one, so we keep the newest. */
    private fun enforceCapLocked() {
        if (queueFile.length() <= MAX_QUEUE_BYTES) return
        val lines = queueFile.readLines().filter { it.isNotBlank() }
        if (lines.size <= 1) return
        val keep = lines.subList(lines.size / 4, lines.size)
        atomicWriteLocked(keep)
    }

    /** Write [lines] to a temp file and atomically rename over the queue, so an
     *  interrupted rewrite leaves the previous queue intact rather than a half file. */
    private fun atomicWriteLocked(lines: List<String>) {
        tmpFile.writeText(lines.joinToString("\n", postfix = "\n"))
        if (!tmpFile.renameTo(queueFile)) {
            // Rename can fail across some FS states; fall back to a direct overwrite.
            queueFile.writeText(lines.joinToString("\n", postfix = "\n"))
            tmpFile.delete()
        }
    }

    companion object {
        private const val QUEUE = "queue.jsonl"
        private const val QUEUE_TMP = "queue.tmp"
        private const val MAX_BATCH = 500                 // fixes per POST
        private const val MAX_FLUSH_BATCHES = 50          // POSTs per flush() call (drain bound)
        private const val MAX_QUEUE_BYTES = 16L * 1024 * 1024   // ~16 MB hard cap on backlog
        private const val BACKOFF_BASE_MS = 5_000L
        private const val BACKOFF_MAX_MS = 5 * 60_000L
        private val JSON = "application/json".toMediaType()

        /** Process-wide guard for the shared queue file (per-instance @Synchronized
         *  isn't enough — service, watchdog and UI each hold a separate Uploader). */
        private val LOCK = Any()
        private val flushing = AtomicBoolean(false)
        @Volatile private var failures = 0
        @Volatile private var nextAttemptAt = 0L

        /** One client for the whole process so the watchdog doesn't build one per tick. */
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /** Exponential backoff capped at [BACKOFF_MAX_MS], with ±25% jitter so many
         *  retries don't synchronize into a thundering herd. */
        private fun backoffMs(failures: Int): Long {
            val exp = BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 16)
            val capped = exp.coerceAtMost(BACKOFF_MAX_MS)
            val jitter = (capped * 0.25).toLong()
            return capped - jitter + Random.nextLong(2 * jitter + 1)
        }
    }
}
