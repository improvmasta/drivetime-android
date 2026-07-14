package org.jupiterns.drivetime

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [Watchdog.reconcile] — the last thing standing between "the OEM battery manager killed the
 * service" and "the app quietly stopped logging", which is this project's #1 bug class.
 *
 * It is a backstop, so nothing else notices when it breaks: a watchdog that has silently stopped
 * restarting the service looks exactly like a phone that never needed it. It is also the code most
 * able to do harm by being *too* eager — restarting a tracker the user switched off, or hammering
 * an FGS start into a permission the user has revoked. So both directions are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class WatchdogTest {

    private lateinit var app: Application
    private lateinit var ctx: Context
    private lateinit var s: Settings
    private val clock = FakeClock()

    private val minute = 60_000L

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ctx = app
        ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        EventLog.init(ctx)
        EventLog.clear()
        Clock.setForTest(clock)
        s = Settings(ctx)
        grantLoggingPrerequisites()
        shadowOf(app).clearStartedServices()
    }

    @After fun tearDown() {
        Clock.setForTest(null)
    }

    /** What [Permissions.Snapshot.isReady] actually requires: the fine-location grant and the
     *  phone's location services switched on. Nothing else gates a restart. */
    private fun grantLoggingPrerequisites() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    /** The state a phone is in when the battery manager has just taken the service away: we still
     *  mean to be logging, and the last proof of life is [agoMs] old. */
    private fun seedDeadLogger(agoMs: Long) {
        s.loggingEnabled = true
        s.lifeBeatAt = clock.wall - agoMs
    }

    private fun restartWasAttempted(): Boolean = shadowOf(app).nextStartedService != null

    // ---- the case it exists for ----

    @Test fun aDeadLoggerThatShouldBeRunningIsRestartedAndTheKillIsRecorded() {
        seedDeadLogger(45 * minute)

        assertTrue("a restart that succeeded is a success, not a retry", Watchdog.reconcile(ctx, running = false))

        assertTrue("the service must actually be started", restartWasAttempted())
        assertEquals("watchdog", s.lastCommandSource)
        // The banner ("your phone does this") and the one-per-episode notification.
        assertEquals(clock.wall, s.lastKillDetectedAt)
        assertEquals(clock.wall, s.lastKillNotifiedAt)
    }

    @Test fun aShortGapIsARestartWithoutAnAccusation() {
        // You swiped the app away, or the phone rebooted, three minutes ago. Restart it — that is
        // the job — but do NOT tell the user their battery manager is eating their drives. The
        // whole reason the heartbeat exists is to stop this being guessed at.
        seedDeadLogger(3 * minute)

        assertTrue(Watchdog.reconcile(ctx, running = false))

        assertTrue(restartWasAttempted())
        assertEquals("nothing here warrants the OEM warning", 0L, s.lastKillDetectedAt)
        assertEquals(0L, s.lastKillNotifiedAt)
    }

    // ---- the two ways being too eager would be worse than being asleep ----

    @Test fun aTrackerTheUserTurnedOffIsNeverResurrected() {
        // The single worst thing this class could do. `loggingEnabled` is the user's intent, and it
        // is the ONLY flag that says so — an OS kill deliberately leaves it set, which is exactly
        // why an intentional stop must clear it (see LocationService's OFF path) and why this must
        // honour it.
        s.loggingEnabled = false
        s.lifeBeatAt = clock.wall - 45 * minute

        assertTrue(Watchdog.reconcile(ctx, running = false))

        assertFalse("the user switched it off — leave it off", restartWasAttempted())
        assertEquals(0L, s.lastKillDetectedAt)
        assertFalse("watchdog" == s.lastCommandSource)
    }

    @Test fun aRevokedPermissionIsNotSomethingARestartCanFix() {
        // Location permission is gone. Starting the FGS would throw at startForeground time
        // (Android 14 enforces the type's prerequisites), so retrying into it every 15 minutes
        // just burns the wake-ups — and the user has to fix this interactively anyway.
        seedDeadLogger(45 * minute)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue("not ready is a clean no-op, not a retry", Watchdog.reconcile(ctx, running = false))

        assertFalse(restartWasAttempted())
    }

    @Test fun locationServicesTurnedOffAlsoBlocksTheRestart() {
        // The other half of isReady, and a genuinely common one: the permission is granted but the
        // phone's location switch is off. There is nothing to restart into.
        seedDeadLogger(45 * minute)
        val lm = ctx.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        assertTrue(Watchdog.reconcile(ctx, running = false))

        assertFalse(restartWasAttempted())
    }

    // ---- the common case: nothing is wrong ----

    @Test fun aLiveServiceIsLeftAloneAndJustBeats() {
        // This is the floor under the heartbeat: the one periodic tick Doze cannot defer forever.
        // A phone asleep in a pocket with no fixes and nothing to upload still proves it is alive
        // every ~15 minutes, which is what bounds how far wrong a death timestamp can ever be.
        s.loggingEnabled = true
        s.lifeBeatAt = clock.wall - 45 * minute

        assertTrue(Watchdog.reconcile(ctx, running = true))

        assertFalse("it is alive — do not start a second one", restartWasAttempted())
        assertEquals("the beat is the whole point of this branch", clock.wall, s.lifeBeatAt)
        assertEquals("and a live service is never a kill", 0L, s.lastKillDetectedAt)
    }

    // ---- the judgement, pinned directly ----

    @Test fun isKill_needsAGapLongerThanTheJobsOwnPeriod() {
        val now = clock.wall
        // 20 minutes: comfortably past this job's own 15-minute period, which is the slowest the
        // heartbeat it reads can possibly beat. Below that a "gap" may just be the watchdog's own
        // cadence, and reading that as a kill is how the old version cried wolf.
        assertFalse(Watchdog.isKill(now - 19 * minute, now))
        assertFalse(Watchdog.isKill(now - 20 * minute, now))   // strictly greater than
        assertTrue(Watchdog.isKill(now - 21 * minute, now))
    }

    @Test fun isKill_aPhoneThatHasNeverBeatenIsNotAVictim() {
        // A fresh install: there is no interval to measure. `now - 0` is 55 years, which sails past
        // any threshold — so without the `> 0` guard the very first watchdog pass on a new phone
        // would announce that it had killed a tracker it has never once run.
        assertFalse(Watchdog.isKill(0L, clock.wall))
    }

    @Test fun theRestartPathAlsoLeavesAnAuditTrail() {
        seedDeadLogger(45 * minute)

        Watchdog.reconcile(ctx, running = false)

        val log = EventLog.recent().map { it.msg }
        assertNotNull("the outage has to be visible in the Activity log, not just in prefs",
            log.firstOrNull { it.contains("killed for") })
        assertNotNull(log.firstOrNull { it.contains("Watchdog restarted") })
    }
}
