package org.jupiterns.drivetime

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/**
 * The tracker's own liveness ledger — the thing that turns "we have no fixes for this window"
 * from a *guess* into a *fact*.
 *
 * ## Why this exists
 *
 * The only thing that ever writes a GPS fix is the FusedLocationProvider callback. So a logger
 * that Android has Doze-throttled, and a logger the OEM battery manager killed outright, write
 * exactly the same amount: nothing. Four features were once built on a comment claiming "the
 * logger heartbeats every ~60s even parked, so silence means it died" — an aspiration stated as
 * fact, which nothing enforced. It made the app announce a dead tracker every time the car sat
 * parked for an hour. Silence is not evidence.
 *
 * ## Why it is NOT a punctual pulse
 *
 * The obvious fix — "write a row every 60s; a missing row means we were dead" — does not work on
 * Android, and would rebuild the same bug in new clothes. A coroutine `delay` does not tick while
 * the CPU is in deep sleep: a phone in a pocket will happily stretch a 60s tick into a 20-minute
 * one. A missing beat would mean *dead OR asleep*, and we would be right back to inferring death
 * from absence.
 *
 * So the beat is not a pulse to be counted — it is a **continuously-updated proof of life**, and
 * the unit of identity is the **process**, not the tick:
 *
 *  - [beat] bumps [Settings.lifeBeatAt] (throttled) from whatever the service is already doing —
 *    a fix, an upload flush, a watchdog pass. A *late* beat from a live process still proves the
 *    process lived through the interval, because the same process wrote both ends of it. Doze
 *    stops being a problem: we never ask "was the beat on time", only "is this the same life".
 *  - [endLife] stamps the moment and the REASON a life ended, from `onDestroy`.
 *  - [startLife] runs in `onCreate` — and anything it finds in prefs can only describe a
 *    *predecessor*, since this process has not written any of it yet. That is the whole trick:
 *    the process boundary is the instance identity, so no uuid is needed. If the predecessor
 *    never got to run `onDestroy`, it was **killed**, and the interval between its last beat and
 *    now is downtime we can state as a fact.
 *
 * ## What lands on disk
 *
 * `web_health.jsonl`, drained by the SPA over the `DrivetimeNative.pullHealth(sinceTs)` bridge
 * exactly like fixes and markers. Two row kinds, both rare (a handful a day — this is O(outages),
 * not O(minutes), which is the other reason not to write a row per tick):
 *
 *  - `{"kind":"down","ts":<to>,"from":…,"to":…,"reason":…,"fault":<bool>}` — the tracker was not
 *    running between `from` and `to`. `reason` is what [classify] decided; `fault` separates "your
 *    phone killed it and you lost drives" from "you turned it off" / "the phone rebooted", which
 *    is the distinction the old gap feature never had and the reason it cried wolf.
 *  - `{"kind":"cond","ts":…,"loc_on":…,"perms":…,"saver":…}` — a TRANSITION in the conditions the
 *    tracker needs to do its job. Written only when the tuple changes, so it costs nothing while
 *    everything is fine. This is what will let the app say "Location was off from 14:02" instead
 *    of "drives may be missing", and is the evidence the still-unbuilt "alive but blind" alarm
 *    (heartbeat present + moving + no fixes) will be tuned against.
 */
object Health {
    private const val FILE = "web_health.jsonl"
    /** Rows are written per outage/transition, not per tick, so this is years of history. */
    private const val MAX_ROWS = 2_000
    private val LOCK = Any()

    /** Don't re-stamp proof-of-life more often than this. The floor under it is the [Watchdog]'s
     *  15-minute period — the one tick Doze cannot defer indefinitely — so even a phone in deep
     *  sleep with no fixes at all keeps a beat, and the death timestamp stays bounded. */
    private const val BEAT_MS = 60_000L

    /** Below this, an absence is a *restart*, not an outage: an app update, a START_STICKY
     *  bounce, a tier reconfigure. Recording those as downtime would bury the real thing in
     *  noise — the same mistake, one layer down. */
    private const val MIN_DOWN_MS = 180_000L

