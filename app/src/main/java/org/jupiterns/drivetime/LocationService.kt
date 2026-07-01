package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import androidx.core.content.ContextCompat
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
    private var pendingSinceFlush = 0             // fixes enqueued since the last flush trigger
    private var lastPersistedFixAt = 0L           // throttle Settings writes for the kill detector

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
        startForeground(NOTIF_ID, buildNotification("Starting…"))
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
        scope.launch { uploader.flush() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        applyTier(detector.tier())
        return START_STICKY
    }

    /** (Re)resolve the tier from the detector and switch sampling if it changed. */
    private fun reevaluate() {
        val t = detector.tier()
        if (t == DriveDetector.Tier.OFF) {
            settings.trackingMode = Settings.MODE_OFF
            settings.loggingEnabled = false
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
                requestUpdates(settings.intervalSec * 1000L, Priority.PRIORITY_HIGH_ACCURACY)
            }
            DriveDetector.Tier.LIGHT -> {
                requestUpdates(settings.lightIntervalSec * 1000L, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                armMotionOnset()      // catch the next start within seconds, in any car
            }
            DriveDetector.Tier.OFF -> { /* handled by reevaluate/stop */ }
        }
        LiveState.tier = tier.name
        LiveState.driveReason = detector.reason()
        updateNotification()
    }

    /** (Re)subscribe to fixes at the given interval/priority. Skips a no-op re-request
     *  when the interval is unchanged to avoid thrashing the GPS request. */
    private fun requestUpdates(intervalMs: Long, priority: Int) {
        if (intervalMs == requestedIntervalMs) return
        requestedIntervalMs = intervalMs
        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minOf(intervalMs, 1000L))
            .build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (se: SecurityException) {
            stopSelf()
        }
    }

    private fun handleFix(loc: Location) {
        uploader.enqueue(
            lat = loc.latitude,
            lon = loc.longitude,
            epochSec = loc.time / 1000,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            courseDeg = if (loc.hasBearing()) loc.bearing else null,
            obd = if (currentTier == DriveDetector.Tier.DRIVING) latestObd else null
        )
        LiveState.logging = true
        LiveState.speedMph = if (loc.hasSpeed()) Math.round(loc.speed * 2.2369362f) else null
        val now = System.currentTimeMillis()
        LiveState.updatedAt = now
        // Persist lastFixAt at most every PERSIST_FIX_MS so the watchdog's kill
        // detector has a recent timestamp without one SharedPreferences write per fix.
        if (now - lastPersistedFixAt >= PERSIST_FIX_MS) {
            settings.lastFixAt = now
            lastPersistedFixAt = now
        }
        // Batched upload: buffer to the durable queue; flush on the periodic tick,
        // when a full batch has accumulated, or when connectivity returns.
        if (++pendingSinceFlush >= BATCH_FIXES) flushNow()

        val speed = if (loc.hasSpeed()) loc.speed else 0f
        movingHint = speed >= MOVING_MPS
        detector.onSpeed(speed, System.currentTimeMillis())
        reevaluate()
        if (currentTier == DriveDetector.Tier.DRIVING) adaptSampling(loc)
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
                requestUpdates(settings.intervalSec * 1000L, Priority.PRIORITY_HIGH_ACCURACY)
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
                        latestObd = if (ticks % 120 == 0) s.copy(dtcs = client.readDtcs()) else s
                        LiveState.rpm = s.rpm; LiveState.coolantC = s.coolantC; LiveState.voltage = s.voltage
                        LiveState.pids = s.pids
                        ticks++
                        delay(1500)
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

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(tierText()))
    }

    private fun tierText(): String = when (currentTier) {
        DriveDetector.Tier.DRIVING -> "Driving · ${detector.reason()}"
        DriveDetector.Tier.LIGHT -> "Light tracking"
        DriveDetector.Tier.OFF -> "Off"
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Drive logging", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("drivetime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Whether the logging service is alive in *this* process. Resets to false on
         *  process death, so the watchdog reading false after an OS kill knows to relaunch. */
        @Volatile var isRunning = false
            private set

        private const val CHANNEL = "drive_logging"
        private const val NOTIF_ID = 1

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
    }
}
