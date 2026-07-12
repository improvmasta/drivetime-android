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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
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
    private val main = Handler(Looper.getMainLooper())
    // Fix handling does disk I/O (queue append, cap check, WebFixBuffer rewrite) on every
    // callback — that must not run on the UI thread of the process that also hosts the
    // WebView (it was a periodic jank/ANR source while driving with the app open).
    private val locThread by lazy { HandlerThread("dt-location").also { it.start() } }
    private lateinit var settings: Settings
    private lateinit var uploader: Uploader
    private lateinit var detector: DriveDetector
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var obd: Elm327Client? = null
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

    private var currentTier = DriveDetector.Tier.LIGHT
    private var requestedIntervalMs = -1L         // active LocationRequest interval (avoid churn)
    private var requestedPriority = -1            // active LocationRequest priority (ditto)
    private var pendingSinceFlush = 0             // fixes enqueued since the last flush trigger
    private var lastPersistedFixAt = 0L           // throttle Settings writes for the kill detector
    private var lastPersistedMeters = 0.0         // last driveMeters mirrored to durable Settings

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
     *  limits and the service is always alive to hear them). */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val car = settings.carBtMac
            if (car.isBlank() || dev.address != car) return
            detector.carConnected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
            reevaluate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        EventLog.init(this)
        settings = Settings(this)
        uploader = Uploader(this, settings)
        detector = DriveDetector(settings)
        // Resuming mid-drive (app update / OEM kill): the persisted drive start survived but the
        // detector's in-memory driving signals didn't. Hold DRIVING through the cold start so the
        // drive keeps its identity — original start time, running miles, marker count — instead
        // of the first (fixless) tier resolution ending it and a brand-new drive taking its place.
        if (settings.loggingEnabled && settings.driveStartedAt != 0L &&
            System.currentTimeMillis() - settings.driveStartedAt in 0L..MAX_DRIVE_MS) {
            detector.resumeDriving = true
            EventLog.info("Resuming drive in progress (restart mid-drive)")
        }
        // Android 14 enforces the FGS type's prerequisites at startForeground time: with
        // fine location revoked this throws SecurityException. Callers can't all pre-check
        // (boot/reboot races), so degrade to a clean stop instead of a crash-loop.
        try {
            startForeground(NOTIF_ID, buildNotification())
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
                val sec = if (currentTier == DriveDetector.Tier.DRIVING)
                    settings.drivingUploadIntervalSec.coerceAtLeast(1)
                else settings.uploadIntervalSec.coerceAtLeast(5)
                delay(sec * 1000L)
                flushNow()
            }
        }
    }

    /** Trigger a (single-flight, draining) flush and reset the size counter. */
    private fun flushNow() {
        pendingSinceFlush = 0
        // runCatching: this scope has no CoroutineExceptionHandler, so an uncaught throw
        // here kills the process — and a sticky service makes that a crash LOOP that also
        // takes the WebView down on every relaunch. An upload must never be able to do that.
        scope.launch {
            runCatching { uploader.flush() }
                .onFailure { EventLog.warn("Upload flush failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A side-effect action, handled before the mode logic below: marking changes no
        // tracking mode, and must never be mistaken for a stop.
        if (intent?.action == Control.ACTION_MARK) {
            markNow()
            return START_STICKY
        }
        // Dashboard opened/closed: re-apply the (now boosted or restored) dense fix rate at
        // once instead of waiting for the next adaptSampling tick. OBD picks up its own boost
        // on its next loop iteration. Never mistaken for a stop.
        if (intent?.action == ACTION_DASHBOARD) {
            if (currentTier == DriveDetector.Tier.DRIVING && !idleMode) {
                requestUpdates(drivingIntervalMs(), Priority.PRIORITY_HIGH_ACCURACY)
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
        // Apply the tier the detector currently resolves (forces a fresh request).
        requestedIntervalMs = -1L
        requestedPriority = -1
        applyTier(detector.tier())
        return START_STICKY
    }

    /** (Re)resolve the tier from the detector and switch sampling if it changed. */
    private fun reevaluate() {
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
     * A surviving mark is reused instead — unless it's implausibly old ([MAX_DRIVE_MS]), which
     * means we crashed out of a long-finished drive without ever clearing it.
     */
    private fun markDriveStart(tier: DriveDetector.Tier): Long {
        if (tier != DriveDetector.Tier.DRIVING) {
            if (settings.driveStartedAt != 0L) {
                settings.driveStartedAt = 0L
                // A drive just ended: on the "after each drive" backup schedule, queue an
                // archive shortly (debounced inside — a quick errand resume re-arms it).
                BackupWorker.afterDrive(this, settings)
            }
            resetDriveTotals()
            return 0L
        }
        val now = System.currentTimeMillis()
        val existing = settings.driveStartedAt
        val resume = existing != 0L && now - existing in 0L..MAX_DRIVE_MS
        if (resume) {
            resumeDriveTotals(existing)
        } else {
            settings.driveStartedAt = now
            resetDriveTotals()   // a NEW drive: its miles and markers start at zero
        }
        return if (resume) existing else now
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

    /** (Re)subscribe to fixes at the given interval/priority. Skips a no-op re-request
     *  only when BOTH are unchanged (a LIGHT→DRIVING flip with equal intervals must
     *  still upgrade the priority, or the whole drive gets balanced-power fixes). */
    private fun requestUpdates(intervalMs: Long, priority: Int) {
        if (intervalMs == requestedIntervalMs && priority == requestedPriority) return
        requestedIntervalMs = intervalMs
        requestedPriority = priority
        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minOf(intervalMs, 1000L))
            .build()
        try {
            // Deliver fixes on the service's own thread: handleFix writes the queue +
            // WebFixBuffer to disk, which must stay off the UI thread.
            fused.requestLocationUpdates(req, callback, locThread.looper)
        } catch (se: SecurityException) {
            stopSelf()
        }
    }

    private fun handleFix(loc: Location) {
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
        val now = System.currentTimeMillis()
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
        }
        // Batched upload: buffer to the durable queue; flush on the periodic tick,
        // when a full batch has accumulated, or when connectivity returns.
        if (++pendingSinceFlush >= BATCH_FIXES) flushNow()

        val speed = if (loc.hasSpeed()) loc.speed else 0f
        movingHint = speed >= MOVING_MPS
        detector.onSpeed(speed, System.currentTimeMillis())
        reevaluate()
        if (currentTier == DriveDetector.Tier.DRIVING) adaptSampling(loc)
        // Redraw the drive card on each recorded fix (~1 Hz while driving), not on a timer:
        // Android rate-limits notify() well above that, and the chronometer covers the gaps.
        // Idle fixes redraw too — cheap, and it's what makes a just-flipped `notif_driving_only`
        // take effect without waiting for the next tier change.
        if (currentTier != DriveDetector.Tier.OFF) updateNotification()
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
            // Consecutive "opened a socket but the adapter never answered" failures. A cheap
            // dongle that sat powered on an already-running car (remote start) gets into this
            // wedged state; after a few fast retries we force a cold reset (see below).
            var wedgedStreak = 0
            while (isActive) {
                val mac = settings.obdMac
                if (mac.isBlank() || !shouldProbeObd()) { delay(OBD_IDLE_MS); continue }
                var connected = false
                try {
                    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    if (adapter == null) { delay(OBD_IDLE_MS); continue }
                    val client = Elm327Client()
                    client.connect(adapter.getRemoteDevice(mac))
                    connected = true
                    wedgedStreak = 0
                    obd = client
                    detector.obdConnected = true
                    LiveState.obdConnected = true
                    EventLog.info("OBD connected")
                    runCatching { client.diagnostic().forEach { EventLog.info("OBD $it") } }
                    main.post { reevaluate(); StateBroadcaster.emit(this@LocationService, "obd") }
                    var ticks = 0
                    var loggedSample = false
                    while (isActive && client.isConnected()) {
                        val s = client.readSample()
                        // Log the first decoded sample so we can confirm PIDs are parsing
                        // (not just connecting) without watching live.
                        if (!loggedSample) {
                            loggedSample = true
                            EventLog.info("OBD sample rpm=${s.rpm} kph=${s.obdKph} coolant=${s.coolantC}" +
                                " load=${s.engineLoad?.let { "%.0f".format(it) }} thr=${s.throttle?.let { "%.0f".format(it) }}" +
                                " maf=${s.maf?.let { "%.1f".format(it) }} fuel=${s.fuelLph?.let { "%.1f".format(it) }}L/h" +
                                " tank=${s.fuelLevel?.let { "%.0f".format(it) }}% v=${s.voltage} ctrlV=${s.ctrlVoltage}")
                        }
                        latestObd = if (ticks % 120 == 0) {
                            val fresh = s.copy(dtcs = client.readDtcs())
                            maybeAlertDtcs(fresh.dtcs)
                            fresh
                        } else s
                        LiveState.rpm = s.rpm; LiveState.throttle = s.throttle
                        LiveState.coolantC = s.coolantC; LiveState.voltage = s.voltage
                        ticks++
                        // Poll faster while the live dashboard is open so the RPM/throttle
                        // gauges keep up; ~0.8s is within a cheap ELM327 clone's reach.
                        delay(if (dashboardBoost) OBD_BOOST_MS else OBD_POLL_MS)
                    }
                } catch (e: Exception) {
                    // dongle off/unpaired/out of range — log it so a real fault is visible
                    // instead of silently logging GPS without engine data.
                    EventLog.warn("OBD error: ${e.message ?: e.javaClass.simpleName}")
                    // A failure *before* we ever connected (mute/wedged adapter) counts toward
                    // the streak; a mid-drive drop after a good connect does not.
                    if (!connected) wedgedStreak++
                } finally {
                    val wasConnected = LiveState.obdConnected
                    if (wasConnected) EventLog.info("OBD disconnected")
                    obd?.close(); obd = null
                    latestObd = null
                    detector.obdConnected = false
                    LiveState.obdConnected = false
                    main.post {
                        reevaluate()
                        if (wasConnected) StateBroadcaster.emit(this@LocationService, "obd")
                    }
                }
                // Recovery cadence. While driving we retry fast; but if the adapter keeps
                // accepting a socket without ever answering, escalate: forget the cached
                // socket strategy and give the dongle a quiet, fully-disconnected window so
                // its firmware watchdog can self-reset — the closest we get to a power-cycle
                // without touching the plug. Not driving → slow idle probe as before.
                when {
                    currentTier != DriveDetector.Tier.DRIVING -> delay(OBD_IDLE_MS)
                    wedgedStreak >= OBD_WEDGE_LIMIT -> {
                        Elm327Client.clearStrategy(mac)
                        EventLog.warn("OBD unresponsive ×$wedgedStreak — cold reset, pausing ${OBD_COLD_PAUSE_MS / 1000}s")
                        wedgedStreak = 0
                        delay(OBD_COLD_PAUSE_MS)
                    }
                    else -> delay(OBD_RETRY_DRIVE_MS)
                }
            }
        }
    }

    private fun shouldProbeObd(): Boolean =
        detector.carConnected || movingHint || currentTier == DriveDetector.Tier.DRIVING

    // ---- Motion-onset (device-agnostic fast start) ----

    /** Arm the one-shot significant-motion trigger (idempotent, LIGHT-tier only). It's
     *  hardware-backed and ~free while parked; on a device without it the app simply
     *  leans on the 60 s LIGHT heartbeat + speed backstop as before. */
    private fun armMotionOnset() {
        if (!settings.motionOnset || sigMotionListener != null) return
        val sm = sensors ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        val l = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                sigMotionListener = null   // one-shot: the OS has disarmed it
                onMotionTrigger()
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
     *  LIGHT and re-arm. Runs on the main thread (sensor callback); the work is a coroutine. */
    private fun onMotionTrigger() {
        if (onsetProbing || currentTier != DriveDetector.Tier.LIGHT) {
            if (currentTier == DriveDetector.Tier.LIGHT) armMotionOnset()  // don't lose the trigger
            return
        }
        onsetProbing = true
        onsetProbationUntil = System.currentTimeMillis() + settings.onsetProbeWindowSec * 1000L
        LiveState.onsetState = "probing"
        EventLog.info("Motion onset → probing")
        requestUpdates(settings.onsetProbeIntervalSec * 1000L, Priority.PRIORITY_HIGH_ACCURACY)
        val tokenSrc = CancellationTokenSource()
        onsetTokenSource = tokenSrc
        scope.launch {
            val doppler = currentDoppler(tokenSrc)
            val accel = sampleAccelRms()
            val confirmed = detector.confirmOnset(doppler, accel, System.currentTimeMillis())
            if (confirmed) {
                LiveState.onsetState = "confirmed"
                val mph = doppler?.let { Math.round(it * 2.2369362f) } ?: -1
                EventLog.info("Driving detected (motion, $mph mph)")
                main.post { reevaluate(); StateBroadcaster.emit(this@LocationService, "motion") }
            } else {
                val remain = onsetProbationUntil - System.currentTimeMillis()
                if (remain > 0) delay(remain)
            }
            onsetProbing = false
            onsetTokenSource = null
            main.post {
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
        runCatching { locThread.quitSafely() }
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
    }

    private fun tierText(): String = when (currentTier) {
        DriveDetector.Tier.DRIVING -> "Driving · ${detector.reason()}"
        DriveDetector.Tier.LIGHT -> "Idle"
        DriveDetector.Tier.OFF -> "Off"
    }

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
     * Fire a check-engine notification the instant the dongle reports a trouble code we
     * haven't already flagged — fully on-device, no server round-trip. The remembered set is
     * replaced with the current codes each read, so a fault that clears drops out and its
     * later return alerts again. Reading DTCs but leaving [Settings.alertsEnabled] off still
     * updates the set, so enabling it later doesn't dump every standing code at once.
     */
    private fun maybeAlertDtcs(codes: List<String>) {
        val cur = codes.toSet()
        val prev = settings.knownDtcs
        if (cur == prev) return
        if (settings.alertsEnabled) {
            for (code in cur) if (code !in prev) postDtcAlert(code)
        }
        settings.knownDtcs = cur
    }

    private fun postDtcAlert(code: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(ALERT_CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Check-engine alerts", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WebViewActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_app)
            .setColor(getColor(R.color.status_red))
            .setContentTitle("Check engine: $code")
            .setContentText("Your vehicle reported a diagnostic trouble code.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        mgr.notify(ALERT_NOTIF_BASE + (code.hashCode() and 0xffff), n)
        EventLog.info("Check-engine alert: $code")
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
        // The brand D is the status-bar icon in every state — the app icon is what the
        // user looks for in the shade; the tier lives in the notification text/tiles.
        val b = Notification.Builder(this, activeChannel())
            .setSmallIcon(R.drawable.ic_notif_app)
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
        // the Mark/Stop actions. The numbers have to survive the lock screen — the whole point.
        b.setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(driveRemoteViews(R.layout.notif_drive, expanded = false, flash = flash))
            .setCustomBigContentView(driveRemoteViews(R.layout.notif_drive_big, expanded = true, flash = flash))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_notif_driving, "Mark", servicePi(Control.ACTION_MARK, 1)))
            .addAction(action(R.drawable.ic_notif_tracking, "Stop", servicePi(Control.ACTION_STOP, 2)))
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
        val miles = String.format("%.1f", LiveState.driveMeters * 0.000621371)
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
            // The speed pill has a chip background now, so an empty string would still draw
            // a stray pill — hide the view outright until the first fix carries a speed.
            rv.setViewVisibility(
                R.id.n_speed,
                if (speed != null) android.view.View.VISIBLE else android.view.View.GONE
            )
            rv.setTextViewText(R.id.n_speed, if (speed != null) "$speed mph" else "")
            val stats = flash ?: listOfNotNull("$miles mi", since).joinToString(" · ")
            rv.setTextViewText(R.id.n_stats, "· $stats")
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
        updateNotification(flash = text)
        scope.launch {
            delay(FLASH_MS)
            updateNotification()
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
        /** Boosted GPS fix interval while the dashboard is open. */
        private const val DASH_BOOST_MS = 1_000L
        /** Boosted OBD poll while the dashboard is open (vs OBD_POLL_MS). */
        private const val OBD_BOOST_MS = 800L
        /** Ordinary OBD poll cadence. */
        private const val OBD_POLL_MS = 1_500L

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
        /** IMPORTANCE_HIGH channel for on-device check-engine alerts (distinct from the
         *  ongoing tracking notification, so the user can tune/silence each separately). */
        private const val ALERT_CHANNEL = "check_engine"
        /** Base id for DTC notifications; one stable id per code (base + code hash) so the
         *  same standing fault re-posts in place instead of stacking. */
        private const val ALERT_NOTIF_BASE = 2000
        private const val NOTIF_ID = 1
        /** How long "Marked #3" replaces the stats line before it falls back. */
        private const val FLASH_MS = 3_000L

        // Within-drive dense/idle thresholds.
        private const val MOVING_MPS = 1.4f    // ~3 mph: at/above this counts as moving
        private const val MOVE_RESET_M = 40f   // displacement that counts as movement
        private const val STOP_CONFIRM = 3     // consecutive slow fixes before idle back-off

        // Motion-onset accelerometer sampling window after a significant-motion wake.
        private const val ACCEL_SAMPLE_MS = 3_000L

        // OBD probe cadence.
        private const val OBD_IDLE_MS = 120_000L      // recheck/probe interval when not driving
        private const val OBD_RETRY_DRIVE_MS = 5_000L // fast reconnect while driving
        // After this many back-to-back "socket opened but no answer" connects while driving,
        // force a cold reset: drop the cached socket strategy and stay fully disconnected for
        // OBD_COLD_PAUSE_MS so a wedged clone's watchdog can reset itself.
        private const val OBD_WEDGE_LIMIT = 3
        private const val OBD_COLD_PAUSE_MS = 20_000L

        // Upload batching: flush early once this many fixes have queued since the last
        // flush, so dense driving doesn't hold more than ~a minute between periodic ticks.
        private const val BATCH_FIXES = 25

        // Throttle for persisting "last fix" to Settings (cheap but not free; we only
        // need this fresh to the order of a minute for the OEM kill detector).
        private const val PERSIST_FIX_MS = 30_000L

        // Longest a persisted drive-start mark stays resumable. Past this it can only be a
        // leak (crashed out of a drive without clearing), never a drive still in progress.
        private const val MAX_DRIVE_MS = 12 * 60 * 60 * 1000L
    }
}
