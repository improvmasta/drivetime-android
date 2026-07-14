package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/**
 * [TierReconciler] — the guarantee that replaced the tier race.
 *
 * What is being pinned here is not a behaviour so much as an *invariant*, and it is the kind that
 * decays silently: the moment one future caller flips `detector.carConnected` and calls
 * `reevaluate()` straight from a broadcast receiver — which is precisely what the code did before
 * this class existed — the race is back, and nothing fails. No test can catch that by asserting on
 * outcomes, because the outcome of a data race is usually "fine". So the invariant itself is what's
 * tested: work is queued rather than run inline, it runs on one thread, it runs in submission
 * order, and touching tier state from anywhere else is *detected*.
 */
@RunWith(RobolectricTestRunner::class)
class TierReconcilerTest {

    private lateinit var r: TierReconciler

    @Before fun setup() {
        EventLog.init(ApplicationProvider.getApplicationContext<Context>())
        EventLog.clear()
        r = TierReconciler("dt-tier-test")
        r.strict = true   // the invariant is a hard error in a test, a logged warning in the app
    }

    @After fun tearDown() {
        r.quit()
    }

    /** Run everything the reconciler has queued, then come back. */
    private fun drain() = shadowOf(r.looper).idle()

    @Test fun workIsQueuedOntoTheReconcilerThread_neverRunInline() {
        // The whole point. If submit() ran its work inline it would run on the *caller's* thread —
        // the main thread for a receiver, the OBD coroutine for a dongle drop — which is exactly
        // the arrangement that raced.
        var ran = false
        r.submit(TierReconciler.Trigger.CAR_BT) { ran = true }

        assertFalse("submit must not run the work on the calling thread", ran)

        drain()
        assertTrue(ran)
    }

    @Test fun everyTriggerRunsOnTheOneThread() {
        val threads = Collections.synchronizedList(mutableListOf<Long>())
        val onOwn = Collections.synchronizedList(mutableListOf<Boolean>())

        for (t in TierReconciler.Trigger.values()) {
            r.submit(t) {
                threads.add(Thread.currentThread().id)
                onOwn.add(r.onOwnThread())
            }
        }
        drain()

        assertEquals("every trigger must land on the same thread", 1, threads.toSet().size)
        assertNotEquals("…and it is not the caller's", Thread.currentThread().id, threads.first())
        assertTrue(onOwn.all { it })
    }

    @Test fun eventsAreAppliedInSubmissionOrder() {
        // Ordering is not a nicety here: a car-Bluetooth disconnect that overtook the fix submitted
        // before it would resolve the tier from a signal set the fix had not yet updated. FIFO is
        // what makes "read-decide-write" atomic without a lock.
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        for (i in 1..50) r.submit(TierReconciler.Trigger.FIX) { seen.add(i) }
        drain()

        assertEquals((1..50).toList(), seen.toList())
    }

    @Test fun submissionsFromManyThreadsAreSerialised() {
        // The real shape of the bug: the locator thread delivering fixes while the main thread
        // handles a BT connect and an OBD coroutine reports a drop. `count` is a plain Int with no
        // synchronisation of its own — exactly like the tier fields — so it only lands on 300 if
        // every increment truly happened one at a time.
        var count = 0
        val overlaps = Collections.synchronizedList(mutableListOf<String>())
        var inside = false

        val threads = (1..3).map { t ->
            Thread {
                repeat(100) {
                    r.submit(TierReconciler.Trigger.FIX) {
                        if (inside) overlaps.add("thread $t re-entered")
                        inside = true
                        count++
                        inside = false
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        drain()

        assertEquals("every submission runs, exactly once", 300, count)
        assertTrue("and no two ever overlap: $overlaps", overlaps.isEmpty())
    }

    // ---- the guard, which is what keeps the invariant from decaying ----

    @Test fun touchingTierStateOffTheReconcilerThreadIsDetected() {
        // A future `reevaluate()` called straight from a receiver. In the app this is a warning in
        // the Activity log naming the offending thread; here it is a failure, which is the only
        // way the invariant survives the next person to touch this file.
        val e = runCatching { r.requireOwnThread("reevaluate") }.exceptionOrNull()

        assertTrue("the guard must fire off-thread", e is IllegalStateException)
        assertTrue(e!!.message!!.contains("reevaluate"))
    }

    @Test fun theGuardIsSilentOnTheReconcilerThread() {
        var threw: Throwable? = null
        r.submit(TierReconciler.Trigger.FIX) {
            threw = runCatching { r.requireOwnThread("handleFix") }.exceptionOrNull()
        }
        drain()

        assertNull(threw)
    }

    @Test fun theGuardOnlyWarnsWhenNotStrict() {
        // Production setting. The app's whole purpose is to not stop logging, so the guard must not
        // hand it a brand-new way to die — a warning that gets read beats a crash that loses the
        // drive.
        r.strict = false

        r.requireOwnThread("reevaluate")   // must not throw

        assertTrue(
            EventLog.recent().any { it.msg.contains("reevaluate") && it.msg.contains("tier state") }
        )
    }

    // ---- the thread must survive a bad trigger ----

    @Test fun aThrowingTriggerDoesNotTakeTheThreadDownWithIt() {
        // An uncaught throw on a HandlerThread kills the process — and on a sticky service that is
        // a crash *loop* which also takes the WebView down on every relaunch. So a bad trigger is
        // logged and dropped, and the queue keeps running.
        var laterRan = false

        r.submit(TierReconciler.Trigger.OBD) { throw IllegalStateException("dongle exploded") }
        r.submit(TierReconciler.Trigger.FIX) { laterRan = true }
        drain()

        assertTrue("the next fix must still be handled", laterRan)
        assertTrue(EventLog.recent().any { it.msg.contains("Tier OBD failed") })
    }

    @Test fun theLooperIsTheOneHandedToTheLocationProvider() {
        // requestUpdates gives this looper to the fused provider, which is what makes a fix arrive
        // *on* the reconciler thread rather than having to hop onto it. If these ever came apart,
        // every fix would be racing again.
        assertNotEquals(Looper.getMainLooper(), r.looper)
        var deliveredOn: Looper? = null
        r.submit(TierReconciler.Trigger.FIX) { deliveredOn = Looper.myLooper() }
        drain()

        assertEquals(r.looper, deliveredOn)
    }
}
