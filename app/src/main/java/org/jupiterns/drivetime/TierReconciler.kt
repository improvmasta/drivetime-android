package org.jupiterns.drivetime

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * The one thread on which the logger's tier state may change — and the queue that gets every
 * trigger onto it.
 *
 * ## The bug this exists to end
 *
 * Six things can change the tier, and they did not agree on a thread. GPS fixes arrive on the
 * locator's own [HandlerThread] (they must: a fix does disk I/O, and doing that on the UI thread
 * of the process that also hosts the WebView was a real ANR source). Everything *else* — car
 * Bluetooth connecting, the OBD link coming up or dropping, a routine command, the motion-onset
 * probe confirming, the dashboard opening — arrived on the main thread. Both then called the same
 * `reevaluate()` → `applyTier()` pair, which reads and writes a dozen plain, non-volatile fields:
 * the current tier, the idle/dense sub-state and its counters, the memo of which
 * `LocationRequest` is currently in force, the notification's redraw signature.
 *
 * Two threads, no lock, no happens-before. The consequences are all silent, which is what makes
 * them this project's #1 bug class rather than a crash:
 *
 *  - **A lost subscription.** `requestUpdates` skips a re-request when the interval *and* priority
 *    it last recorded are unchanged. Interleave two callers and that memo can end up describing a
 *    request that was superseded — the service believes it is sampling at 1 Hz while the fused
 *    provider is actually delivering a fix a minute, or has none pending at all. Nothing throws.
 *    You find out from the shape of the drive, days later.
 *  - **A drive that starts or ends twice.** `markDriveStart` stamps and clears the durable drive
 *    mark. Run it concurrently from both threads and a single drive can fire two "drive complete"
 *    notifications, two after-drive backups, and two battery stamps — or lose its start mark and
 *    take its miles with it.
 *  - **Stale reads of the tier itself**, which decide whether a fix carries OBD telemetry and
 *    whether its distance counts as mileage.
 *
 * ## The fix: confinement, not locking
 *
 * A lock around the tier fields would have made each *access* safe while leaving the
 * read-decide-write sequences racy — and the races here are all read-decide-write. So instead
 * there is exactly one thread, and everything that touches tier state runs on it, in the order it
 * was submitted. Fixes are already delivered here (this reconciler's [looper] is the one handed to
 * the fused location provider), so the hot path costs nothing extra: it is not a hop, it *is* the
 * thread. Every other trigger [submit]s.
 *
 * The fields therefore need no `@Volatile` and no locks — they are single-threaded — with one
 * documented exception: [LocationService.currentTier] is read by the OBD and upload loops from
 * their own coroutines, so it stays volatile. Single-writer, many-reader is safe; it is the
 * read-decide-write that was not.
 *
 * ## Why the guard
 *
 * "Nothing mutates tier fields directly" is an invariant, and an invariant nothing enforces is a
 * comment. [requireOwnThread] turns a future call to `reevaluate()` straight from a receiver — the
 * exact mistake this class exists to prevent, and one that would otherwise reintroduce the race in
 * silence — into a line in the Activity log naming the offending thread. It deliberately does not
 * throw in production: an app whose entire purpose is to not stop logging must not acquire a new
 * way to die, and a warning that gets read is worth more than a crash that loses the drive.
 * [strict] makes it throw, and the tests turn it on.
 */
class TierReconciler(name: String = "dt-tier") {

    /** What woke the reconciler. Carried for the log and for tests — the reconciler does not
     *  branch on it; every trigger runs the same way, which is the point. */
    enum class Trigger { FIX, CAR_BT, OBD, COMMAND, MOTION, DASHBOARD, MARK, FLASH }

    private val thread = HandlerThread(name).also { it.start() }
    private val handler = Handler(thread.looper)

    /** The looper tier work runs on. Handed to the fused location provider so fixes are delivered
     *  straight onto this thread rather than hopping through it. */
    val looper: Looper get() = thread.looper

    /** Throw instead of warning when the invariant is broken. Tests only — see the class doc. */
    var strict = false

    /**
     * Run [work] on the reconciler thread, after everything already queued.
     *
     * [work] must not throw: the reconciler thread is the logger's heartbeat, and an uncaught
     * throw on a [HandlerThread] takes the whole process with it — which, on a sticky service,
     * means a crash *loop* that also takes the WebView down on every relaunch. So a failing
     * trigger is logged and dropped, and the next one still runs. (Same reasoning as the
     * `runCatching` around the upload flush.)
     */
    fun submit(trigger: Trigger, work: () -> Unit) {
        handler.post {
            runCatching { work() }.onFailure {
                EventLog.warn("Tier ${trigger.name} failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** Is the caller already on the reconciler thread? */
    fun onOwnThread(): Boolean = Looper.myLooper() === thread.looper

    /**
     * Assert that tier state is being touched from the right thread. Call it at the head of
     * anything that reads-then-writes the tier; [what] names the caller so the log says which one.
     */
    fun requireOwnThread(what: String) {
        if (onOwnThread()) return
        val msg = "$what touched tier state on '${Thread.currentThread().name}' — " +
            "it must be submitted to the reconciler"
        if (strict) throw IllegalStateException(msg)
        EventLog.warn(msg)
    }

    /** Stop the thread once the queue drains, so a final tier event isn't dropped on the way out. */
    fun quit() {
        runCatching { thread.quitSafely() }
    }
}
