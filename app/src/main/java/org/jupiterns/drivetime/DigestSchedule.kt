package org.jupiterns.drivetime

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * When the weekly digest ([DigestWorker], NOTIFICATIONS.md P4) next fires. Pure — no Android,
 * no clock of its own — because "Monday at 18:00, every week, forever" is the whole feature
 * and CI is the only Kotlin compiler we have: a wrong delay would only ever show up as a
 * notification that arrives on the wrong day, a week later. [DigestScheduleTest] pins it.
 *
 * The slot is recomputed from the wall clock at every fire (self-rescheduling one-time work)
 * rather than run as 7-day periodic work: local time is what the user set, so a DST boundary
 * must not walk the digest an hour off — `plusWeeks` on a [ZonedDateTime] keeps 18:00 at 18:00.
 */
object DigestSchedule {

    const val DEFAULT_TIME = "18:00"
    /** Monday, in the JS `Date.getDay()` numbering the SPA's day picker sends (0 = Sunday). */
    const val DEFAULT_DAY = 1

    /** Milliseconds from [nowMs] to the next [day]-at-[time] in [zone]; always > 0 (a slot
     *  that is exactly now, or already past today, means next week). */
    fun nextDelayMs(nowMs: Long, day: Int, time: String, zone: ZoneId): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val at = parseTime(time)
        var next = now.with(TemporalAdjusters.nextOrSame(dayOfWeek(day)))
            .withHour(at.hour).withMinute(at.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return next.toInstant().toEpochMilli() - nowMs
    }

    /** SPA day index (0 = Sunday … 6 = Saturday) → java.time. Anything else reads as Monday. */
    fun dayOfWeek(day: Int): DayOfWeek = when (day) {
        0 -> DayOfWeek.SUNDAY
        in 1..6 -> DayOfWeek.of(day)
        else -> DayOfWeek.MONDAY
    }

    /** "HH:mm" → the time of day, falling back to [DEFAULT_TIME] for anything out of range or
     *  unparseable — a junk pref must not be able to throw inside a worker. Hand-parsed, not
     *  [LocalTime.parse], which is strict ISO: it rejects the un-padded "7:05". */
    fun parseTime(time: String): LocalTime {
        val parts = time.trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return DEFAULT_LOCAL_TIME
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return DEFAULT_LOCAL_TIME
        return LocalTime.of(h, m)
    }

    private val DEFAULT_LOCAL_TIME: LocalTime = LocalTime.of(18, 0)

    /** The stored form of a user-picked time: valid "HH:mm" or the default. */
    fun normalizeTime(time: String): String {
        val t = parseTime(time)
        return "%02d:%02d".format(t.hour, t.minute)
    }
}
