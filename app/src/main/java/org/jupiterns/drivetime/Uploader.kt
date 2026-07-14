package org.jupiterns.drivetime

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jupiterns.drivetime.obd.Elm327Client
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
 *  - **Atomic rewrites** — trimming the queue writes a temp file, fsyncs it, and renames
 *    it over the queue, so neither a kill nor a power loss mid-rewrite can corrupt or
 *    truncate it. A rewrite that fails keeps the previous queue rather than overwriting
 *    in place ([atomicWriteLocked]).
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
            obd.fuelLph?.let { o.put("fuel_lph", it) }
            obd.fuelLevel?.let { o.put("fuel_level", it) }
            obd.voltage?.let { o.put("voltage", it) }
            obd.ctrlVoltage?.let { o.put("ctrl_voltage", it) }
            if (obd.pids.isNotEmpty()) o.put("pids", JSONObject(obd.pids))   // full PID bag
            if (obd.dtcs.isNotEmpty()) o.put("dtc", JSONArray(obd.dtcs))
        }
        synchronized(LOCK) {
            ensureCountLocked()
            // runCatching like every other queue write: a full disk must degrade to a
            // dropped fix + a log line, not crash the location callback.
            runCatching { queueFile.appendText(o.toString() + "\n") }
                .onSuccess { queuedApprox++ }
                .onFailure { EventLog.warn("Queue append failed: ${it.message}") }
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
                    synchronized(LOCK) { if (queuedCountLocked() == 0) { queueFile.delete(); queuedApprox = 0 } }
                    return true
                }
                val body = JSONArray()
                batch.forEach { runCatching { body.put(JSONObject(it)) } }  // skip any corrupt line
                lastAttemptAt = System.currentTimeMillis()
                var was401 = false
                val err = try {
                    // Request.Builder().url() throws on a malformed server URL; built inside
                    // the try so that surfaces as an upload error (backoff + retry), never an
                    // escape from this thread — it crash-looped the whole app once.
                    val req = Request.Builder()
                        .url(settings.ingestUrl)
                        .header("Authorization", settings.authHeader)
                        .post(body.toString().toRequestBody(JSON))
                        .build()
                    Http.client.newCall(req).execute().use {
                        when {
                            it.isSuccessful -> null
                            it.code == 401 -> {
                                was401 = true
                                "Auth failed — re-scan the pairing QR (the token may have rotated)"
                            }
                            else -> "Server returned HTTP ${it.code}"
                        }
                    }
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                lastAuthFailed = was401
                if (err == null) {
                    // Drop exactly the lines we sent — matched by CONTENT, not count.
                    // While the POST was in flight, enqueue may have appended AND its cap
                    // trim may have rewritten the file (dropping the oldest quarter, i.e.
                    // possibly part of our in-flight head); a count-based drop would then
                    // delete newer, never-sent fixes.
                    val empty = synchronized(LOCK) { dropHeadLocked(batch) }
                    if (failures > 0) EventLog.info("Upload recovered — back online")
                    failures = 0
                    nextAttemptAt = 0L
                    lastError = null
                    lastSuccessAt = System.currentTimeMillis()
                    if (empty) return true   // else loop to send the next batch
                } else {
                    if (failures == 0 || err != lastError) EventLog.warn("Upload failed: $err")
                    lastError = err
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

    /** Cached queued count, so the dashboard can poll cheaply (no file read per tick). */
    fun queuedCount(): Int = synchronized(LOCK) { ensureCountLocked(); queuedApprox }

    /** Process-global upload health snapshot for the connection card. */
    fun health(): Health = synchronized(LOCK) {
        ensureCountLocked()
        Health(queuedApprox, lastSuccessAt, lastAttemptAt, lastError, failures, nextAttemptAt,
            lastAuthFailed)
    }

    // --- internals (all callers hold LOCK) ---

    /** Seed [queuedApprox] from disk once; thereafter enqueue/drop keep it exact. */
    private fun ensureCountLocked() { if (queuedApprox < 0) queuedApprox = queuedCountLocked() }

    private fun queuedCountLocked(): Int =
        if (queueFile.exists()) queueFile.readLines().count { it.isNotBlank() } else 0

    /** Remove the acked [sent] lines from the head of the queue, matching by content:
     *  drop leading lines only while they equal the batch in order. If the cap trim
     *  rewrote the file mid-POST the prefix match stops early — a few acked fixes may
     *  be re-sent later, which is safe (the server ingests idempotently on (ts,source));
     *  the reverse (deleting unsent fixes) is not. Returns true if the queue is empty. */
    private fun dropHeadLocked(sent: List<String>): Boolean {
        if (!queueFile.exists()) { queuedApprox = 0; return true }
        val lines = queueFile.readLines().filter { it.isNotBlank() }
        var i = 0
        while (i < sent.size && i < lines.size && lines[i] == sent[i]) i++
        val remaining = lines.drop(i)
        if (remaining.isEmpty()) { queueFile.delete(); tmpFile.delete(); queuedApprox = 0; return true }
        // A failed rewrite leaves the OLD queue on disk (acked lines and all), so the count we
        // were about to cache would under-report it. -1 reseeds from the file on next read.
        queuedApprox = if (atomicWriteLocked(remaining)) remaining.size else -1
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
        if (!atomicWriteLocked(keep)) { queuedApprox = -1; return }   // nothing dropped, count stale
        queuedApprox = keep.size
        EventLog.warn("Upload queue full — dropped ${lines.size - keep.size} oldest fixes")
    }

    /**
     * Write [lines] to a temp file and atomically rename over the queue, so an interrupted
     * rewrite leaves the previous queue intact rather than a half file. Returns false if the
     * queue is unchanged on disk (the caller's cached count is then a lie — reseed it).
     *
     * Two things make it actually atomic, and both were missing:
     *  - **fsync before the rename.** A rename is atomic with respect to the bytes the kernel
     *    has *written*, not the bytes we handed it. Without the sync, power loss between the
     *    two can publish a queue file that is correctly named and half there.
     *  - **No overwrite fallback.** The old one caught a failed rename by writing the lines
     *    straight over `queue.jsonl` — which is precisely the non-atomic write the temp file
     *    exists to avoid, run at the moment the filesystem has already said it is unhappy. It
     *    could truncate a good queue into a torn one. Failing loudly and keeping the old queue
     *    is strictly better: its head is the batch we just uploaded, so the worst case is that
     *    those fixes are re-sent, and the server ingests idempotently on (ts, source).
     */
    private fun atomicWriteLocked(lines: List<String>): Boolean = runCatching {
        val bytes = lines.joinToString("\n", postfix = "\n").toByteArray()
        FileOutputStream(tmpFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmpFile.renameTo(queueFile)) throw IOException("queue rename failed")
        true
    }.getOrElse {
        EventLog.warn("Queue rewrite failed — keeping the previous queue: ${it.message}")
        runCatching { tmpFile.delete() }
        false
    }

    companion object {
        private const val QUEUE = "queue.jsonl"
        private const val QUEUE_TMP = "queue.tmp"
        private const val MAX_BATCH = 500                 // fixes per POST
        private const val MAX_FLUSH_BATCHES = 50          // POSTs per flush() call (drain bound)
        // Hard cap on the backlog. Dropping the oldest fixes is what made the server segment a
        // strictly smaller stream than the phone, so the two disagreed about where a drive began
        // and the same drive rendered twice. The SPA now re-sends the intervals the server is
        // short of, and the server adopts the phone's boundaries either way — but a cap this
        // generous means an offline stretch rarely reaches the drop path at all.
        private const val MAX_QUEUE_BYTES = 64L * 1024 * 1024
        private const val BACKOFF_BASE_MS = 5_000L
        private const val BACKOFF_MAX_MS = 5 * 60_000L
        private val JSON = "application/json".toMediaType()

        /** Process-wide guard for the shared queue file (per-instance @Synchronized
         *  isn't enough — service, watchdog and UI each hold a separate Uploader). */
        private val LOCK = Any()
        private val flushing = AtomicBoolean(false)
        @Volatile private var failures = 0
        @Volatile private var nextAttemptAt = 0L

        // Upload health, shared process-wide (service + UI are one process).
        @Volatile private var lastSuccessAt = 0L
        @Volatile private var lastAttemptAt = 0L
        @Volatile private var lastError: String? = null
        @Volatile private var lastAuthFailed = false   // last failure was a 401 (typed, not string-matched)
        @Volatile private var queuedApprox = -1   // -1 = not yet seeded from disk

        /** Test-only: forget the process-global queue state — the cached count (so the next
         *  access re-seeds from disk) *and* the backoff window. All of it is static (shared
         *  across instances, and across test methods in one JVM), so a test that drives a
         *  failing flush would otherwise leave every later test inside a backoff window,
         *  where flush() returns early and asserts nothing. */
        internal fun resetQueueCacheForTest() {
            synchronized(LOCK) {
                queuedApprox = -1
                failures = 0
                nextAttemptAt = 0L
                lastError = null
                lastAuthFailed = false
            }
        }

        /** A glanceable snapshot of upload state for the dashboard's connection card.
         *  [authFailed] is the typed 401 signal (the UI used to string-match the message). */
        data class Health(
            val queued: Int,
            val lastSuccessAt: Long,
            val lastAttemptAt: Long,
            val lastError: String?,
            val failures: Int,
            val backoffUntil: Long,
            val authFailed: Boolean = false,
        )

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