    /** Recording an outage is free; *interrupting the user* about one is not. So the shade has a
     *  much higher bar than the ledger: we write down every outage past [MIN_DOWN_MS], and speak
     *  only for the ones big enough to have actually cost a drive. Same 20 minutes the
     *  [Watchdog] already uses, so the two can never disagree about what is worth saying. */
    private const val NOTIFY_DOWN_MS = 20L * 60_000L

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * The logging service is starting. Close the books on the previous life first: whatever sits
     * in [Settings] right now was written by a process that is gone, so if it left an unexplained
     * absence behind, that absence is downtime and we can finally bound it — `to` is now, and this
     * is the only moment both ends are known, which is why a `down` row is written on the way UP.
     */
    fun startLife(context: Context, s: Settings) {
        val now = Clock.now()
        val prevBeat = s.lifeBeatAt
        if (prevBeat > 0) {
            val endedAt = s.lifeEndedAt
            // A clean stop knows exactly when it happened; a kill only tells us "sometime after
            // the last beat", so the last beat is the honest lower bound.
            val from = if (endedAt > 0) endedAt else prevBeat
            val reason = classify(endedAt > 0, s.lifeEndReason, deviceRebooted(now - prevBeat))
            if (now - from >= MIN_DOWN_MS) recordDown(context, s, from, now, reason)
        }
        s.lifeEndedAt = 0L
        s.lifeEndReason = ""
        beat(context, s, force = true)
    }

    /** The service is stopping, and — unlike a kill — it gets to say so. [Settings.loggingEnabled]
     *  is the same flag `onDestroy` already reads to tell an intentional stop from an OS one: it
     *  is still set when the system took the service away from us. */
    fun endLife(context: Context, s: Settings) {
        val now = Clock.now()
        s.lifeBeatAt = now          // alive right up to here
        s.lifeEndedAt = now
        s.lifeEndReason = if (s.loggingEnabled) "system" else "stop"
    }

    /**
     * Proof the logging process is alive, stamped from whatever it happens to be doing. Cheap and
     * idempotent — call it freely; the [BEAT_MS] throttle keeps it to one prefs write a minute.
     * Also samples the conditions the tracker depends on, and records a row only when they CHANGE.
     */
    fun beat(context: Context, s: Settings, force: Boolean = false) {
        val now = Clock.now()
        if (!force && now - s.lifeBeatAt < BEAT_MS) return
        s.lifeBeatAt = now
        val (locOn, perms, saver) = sample(context, s)
        val sig = "$locOn|$perms|$saver"
        if (sig == s.healthCond) return
        s.healthCond = sig
        if (!locOn) EventLog.warn("Location services are OFF — the tracker cannot record")
        append(context, JSONObject()
            .put("kind", "cond")
            .put("ts", now / 1000)
            .put("loc_on", locOn)
            .put("perms", perms)
            .put("saver", saver))
    }

    /**
     * State the outage, once, and — when it is our problem — say so out loud. The duration here is
     * REAL (both ends are known), unlike the watchdog's estimate, which is why this is also the
     * place the durable record is written.
     *
     * The watchdog may already have announced this episode *while it was happening* (it notices a
     * dead service before the restart it then performs brings us here). `lastKillNotifiedAt`
     * landing inside the outage is how we know that, and is what keeps one interruption to one
     * notification.
     */
    private fun recordDown(context: Context, s: Settings, fromMs: Long, toMs: Long, reason: String) {
        val fault = reason == "killed" || reason == "system"
        val mins = (toMs - fromMs) / 60_000L
        append(context, JSONObject()
            .put("kind", "down")
            .put("ts", toMs / 1000)
            .put("from", fromMs / 1000)
            .put("to", toMs / 1000)
            .put("reason", reason)
            .put("fault", fault))
        EventLog.warn("Logging was down ~${mins}min ($reason)")
        if (!fault) return
        // The banner stands for any fault (it's a standing condition — "your phone does this"),
        // but the shade only speaks for an outage long enough to have swallowed a real drive.
        s.lastKillDetectedAt = toMs
        if (toMs - fromMs < NOTIFY_DOWN_MS) return

        // The gap, as a recorded fact with both ends known — the same row the app's own bell has
        // always shown as a "coverage gap", now available to the OS too (default OFF; the two
        // deliberately overlap, see Settings.notifyCoverageGap).
        //
        // Posted BEFORE the watchdog gate below and keyed by `fromMs` rather than HEALTH_ID: this
        // one is not a standing condition to be replaced in place, it is a dated entry in a log —
        // two outages are two facts, and neither retracts the other. It also therefore posts even
        // when the watchdog already cried out mid-episode, because "the tracker seems to be dead"
        // and "you lost 14:02–15:10" are different statements and only this one is final.
        Notify.post(
            context, Notify.KIND_COVERAGE_GAP, (fromMs / 1000).toString(),
            "Tracking gap: ~${mins} min",
            "The tracker recorded that it was not running from ${clock(fromMs)} to ${clock(toMs)}. " +
                "Any drives in that window were not captured.",
            "/drives",
        )

        if (s.lastKillNotifiedAt >= fromMs) return   // the watchdog already said it
        s.lastKillNotifiedAt = toMs
        Notify.post(
            context, Notify.KIND_TRACKING_HEALTH, Notify.HEALTH_ID,
            "Drive tracking was interrupted",
            "The tracker wasn't running for ~$mins minutes, so any drives in that window are " +
                "missing. Open Settings → Access & battery to keep it running.",
            "/settings",
        )
    }

