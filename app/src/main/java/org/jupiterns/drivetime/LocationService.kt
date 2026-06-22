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
 * Drive start/stop detection (OBD-connected or activity-recognition) is Phase A+;
 * for now it logs whenever started and stops on demand.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: Settings
    private lateinit var uploader: Uploader
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var obd: Elm327Client? = null
    @Volatile private var latestObd: Elm327Client.ObdSample? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleFix(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        uploader = Uploader(this, settings)
        startForeground(NOTIF_ID, buildNotification("Logging drive…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Control.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        settings.loggingEnabled = true
        requestUpdates()
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
                    ticks++
                    delay(1500)
                }
            } catch (e: Exception) {
                // dongle off/unpaired — keep logging GPS without engine data
            }
        }
    }

    private fun requestUpdates() {
        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            settings.intervalSec * 1000L
        ).setMinUpdateIntervalMillis(1000L).build()
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
        scope.launch { uploader.flush() }
    }

    override fun onDestroy() {
        settings.loggingEnabled = false
        fused.removeLocationUpdates(callback)
        obd?.close(); obd = null
        scope.launch { uploader.flush() }
        scope.cancel()
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
        private const val CHANNEL = "drive_logging"
        private const val NOTIF_ID = 1
    }
}
