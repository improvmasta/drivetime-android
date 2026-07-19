package org.jupiterns.drivetime

/**
 * The accel event extractor (INSIGHTS P3a): continuous accelerometer *while the driving tier
 * is active only*, consumed at the edge — what leaves this class is events, never samples.
 *
 * v1 metric is **gravity-removed magnitude fused with GPS speed deltas**, deliberately with
 * no orientation math (that's P4, if lived results ask for it):
 *
 *  - The accelerometer runs batched (the service registers it with a report latency, so the
 *    CPU naps) and is reduced here to a per-second peak of |‖a‖ − g| — O(1) work per sample,
 *    no allocation churn on the hot path (the kill-risk guard: [Health]'s ledger is the
 *    regression detector if kill frequency rises).
 *  - The GPS speed delta between consecutive dense-tier fixes *decides* — a phone knocked off
 *    its mount produces accel spikes but no speed change, so accel alone never mints an event.
 *    The accel window then *refines*: the event's timestamp is the second the sensor actually
 *    peaked inside the fix pair, and the peak magnitude rides along as `mag`.
 *  - The one accel-only signal is the **launch spike at standstill** (INSIGHTS P3b): speed ~0
 *    and the sensor jumps ⇒ the car is launching *right now*, worth a 1 s GPS burst before
 *    the 3 s cadence would even notice. That fires [onBurst], never an event.
 *
 * Thread contract: every method is called on the service's reconciler thread (samples are
 * delivered on its looper, fixes originate there), so the fields are single-threaded plain —
 * the same confinement discipline as the tier state (see [TierReconciler]).
 *
 * Pure Kotlin, no Android imports: the service feeds [onAccelSample]/[onFix], which makes the
 * whole detection surface JVM-unit-testable (AccelExtractorTest).
 */
class AccelExtractor(
    private val onEvent: (Event) -> Unit,
    private val onBurst: () -> Unit,
) {
    data class Event(
        val ts: Long, val kind: String,
        val mag: Double?, val mphs: Double, val fromMph: Double, val toMph: Double,
    )

    companion object {
        const val GRAVITY = 9.81
        /** GPS-delta braking at or past this (mph/s, negative) is a hard brake. ~-7 mph/s ≈ 0.32 g. */
        const val HARD_BRAKE_MPHS = -7.0
        /** GPS-delta launch at or past this (mph/s) from standstill is a hard launch. */
        const val HARD_LAUNCH_MPHS = 6.0
        /** "Standstill" for launch detection (mph). */
        const val STANDSTILL_MPH = 2.0
        /** A fix pair farther apart than this can't attribute a rate honestly (s). */
        const val MAX_FIX_GAP_S = 6L
        /** Accel-window peak (m/s², gravity-removed) that reads as a launch while stopped. */
        const val LAUNCH_SPIKE_MS2 = 2.5
        /** Per-kind event cooldown: one braking maneuver is one event, not one per fix (s). */
        const val EVENT_COOLDOWN_S = 5L
        /** Standstill-spike burst cooldown (ms) — a stop-and-go queue must not re-trigger per creep. */
        const val SPIKE_BURST_COOLDOWN_MS = 30_000L
        /** How many 1 s peak buckets to keep for timestamp/magnitude refinement. */
        const val PEAK_KEEP_S = 30
    }

    // per-second peak ring: peaks[sec % PEAK_KEEP_S] holds (sec, peak). Plain arrays, no churn.
    private val peakSec = LongArray(PEAK_KEEP_S)
    private val peakVal = DoubleArray(PEAK_KEEP_S)

    private var lastFixTs = 0L
    private var lastFixMph = -1.0 // <0 = unknown
    private var lastBrakeTs = 0L
    private var lastLaunchTs = 0L
    private var lastSpikeBurstMs = 0L

    /** One accelerometer sample (epoch ms + raw axes). Reduced to a per-second peak. */
    fun onAccelSample(epochMs: Long, x: Float, y: Float, z: Float) {
        val mag = Math.abs(Math.sqrt((x * x + y * y + z * z).toDouble()) - GRAVITY)
        val sec = epochMs / 1000
        val slot = (sec % PEAK_KEEP_S).toInt()
        if (peakSec[slot] != sec) {
            peakSec[slot] = sec
            peakVal[slot] = mag
        } else if (mag > peakVal[slot]) {
            peakVal[slot] = mag
        }
        // Launch-from-standstill spike (P3b): the car is moving before GPS knows it.
        if (lastFixMph in 0.0..STANDSTILL_MPH && mag >= LAUNCH_SPIKE_MS2 &&
            epochMs - lastSpikeBurstMs >= SPIKE_BURST_COOLDOWN_MS
        ) {
            lastSpikeBurstMs = epochMs
            onBurst()
        }
    }

    /** The peak accel magnitude (and its second) inside (fromSec, toSec], or null. */
    fun peakIn(fromSec: Long, toSec: Long): Pair<Long, Double>? {
        var bestSec = -1L
        var bestVal = 0.0
        for (i in 0 until PEAK_KEEP_S) {
            val s = peakSec[i]
            if (s in (fromSec + 1)..toSec && (bestSec < 0 || peakVal[i] > bestVal)) {
                bestSec = s
                bestVal = peakVal[i]
            }
        }
        return if (bestSec >= 0) bestSec to bestVal else null
    }

    /**
     * One dense-tier GPS fix. The speed delta against the previous fix decides whether an
     * event happened; the accel ring refines its timestamp and magnitude.
     */
    fun onFix(epochSec: Long, mph: Double?) {
        val prevTs = lastFixTs
        val prevMph = lastFixMph
        lastFixTs = epochSec
        lastFixMph = mph ?: -1.0
        if (mph == null || prevMph < 0 || prevTs <= 0) return
        val dt = epochSec - prevTs
        if (dt < 1 || dt > MAX_FIX_GAP_S) return
        val rate = (mph - prevMph) / dt
        if (rate <= HARD_BRAKE_MPHS && epochSec - lastBrakeTs >= EVENT_COOLDOWN_S) {
            lastBrakeTs = epochSec
            val peak = peakIn(prevTs, epochSec)
            onEvent(Event(peak?.first ?: prevTs, "brake", peak?.second, r1(rate), prevMph, mph))
            onBurst() // sharpen the rest of the decel profile with 1 s fixes
        } else if (prevMph <= STANDSTILL_MPH && rate >= HARD_LAUNCH_MPHS &&
            epochSec - lastLaunchTs >= EVENT_COOLDOWN_S
        ) {
            lastLaunchTs = epochSec
            val peak = peakIn(prevTs, epochSec)
            onEvent(Event(peak?.first ?: prevTs, "launch", peak?.second, r1(rate), prevMph, mph))
            onBurst() // the 0-60 in progress deserves 1 s resolution
        }
    }

    /** Forget everything (tier left DRIVING) — a stale peak must not time a future event. */
    fun reset() {
        for (i in 0 until PEAK_KEEP_S) peakSec[i] = 0L
        lastFixTs = 0L
        lastFixMph = -1.0
    }

    private fun r1(v: Double) = Math.round(v * 10.0) / 10.0
}