    /** Wall-clock HH:mm for the coverage-gap body — the user thinks in clock time, not epochs. */
    private fun clock(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    /** JSON array (string) of every health row at or after [sinceTs] — at-or-after, like markers:
     *  two rows can share a second, and the SPA keys them by `kind|ts`, so re-delivery is a no-op
     *  but a skipped boundary would lose one forever. */
    fun pullSince(context: Context, sinceTs: Double): String =
        synchronized(LOCK) { selectSince(file(context).readLinesOrEmpty(), sinceTs) }

    private fun append(context: Context, o: JSONObject) {
        synchronized(LOCK) {
            val f = file(context)
            runCatching { f.appendText(o.toString() + "\n") }
                .onFailure { EventLog.warn("Health buffer append failed: ${it.message}") }
            // Rows are rare, so trim on every append rather than amortising (the WebMarkerBuffer
            // precedent): the read costs nothing at this size, and an unbounded file is worse.
            val lines = f.readLinesOrEmpty()
            val trimmed = trimOldest(lines, MAX_ROWS)
            if (trimmed.size < lines.size) {
                runCatching { f.writeText(trimmed.joinToString("\n", postfix = "\n")) }
            }
        }
    }

    /**
     * Did the device boot during a gap of [gapMs]? [Clock.sinceBoot] counts real time since boot
     * (deep sleep included) and resets to zero on boot — so if less of it has passed than the gap
     * we are trying to explain, the gap contains a boot.
     *
     * This matters more than it looks: a graceful shutdown DOES run `onDestroy` with logging still
     * enabled, which is indistinguishable from the OS killing the service. Without this check every
     * ordinary reboot would be reported as "your phone killed the tracker" — the exact false alarm
     * this whole file exists to stop telling.
     */
    private fun deviceRebooted(gapMs: Long): Boolean = Clock.sinceBoot() < gapMs

    /** The conditions the logger needs to do its job. Every probe defaults to the HEALTHY value on
     *  failure: a probe that throws must never be able to manufacture an alarm. */
    private fun sample(context: Context, s: Settings): Triple<Boolean, Boolean, Boolean> {
        val locOn = runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            when {
                lm == null -> true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> lm.isLocationEnabled
                // minSdk 26: isLocationEnabled is API 28, so ask the providers directly.
                else -> lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(true)
        val perms = runCatching { Permissions.snapshot(context, s).isReady }.getOrDefault(true)
        val saver = runCatching {
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }.getOrDefault(false)
        return Triple(locOn, perms, saver)
    }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()

    // ---- pure helpers (JVM-unit-testable; no Android, no file I/O) ----

    /**
     * What ended the previous life, in the order the evidence actually settles:
     *
     *  - **stop** — the user turned tracking off (or snoozed it). Not a fault, and it outranks a
     *    reboot: if you switched it off on Friday and rebooted on Saturday, the tracker was off
     *    because you turned it off, not because the phone restarted.
     *  - **reboot** — the device booted during the gap. Checked BEFORE `system` on purpose: a
     *    clean shutdown stops the service exactly the way a low-memory kill does, and blaming the
     *    battery manager for a reboot is precisely the lie we are here to stop telling.
     *  - **system** — `onDestroy` ran while logging was still meant to be on. The OS took the
     *    service away.
     *  - **killed** — no `onDestroy` at all and no reboot: the process was destroyed outright,
     *    which on Android means the OEM battery manager. This is the one that loses drives.
     *
     * A hard kill that is only noticed *after* a reboot reads as "reboot" and understates. That is
     * deliberate — the two are genuinely indistinguishable by then, and of the two possible lies,
     * under-blaming the phone is the one that doesn't send the user to fix a setting that was never
     * the problem.
     */
    fun classify(hadDestroy: Boolean, endReason: String, rebooted: Boolean): String = when {
        hadDestroy && endReason == "stop" -> "stop"
        rebooted -> "reboot"
        hadDestroy -> "system"
        else -> "killed"
    }

    private val TS = Regex("\"ts\"\\s*:\\s*(\\d+)")

    /** The integer `ts` of a health row, or null if absent/garbled. */
    fun tsOf(line: String): Long? = TS.find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** A JSON-array string of the raw rows whose ts >= [sinceTs]. Each line is already a JSON
     *  object, so joining them in brackets is a valid JSON array. */
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
