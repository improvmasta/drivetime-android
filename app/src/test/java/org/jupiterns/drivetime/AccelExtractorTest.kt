package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accel event extractor (INSIGHTS P3a/P3b) — pure detection, no Android.
 *
 * The invariants pinned here are the silent-failure ones: accel alone never mints an event
 * (a phone knocked off its mount is not a hard brake), the GPS delta decides, the accel
 * window only refines timestamp/magnitude, and the standstill spike triggers a burst without
 * an event. All of it fails silently in production — an event stream that quietly stops (or
 * quietly floods) just makes Insights cards wrong, and nothing throws.
 */
class AccelExtractorTest {

    private class Recorder {
        val events = mutableListOf<AccelExtractor.Event>()
        var bursts = 0
        val extractor = AccelExtractor(onEvent = { events.add(it) }, onBurst = { bursts++ })
    }

    @Test
    fun `hard brake by GPS delta mints one event and a burst`() {
        val r = Recorder()
        r.extractor.onFix(1000, 45.0)
        r.extractor.onFix(1003, 15.0) // -10 mph/s
        assertEquals(1, r.events.size)
        val e = r.events[0]
        assertEquals("brake", e.kind)
        assertEquals(-10.0, e.mphs, 0.01)
        assertEquals(45.0, e.fromMph, 0.01)
        assertEquals(1, r.bursts)
    }

    @Test
    fun `the accel peak inside the fix pair times the event and carries its magnitude`() {
        val r = Recorder()
        r.extractor.onFix(1000, 45.0)
        // a strong sample at second 1002 inside the pair (magnitude |‖a‖−g| ≈ 4.2)
        r.extractor.onAccelSample(1002_000, 0f, 0f, 14.0f)
        r.extractor.onFix(1003, 15.0)
        val e = r.events[0]
        assertEquals(1002L, e.ts)
        assertNotNull(e.mag)
        assertTrue(e.mag!! > 3.5)
    }

    @Test
    fun `a gentle stop is not an event`() {
        val r = Recorder()
        r.extractor.onFix(1000, 40.0)
        r.extractor.onFix(1003, 30.0) // -3.3 mph/s: ordinary braking
        r.extractor.onFix(1006, 15.0)
        r.extractor.onFix(1009, 0.0)
        assertEquals(0, r.events.size)
        assertEquals(0, r.bursts)
    }

    @Test
    fun `accel spikes without a speed change mint nothing`() {
        val r = Recorder()
        r.extractor.onFix(1000, 40.0)
        // phone knocked off the mount: violent samples, steady speed
        for (s in 0..5) r.extractor.onAccelSample(1000_500L + s * 500, 15f, 10f, 20f)
        r.extractor.onFix(1003, 40.0)
        assertEquals(0, r.events.size)
    }

    @Test
    fun `hard launch from standstill mints a launch event`() {
        val r = Recorder()
        r.extractor.onFix(1000, 0.0)
        r.extractor.onFix(1003, 21.0) // +7 mph/s from standstill
        assertEquals(1, r.events.size)
        assertEquals("launch", r.events[0].kind)
        assertTrue(r.bursts >= 1)
    }

    @Test
    fun `a rolling speed-up is not a launch`() {
        val r = Recorder()
        r.extractor.onFix(1000, 30.0)
        r.extractor.onFix(1003, 51.0) // same +7 mph/s, but not from standstill
        assertEquals(0, r.events.size)
    }

    @Test
    fun `standstill accel spike triggers a burst with no event`() {
        val r = Recorder()
        r.extractor.onFix(1000, 0.0) // parked at a light — last known speed ~0
        r.extractor.onAccelSample(1002_000, 0f, 0f, 13.0f) // |‖a‖−g| ≈ 3.2 ≥ spike
        assertEquals(1, r.bursts)
        assertEquals(0, r.events.size)
        // …and the cooldown holds: a creep forward doesn't re-trigger per sample
        r.extractor.onAccelSample(1003_000, 0f, 0f, 13.0f)
        assertEquals(1, r.bursts)
    }

    @Test
    fun `event cooldown collapses one maneuver into one event`() {
        val r = Recorder()
        r.extractor.onFix(1000, 60.0)
        r.extractor.onFix(1002, 40.0) // -10 mph/s → event
        r.extractor.onFix(1004, 20.0) // still braking hard, same maneuver → suppressed
        assertEquals(1, r.events.size)
    }

    @Test
    fun `a wide fix gap attributes no rate`() {
        val r = Recorder()
        r.extractor.onFix(1000, 60.0)
        r.extractor.onFix(1020, 0.0) // 20 s apart — idle-tier gap, not a measured decel
        assertEquals(0, r.events.size)
    }

    @Test
    fun `a fix with no Doppler says nothing`() {
        val r = Recorder()
        r.extractor.onFix(1000, 60.0)
        r.extractor.onFix(1003, null)
        r.extractor.onFix(1006, 0.0) // prev speed unknown — no honest rate exists
        assertEquals(0, r.events.size)
    }

    @Test
    fun `reset forgets stale peaks and speeds`() {
        val r = Recorder()
        r.extractor.onFix(1000, 0.0)
        r.extractor.onAccelSample(1001_000, 0f, 0f, 14.0f)
        r.extractor.reset()
        assertNull(r.extractor.peakIn(999, 1005))
        // after reset, a spike can't read "standstill" off the stale speed
        val before = r.bursts
        r.extractor.onAccelSample(1002_000, 0f, 0f, 14.0f)
        assertEquals(before, r.bursts)
    }
}
