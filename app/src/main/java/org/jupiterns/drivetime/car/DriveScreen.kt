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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jupiterns.drivetime.Control
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

    private fun livePane(): Pane = Pane.Builder()
        .addRow(row("Speed", LiveState.speedMph?.let { "$it mph" }))
        .addRow(row("RPM", LiveState.rpm?.toString()))
        .addRow(row("Coolant", LiveState.coolantC?.let { "$it°C" }))
        .addRow(row("Battery", LiveState.voltage?.let { String.format("%.1f V", it) }))
        .addAction(toggle("Stop", Control.ACTION_STOP))
        .build()

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
        Thread {
            try {
                val url = Settings(carContext).serverUrl + "/api/commute/today"
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
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
