package org.jupiterns.drivetime

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
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import androidx.core.content.ContextCompat
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

    private var currentTier = DriveDetector.Tier.LIGHT
    private var requestedIntervalMs = -1L         // active LocationRequest interval (avoid churn)

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
        settings = Settings(this)
        uploader = Uploader(this, settings)
        detector = DriveDetector(settings)
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        ContextCompat.registerReceiver(this, btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        startObdLoop()
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
        currentTier = tier
        when (tier) {
            DriveDetector.Tier.DRIVING -> {
                idleMode = false; slowCount = 0; lastMoveLoc = null
                requestUpdates(settings.intervalSec * 1000L, Priority.PRIORITY_HIGH_ACCURACY)
            }
            DriveDetector.Tier.LIGHT -> {
                requestUpdates(settings.lightIntervalSec * 1000L, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
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
        LiveState.updatedAt = System.currentTimeMillis()
        scope.launch { uploader.flush() }

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
            while (isActive) {
                val mac = settings.obdMac
                if (mac.isBlank() || !shouldProbeObd()) { delay(OBD_IDLE_MS); continue }
                try {
                    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    if (adapter == null) { delay(OBD_IDLE_MS); continue }
                    val client = Elm327Client()
                    client.connect(adapter.getRemoteDevice(mac))
                    obd = client
                    detector.obdConnected = true
                    main.post { reevaluate() }
                    var ticks = 0
                    while (isActive && client.isConnected()) {
                        val s = client.readSample()
                        latestObd = if (ticks % 120 == 0) s.copy(dtcs = client.readDtcs()) else s
                        LiveState.rpm = s.rpm; LiveState.coolantC = s.coolantC; LiveState.voltage = s.voltage
                        ticks++
                        delay(1500)
                    }
                } catch (e: Exception) {
                    // dongle off/unpaired/out of range — keep logging GPS without engine data
                } finally {
                    obd?.close(); obd = null
                    latestObd = null
                    detector.obdConnected = false
                    main.post { reevaluate() }
                }
                // Reconnect fast while driving, slowly otherwise.
                delay(if (currentTier == DriveDetector.Tier.DRIVING) OBD_RETRY_DRIVE_MS else OBD_IDLE_MS)
            }
        }
    }

    private fun shouldProbeObd(): Boolean =
        detector.carConnected || movingHint || currentTier == DriveDetector.Tier.DRIVING

    override fun onDestroy() {
        isRunning = false
        LiveState.logging = false
        LiveState.clear()
        runCatching { unregisterReceiver(btReceiver) }
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

        // OBD probe cadence.
        private const val OBD_IDLE_MS = 120_000L      // recheck/probe interval when not driving
        private const val OBD_RETRY_DRIVE_MS = 5_000L // fast reconnect while driving
    }
}
