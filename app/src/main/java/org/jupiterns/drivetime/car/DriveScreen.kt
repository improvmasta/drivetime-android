package org.jupiterns.drivetime.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import okhttp3.Request
import org.json.JSONObject
import org.jupiterns.drivetime.Control
import org.jupiterns.drivetime.Http
import org.jupiterns.drivetime.LiveState
import org.jupiterns.drivetime.Settings

/**
 * Glanceable in-car dashboard: live speed / RPM / coolant / battery and a
 * Start/Stop toggle. Refreshes itself every couple seconds while visible.
 */
class DriveScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 2000) }
    }
    @Volatile private var commute: JSONObject? = null
    private var fetchedAt = 0L

    init { lifecycle.addObserver(this) }

    override fun onResume(owner: LifecycleOwner) { handler.post(tick) }
    override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(tick) }

    override fun onGetTemplate(): Template {
        val logging = LiveState.logging
        if (!logging) maybeFetchCommute()
        val pane = if (logging) livePane() else idlePane()
        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.APP_ICON)
            .setTitle(if (logging) "drivetime · logging" else "drivetime")
            .build()
    }

    private fun livePane(): Pane {
        val driving = LiveState.tier == "DRIVING"
        val b = Pane.Builder()
            .addRow(row("Speed", LiveState.speedMph?.let { "$it mph" }))
        if (driving) {
            b.addRow(row("Distance", String.format("%.1f mi", LiveState.driveMeters * 0.000621371)))
            b.addRow(row("Marks", LiveState.markerCount.toString()))
        } else {
            b.addRow(row("RPM", LiveState.rpm?.toString()))
            b.addRow(row("Coolant", LiveState.coolantC?.let { "$it°C" }))
        }
        b.addRow(row("Battery", LiveState.voltage?.let { String.format("%.1f V", it) }))
        // A steering-wheel-height tap target is the actual ergonomics of marking a job site
        // (MARKERS.md §6). Same ACTION_MARK the notification button dispatches. A Pane takes
        // at most two actions, and while driving these are the two that earn their place.
        if (driving) b.addAction(toggle("Mark", Control.ACTION_MARK))
        b.addAction(toggle("Stop", Control.ACTION_STOP))
        return b.build()
    }

    private fun idlePane(): Pane {
        val c = commute
        val b = Pane.Builder()
        if (c != null && c.has("leave_by")) {
            b.addRow(row("Leave by", c.optString("leave_by")))
            b.addRow(row("Drive", "~${c.optDouble("expected_min").toInt()} min"))
            b.addRow(row("Route", c.optString("route")))
        } else {
            b.addRow(row("Ready", "Start to log this drive"))
        }
        b.addAction(toggle("Start", Control.ACTION_START))
        return b.build()
    }

    private fun toggle(title: String, action: String) = Action.Builder()
        .setTitle(title)
        .setOnClickListener { Control.apply(carContext, action); invalidate() }
        .build()

    private fun maybeFetchCommute() {
        if (System.currentTimeMillis() - fetchedAt < 120_000) return
        fetchedAt = System.currentTimeMillis()
        // Standalone / unpaired: there is no server to ask (an empty serverUrl used to
        // build a relative URL that threw; a paired server 401'd without the header).
        val settings = Settings(carContext)
        if (!settings.isConfigured) return
        Thread {
            try {
                val req = Request.Builder()
                    .url(settings.serverUrl + "/api/commute/today")
                    .header("Authorization", settings.authHeader)
                    .build()
                Http.client.newCall(req).execute().use { r ->
                    commute = JSONObject(r.body?.string() ?: "{}")
                }
            } catch (_: Exception) {
            }
            handler.post { invalidate() }
        }.start()
    }

    private fun row(label: String, value: String?): Row =
        Row.Builder().setTitle(label).addText(value ?: "—").build()
}
