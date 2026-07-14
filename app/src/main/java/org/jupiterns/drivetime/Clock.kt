package org.jupiterns.drivetime

import android.os.SystemClock

/**
 * The one seam between this app and the two clocks it reasons about.
 *
 * ## Why there are two
 *
 * They answer different questions, and [Health] depends on the difference:
 *
 *  - [now] — **wall clock**. What a timestamp means to a human, what goes in a JSONL row, and
 *    what the drive log is keyed by. It can jump: NTP corrects it, the user changes the time
 *    zone, a reboot restores it from a battery-backed RTC that drifted.
 *  - [sinceBoot] — **real time since boot**, deep sleep included, reset to zero by a reboot. It
 *    cannot jump and cannot be set, which is the only reason [Health.classify] can tell a reboot
 *    ("less time has passed since boot than the gap I am trying to explain") from an OEM kill.
 *    Using the wall clock for that would call every ordinary reboot a kill — the exact false
 *    alarm [Health] exists to stop telling.
 *
 * ## Why it is a seam
 *
 * The silent-stop spine is all *elapsed-time* logic — is this gap an outage, is this drive-start
 * mark stale, has the sustained stop actually been sustained — and none of it was reachable from
 * a test, because the clock was a static call. A test can move time here instead of sleeping for
 * twenty minutes, which is what makes the safety-net tests (HARDENING.md 5.4) possible at all.
 *
 * **Scope, deliberately narrow:** the spine — [Health], [Watchdog], [LocationService]'s drive
 * session — reads its time through here. Everything else (a snooze alarm, a log entry's
 * timestamp) still calls [System.currentTimeMillis] directly and should keep doing so. A clock
 * no test ever needs to move is not worth a seam, and a seam nobody uses is just indirection.
 */
object Clock {

    /** Where the time comes from. The real one below; a fake one in tests. */
    interface Source {
        fun now(): Long
        fun sinceBoot(): Long
    }

    private val REAL = object : Source {
        override fun now(): Long = System.currentTimeMillis()
        override fun sinceBoot(): Long = SystemClock.elapsedRealtime()
    }

    @Volatile private var source: Source = REAL

    /** Wall-clock milliseconds since the epoch. */
    fun now(): Long = source.now()

    /** Milliseconds of real time since boot, deep sleep included; zero at boot. */
    fun sinceBoot(): Long = source.sinceBoot()

    /** Tests only. Pass null to restore the real clock — do it in an `@After`, because this is
     *  process-global state and a test that leaves a frozen clock behind hands the *next* test a
     *  world where no time ever passes. */
    fun setForTest(s: Source?) { source = s ?: REAL }
}
