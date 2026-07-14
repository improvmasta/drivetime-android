package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.RemoteViews
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jupiterns.drivetime.obd.Elm327Client
import org.jupiterns.drivetime.obd.ObdSession

/**
 * Always-on foreground logger with two sampling tiers, chosen by [DriveDetector]:
 *
 *  - **LIGHT** — sparse, low-power everyday-location pulse (`lightIntervalSec`) when
 *    you're not driving, so the timeline stays continuous without chugging battery.
 *  - **DRIVING** — dense, high-accuracy logging (`intervalSec`) with full OBD
 *    telemetry; *within* a drive it adapts to motion (idle back-off at red lights).
 *
 * "Driving" is detected from a layered signal cascade (car Bluetooth → OBD → speed),
 * never from activity-recognition guessing. A routine/shortcut can force a tier or
 * turn logging off via the control API; the service just reconciles to the tier the
 * detector resolves. OFF stops the service.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The one thread tier state lives on, and the queue every trigger reaches it through. Fixes are
     * delivered straight onto it (its looper is what [requestUpdates] hands the fused provider), so
     * the hot path pays nothing; the car-Bluetooth receiver, the OBD loop, the motion-onset probe
     * and `onStartCommand` all [TierReconciler.submit] instead of running inline on their own
     * threads, which is what they used to do. See [TierReconciler] for the races that ended.
     *
     * It also keeps the property the old `dt-location` HandlerThread was created for: a fix does
     * disk I/O (queue append, cap check, WebFixBuffer rewrite) on every callback, and that must not
     * run on the UI thread of the process that also hosts the WebView — it was a periodic jank/ANR
     * source while driving with the app open.
     */
    private val reconciler = TierReconciler()
    private lateinit var settings: Settings
    private lateinit var uploader: Uploader
    private lateinit var detector: DriveDetector
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    @Volatile private var obd: Elm327Client? = null
    @Volatile private var latestObd: Elm327Client.ObdSample? = null
    @Volatile private var movingHint = false      // recent non-trivial motion (gates OBD probe)

    // Motion-onset (device-agnostic fast start). A one-shot significant-motion trigger,
    // armed only in the LIGHT tier, wakes an instant Doppler check so a drive starts dense
    // logging within seconds in ANY car. The probationary dense GPS it briefly raises also
    // feeds the existing speed backstop, so the start is captured even if confirmOnset is shy.
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var sigMotionListener: TriggerEventListener? = null
    @Volatile private var accelListener: SensorEventListener? = null
    @Volatile private var onsetProbing = false
    @Volatile private var onsetProbationUntil = 0L
    @Volatile private var onsetTokenSource: CancellationTokenSource? = null

    /**
     * ## Tier state — confined to the [reconciler] thread
     *
     * Every field below is read and written **only** on the reconciler thread, which is why none of
     * them is `@Volatile` or guarded by a lock and none of them needs to be. They were all plain
     * fields before too — the difference is that they were touched from two threads, and the
     * sequences that touch them are read-decide-write (is the tier different? then switch it; is
     * this request the one already in force? then skip it), which a lock per field would not have
     * made safe anyway.
     *
     * The one exception is [currentTier]: the OBD and upload loops read it from their own
     * coroutines to decide their cadence. Single-writer / many-reader is fine — a stale read there
     * costs one poll interval — so it stays volatile and everything else stays plain.
     */
    @Volatile private var currentTier = DriveDetector.Tier.LIGHT
    private var requestedIntervalMs = -1L         // active LocationRequest interval (avoid churn)
    private var requestedPriority = -1            // active LocationRequest priority (ditto)
    private var lastPersistedFixAt = 0L           // throttle Settings writes for the kill detector
    private var lastPersistedMeters = 0.0         // last driveMeters mirrored to durable Settings
    private var notifSig: String? = null          // signature of the last-drawn drive card (redraw coalescing)
    private var flashUntil = 0L                   // hold the "Marked #N" flash; suppress per-fix redraws until then

    /**
     * Fixes enqueued since the last flush — the one counter that is genuinely shared, and so the
     * one exception to the block above. It is *incremented* on the reconciler thread (a fix) but
     * *zeroed* by [flushNow], which fires from the network callback, the charge receiver and the
     * upload loop — three other threads. It was a plain `Int` doing that, which is a data race.
     *
     * Atomic rather than submitted, because it is a counter, not a decision: nothing reads it and
     * then acts on the tier, so there is no read-decide-write to serialise, and putting a queue hop
     * on every network event to protect one increment would be ceremony. The worst a lost update
     * could ever cost is a batch flushing one tick late, which the periodic cadence covers anyway.
     */
    private val pendingSinceFlush = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * How the service enters the foreground. A **seam, not a strategy** — production has exactly
     * one implementation and always will.
     *
     * It exists because the one test this file most needs is "startForeground threw, so we degrade
     * to a clean stop instead of a crash-loop", and the throw it must survive is Android 14
     * enforcing an FGS type's prerequisites — which no unit-test runtime can provoke. Without a
     * seam the degrade path is unreachable from a test, and an unreachable safety net is one a
     * future refactor can quietly delete. Assign before `onCreate` runs (the test does; nothing
     * else may).
     */
    internal var enterForeground: (Notification) -> Unit = { startForeground(NOTIF_ID, it) }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    /** Flush as soon as a usable network returns, so a dead-zone backlog clears
     *  immediately instead of waiting for the next periodic tick. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { flushNow() }
    }

    /** Charge-connected → immediate flush: charging is the cheapest possible window
     *  to drain any LIGHT-tier backlog (radio is "free" relative to the battery cost
     *  we usually avoid). Registered at runtime since ACTION_POWER_CONNECTED is an
     *  implicit broadcast that won't fire from a manifest receiver. */
    private val chargeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { flushNow() }
    }

    // Within-drive adaptive-sampling state (dense vs idle).
    private var idleMode = false
    private var slowCount = 0
    private var lastMoveLoc: Location? = null
    /** Previous fix, for the running [LiveState.driveMeters] sum. Distinct from lastMoveLoc,
     *  which adaptSampling resets on its own schedule. */
    private var lastDistLoc: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleFix(loc)
        }
    }

    /** Car Bluetooth connect/disconnect → flip the #1 driving signal. Registered at
     *  runtime (runtime receivers aren't subject to the manifest implicit-broadcast
     *  limits and the service is always alive to hear them).
     *
     *  The parse happens here, on the main thread, because that is where the Intent is; the
     *  *decision* is submitted, because it changes the tier. This receiver used to flip
     *  `carConnected` and call `reevaluate()` inline — main-thread writes racing the locator
     *  thread's, and the reason the whole reconciler exists. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            // Multi-vehicle (Phase 4): any registered car MAC means "driving". Match the whole
            // set, not just the legacy single MAC, and record WHICH car connected.
            val mac = dev.address?.uppercase() ?: return
            if (mac !in settings.carBtMacs) return
            val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
            reconciler.submit(TierReconciler.Trigger.CAR_BT) {
                detector.carConnected = connected
                // Which car we're in — the OBD loop reads this to pick THAT car's adapter
                // (Settings.obdTarget). Kept as a MAC even after stampVehicle upgrades the drive's
                // vehicle key to the VIN.
                LiveState.carBtMac = if (connected) mac else null
                if (connected) stampVehicle(mac)  // the drive's vehicle key (upgraded to VIN by OBD)
                reevaluate()
            }
        }
    }

    /** Record which vehicle the current drive is on and append a durable event the SPA drains
     *  (Phase 4). Only stamps when the key actually changes, so a reconnect flutter doesn't
     *  spam the buffer. [key] is a BT MAC or, once OBD reads it, a VIN — and on a VIN stamp
     *  [obdMac] names the adapter that read it, so the SPA can follow a dongle moved to a
     *  different car. */
    private fun stampVehicle(key: String, obdMac: String? = null) {
        if (key.isBlank() || key == LiveState.vehicleKey) return
        LiveState.vehicleKey = key
        val nowSec = System.currentTimeMillis() / 1000
        runCatching { WebVehicleBuffer.append(this, nowSec, key, obdMac) }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        EventLog.init(this)
        settings = Settings(this)
        uploader = Uploader(this, settings)
        detector = DriveDetector(settings)
        // Open this process's life in the liveness ledger — and, in doing so, close the previous
        // one. Whatever [Health] finds in Settings right now was written by a process that no
        // longer exists, so an absence it left behind can be stated as downtime rather than
        // guessed at from missing fixes. First thing after Settings, so even a service that dies
        // in startForeground below is bracketed.
        runCatching { Health.startLife(this, settings) }
        // Resuming mid-drive (app update / OEM kill): the persisted drive start survived but the
        // detector's in-memory driving signals didn't. Hold DRIVING through the cold start so the
        // drive keeps its identity — original start time, running miles, marker count — instead
        // of the first (fixless) tier resolution ending it and a brand-new drive taking its place.
        // Same rule markDriveStart reuses the mark by ([DriveSession.resumable]) — they are two
        // halves of one decision and must not be able to disagree.
        if (settings.loggingEnabled && DriveSession.resumable(settings.driveStartedAt, Clock.now())) {
            detector.resumeDriving = true
            EventLog.info("Resuming drive in progress (restart mid-drive)")
        }
        // Android 14 enforces the FGS type's prerequisites at startForeground time: with
        // fine location revoked this throws SecurityException. Callers can't all pre-check
        // (boot/reboot races), so degrade to a clean stop instead of a crash-loop.
        //
        // Note what is NOT done here: `loggingEnabled` is left alone. This is not a stop, it is a
        // start we could not perform — so the flag stays set, [Watchdog] keeps the intent alive,
        // and it retries once its own readiness gate ([Permissions.Snapshot.isReady]) says the
        // permission is back. Clearing it would turn a revoked permission into a permanent,
        // silent end to tracking.
        try {
            enterForeground(buildNotification())
        } catch (e: Exception) {
            EventLog.warn("Can't start logging: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            return
        }
        EventLog.info("Logging service started")
        ContextCompat.registerReceiver(this, btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, chargeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { connectivity.registerDefaultNetworkCallback(netCallback) }
        startObdLoop()
        startUploadLoop()
    }

    /**
     * Periodic batched flush. Cadence is **tier-aware**: dense (`drivingUploadIntervalSec`,
     * ~10s default) while DRIVING so the dashboard / live ETA / Auto pane see near-real-
     * time position, slower (`uploadIntervalSec`, ~45s default) while LIGHT to spare the
     * radio. We also flush on connectivity-regained, charge-connected, and BATCH_FIXES;
     * the backoff guard inside flush() keeps this from hammering during an outage.
     */
    private fun startUploadLoop() {
        scope.launch {
            while (isActive) {
                // No server paired (standalone) → don't wake to flush a queue that nothing
                // drains; a slow idle tick still notices a server appearing (Phase 5 audit).
                // charge/connectivity/batch triggers keep an eventual server install prompt.
                val sec = when {
                    !settings.isConfigured -> STANDALONE_IDLE_SEC
                    currentTier == DriveDetector.Tier.DRIVING ->
                        settings.drivingUploadIntervalSec.coerceAtLeast(1)
                    else -> settings.uploadIntervalSec.coerceAtLeast(5)
                }
                delay(sec * 1000L)
                // Proof of life, stamped from work the service was doing anyway. This loop is
                // the beat's floor while parked: fixes may stop arriving, but the process still
                // wakes to check its queue. (A late tick is not a problem — see [Health]: what
                // proves continuity is that the SAME process wrote both ends of the interval,
                // not that it wrote them on time.)
                runCatching { Health.beat(this@LocationService, settings) }
                if (settings.isConfigured) flushNow()
            }
        }
    }

    /** Trigger a (single-flight, draining) flush and reset the size counter. */
    private fun flushNow() {
        pendingSinceFlush.set(0)
        // runCatching: this scope has no CoroutineExceptionHandler, so an uncaught throw
        // here kills the process — and a sticky service makes that a crash LOOP that also
        // takes the WebView down on every relaunch. An upload must never be able to do that.
        scope.launch {
            runCatching { uploader.flush() }
                .onFailure { EventLog.warn("Upload flush failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /**
     * Every branch here that touches tier state is [TierReconciler.submit]ted rather than run
     * inline: `onStartCommand` is delivered on the main thread, and this method used to mark, boost
     * and re-apply the tier from it while the locator thread was doing the same.
     *
     * The OFF branch is the exception, and stays synchronous on purpose. It touches no tier state
     * (it writes the two Settings flags and stops the service), and it has to be *done* by the time
     * this method returns `START_NOT_STICKY` — deferring the stop would leave a window in which the
     * queue still holds tier work for a service that is on its way out.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A side-effect action, handled before the mode logic below: marking changes no
        // tracking mode, and must never be mistaken for a stop.
        if (intent?.action == Control.ACTION_MARK) {
            reconciler.submit(TierReconciler.Trigger.MARK) { markNow() }
            return START_STICKY
        }
        // Dashboard opened/closed: re-apply the (now boosted or restored) dense fix rate at
        // once instead of waiting for the next adaptSampling tick. OBD picks up its own boost
        // on its next loop iteration. Never mistaken for a stop.
        if (intent?.action == ACTION_DASHBOARD) {
            reconciler.submit(TierReconciler.Trigger.DASHBOARD) {
                if (currentTier == DriveDetector.Tier.DRIVING && !idleMode) {
                    requestUpdates(drivingIntervalMs(), Priority.PRIORITY_HIGH_ACCURACY)
                }
            }
            return START_STICKY
        }
        if (intent?.action == Control.ACTION_STOP || settings.trackingMode == Settings.MODE_OFF) {
            settings.trackingMode = Settings.MODE_OFF
            settings.loggingEnabled = false   // intentional stop
            Watchdog.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        settings.loggingEnabled = true
        Watchdog.schedule(this)               // arm the self-healing backstop
        reconciler.submit(TierReconciler.Trigger.COMMAND) {
            // Apply the tier the detector currently resolves (forces a fresh request).
            requestedIntervalMs = -1L
            requestedPriority = -1
            applyTier(detector.tier())
        }
        return START_STICKY
    }

    /** (Re)resolve the tier from the detector and switch sampling if it changed. */
    private fun reevaluate() {
        reconciler.requireOwnThread("reevaluate")
        val t = detector.tier()
        if (t == DriveDetector.Tier.OFF) {
            settings.trackingMode = Settings.MODE_OFF
            settings.loggingEnabled = false
            settings.driveStartedAt = 0L   // tracking off ends the drive; don't resume it later
            Watchdog.cancel(this)
            stopSelf()
            return
        }
        if (t != currentTier) applyTier(t)
    }

    private fun applyTier(tier: DriveDetector.Tier) {
        reconciler.requireOwnThread("applyTier")
        val changed = tier != currentTier
        currentTier = tier
        if (changed) {
            when (tier) {
                DriveDetector.Tier.DRIVING -> EventLog.info("Driving detected (${detector.reason()})")
                DriveDetector.Tier.LIGHT -> EventLog.info("Light background tracking")
                else -> {}
            }
            StateBroadcaster.emit(this, "tier")
        }
        when (tier) {
            DriveDetector.Tier.DRIVING -> {
                idleMode = false; slowCount = 0; lastMoveLoc = null
                disarmMotionOnset()   // already dense; no point waking on motion
                requestUpdates(drivingIntervalMs(), Priority.PRIORITY_HIGH_ACCURACY)
            }
            DriveDetector.Tier.LIGHT -> {
                requestUpdates(settings.lightIntervalSec * 1000L, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                armMotionOnset()      // catch the next start within seconds, in any car
            }
            DriveDetector.Tier.OFF -> { /* handled by reevaluate/stop */ }
        }
        LiveState.tier = tier.name
        LiveState.driveReason = detector.reason()
        LiveState.driveStartedAt = markDriveStart(tier)
        updateNotification()
    }

    /**
     * Maintain the durable drive-start mark and return it (0 = not driving). Entering DRIVING
     * from anywhere but a mid-drive service restart stamps `now`; leaving DRIVING clears it.
     *
     * The reason this is a *durable* mark rather than a field: when the service is killed and
     * restarts mid-drive it re-enters DRIVING with the car still connected, and stamping a
     * fresh start there would silently reset the live bar's clock in the middle of the drive.
     * A surviving mark is reused instead — on the terms [DriveSession.resumable] sets, which is
     * the same rule `onCreate`'s resume latch used to hold the tier long enough to get here.
     */
    private fun markDriveStart(tier: DriveDetector.Tier): Long {
        if (tier != DriveDetector.Tier.DRIVING) {
            if (settings.driveStartedAt != 0L) {
                stampBatteryUsed(settings.driveStartedAt)   // before the start mark is cleared
                onDriveEnded(settings.driveStartedAt)       // notifications (P3): before totals reset
                settings.driveStartedAt = 0L
                // A drive just ended: on the "after each drive" backup schedule, queue an
                // archive shortly (debounced inside — a quick errand resume re-arms it).
                BackupWorker.afterDrive(this, settings)
            }
            resetDriveTotals()
            return 0L
        }
        val now = Clock.now()
        val existing = settings.driveStartedAt
        val resume = DriveSession.resumable(existing, now)
        // Entering DRIVING — resumed or new — invalidates the previous leg's pending
        // "tag your drive" prompt: a gas-stop chain prompts once, after its final leg.
        DriveCompleteWorker.cancel(this)
        if (resume) {
            resumeDriveTotals(existing)
        } else {
            settings.driveStartedAt = now
            resetDriveTotals()   // a NEW drive: its miles and markers start at zero
            // Snapshot the battery at the drive's start so drive end can report what it cost.
            settings.driveBatteryStart = Battery.levelPct(this) ?: -1
            // The start position (last known fix ≈ the spot the car starts from), for the
            // gas-stop heuristic's same-spot / onward-progress checks at drive end.
            val lat = LiveState.lat
            val lon = LiveState.lon
            settings.driveStartPos = if (lat != null && lon != null) "$lat,$lon" else ""
        }
        return if (resume) existing else now
    }

    /**
     * A drive just ended (still holds its start mark). For REAL drives — far/long enough to
     * be a visible trip — arm the delayed "tag your drive" prompt, run the gas-stop pair
     * heuristic against the PREVIOUS drive, and stamp this drive as the next check's
     * "previous". Jitter loops are ignored entirely: they never prompt and never become the
     * left leg of a gas pair. Everything here is default-off (NOTIFICATIONS.md P3).
     */
    private fun onDriveEnded(startedAtMs: Long) {
        val now = Clock.now()
        // LiveState is live but zeroed by a mid-drive restart; the Settings mirror is durable
        // but throttled. The max of the two is the honest distance either way.
        val meters = maxOf(LiveState.driveMeters, settings.driveMeters.toDouble())
        if (!DriveEndProcessor.isRealDrive(meters, now - startedAtMs)) return
        if (settings.notifyDriveComplete) {
            DriveCompleteWorker.schedule(this, startedAtMs, now, meters)
        }
        // This drive as a Leg — null if we never got a fix at one end or the other, in which case
        // it can neither be paired against the previous drive nor become the next one's previous.
        val start = DriveEndProcessor.decodePos(settings.driveStartPos)
        val endLat = LiveState.lat
        val endLon = LiveState.lon
        val cur = if (start != null && endLat != null && endLon != null) {
            DriveEndProcessor.Leg(startedAtMs, now, start.first, start.second, endLat, endLon)
        } else null

        if (cur != null && settings.notifyGasStop) maybeNotifyGasStop(cur)
        settings.prevDriveSummary = cur?.let { DriveEndProcessor.encode(it) } ?: ""
    }

    /**
     * The SPA's gas-stop rules ([DriveEndProcessor.gasStopGapMinutes]), run natively at drive end
     * so the split is flagged even with the app closed. Keyed by this drive's start (the pair's
     * `right_ts`, the same boundary the SPA uses) so the attention push can retract it after a
     * merge or a dismissal, and deep-linked to the *previous* leg, which is the one you would open
     * to do the merging.
     */
    private fun maybeNotifyGasStop(cur: DriveEndProcessor.Leg) {
        val prev = DriveEndProcessor.decode(settings.prevDriveSummary) ?: return
        val gapMin = DriveEndProcessor.gasStopGapMinutes(prev, cur) ?: return
        Notify.post(
            this, Notify.KIND_GAS_STOP, (cur.startedAtMs / 1000).toString(),
            "Likely gas-stop split",
            "Two legs ${Math.round(gapMin)} min apart look like one drive — merge them?",
            "/drive/L${prev.startedAtMs / 1000}"
        )
    }

    /**
     * At drive end, stamp one "battery used" event for the drive that started at [startedAtMs]:
     * the start reading ([Settings.driveBatteryStart], captured when the drive began and durable
     * across a mid-drive service restart) and the reading now. The SPA drains it into the
     * per-drive `trip_battery` overlay and shows it on the drive-detail view, so battery
     * improvements are visible and regressions catchable (Phase 5 — measure it).
     */
    private fun stampBatteryUsed(startedAtMs: Long) {
        val start = settings.driveBatteryStart
        settings.driveBatteryStart = -1
        if (start !in 0..100) return
        val end = Battery.levelPct(this) ?: return
        runCatching { WebBatteryBuffer.append(this, startedAtMs / 1000, start, end) }
        val used = start - end
        if (used > 0) EventLog.info("Drive used $used% battery ($start→$end)")
    }

    /**
     * Zero the running totals the notification shows. On a *resumed* drive (a service restart
     * mid-drive) they are rebuilt instead: the marker count is recovered from the on-disk
     * buffer and the running distance from the durable [Settings.driveMeters] mirror, because a
     * driver who marked three job sites and drove twenty miles must not see either fall to zero
     * when the OS kills and revives the service.
     */
    private fun resetDriveTotals() {
        LiveState.driveMeters = 0.0
        settings.driveMeters = 0f
        LiveState.markerCount = 0
        LiveState.lastMarkerTs = null
        lastDistLoc = null
        lastPersistedMeters = 0.0
    }

    private fun resumeDriveTotals(startedAtMs: Long) {
        val startSec = startedAtMs / 1000
        LiveState.driveMeters = settings.driveMeters.toDouble()
        lastPersistedMeters = LiveState.driveMeters
        LiveState.markerCount = runCatching { WebMarkerBuffer.countSince(this, startSec) }.getOrDefault(0)
        LiveState.lastMarkerTs = runCatching { WebMarkerBuffer.latestTs(this) }.getOrNull()
            ?.takeIf { it >= startSec }
    }

    /** The dense-tier GPS interval, dropped to [DASH_BOOST_MS] while the live dashboard is open
     *  so the speedometer reads ~1s-live; otherwise the user's `intervalSec`. */
    private fun drivingIntervalMs(): Long =
        if (dashboardBoost) minOf(settings.intervalSec * 1000L, DASH_BOOST_MS) else settings.intervalSec * 1000L

    /**
     * (Re)subscribe to fixes at the given interval/priority. Skips a no-op re-request only when
     * BOTH are unchanged (a LIGHT→DRIVING flip with equal intervals must still upgrade the
     * priority, or the whole drive gets balanced-power fixes).
     *
     * That skip is a memo of what is currently in force, and it is exactly what the old race could
     * corrupt: two threads interleaving here could leave the memo describing a request that had
     * been superseded, so the service believed it was sampling densely while the provider was
     * delivering a fix a minute. Nothing threw. On the reconciler thread the read-decide-write is
     * atomic by construction.
     */
    private fun requestUpdates(intervalMs: Long, priority: Int) {
        reconciler.requireOwnThread("requestUpdates")
        if (intervalMs == requestedIntervalMs && priority == requestedPriority) return
        requestedIntervalMs = intervalMs
        requestedPriority = priority
        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minOf(intervalMs, 1000L))
            .build()
        try {
            // Deliver fixes onto the reconciler's own thread — so a fix IS a tier event rather
            // than something that has to hop onto the tier thread, and so the disk I/O handleFix
            // does (queue append, cap check, WebFixBuffer rewrite) stays off the UI thread.
            fused.requestLocationUpdates(req, callback, reconciler.looper)
        } catch (se: SecurityException) {
            stopSelf()
        }
    }

    /** Runs on the reconciler thread: the fused provider delivers straight onto it. */
    private fun handleFix(loc: Location) {
        reconciler.requireOwnThread("handleFix")
        val obd = if (currentTier == DriveDetector.Tier.DRIVING) latestObd else null
        uploader.enqueue(
            lat = loc.latitude,
            lon = loc.longitude,
            epochSec = loc.time / 1000,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            courseDeg = if (loc.hasBearing()) loc.bearing else null,
            obd = obd
        )
        // Also feed the phone's own GPS to the on-device SPA replica (STANDALONE.md A2), so
        // drives + mileage work with no server. The SPA drains this via the DrivetimeNative
        // bridge; idempotent on `ts`, independent of upload.
        WebFixBuffer.append(
            this, loc.latitude, loc.longitude, loc.time / 1000,
            if (loc.hasSpeed()) loc.speed else null, obd
        )
        LiveState.logging = true
        LiveState.speedMph = if (loc.hasSpeed()) Math.round(loc.speed * 2.2369362f) else null
        LiveState.lat = loc.latitude
        LiveState.lon = loc.longitude
        // Running distance for the notification + the SPA's live bar. Only while DRIVING:
        // idle-tier wander (GPS drift in a parking lot) is not mileage.
        if (currentTier == DriveDetector.Tier.DRIVING) {
            lastDistLoc?.let { LiveState.driveMeters += it.distanceTo(loc) }
            lastDistLoc = loc
        }
        val now = Clock.now()
        LiveState.updatedAt = now
        // Persist lastFixAt at most every PERSIST_FIX_MS so the watchdog's kill
        // detector has a recent timestamp without one SharedPreferences write per fix.
        // Mirror the running distance on the same tick (near-free) so a mid-drive restart
        // resumes the miles instead of zeroing them (resumeDriveTotals reads Settings.driveMeters).
        if (now - lastPersistedFixAt >= PERSIST_FIX_MS) {
            settings.lastFixAt = now
            lastPersistedFixAt = now
            if (LiveState.driveMeters != lastPersistedMeters) {
                settings.driveMeters = LiveState.driveMeters.toFloat()
                lastPersistedMeters = LiveState.driveMeters
            }
            // A fix is also proof the process is alive — the densest such proof we get. (It is
            // not the ONLY one, and that is the point: [Health] must keep beating through a
            // parked hour when no fix ever arrives.)
            runCatching { Health.beat(this, settings) }
        }
        // Batched upload: buffer to the durable queue; flush on the periodic tick,
        // when a full batch has accumulated, or when connectivity returns.
        if (pendingSinceFlush.incrementAndGet() >= BATCH_FIXES) flushNow()

        val speed = if (loc.hasSpeed()) loc.speed else 0f
        movingHint = speed >= MOVING_MPS
        detector.onSpeed(speed, now)
        // The drive's signal light — green moving, red stopped — plus when the stop began, so
        // the card and the HUD can both count it up. A drive sitting at a pump says so.
        LiveState.moving = detector.isMoving
        LiveState.stoppedSince = detector.stoppedSince
        reevaluate()
        if (currentTier == DriveDetector.Tier.DRIVING) adaptSampling(loc)
        // Coalesce the drive-card redraw: only re-post when a value the card actually shows has
        // changed (speed int, miles at 0.1, marker count, tier). The elapsed Chronometer ticks
        // natively without a redraw, so a per-fix (~1 Hz) notify() would be pure battery cost for
        // an identical card — this is the cheapest real win of Phase 5.
        maybeUpdateNotification()
    }

    /**
     * Within a drive, adapt the fix rate to motion: dense while moving, idle back-off
     * while stopped (red light / traffic). Tier exit is the detector's job now, so
     * this no longer ends the trip — it only trades dense for idle.
     */
    private fun adaptSampling(loc: Location) {
        val ref = lastMoveLoc
        val moved = if (ref != null) ref.distanceTo(loc) else Float.MAX_VALUE
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val moving = speed >= MOVING_MPS || moved >= MOVE_RESET_M

        if (moving) {
            lastMoveLoc = loc
            slowCount = 0
            if (idleMode) {
                idleMode = false
                requestUpdates(drivingIntervalMs(), Priority.PRIORITY_HIGH_ACCURACY)
            }
            return
        }
        if (lastMoveLoc == null) lastMoveLoc = loc
        slowCount++
        if (!idleMode && slowCount >= STOP_CONFIRM) {
            idleMode = true
            requestUpdates(settings.idleIntervalSec * 1000L, Priority.PRIORITY_HIGH_ACCURACY)
        }
    }

    /**
     * Maintain the OBD link as both a telemetry source and a *driving signal*. We only
     * attempt a (re)connect when there's reason to think we're in the car — already
     * driving, car-BT connected, or recent motion — so a configured-but-off dongle
     * isn't probed 24/7. A successful connect flips `obdConnected` (→ DRIVING).
     */
    private fun startObdLoop() {
        scope.launch {
            // The loop's judgement — the probe gate, the wedged-adapter streak and the recovery
            // cadence — lives in ObdSession, which is pure and tested. What stays here is the part
            // that genuinely needs Android: a BluetoothManager, a socket, and a coroutine to block
            // on. The session outlives any one connection, because the streak it counts is the
            // thing a single attempt cannot see.
            val session = ObdSession()
            while (isActive) {
                // The adapter belonging to the car we're actually in (by its connected car BT),
                // falling back to the sole registered one — or, on an install that predates the
                // vehicle registry, the legacy single OBD setting. Re-read every pass, so
                // getting into the other car re-targets without a restart.
                val mac = settings.obdTarget(LiveState.carBtMac)
                if (mac.isBlank() || !shouldProbeObd()) { delay(ObdSession.IDLE_MS); continue }
                var connected = false
                try {
                    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    if (adapter == null) { delay(ObdSession.IDLE_MS); continue }
                    val client = Elm327Client()
                    client.connect(adapter.getRemoteDevice(mac))
                    connected = true
                    session.onConnected()
                    obd = client
                    LiveState.obdConnected = true
                    EventLog.info("OBD connected")
                    // Multi-vehicle (Phase 4): a read VIN is the strongest vehicle identity —
                    // upgrade the drive's key from the BT MAC to the VIN so the SPA can stamp the
                    // drive with the right car (and register one if this VIN is genuinely new).
                    // The adapter's own MAC rides along, so moving the dongle to another car
                    // moves the adapter in the registry too, instead of stranding it on the old one.
                    client.vin?.takeIf { it.isNotBlank() }?.let { stampVehicle(it, mac) }
                    runCatching { client.diagnostic().forEach { EventLog.info("OBD $it") } }
                    // The signal flip and the tier decision it causes are one event, applied on
                    // the reconciler thread. Setting `obdConnected` out here and reevaluating over
                    // there would let a fix land in between and resolve the tier from a
                    // half-applied signal.
                    reconciler.submit(TierReconciler.Trigger.OBD) {
                        detector.obdConnected = true
                        reevaluate()
                        StateBroadcaster.emit(this@LocationService, "obd")
                    }
                    var ticks = 0
                    var loggedSample = false
                    // Leave when we're parked, not just when the socket dies. An OBD-II port is
                    // permanently powered, so `isConnected()` stays true with the ignition off —
                    // this loop used to spin forever on a key-off dongle, which both kept
                    // `obdConnected` (and therefore DRIVING) pinned and polled a dead ECU for
                    // hours. Dropping the link runs the `finally` below, which clears both.
                    while (isActive && client.isConnected() && !detector.isParked) {
                        val s = client.readSample()
                        // rpm is the ONLY thing that distinguishes "engine running" from "dongle
                        // still plugged into a parked car" — the detector needs it to know that
                        // idling at a light is not the same as sitting in a parking lot.
                        //
                        // Written straight from this thread, unlike `obdConnected` above, and the
                        // difference is real: `obdConnected` is an EDGE that must be paired with a
                        // tier decision (connecting starts a drive; dropping lets a parked one
                        // end), so the flip and the reevaluate have to be one atomic event.
                        // `engineRunning` is a LEVEL, resampled every poll, that nothing reevaluates
                        // on — it only modulates the `parked` latch, which is computed on the
                        // reconciler thread on the next fix. A volatile write is the whole
                        // contract; queueing one per poll would be traffic for nothing.
                        detector.engineRunning = ObdSession.engineRunning(s.rpm)
                        // Log the first decoded sample so we can confirm PIDs are parsing
                        // (not just connecting) without watching live.
                        if (!loggedSample) {
                            loggedSample = true
                            EventLog.info("OBD sample rpm=${s.rpm} kph=${s.obdKph} coolant=${s.coolantC}" +
                                " load=${s.engineLoad?.let { "%.0f".format(it) }} thr=${s.throttle?.let { "%.0f".format(it) }}" +
                                " maf=${s.maf?.let { "%.1f".format(it) }} fuel=${s.fuelLph?.let { "%.1f".format(it) }}L/h" +
                                " tank=${s.fuelLevel?.let { "%.0f".format(it) }}% v=${s.voltage} ctrlV=${s.ctrlVoltage}")
                        }
                        latestObd = if (ObdSession.readDtcsOnTick(ticks)) {
                            val fresh = s.copy(dtcs = client.readDtcs())
                            maybeAlertDtcs(fresh.dtcs)
                            fresh
                        } else s
                        LiveState.rpm = s.rpm; LiveState.throttle = s.throttle
                        LiveState.coolantC = s.coolantC; LiveState.voltage = s.voltage
                        ticks++
                        delay(ObdSession.pollDelayMs(dashboardBoost))
                    }
                } catch (e: Exception) {
                    // dongle off/unpaired/out of range — log it so a real fault is visible
                    // instead of silently logging GPS without engine data.
                    EventLog.warn("OBD error: ${e.message ?: e.javaClass.simpleName}")
                    session.onFailure(everConnected = connected)
                } finally {
                    val wasConnected = LiveState.obdConnected
                    if (wasConnected) EventLog.info("OBD disconnected")
                    obd?.close(); obd = null
                    latestObd = null
                    LiveState.obdConnected = false
                    // Dropping the link is a driving signal going away — the same event as it
                    // arriving, and it goes through the same door. `obdConnected` false is what
                    // lets a parked car actually leave DRIVING, so it must not be applied
                    // half-way while the tier is being resolved from the other side.
                    reconciler.submit(TierReconciler.Trigger.OBD) {
                        detector.obdConnected = false
                        detector.engineRunning = false
                        reevaluate()
                        if (wasConnected) StateBroadcaster.emit(this@LocationService, "obd")
                    }
                }
                // What to do next is the session's call; performing it is ours. The cold reset is
                // the only arm with a side effect out here, because forgetting the cached socket
                // strategy is Bluetooth state, not a decision.
                val next = session.recovery(driving = currentTier == DriveDetector.Tier.DRIVING)
                if (next is ObdSession.Recovery.ColdReset) {
                    Elm327Client.clearStrategy(mac)
                    EventLog.warn(
                        "OBD unresponsive ×${session.wedgedStreak} — cold reset, " +
                            "pausing ${next.delayMs / 1000}s"
                    )
                    session.onColdReset()   // after the log: it reads the streak
                }
                delay(next.delayMs)
            }
        }
    }

    /** Only probe when there's reason to think we're in a *running* car — see
     *  [ObdSession.shouldProbe], which owns the rule and the two bugs behind it. */
    private fun shouldProbeObd(): Boolean = ObdSession.shouldProbe(
        parked = detector.isParked,
        carConnected = detector.carConnected,
        movingHint = movingHint,
        driving = currentTier == DriveDetector.Tier.DRIVING,
    )

    // ---- Motion-onset (device-agnostic fast start) ----

    /** Arm the one-shot significant-motion trigger (idempotent, LIGHT-tier only). It's
     *  hardware-backed and ~free while parked; on a device without it the app simply
     *  leans on the 60 s LIGHT heartbeat + speed backstop as before. */
    private fun armMotionOnset() {
        reconciler.requireOwnThread("armMotionOnset")
        if (!settings.motionOnset || sigMotionListener != null) return
        val sm = sensors ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        val l = object : TriggerEventListener() {
            // The OS delivers this on its own thread, so the whole handler is submitted — including
            // clearing `sigMotionListener`, which arm/disarm also write. That field is the arm
            // latch: two threads writing it is how you end up either double-armed or, worse,
            // permanently disarmed with the app waiting for a trigger that will never come.
            override fun onTrigger(event: TriggerEvent?) {
                reconciler.submit(TierReconciler.Trigger.MOTION) {
                    sigMotionListener = null   // one-shot: the OS has disarmed it
                    onMotionTrigger()
                }
            }
        }
        if (sm.requestTriggerSensor(l, sensor)) sigMotionListener = l
    }

    private fun disarmMotionOnset() {
        val l = sigMotionListener ?: return
        sigMotionListener = null
        val sm = sensors ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sm.cancelTriggerSensor(l, sensor) }
    }

    /** A significant-motion wake fired. Raise GPS to a probationary dense rate, take one
     *  instant Doppler fix + a short accelerometer read, and let [DriveDetector.confirmOnset]
     *  decide. Confirmed → flip to DRIVING now; otherwise hold dense GPS until the probation
     *  window expires (so the speed backstop can still promote a shy start) then fall back to
     *  LIGHT and re-arm. Runs on the reconciler thread (it reads the tier and raises the fix rate);
     *  the blocking Doppler/accelerometer work is a coroutine, which submits its answer back. */
    private fun onMotionTrigger() {
        reconciler.requireOwnThread("onMotionTrigger")
        if (onsetProbing || currentTier != DriveDetector.Tier.LIGHT) {
            if (currentTier == DriveDetector.Tier.LIGHT) armMotionOnset()  // don't lose the trigger
            return
        }
        onsetProbing = true
        onsetProbationUntil = Clock.now() + settings.onsetProbeWindowSec * 1000L
        LiveState.onsetState = "probing"
        EventLog.info("Motion onset → probing")
        requestUpdates(settings.onsetProbeIntervalSec * 1000L, Priority.PRIORITY_HIGH_ACCURACY)
        val tokenSrc = CancellationTokenSource()
        onsetTokenSource = tokenSrc
        scope.launch {
            // Off-thread on purpose: an instant-fix await plus a 3s accelerometer window. Blocking
            // the reconciler thread for that would stall every fix behind it.
            val doppler = currentDoppler(tokenSrc)
            val accel = sampleAccelRms()
            val confirmed = detector.confirmOnset(doppler, accel, Clock.now())
            if (confirmed) {
                LiveState.onsetState = "confirmed"
                val mph = doppler?.let { Math.round(it * 2.2369362f) } ?: -1
                EventLog.info("Driving detected (motion, $mph mph)")
                reconciler.submit(TierReconciler.Trigger.MOTION) {
                    reevaluate()
                    StateBroadcaster.emit(this@LocationService, "motion")
                }
            } else {
                val remain = onsetProbationUntil - Clock.now()
                if (remain > 0) delay(remain)
            }
            // Close the probe on the reconciler thread — it clears the probing latch and, if the
            // tier never went DRIVING, drops the probationary dense GPS back to LIGHT and re-arms.
            // Submitted after the confirm above, so it observes the tier that confirm produced.
            reconciler.submit(TierReconciler.Trigger.MOTION) {
                onsetProbing = false
                onsetTokenSource = null
                if (currentTier == DriveDetector.Tier.LIGHT) {
                    LiveState.onsetState = "idle"
                    requestUpdates(settings.lightIntervalSec * 1000L, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    armMotionOnset()
                }
            }
        }
    }

    /** One instant high-accuracy fix's Doppler speed (m/s), or null if unavailable. Blocking
     *  await on the IO coroutine; a permission/timeout/failure degrades to null (the dense
     *  probe + speed backstop still cover the start). The service only probes while running,
     *  which requires location permission; a revoked permission throws → caught → null. */
    @SuppressLint("MissingPermission")
    private fun currentDoppler(tokenSrc: CancellationTokenSource): Float? = try {
        val task = fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSrc.token)
        val loc = Tasks.await(task, 8, TimeUnit.SECONDS)
        if (loc != null && loc.hasSpeed()) loc.speed else null
    } catch (e: Exception) {
        null
    }

    /** RMS of gravity-removed accelerometer magnitude over a short window (m/s²) — a smooth
     *  vehicle reads low, an on-foot bounce reads high. Null if no accelerometer. */
    private suspend fun sampleAccelRms(): Float? {
        val sm = sensors ?: return null
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val samples = java.util.Collections.synchronizedList(mutableListOf<Float>())
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val mag = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                samples.add(mag - 9.81f)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        accelListener = l
        sm.registerListener(l, accel, SensorManager.SENSOR_DELAY_GAME)
        delay(ACCEL_SAMPLE_MS)
        sm.unregisterListener(l)
        accelListener = null
        val arr = synchronized(samples) { samples.toList() }
        if (arr.isEmpty()) return null
        val meanSq = arr.fold(0.0) { acc, v -> acc + v.toDouble() * v } / arr.size
        return Math.sqrt(meanSq).toFloat()
    }

    override fun onDestroy() {
        isRunning = false
        LiveState.logging = false
        LiveState.clear()
        // Close this life, WITH its reason, while we still have the chance to say one. A process
        // the OEM battery manager destroys never reaches here — and that silence is precisely how
        // the next [Health.startLife] knows it was killed rather than stopped.
        runCatching { Health.endLife(this, settings) }
        if (settings.loggingEnabled) EventLog.warn("Logging stopped by system — will auto-resume")
        else EventLog.info("Logging stopped")
        StateBroadcaster.emit(this, "service")
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(chargeReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        disarmMotionOnset()
        runCatching { onsetTokenSource?.cancel() }
        accelListener?.let { runCatching { sensors?.unregisterListener(it) } }
        fused.removeLocationUpdates(callback)
        // Unsubscribe from fixes BEFORE the reconciler quits, so no fix is delivered onto a
        // looper that is on its way out. quitSafely drains what is already queued.
        reconciler.quit()
        obd?.close(); obd = null
        // Final flush off the cancelled scope so a last batch isn't dropped.
        Thread { runCatching { uploader.flush() } }.start()
        scope.cancel()
        // Deliberately do NOT clear loggingEnabled here: intentional stops (mode OFF)
        // already did. If it's still set, this is an OS kill — leave it so START_STICKY
        // and the watchdog resume.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(flash: String? = null) {
        val mgr = getSystemService(NotificationManager::class.java)
        runCatching { mgr.notify(NOTIF_ID, buildNotification(flash)) }
        // A plain (non-flash) draw reflects the current values — remember them so the coalesced
        // per-fix path can skip an identical redraw. A flash draws transient text over the same
        // values, so it must not update the signature (the post-flash draw restores it).
        if (flash == null) notifSig = notifSignature()
    }

    /**
     * Redraw the ongoing notification only if what the driver can see has changed. The elapsed
     * time is a self-ticking [Chronometer], so an unchanged card would redraw for nothing; and
     * while a "Marked #N" flash is up we deliberately hold it (a per-fix redraw used to stomp it
     * within a second). Non-fix paths (tier flips, marks) still call [updateNotification] directly.
     */
    private fun maybeUpdateNotification() {
        if (currentTier == DriveDetector.Tier.OFF) return
        if (Clock.now() < flashUntil) return
        if (notifSignature() == notifSig) return
        updateNotification()
    }

    /** See [DriveNotification.signature] — what the card shows, as one comparable string. */
    private fun notifSignature(): String = DriveNotification.signature(
        tier = currentTier,
        moving = detector.isMoving,
        reason = detector.reason(),
        channel = activeChannel(),
        speedMph = LiveState.speedMph,
        driveMeters = LiveState.driveMeters,
        markerCount = LiveState.markerCount,
    )

    /** See [DriveNotification.tierText] — "Driving · car BT", "Stopped · OBD", "Idle", "Off". */
    private fun tierText(): String =
        DriveNotification.tierText(currentTier, detector.isMoving, detector.reason())

    /** Car glyph while a drive is in progress, crosshair otherwise — the status bar says
     *  which state we're in by its shape, without the shade having to be pulled down. */
    private fun tierIcon(): Int =
        if (currentTier == DriveDetector.Tier.DRIVING) R.drawable.ic_notif_driving
        else R.drawable.ic_notif_tracking

    private fun driving(): Boolean = currentTier == DriveDetector.Tier.DRIVING

    /**
     * Which channel the ongoing notification rides on. `notif_driving_only` demotes the idle
     * card to IMPORTANCE_MIN — no status-bar icon, collapsed at the bottom of the shade —
     * instead of removing it, which a location FGS may not do (see [Settings.notifDrivingOnly]).
     * Re-posting an FGS notification on a different channel under the same id is allowed.
     */
    private fun activeChannel(): String =
        if (settings.notifDrivingOnly && !driving()) IDLE_CHANNEL else CHANNEL

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Drive logging", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (mgr.getNotificationChannel(IDLE_CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(IDLE_CHANNEL, "Idle tracking", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    /**
     * Reconcile the dongle's trouble codes against the ones we've already flagged, and let
     * [Notify] carry the difference both ways — fully on-device, no server round-trip.
     *
     * A code that APPEARS alerts. A code that CLEARS retracts its own notification: this is
     * the only place that knows a fault went away (the SPA's replica has no idea a car has a
     * check-engine light), so leaving the shade to time out on its own would strand a red
     * warning for a fault the driver already fixed. The remembered set is replaced wholesale
     * each read, so a fault that clears and returns alerts again.
     *
     * [Notify.post] is itself gated on [Settings.alertsEnabled], so a reader with alerts off
     * still just updates the set — enabling them later doesn't dump every standing code at
     * once.
     */
    private fun maybeAlertDtcs(codes: List<String>) {
        val cur = codes.toSet()
        val prev = settings.knownDtcs
        if (cur == prev) return
        for (code in cur - prev) {
            Notify.post(
                this, Notify.KIND_CHECK_ENGINE, code,
                "Check engine: $code",
                "Your vehicle reported a diagnostic trouble code.",
                "/settings",
            )
        }
        for (code in prev - cur) Notify.cancel(this, Notify.KIND_CHECK_ENGINE, code)
        settings.knownDtcs = cur
    }

    private fun servicePi(action: String, code: Int): PendingIntent {
        val i = Intent(this, LocationService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(icon: Int, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi)
            .build()

    /**
     * The notification IS the live drive (MARKERS.md §6): while driving it mirrors what
     * ActiveDriveBar.svelte shows — tier, speed, miles, elapsed, "since #N" — plus Mark and
     * Stop. It is on the lock screen for the whole drive by law, which is precisely when the
     * SPA is not on screen and the driver still needs to mark a job site.
     *
     * Idle and Off collapse back to one line with no actions: a Mark button on a parked phone
     * would write a marker into no drive at all.
     *
     * [flash] briefly replaces the stats line ("Marked #3") — the only confirmation a driver
     * with a locked phone ever gets.
     */
    private fun buildNotification(flash: String? = null): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WebViewActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // State-shaped status-bar icon (the rescaled brand D read as mush at glyph size):
        // the small crosshair while merely tracking, the car while a drive is live.
        val b = Notification.Builder(this, activeChannel())
            .setSmallIcon(tierIcon())
            .setOngoing(true)
            .setContentIntent(open)
            // A per-fix redraw (~1 Hz while driving) must never buzz or re-alert.
            .setOnlyAlertOnce(true)
            // Brand accent: tints the small icon, header and action titles so the card reads
            // as drivetime at a glance instead of grey system text. Resolves day/night.
            .setColor(getColor(R.color.dt_accent))

        if (!driving()) {
            return b.setContentTitle("drivetime").setContentText(tierText()).build()
        }

        // A custom mini-dashboard (speed / miles / live elapsed) drawn inside the system's
        // DecoratedCustomViewStyle, which still renders the header, small icon, background and
        // the Mark action. The numbers have to survive the lock screen — the whole point.
        b.setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(driveRemoteViews(R.layout.notif_drive, expanded = false, flash = flash))
            .setCustomBigContentView(driveRemoteViews(R.layout.notif_drive_big, expanded = true, flash = flash))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_notif_driving, "Mark", servicePi(Control.ACTION_MARK, 1)))
        return b.build()
    }

    /**
     * Populate the drive card's custom content. Both the collapsed and expanded layouts read the
     * same live totals; elapsed is a [Chronometer] anchored to [LiveState.driveStartedAt] so it
     * ticks on the lock screen without waking the service. [flash] briefly replaces the stats
     * line ("Marked #3") — the only confirmation a driver with a locked phone gets.
     */
    private fun driveRemoteViews(layout: Int, expanded: Boolean, flash: String?): RemoteViews {
        val rv = RemoteViews(packageName, layout)
        val startedMs = LiveState.driveStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - startedMs)
        val speed = LiveState.speedMph
        // Same constant and same one-decimal precision the redraw signature compares on — if these
        // two ever drift, the card either stops updating a number the driver can see or redraws
        // once a second for a change they cannot.
        val miles = String.format("%.1f", LiveState.driveMeters * DriveNotification.METERS_TO_MILES)
        val since = if (LiveState.markerCount > 0) "since #${LiveState.markerCount}" else null
        if (expanded) {
            rv.setChronometer(R.id.n_chrono_big, base, null, true)
            rv.setTextViewText(R.id.n_tier, tierText())
            rv.setTextViewText(R.id.n_speed_val, if (speed != null) "$speed" else "—")
            rv.setTextViewText(R.id.n_miles_val, miles)
            // The pin row only earns its glyph when there is something to say — an empty
            // line under the tiles with a dangling pin looks broken.
            val sinceLine = flash ?: since
            rv.setViewVisibility(
                R.id.n_since_row,
                if (sinceLine != null) android.view.View.VISIBLE else android.view.View.GONE
            )
            rv.setTextViewText(R.id.n_since, sinceLine ?: "")
        } else {
            rv.setChronometer(R.id.n_chrono, base, null, true)
            rv.setTextViewText(R.id.n_title, tierText())
            // One clean stats line — speed · miles — with the live chronometer trailing it in
            // the layout; the flash ("Marked #3") briefly takes the whole line.
            val stats = listOfNotNull(speed?.let { "$it mph" }, "$miles mi").joinToString(" · ")
            rv.setTextViewText(R.id.n_stats, flash ?: stats)
        }
        return rv
    }

    /**
     * Stamp a marker at the phone's current place and time, from the notification, Android
     * Auto, or a routine. Native mints the uuid so the SPA's pull is idempotent by `id`
     * (MARKERS.md §6) — a crash between append and drain re-delivers, never duplicates.
     *
     * The service does NOT need to know which drive is in progress: a marker resolves to the
     * drive spanning its `ts` at read time. That is the whole reason this button is cheap.
     */
    private fun markNow() {
        reconciler.requireOwnThread("markNow")
        if (!driving()) return  // a mark on a parked phone would belong to no drive
        val lat = LiveState.lat
        val lon = LiveState.lon
        if (lat == null || lon == null) {
            // Marking before the drive's first fix: there is nothing honest to stamp, and a
            // marker at 0,0 would be worse than none.
            flashNotification("No GPS yet — not marked")
            return
        }
        val ts = System.currentTimeMillis() / 1000
        try {
            WebMarkerBuffer.append(this, UUID.randomUUID().toString(), ts, lat, lon)
        } catch (e: Exception) {
            EventLog.warn("Mark failed: ${e.message ?: e.javaClass.simpleName}")
            flashNotification("Couldn't mark")
            return
        }
        LiveState.markerCount += 1
        LiveState.lastMarkerTs = ts
        EventLog.info("Marker #${LiveState.markerCount} stamped")
        flashNotification("Marked #${LiveState.markerCount}")
    }

    /** Show [text] in place of the stats line, then fall back. With the phone locked this is
     *  the only feedback the driver gets, so it has to land. */
    private fun flashNotification(text: String) {
        flashUntil = Clock.now() + FLASH_MS   // hold it against the coalesced redraw
        updateNotification(flash = text)
        scope.launch {
            delay(FLASH_MS)
            // Back onto the reconciler thread to clear the hold and restore the card: `flashUntil`
            // and `notifSig` are read by every fix's coalesced redraw, and a timer thread stomping
            // them mid-fix is how the flash used to be erased within a second of appearing.
            reconciler.submit(TierReconciler.Trigger.FLASH) {
                flashUntil = 0L
                updateNotification()
            }
        }
    }

    companion object {
        /** Whether the logging service is alive in *this* process. Resets to false on
         *  process death, so the watchdog reading false after an OS kill knows to relaunch. */
        @Volatile var isRunning = false
            private set

        /** The live dashboard is open in the WebView: boost the GPS + OBD sample rate so the
         *  gauges read ~1s-live. A short, screen-on, user-attended window, so the extra draw is
         *  self-limiting; cleared the moment the dashboard collapses. */
        @Volatile var dashboardBoost = false
            private set

        const val ACTION_DASHBOARD = "org.jupiterns.drivetime.action.DASHBOARD"
        /** Boosted GPS fix interval while the dashboard is open. (The OBD half of the boost is
         *  [ObdSession.pollDelayMs], with the rest of the dongle's cadence.) */
        private const val DASH_BOOST_MS = 1_000L

        /** Toggle the dashboard boost and, if the service is live, re-apply the fix rate now. */
        fun setDashboardBoost(context: Context, active: Boolean) {
            if (dashboardBoost == active) return
            dashboardBoost = active
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, LocationService::class.java).setAction(ACTION_DASHBOARD)
                )
            }
        }

        private const val CHANNEL = "drive_logging"
        /** IMPORTANCE_MIN twin of [CHANNEL]: same notification id, no status-bar icon, used
         *  while idle when `notif_driving_only` is on. */
        private const val IDLE_CHANNEL = "drive_idle"
        private const val NOTIF_ID = 1
        /** How long "Marked #3" replaces the stats line before it falls back. */
        private const val FLASH_MS = 3_000L

        // Within-drive dense/idle thresholds.
        private const val MOVING_MPS = 1.4f    // ~3 mph: at/above this counts as moving
        private const val MOVE_RESET_M = 40f   // displacement that counts as movement
        private const val STOP_CONFIRM = 3     // consecutive slow fixes before idle back-off

        // Motion-onset accelerometer sampling window after a significant-motion wake.
        private const val ACCEL_SAMPLE_MS = 3_000L

        // The OBD probe/retry/wedge cadences now live with the decisions that read them, in
        // ObdSession — a constant whose only reader is a policy belongs next to the policy.

        // Upload batching: flush early once this many fixes have queued since the last
        // flush, so dense driving doesn't hold more than ~a minute between periodic ticks.
        private const val BATCH_FIXES = 25

        // Standalone (no server paired): the upload loop's idle re-check cadence. Nothing to
        // flush, so wake rarely — a server pairing is noticed within this window, and charge /
        // connectivity / batch triggers still fire immediately once one exists.
        private const val STANDALONE_IDLE_SEC = 300

        // Throttle for persisting "last fix" to Settings (cheap but not free; we only
        // need this fresh to the order of a minute for the OEM kill detector).
        private const val PERSIST_FIX_MS = 30_000L

        // The real-drive gate and the gas-stop pair rules moved to DriveEndProcessor, with the
        // logic that reads them.
    }
}
