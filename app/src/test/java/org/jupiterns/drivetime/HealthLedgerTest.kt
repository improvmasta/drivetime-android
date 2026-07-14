package org.jupiterns.drivetime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [Health.startLife] end to end — the *ledger*, not the classifier. [HealthTest] already pins
 * [Health.classify] as a pure function; what was never covered is the thing that actually calls
 * it: closing the books on a predecessor process, deciding whether its absence was an outage at
 * all, writing the durable `down` row, and deciding whether to interrupt the user about it.
 *
 * That chain is the app's single loudest statement — "your phone killed the tracker and you lost
 * drives" — and it is made about a process that no longer exists, from four numbers in prefs, on a
 * phone nobody is watching. It used to be made wrongly, every time a car sat parked for an hour.
 * These are the cases where getting it wrong would either cry wolf or swallow a real outage.
 */
@RunWith(RobolectricTestRunner::class)
class HealthLedgerTest {

    private lateinit var ctx: Context
    private lateinit var s: Settings
    private val clock = FakeClock()

    private val minute = 60_000L

    private fun ledger(): String = Health.pullSince(ctx, 0.0)
    private fun healthFile() = File(ctx.filesDir, "web_health.jsonl")

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        healthFile().delete()
        EventLog.init(ctx)
        EventLog.clear()
        Clock.setForTest(clock)
        s = Settings(ctx)
    }

    /** The clock is process-global: a test that leaves it frozen hands the next one a world where
     *  no time passes. */
    @After fun tearDown() {
        Clock.setForTest(null)
    }

    /** A predecessor that was beating, then vanished without ever reaching `onDestroy`. */
    private fun seedKilledPredecessor(agoMs: Long) {
        s.loggingEnabled = true
        s.lifeBeatAt = clock.wall - agoMs
        s.lifeEndedAt = 0L        // it never got to say goodbye — that IS the evidence
        s.lifeEndReason = ""
    }

    @Test fun aKilledPredecessorIsWrittenDownAsAFaultAndAnnounced() {
        seedKilledPredecessor(30 * minute)
        val beat = s.lifeBeatAt

        Health.startLife(ctx, s)

        val rows = ledger()
        assertTrue("a down row must be written", rows.contains("\"kind\":\"down\""))
        assertTrue("no onDestroy and no reboot ⇒ the OEM battery manager", rows.contains("\"reason\":\"killed\""))
        assertTrue("a kill loses drives — it is our fault to report", rows.contains("\"fault\":true"))
        // Both ends of the outage are known here, which is why THIS is where the durable record is
        // written and not in the watchdog's estimate. `from` is the last beat (a kill only tells us
        // "sometime after"), `to` is now.
        assertTrue(rows.contains("\"from\":${beat / 1000}"))
        assertTrue(rows.contains("\"to\":${clock.wall / 1000}"))

        // Long enough to have swallowed a real drive → say so out loud, once.
        assertEquals(clock.wall, s.lastKillDetectedAt)
        assertEquals(clock.wall, s.lastKillNotifiedAt)
    }

    @Test fun aCleanStopIsNeverBlamedOnThePhone() {
        // The user turned tracking off. onDestroy ran and said so.
        s.loggingEnabled = false
        Health.endLife(ctx, s)
        assertEquals("stop", s.lifeEndReason)

        clock.advance(3 * 60 * minute)   // off for three hours, as intended
        Health.startLife(ctx, s)

        val rows = ledger()
        assertTrue(rows.contains("\"reason\":\"stop\""))
        assertTrue("the user's own decision is not a fault", rows.contains("\"fault\":false"))
        assertEquals("nothing to accuse the phone of", 0L, s.lastKillDetectedAt)
        assertEquals(0L, s.lastKillNotifiedAt)
    }

    @Test fun anOrdinaryRebootIsNotAKill() {
        // The trap this whole file exists for. A graceful shutdown DOES run onDestroy with logging
        // still enabled — indistinguishable, from prefs alone, from the OS killing the service.
        // Only time-since-boot can tell them apart.
        s.loggingEnabled = true
        Health.endLife(ctx, s)
        assertEquals("system", s.lifeEndReason)   // looks like a fault…

        clock.advanceAcrossReboot(40 * minute)    // …but the phone restarted inside the gap
        Health.startLife(ctx, s)

        val rows = ledger()
        assertTrue(rows.contains("\"reason\":\"reboot\""))
        assertTrue(rows.contains("\"fault\":false"))
        assertEquals("a reboot must never send the user to their battery settings", 0L, s.lastKillDetectedAt)
    }

    @Test fun aShortBounceIsNotAnOutageAtAll() {
        // An app update, a START_STICKY bounce, a tier reconfigure: the process is gone for
        // seconds. Recording those as downtime would bury the real thing in noise — the same
        // mistake as the old "no fixes ⇒ dead" heuristic, one layer down.
        seedKilledPredecessor(2 * minute)         // under Health's 3-minute floor

        Health.startLife(ctx, s)

        assertFalse("a 2-minute restart is not an outage", ledger().contains("\"kind\":\"down\""))
        assertEquals(0L, s.lastKillDetectedAt)
    }

    @Test fun aShortOutageIsRecordedButNotAnnounced() {
        // The two bars are deliberately different: writing a row is free, interrupting the user is
        // not. A 5-minute kill goes in the ledger (the Drives timeline can name it as the cause of
        // a gap) but is nowhere near long enough to have cost a drive, so the shade stays quiet.
        seedKilledPredecessor(5 * minute)

        Health.startLife(ctx, s)

        assertTrue(ledger().contains("\"reason\":\"killed\""))
        assertEquals("the standing banner still stands", clock.wall, s.lastKillDetectedAt)
        assertEquals("but nothing is pushed to the shade", 0L, s.lastKillNotifiedAt)
    }

    @Test fun theWatchdogHavingAlreadySpokenKeepsThisQuiet() {
        // The watchdog notices a dead service *while the outage is happening* and restarts it,
        // which is what brings us here. One interruption must be one notification: the tell is
        // that `lastKillNotifiedAt` already sits INSIDE the outage window.
        seedKilledPredecessor(40 * minute)
        val spokeAt = clock.wall - 10 * minute    // mid-episode, after `from`
        s.lastKillNotifiedAt = spokeAt

        Health.startLife(ctx, s)

        assertTrue("the durable record is still written", ledger().contains("\"kind\":\"down\""))
        assertEquals("but we do not say it twice", spokeAt, s.lastKillNotifiedAt)
    }

    @Test fun aFreshInstallHasNoPredecessorToBury() {
        // lifeBeatAt == 0: this process is the first one there has ever been. There is no interval
        // to measure, and an install that opens by accusing the phone of killing a tracker it has
        // never run is cry-wolf in its purest form.
        s.loggingEnabled = true

        Health.startLife(ctx, s)

        assertFalse(ledger().contains("\"kind\":\"down\""))
        assertEquals(0L, s.lastKillDetectedAt)
        assertEquals("and the new life beats immediately", clock.wall, s.lifeBeatAt)
    }

    @Test fun startingALifeClearsThePreviousOnesEndMarks() {
        // Otherwise the NEXT startLife reads a stale `lifeEndedAt` from two lives ago and dates an
        // outage from a moment that has nothing to do with it.
        seedKilledPredecessor(30 * minute)
        s.lifeEndedAt = clock.wall - 25 * minute
        s.lifeEndReason = "system"

        Health.startLife(ctx, s)

        assertEquals(0L, s.lifeEndedAt)
        assertEquals("", s.lifeEndReason)
        assertEquals(clock.wall, s.lifeBeatAt)
    }
}
