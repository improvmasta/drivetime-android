package org.jupiterns.drivetime.obd

/**
 * The OBD loop's *judgement*, lifted out of its I/O.
 *
 * What was left behind in [org.jupiterns.drivetime.LocationService] is the part that genuinely
 * needs Android — a `BluetoothManager`, a socket, a coroutine to block on. What moved here is
 * everything that is actually a *decision*, and every one of them was both subtle and untested:
 * when it is worth dialling the dongle at all, what a failed connect means, and how long to wait
 * before trying again. They are the reason the loop had three separate multi-line comments
 * explaining bugs it had already caused.
 *
 * One session object lives for the whole loop, not for one connection — the streak it counts is
 * the thing a single attempt cannot see.
 */
class ObdSession {

    /**
     * Consecutive "opened a socket but the adapter never answered" failures.
     *
     * The distinction this counts is the whole point: a cheap ELM327 clone that was already
     * powered when the engine started (a remote start, most often) will happily *accept* a
     * Bluetooth socket and then never answer a single command. It is not absent, it is wedged —
     * and retrying it fast forever is exactly the wrong response.
     */
    var wedgedStreak = 0
        private set

    /** The adapter answered. Whatever it was doing before, it is healthy now. */
    fun onConnected() {
        wedgedStreak = 0
    }

    /**
     * The attempt failed. [everConnected] is what separates the two failures that look identical
     * from the outside: a socket that opened but never spoke (wedged — count it), and a link that
     * was working and then dropped mid-drive (out of range, dongle yanked, engine off — do not).
     * Counting a mid-drive drop would let a normal drive escalate itself into a cold reset and
     * then sit out twenty seconds of a live trip with no telemetry.
     */
    fun onFailure(everConnected: Boolean) {
        if (!everConnected) wedgedStreak++
    }

    /** The cold reset has been performed; the streak has done its job. */
    fun onColdReset() {
        wedgedStreak = 0
    }

    /**
     * How long to wait before the next attempt, and whether to escalate.
     *
     * Not driving is the cheap case: probe slowly, because the only cost of being late is a few
     * seconds of missing telemetry at the start of a drive the GPS is logging anyway. Driving is
     * the expensive case: every second without the dongle is a second of a real trip with no engine
     * data, so retry fast — unless the adapter has now refused to speak [WEDGE_LIMIT] times in a
     * row, in which case retrying fast is precisely what is *not* working, and the answer is to
     * stop talking to it long enough for its own firmware watchdog to reset it. That quiet,
     * fully-disconnected window is the closest we can get to power-cycling the plug without
     * touching it.
     */
    fun recovery(driving: Boolean): Recovery = when {
        !driving -> Recovery.Idle(IDLE_MS)
        wedgedStreak >= WEDGE_LIMIT -> Recovery.ColdReset(COLD_PAUSE_MS)
        else -> Recovery.Retry(RETRY_DRIVE_MS)
    }

    sealed interface Recovery {
        val delayMs: Long

        /** Not in a car (or not driving): probe slowly and spare the battery. */
        data class Idle(override val delayMs: Long) : Recovery

        /** Driving with no dongle: get it back fast. */
        data class Retry(override val delayMs: Long) : Recovery

        /** The adapter keeps accepting a socket without answering. Drop the cached socket strategy
         *  and stay away long enough for it to reset itself. */
        data class ColdReset(override val delayMs: Long) : Recovery
    }

    companion object {

        /**
         * Is there any reason to think we are in a *running* car right now?
         *
         * A configured-but-off dongle must not be dialled 24/7 (it is a Bluetooth connect attempt
         * every few seconds, forever, on a phone in a drawer), so this gates the whole loop. Each
         * arm is load-bearing, and the last two are the interesting ones:
         *
         *  - **[driving]** is what makes the loop self-sustaining, and is also what once made it a
         *    trap: a connected dongle pins the DRIVING tier, and DRIVING re-probes the dongle within
         *    seconds of any drop — a closed loop with no exit, which polled a dead ECU in a parking
         *    lot for hours and held the dense GPS tier there with it.
         *  - **[parked]** is what breaks that loop, and it must be checked *outside* the others, not
         *    folded into one of them. An OBD-II port is permanently powered, so the dongle is still
         *    reachable with the ignition off; "we can reach it" was never evidence that we should.
         *    Re-promotion costs nothing when the car does move again, because the first moving fix
         *    clears `parked` and there is nothing to re-acquire — the dongle never went anywhere.
         */
        fun shouldProbe(
            parked: Boolean,
            carConnected: Boolean,
            movingHint: Boolean,
            driving: Boolean,
        ): Boolean = !parked && (carConnected || movingHint || driving)

        /**
         * Is the engine actually turning, on the evidence of one rpm reading?
         *
         * Deliberately *not* `rpm > 0`. This is the only OBD signal that feeds a tracking
         * decision — it is what holds `DriveDetector.parked` off, so an idling car is not
         * mistaken for a parked one — and it is therefore the only place a bad frame from a
         * cheap adapter can cost a drive. A turning engine idles somewhere near 600–900 rpm and
         * does not survive past redline; a decode that lands on 1, or on 16 000, is a garbled or
         * stale frame, not an engine. Reading it as "running" is how a dongle left in a parked
         * car keeps the tier pinned to DRIVING.
         *
         * Null (the adapter said NO DATA, or the PID is unsupported) is not-running, which is the
         * safe answer: it lets the car park. The detector bounds this signal regardless — see
         * `DriveDetector.ENGINE_HOLD_MAX_MS` — so this check is the first of two nets, not the
         * only one.
         */
        fun engineRunning(rpm: Int?): Boolean = (rpm ?: 0) in RPM_RUNNING

        /** The band an rpm reading has to fall in to count as a turning engine. */
        val RPM_RUNNING = 250..8_000

        /** Poll cadence, boosted while the live dashboard is open so the RPM/throttle gauges keep
         *  up. ~0.8 s is within a cheap clone's reach; it is a short, screen-on, user-attended
         *  window, so the extra draw is self-limiting. */
        fun pollDelayMs(dashboardBoost: Boolean): Long = if (dashboardBoost) BOOST_MS else POLL_MS

        /** Re-read the trouble codes every Nth sample rather than every one — they change on the
         *  order of never, and the read is a separate round-trip to the ECU. */
        fun readDtcsOnTick(tick: Int): Boolean = tick % DTC_EVERY == 0

        /** After this many back-to-back "socket opened but no answer" connects *while driving*,
         *  stop retrying and force the cold reset. */
        const val WEDGE_LIMIT = 3

        /** Recheck/probe interval when not driving. */
        const val IDLE_MS = 120_000L

        /** Fast reconnect while driving — every second here is a second of a live trip with no
         *  engine data. */
        const val RETRY_DRIVE_MS = 5_000L

        /** How long to stay fully disconnected so a wedged clone's firmware watchdog can reset it. */
        const val COLD_PAUSE_MS = 20_000L

        /** Ordinary OBD poll cadence. */
        const val POLL_MS = 1_500L

        /** Boosted poll while the live dashboard is open. */
        const val BOOST_MS = 800L

        private const val DTC_EVERY = 120
    }
}
