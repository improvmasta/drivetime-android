package org.jupiterns.drivetime

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [Control.repairNeverStarted] — the rescue for installs stranded by the "tracking looks on but
 * never started" bug, where every UI surface derived "on" from [Settings.trackingMode] (desired
 * *tier*, defaults to `auto`) instead of [Settings.loggingEnabled] (the master switch, defaults
 * to false). Those phones showed a green light, a flipped switch and a wizard reading
 * "✓ Tracking is on" while the logger had never run once.
 *
 * This code **starts a background GPS service without the user tapping anything**, which is the
 * most invasive thing in the app. It is only defensible because it fires for exactly one
 * signature — a user the app *told* tracking was on, who never contradicted it — so every
 * exclusion below is load-bearing, and a false positive here is a privacy bug, not a UX one.
 * The precision comes from [Settings.lifeBeatAt]: `Health.startLife` stamps it the instant the
 * service starts and nothing ever clears it, so 0 is durable proof the logger has never lived.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingRepairTest {

    private lateinit var app: Application
    private lateinit var ctx: Context
    private lateinit var s: Settings

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ctx = app
        ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        EventLog.init(ctx)
        EventLog.clear()
        // A repair arms the watchdog before it starts anything (applyMode), which needs a
        // WorkManager to arm it in.
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        s = Settings(ctx)
        grantLocation()
        shadowOf(app).clearStartedServices()
    }

    private fun grantLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    /** Exactly the state the bug left a phone in: set up, permissions granted, mode at its `auto`
     *  default, and a logger that has never run — because the app said it already was. */
    private fun seedStrandedInstall() {
        s.loggingEnabled = false
        s.trackingMode = Settings.MODE_AUTO
        s.lifeBeatAt = 0L
    }

    private fun serviceWasStarted(): Boolean = shadowOf(app).nextStartedService != null

    // ---- the case it exists for ----

    @Test fun anInstallThatWasToldItWasTrackingButNeverRanIsStarted() {
        seedStrandedInstall()

        assertTrue("the stranded install is exactly what this repairs", Control.repairNeverStarted(ctx, s))

        assertTrue("the master switch is finally set", s.loggingEnabled)
        assertEquals("and it resumes in auto, the mode it always claimed", Settings.MODE_AUTO, s.trackingMode)
        assertTrue("the logging service is actually asked to start", serviceWasStarted())
    }

    @Test fun theRepairSaysWhatItDidInTheOneLogAUserCanSend() {
        // A GPS service that switches itself on and says nothing is indistinguishable from spyware,
        // and the activity log is the only forensic record this app has.
        seedStrandedInstall()

        Control.repairNeverStarted(ctx, s)

        val said = EventLog.recent().firstOrNull { it.msg.contains("never actually started") }
        assertNotNull("the repair must leave a trail", said)
    }

    // ---- everyone it must leave alone ----

    @Test fun aUserWhoTurnedTrackingOffIsLeftOff() {
        // The one that matters most. Every route to off — the switch, a routine, a snooze — goes
        // through applyMode and writes MODE_OFF. Re-starting a tracker somebody deliberately
        // switched off is the worst thing this function could do.
        seedStrandedInstall()
        s.trackingMode = Settings.MODE_OFF

        assertFalse(Control.repairNeverStarted(ctx, s))

        assertFalse("a deliberate off stays off", s.loggingEnabled)
        assertFalse("and nothing is started behind their back", serviceWasStarted())
    }

    @Test fun aLoggerThatHasRunBeforeIsNotThisBug() {
        // It ran, so the app was never lying to this user: they stopped it on purpose (or a
        // routine did). A single beat, ever, is enough to prove it and disqualify the repair.
        seedStrandedInstall()
        s.lifeBeatAt = 1L

        assertFalse(Control.repairNeverStarted(ctx, s))
        assertFalse(serviceWasStarted())
    }

    @Test fun anInstallAlreadyTrackingIsNotTouched() {
        // Nothing to fix. A killed-but-enabled logger is Watchdog's job — two things racing to
        // restart the same service is how you get a restart loop.
        seedStrandedInstall()
        s.loggingEnabled = true

        assertFalse(Control.repairNeverStarted(ctx, s))
    }

    @Test fun anInstallThatNeverFinishedSetupIsNotHandedAGpsService() {
        // No location grant = they never completed setup, so they were never shown the green light
        // that made this a broken promise. There is no consent here to honour.
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        seedStrandedInstall()

        assertFalse(Control.repairNeverStarted(ctx, s))
        assertFalse(serviceWasStarted())
        assertFalse(s.loggingEnabled)
    }

    // ---- it is a repair, not a policy ----

    @Test fun itFiresAtMostOnceEvenWhenItDidNothing() {
        // Claimed on the first pass whatever the outcome. Otherwise a user who turns tracking off
        // gets it turned back on by the next resume — a policy of "start tracking if it's off",
        // which is emphatically not what this is.
        seedStrandedInstall()
        s.trackingMode = Settings.MODE_OFF
        assertFalse(Control.repairNeverStarted(ctx, s))
        assertTrue("the one shot is spent even on a no-op", s.trackingRepairDone)

        // Now they turn tracking on and off again, landing back in a repairable-looking shape.
        s.trackingMode = Settings.MODE_AUTO
        s.lifeBeatAt = 0L
        assertFalse("the repair never runs twice", Control.repairNeverStarted(ctx, s))
        assertFalse(s.loggingEnabled)
    }

    @Test fun itDoesNotRunAgainAfterItSucceeded() {
        seedStrandedInstall()
        assertTrue(Control.repairNeverStarted(ctx, s))
        assertTrue(s.trackingRepairDone)

        // Simulate the user switching it off afterwards, then resuming the app again.
        Control.apply(ctx, Control.ACTION_STOP, "user")
        shadowOf(app).clearStartedServices()

        assertFalse("their off must survive every future resume", Control.repairNeverStarted(ctx, s))
        assertFalse(s.loggingEnabled)
        assertNull(shadowOf(app).nextStartedService)
    }
}
