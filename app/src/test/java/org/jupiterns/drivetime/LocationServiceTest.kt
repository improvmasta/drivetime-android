package org.jupiterns.drivetime

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The two [LocationService] lifecycle paths that can end logging, and the one that must *not*.
 *
 * Both of the paths below are one line away from a silent-stop bug, and neither had a test:
 *
 *  - **The OFF path** must return `START_NOT_STICKY` and clear `loggingEnabled`. Get the sticky
 *    flag wrong and the OS resurrects a service the user just switched off; leave the flag set and
 *    [Watchdog] does it 15 minutes later instead. The flag is the *only* thing that distinguishes
 *    "the user stopped this" from "the system took it away", which is precisely why the OS-kill
 *    path deliberately leaves it alone.
 *  - **The startForeground degrade** must stop cleanly rather than crash — a sticky service that
 *    throws in `onCreate` is a crash *loop*, and it takes the WebView down with it on every
 *    relaunch — while leaving `loggingEnabled` set, because a start we could not perform is not a
 *    stop the user asked for.
 *
 * The RUN path is deliberately not driven here: it subscribes to the fused location provider,
 * which no unit-test runtime provides. What it *decides* is covered where the decisions live
 * ([DriveSession], [DriveDetector], [ControlParse]).
 */
@RunWith(RobolectricTestRunner::class)
class LocationServiceTest {

    private lateinit var ctx: Context
    private lateinit var s: Settings
    private val clock = FakeClock()

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        // The OFF path cancels the watchdog, which needs a WorkManager to cancel it in.
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        EventLog.init(ctx)
        EventLog.clear()
        Clock.setForTest(clock)
        s = Settings(ctx)
    }

    @After fun tearDown() {
        Clock.setForTest(null)
    }

    private fun stopIntent() = Intent(ctx, LocationService::class.java).setAction(Control.ACTION_STOP)

    /** Build the service with the foreground start stubbed out. Robolectric will happily run the
     *  real `startForeground`, but stubbing it keeps these tests about the paths under test. */
    private fun service(foreground: (android.app.Notification) -> Unit = {}): LocationService {
        val controller = Robolectric.buildService(LocationService::class.java)
        val svc = controller.get()
        svc.enterForeground = foreground
        controller.create()
        return svc
    }

    // ---- the OFF path ----

    @Test fun aStopCommandEndsTrackingAndDoesNotComeBack() {
        s.loggingEnabled = true
        s.trackingMode = Settings.MODE_AUTO
        val svc = service()

        val result = svc.onStartCommand(stopIntent(), 0, 1)

        assertEquals(
            "START_STICKY here would have the OS restart a service the user just switched off",
            Service.START_NOT_STICKY, result
        )
        assertEquals(Settings.MODE_OFF, Settings(ctx).trackingMode)
        assertFalse(
            "loggingEnabled is what tells onDestroy and the watchdog this was intentional",
            Settings(ctx).loggingEnabled
        )
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test fun aServiceStartedWithTheModeAlreadyOffStopsInstead() {
        // The other way in: a restart (START_STICKY, boot, watchdog) that arrives with no action at
        // all, on a phone whose mode is OFF. Without the mode check it would happily start logging
        // again — which is the same bug as above, reached from the other side.
        s.trackingMode = Settings.MODE_OFF
        s.loggingEnabled = true
        val svc = service()

        val result = svc.onStartCommand(Intent(ctx, LocationService::class.java), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(Settings(ctx).loggingEnabled)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    // ---- the startForeground degrade ----

    @Test fun aRefusedForegroundStartStopsCleanlyInsteadOfCrashLooping() {
        // Android 14 enforces an FGS type's prerequisites at startForeground time: with fine
        // location revoked, this throws. Callers cannot all pre-check it (boot races), so the
        // service has to survive being told no.
        s.loggingEnabled = true

        val svc = service { throw SecurityException("FGS type location requires ACCESS_FINE_LOCATION") }

        assertTrue("the throw must become a clean stop", shadowOf(svc).isStoppedBySelf)
        assertTrue(
            "…but NOT a stop of tracking: this is a start we could not perform, not one the user " +
                "cancelled. Clearing the flag here would turn a revoked permission into a " +
                "permanent, silent end to logging that nothing would ever undo.",
            Settings(ctx).loggingEnabled
        )
    }

    @Test fun aRefusedForegroundStartStillBracketsTheLifeInTheLedger() {
        // Health.startLife runs BEFORE startForeground on purpose, so even a service that dies in
        // the degrade path above has opened its life in the ledger — otherwise a phone stuck in
        // this state would look, to the next launch, like a process that never existed.
        s.loggingEnabled = true

        service { throw SecurityException("nope") }

        assertEquals("the life was opened before the start was attempted", clock.wall, Settings(ctx).lifeBeatAt)
    }

    // ---- the mid-drive resume latch ----

    @Test fun aRestartMidDriveHoldsTheDriveOpen() {
        // The service died 30 seconds into a drive (OEM kill, app update). The persisted start mark
        // survived but the detector's in-memory driving signals did not — so a cold tier resolution
        // would resolve LIGHT, end the drive, and reset its clock, miles and marker count while the
        // car is still moving. The latch holds DRIVING across the cold start until a real fix lands.
        s.loggingEnabled = true
        s.driveStartedAt = clock.wall - 30_000L

        service()

        assertNotNull(
            "the drive must be picked back up, not replaced by a new one",
            EventLog.recent().firstOrNull { it.msg.contains("Resuming drive in progress") }
        )
        assertEquals("and its identity — the start mark — must survive", clock.wall - 30_000L, Settings(ctx).driveStartedAt)
    }

    @Test fun aStaleDriveMarkIsNotResumed() {
        // The mirror image: we crashed out of a drive yesterday without clearing its mark. Resuming
        // that would date today's drive to yesterday morning and hand it every mile in between.
        s.loggingEnabled = true
        s.driveStartedAt = clock.wall - DriveSession.MAX_DRIVE_MS - 1

        service()

        assertNull(EventLog.recent().firstOrNull { it.msg.contains("Resuming drive in progress") })
    }

    @Test fun aDriveMarkIsNotResumedWhenTrackingIsOff() {
        // Tracking was switched off mid-drive. `reevaluate` clears the mark on the way out, but a
        // process that was killed before it got there leaves one behind — and it must not become a
        // reason to hold DRIVING the next time the service starts.
        s.loggingEnabled = false
        s.driveStartedAt = clock.wall - 30_000L

        service()

        assertNull(EventLog.recent().firstOrNull { it.msg.contains("Resuming drive in progress") })
    }
}
