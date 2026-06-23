package org.jupiterns.drivetime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jupiterns.drivetime.obd.Elm327Client

/**
 * Foreground service: streams GPS fixes into the Uploader while driving.
 *
 * Sampling is motion-adaptive: dense (intervalSec) while moving, backed off
 * (idleIntervalSec) while stopped, so a red light or sitting in the car doesn't
 * flood the same rate as 70 mph. With autoTrip on, a stationary watchdog ends the
 * trip after stationaryStopMin as a backstop for a missed "exited vehicle".
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: Settings
    private lateinit var uploader: Uploader
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var obd: Elm327Client? = null
    @Volatile private var latestObd: Elm327Client.ObdSample? = null

    // Adaptive-sampling state.
    private var idleMode = false
    private var slowCount = 0
    private var lastMoveLoc: Location? = null
    private var lastMoveAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleFix(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settings = Settings(this)
        uploader = Uploader(this, settings)
        startForeground(NOTIF_ID, buildNotification("Logging drive…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Control.ACTION_STOP) {
            settings.loggingEnabled = false   // intentional stop
            Watchdog.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        settings.loggingEnabled = true
        Watchdog.schedule(this)               // arm the self-healing backstop
        // Fresh trip starts dense; reset adaptive state.
        idleMode = false; slowCount = 0; lastMoveLoc = null; lastMoveAt = 0L
        requestUpdates(settings.intervalSec * 1000L)
        startObd()
        return START_STICKY
    }

    /** Connect the ELM327 dongle (if configured) and poll PIDs into latestObd. */
    private fun startObd() {
        val mac = settings.obdMac
        if (mac.isBlank() || obd != null) return
        scope.launch {
            try {
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: return@launch
                val client = Elm327Client()
                client.connect(adapter.getRemoteDevice(mac))
                obd = client
                var ticks = 0
                while (isActive && client.isConnected()) {
                    val s = client.readSample()
                    // poll DTCs occasionally (~every 3 min), not every tick
                    latestObd = if (ticks % 120 == 0) s.copy(dtcs = client.readDtcs()) else s
                    LiveState.rpm = s.rpm
                    LiveState.coolantC = s.coolantC
                    LiveState.voltage = s.voltage
                    ticks++
                    delay(1500)
                }
            } catch (e: Exception) {
                // dongle off/unpaired — keep logging GPS without engine data
            }
        }
    }

    /** (Re)subscribe to fixes at the given interval. Calling again with a new
     *  interval replaces the active request for the same callback. */
    private fun requestUpdates(intervalMs: Long) {
        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).setMinUpdateIntervalMillis(minOf(intervalMs, 1000L)).build()
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
            obd = latestObd
        )
        LiveState.logging = true
        LiveState.speedMph = if (loc.hasSpeed()) Math.round(loc.speed * 2.2369362f) else null
        LiveState.updatedAt = System.currentTimeMillis()
        scope.launch { uploader.flush() }
        adaptSampling(loc)
    }

    /**
     * Adjust the fix rate to motion and auto-end a parked trip.
     * "Moving" = GPS speed over the threshold, or displacement from the last
     * moving fix over the reset distance (catches movement when speed is absent).
     */
    private fun adaptSampling(loc: Location) {
        val now = System.currentTimeMillis()
        val ref = lastMoveLoc
        val moved = if (ref != null) ref.distanceTo(loc) else Float.MAX_VALUE
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val moving = speed >= MOVING_MPS || moved >= MOVE_RESET_M

        if (moving) {
            lastMoveLoc = loc
            lastMoveAt = now
            slowCount = 0
            if (idleMode) {
                idleMode = false
                requestUpdates(settings.intervalSec * 1000L)
            }
            return
        }

        // Stationary fix.
        if (lastMoveLoc == null) lastMoveLoc = loc
        if (lastMoveAt == 0L) lastMoveAt = now
        slowCount++
        if (!idleMode && slowCount >= STOP_CONFIRM) {
            idleMode = true
            requestUpdates(settings.idleIntervalSec * 1000L)
        }
        val stopMin = settings.stationaryStopMin
        if (settings.autoTrip && stopMin > 0 && now - lastMoveAt >= stopMin * 60_000L) {
            settings.loggingEnabled = false   // intentional end-of-trip (missed-EXIT backstop)
            stopSelf()   // parked long enough — end the trip
        }
    }

    override fun onDestroy() {
        isRunning = false
        LiveState.logging = false
        LiveState.clear()
        fused.removeLocationUpdates(callback)
        obd?.close(); obd = null
        // Final flush on a thread that isn't cancelled with the service scope, so the
        // last batch isn't dropped on the way out. (The durable queue would resend it
        // regardless, but a clean stop should leave a clean queue.)
        Thread { runCatching { uploader.flush() } }.start()
        scope.cancel()
        // Deliberately do NOT clear loggingEnabled here: the intentional-stop paths
        // (user/routine STOP, stationary auto-end) already cleared it. If it's still
        // set, this is an OS kill — leave it so START_STICKY and the watchdog resume.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

        // Adaptive-sampling thresholds.
        private const val MOVING_MPS = 1.4f    // ~3 mph: at/above this counts as moving
        private const val MOVE_RESET_M = 40f   // displacement that counts as movement
        private const val STOP_CONFIRM = 3     // consecutive slow fixes before backing off
    }
}
