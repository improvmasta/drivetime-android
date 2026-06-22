package org.jupiterns.drivetime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import kotlinx.coroutines.launch

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
        requestUpdates()
        return START_STICKY
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
            courseDeg = if (loc.hasBearing()) loc.bearing else null
        )
        scope.launch { uploader.flush() }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
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
