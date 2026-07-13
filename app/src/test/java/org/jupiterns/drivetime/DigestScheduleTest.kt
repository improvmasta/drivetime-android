package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The weekly digest's slot maths ([DigestSchedule]) — the one part of P4 whose bugs would
 * only surface as "it fired on the wrong day", a week at a time.
 */
class DigestScheduleTest {

    private val ny: ZoneId = ZoneId.of("America/New_York")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = ny): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun fireAt(nowMs: Long, day: Int, time: String, zone: ZoneId = ny): ZonedDateTime =
        ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nowMs + DigestSchedule.nextDelayMs(nowMs, day, time, zone)),
            zone
        )

    @Test
    fun `later the same day fires today`() {
        // Mon 2026-07-13 09:00 → Mon 18:00, 9 hours out.
        val now = ms(2026, 7, 13, 9, 0)
        assertEquals(9 * 3600_000L, DigestSchedule.nextDelayMs(now, 1, "18:00", ny))
    }

    @Test
    fun `past the slot waits a full week`() {
        // Mon 18:30, slot was 18:00 → next Monday, not in 30 minutes ago.
        val next = fireAt(ms(2026, 7, 13, 18, 30), 1, "18:00")
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(20, next.dayOfMonth)
        assertEquals(18, next.hour)
    }

    @Test
    fun `exactly at the slot waits a week rather than firing instantly`() {
        val now = ms(2026, 7, 13, 18, 0)
        assertEquals(7 * 24 * 3600_000L, DigestSchedule.nextDelayMs(now, 1, "18:00", ny))
    }

    @Test
    fun `a later day this week is days out, never negative`() {
        // Mon → Friday digest.
        val now = ms(2026, 7, 13, 9, 0)
        val next = fireAt(now, 5, "18:00")
        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
        assertEquals(17, next.dayOfMonth)
        assertTrue(DigestSchedule.nextDelayMs(now, 5, "18:00", ny) > 0)
    }

    @Test
    fun `sunday is day zero, the SPA's numbering`() {
        assertEquals(DayOfWeek.SUNDAY, DigestSchedule.dayOfWeek(0))
        assertEquals(DayOfWeek.MONDAY, DigestSchedule.dayOfWeek(1))
        assertEquals(DayOfWeek.SATURDAY, DigestSchedule.dayOfWeek(6))
        // Sun 2026-07-12 20:00, digest set to Sunday 18:00 → next Sunday.
        val next = fireAt(ms(2026, 7, 12, 20, 0), 0, "18:00")
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(19, next.dayOfMonth)
    }

    @Test
    fun `across the DST fall-back the digest stays at the wall-clock time`() {
        // 2026-11-01 is the US fall-back. Fire Sun 2026-10-25 18:00 → next is Sun 11-01 18:00
        // LOCAL (an 8-day-equivalent 169-hour gap in absolute time, not 168). Periodic 7-day
        // work would have walked it to 17:00; recomputing from the wall clock does not.
        val now = ms(2026, 10, 25, 18, 0)
        val next = fireAt(now, 0, "18:00")
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(11, next.monthValue)
        assertEquals(1, next.dayOfMonth)
        assertEquals(18, next.hour)
        assertEquals(169 * 3600_000L, DigestSchedule.nextDelayMs(now, 0, "18:00", ny))
    }

    @Test
    fun `junk prefs fall back instead of throwing`() {
        assertEquals("18:00", DigestSchedule.normalizeTime("nonsense"))
        assertEquals("07:05", DigestSchedule.normalizeTime("7:05"))
        assertEquals("09:30", DigestSchedule.normalizeTime(" 09:30 "))
        assertEquals(DayOfWeek.MONDAY, DigestSchedule.dayOfWeek(99))
        assertTrue(DigestSchedule.nextDelayMs(ms(2026, 7, 13, 9, 0), 99, "??", ny) > 0)
    }
}
