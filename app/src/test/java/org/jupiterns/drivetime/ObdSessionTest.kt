package org.jupiterns.drivetime

import org.jupiterns.drivetime.obd.ObdSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ObdSession] — the OBD loop's judgement, now that it is separable from its Bluetooth I/O.
 *
 * Each of these rules was written in response to a bug that had already happened, and none of them
 * had a test, because they were buried in a `while` loop wrapped around a socket. They are the kind
 * of rule that is obvious in isolation and invisible in place.
 */
class ObdSessionTest {

    // ---- the probe gate ----

    @Test fun aDongleInAParkedCarIsNotProbed() {
        // The bug that made `parked` necessary at all. An OBD-II port is permanently powered, so
        // the dongle stays *reachable* with the ignition off — and "we can reach it" was being
        // taken as evidence that we should. The result was a phone polling a dead ECU in a parking
        // lot for hours, holding the dense GPS tier there with it.
        //
        // `parked` therefore has to veto every other arm, including `driving`, which is why it is
        // an outer `!parked && (…)` and not just another term in the or.
        assertFalse(ObdSession.shouldProbe(parked = true, carConnected = true, movingHint = true, driving = true))
        assertFalse(ObdSession.shouldProbe(parked = true, carConnected = false, movingHint = false, driving = true))
    }

    @Test fun anyOneReasonToThinkWeAreInACarIsEnough() {
        assertTrue(ObdSession.shouldProbe(parked = false, carConnected = true, movingHint = false, driving = false))
        assertTrue(ObdSession.shouldProbe(parked = false, carConnected = false, movingHint = true, driving = false))
        assertTrue(ObdSession.shouldProbe(parked = false, carConnected = false, movingHint = false, driving = true))
    }

    @Test fun aConfiguredButOffDongleIsNotDialledForever() {
        // No signal at all: the phone is in a drawer. Without this gate the app opens a Bluetooth
        // socket to a car that isn't there, every few seconds, indefinitely.
        assertFalse(ObdSession.shouldProbe(parked = false, carConnected = false, movingHint = false, driving = false))
    }

    @Test fun drivingKeepsTheLoopSelfSustaining() {
        // The subtle one. A connected dongle pins DRIVING, and DRIVING is what re-probes the dongle
        // within seconds of a drop — so this arm is what lets a mid-drive reconnect happen at all.
        // It is also exactly what made the loop a trap before `parked` bounded it, so the two rules
        // have to be read together: this arm makes recovery fast, `parked` gives it an exit.
        assertTrue(ObdSession.shouldProbe(parked = false, carConnected = false, movingHint = false, driving = true))
        assertFalse(ObdSession.shouldProbe(parked = true, carConnected = false, movingHint = false, driving = true))
    }

    // ---- the wedged-adapter streak ----

    @Test fun aSocketThatOpensAndNeverSpeaksCountsTowardTheStreak() {
        val s = ObdSession()
        s.onFailure(everConnected = false)
        s.onFailure(everConnected = false)
        assertEquals(2, s.wedgedStreak)
    }

    @Test fun aMidDriveDropIsNotAWedgedAdapter() {
        // The distinction the whole streak exists for, and the one that is invisible from outside:
        // a link that WORKED and then dropped (out of range, dongle knocked loose, engine off) is a
        // different animal from one that never answered. Counting the drop would let an ordinary
        // drive escalate itself into a cold reset — twenty seconds of a live trip deliberately
        // spent not talking to a dongle that was fine.
        val s = ObdSession()
        s.onFailure(everConnected = true)
        s.onFailure(everConnected = true)
        s.onFailure(everConnected = true)
        assertEquals(0, s.wedgedStreak)
        assertTrue(s.recovery(driving = true) is ObdSession.Recovery.Retry)
    }

    @Test fun aGoodConnectClearsTheStreak() {
        val s = ObdSession()
        s.onFailure(everConnected = false)
        s.onFailure(everConnected = false)
        s.onConnected()
        assertEquals(0, s.wedgedStreak)
    }

    // ---- the recovery cadence ----

    @Test fun notDrivingProbesSlowly() {
        val s = ObdSession()
        val r = s.recovery(driving = false)
        assertTrue(r is ObdSession.Recovery.Idle)
        assertEquals(ObdSession.IDLE_MS, r.delayMs)
    }

    @Test fun notDrivingNeverColdResets_evenWithAWedgedStreak() {
        // The escalation is expensive (a 20-second deliberate blackout) and only worth paying
        // inside a drive. Parked, the slow idle probe already gives the dongle all the quiet it
        // needs — so `driving` is checked first, before the streak.
        val s = ObdSession()
        repeat(ObdSession.WEDGE_LIMIT + 2) { s.onFailure(everConnected = false) }
        assertTrue(s.recovery(driving = false) is ObdSession.Recovery.Idle)
    }

    @Test fun drivingRetriesFastUntilTheAdapterHasRefusedEnoughTimes() {
        val s = ObdSession()
        repeat(ObdSession.WEDGE_LIMIT - 1) { s.onFailure(everConnected = false) }

        val r = s.recovery(driving = true)
        assertTrue("still under the limit — keep trying fast", r is ObdSession.Recovery.Retry)
        assertEquals(ObdSession.RETRY_DRIVE_MS, r.delayMs)
    }

    @Test fun aPersistentlyMuteAdapterGetsAColdReset() {
        // Retrying fast is what is *not* working. The answer is to stop talking to it long enough
        // for its own firmware watchdog to reset it — the closest we get to power-cycling the plug
        // without touching it.
        val s = ObdSession()
        repeat(ObdSession.WEDGE_LIMIT) { s.onFailure(everConnected = false) }

        val r = s.recovery(driving = true)
        assertTrue(r is ObdSession.Recovery.ColdReset)
        assertEquals(ObdSession.COLD_PAUSE_MS, r.delayMs)
        assertTrue("a cold reset must be longer than the ordinary retry, or it isn't one",
            r.delayMs > ObdSession.RETRY_DRIVE_MS)
    }

    @Test fun theColdResetDoesNotRepeatOnEveryPass() {
        // Once performed, the streak has done its job. Without this the loop would cold-reset every
        // single attempt from here on — a permanent 20s-on, 20s-off cycle that never recovers.
        val s = ObdSession()
        repeat(ObdSession.WEDGE_LIMIT) { s.onFailure(everConnected = false) }
        assertTrue(s.recovery(driving = true) is ObdSession.Recovery.ColdReset)

        s.onColdReset()

        assertEquals(0, s.wedgedStreak)
        assertTrue("back to fast retries — give the reset a chance to have worked",
            s.recovery(driving = true) is ObdSession.Recovery.Retry)
    }

    // ---- cadences ----

    @Test fun theDashboardBoostsThePollRate() {
        assertEquals(ObdSession.BOOST_MS, ObdSession.pollDelayMs(dashboardBoost = true))
        assertEquals(ObdSession.POLL_MS, ObdSession.pollDelayMs(dashboardBoost = false))
        assertTrue("a boost that isn't faster is not a boost", ObdSession.BOOST_MS < ObdSession.POLL_MS)
    }

    @Test fun troubleCodesAreReadOnTheFirstSampleAndThenRarely() {
        // The first one matters: a check-engine light that is already on when you get in the car
        // must be noticed on connect, not two minutes into the drive.
        assertTrue(ObdSession.readDtcsOnTick(0))
        assertFalse(ObdSession.readDtcsOnTick(1))
        assertFalse(ObdSession.readDtcsOnTick(119))
        assertTrue(ObdSession.readDtcsOnTick(120))
    }
}
