package org.jupiterns.drivetime.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import okhttp3.Request
import org.json.JSONObject
import org.jupiterns.drivetime.Control
import org.jupiterns.drivetime.Http
import org.jupiterns.drivetime.LiveState
import org.jupiterns.drivetime.R
import org.jupiterns.drivetime.Settings

/**
 * The in-car surface, one screen with three faces:
 *
 *  - DRIVING: a GridTemplate dashboard — big glanceable tiles (speed / miles / elapsed /
 *    marks, plus rpm / volts when the OBD dongle is up) with Mark and Stop on the action
 *    strip. Grid tiles beat Pane rows at arm's length, which is the whole point of a
 *    head unit.
 *  - Logging but idle: a quiet status pane with a Stop action.
 *  - Off: today's commute (leave-by / expected / route) when a server is paired, and a
 *    primary Start action.
 *
 * Refresh is a self-tick (1 s while driving for the elapsed clock, 2.5 s otherwise);
 * same-template refreshes are exempt from the host's step quota, so this is legal.
 * Mark confirms with a CarToast — the driver never looks at the phone (MARKERS.md §6).
 */
class DriveScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, if (LiveState.tier == "DRIVING") 1000L else 2500L)
        }
    }
    @Volatile private var commute: JSONObject? = null
    private var fetchedAt = 0L
    private var marking = false

    // SPA accent, both shade variants: hosts pick the first on light backgrounds and the
    // second on dark (most head units run dark).
    private val accent = CarColor.createCustom(0xFF1668A0.toInt(), 0xFF56C8FF.toInt())

    init { lifecycle.addObserver(this) }

    override fun onResume(owner: LifecycleOwner) { handler.post(tick) }
    override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(tick) }

    override fun onGetTemplate(): Template =
        if (LiveState.logging && LiveState.tier == "DRIVING") drivingTemplate()
        else idleTemplate()

    /** The drive dashboard: stat tiles + Mark/Stop, mirroring the notification's card. */
    private fun drivingTemplate(): Template {
        val items = ItemList.Builder()
            .addItem(tile(LiveState.speedMph?.toString() ?: "—", "mph", R.drawable.ic_stat_speed))
            .addItem(tile(String.format("%.1f", LiveState.driveMeters * 0.000621371), "miles", R.drawable.ic_stat_distance))
            .addItem(tile(fmtElapsed(), "elapsed", R.drawable.ic_stat_time))
            .addItem(tile(LiveState.markerCount.toString(), "marks", R.drawable.ic_stat_marker))
        if (LiveState.obdConnected) {
            LiveState.rpm?.let { items.addItem(tile(it.toString(), "rpm", R.drawable.ic_notif_driving)) }
            LiveState.voltage?.let { items.addItem(tile(String.format("%.1f V", it), "battery", R.drawable.ic_stat_bolt)) }
        }
        val strip = ActionStrip.Builder()
            // Mark first: the steering-wheel-height tap target that stamps a job site
            // (MARKERS.md §6) — same ACTION_MARK the notification button dispatches.
            .addAction(
                Action.Builder()
                    .setTitle("Mark")
                    .setIcon(icon(R.drawable.ic_stat_marker))
                    .setOnClickListener { onMark() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Stop")
                    .setOnClickListener { Control.apply(carContext, Control.ACTION_STOP); invalidate() }
                    .build()
            )
            .build()
        return GridTemplate.Builder()
            .setTitle("Driving · ${LiveState.driveReason ?: "auto"}")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .setActionStrip(strip)
            .build()
    }

    /** Idle faces: tracking-but-parked status, or the off-state commute + Start. */
    private fun idleTemplate(): Template {
        val logging = LiveState.logging
        if (!logging) maybeFetchCommute()
        val b = Pane.Builder()
        if (logging) {
            b.addRow(row("Tracking", "Idle — watching for a drive", R.drawable.ic_notif_tracking))
            LiveState.voltage?.let { b.addRow(row("Battery", String.format("%.1f V", it), R.drawable.ic_stat_bolt)) }
            LiveState.coolantC?.let { b.addRow(row("Coolant", "$it°C", null)) }
            b.addAction(controlAction("Stop", Control.ACTION_STOP, primary = false))
        } else {
            val c = commute
            if (c != null && c.has("leave_by")) {
                b.addRow(row("Leave by", c.optString("leave_by"), R.drawable.ic_stat_time))
                b.addRow(row("Drive", "~${c.optDouble("expected_min").toInt()} min", R.drawable.ic_stat_distance))
                b.addRow(row("Route", c.optString("route"), R.drawable.ic_stat_marker))
            } else {
                b.addRow(row("Ready", "Start to log this drive", R.drawable.ic_notif_driving))
            }
            b.addAction(controlAction("Start", Control.ACTION_START, primary = true))
        }
        return PaneTemplate.Builder(b.build())
            .setHeaderAction(Action.APP_ICON)
            .setTitle(if (logging) "drivetime · tracking" else "drivetime")
            .build()
    }

    /**
     * Mark, then confirm on the head unit. Control hands ACTION_MARK to the service
     * asynchronously, so the outcome is read back a beat later: the counter moved → toast
     * "Marked #N"; it didn't → the service refused (no GPS fix yet) and the driver needs
     * to know that too. [marking] swallows double-taps during that window.
     */
    private fun onMark() {
        if (marking) return
        marking = true
        val before = LiveState.markerCount
        Control.apply(carContext, Control.ACTION_MARK)
        handler.postDelayed({
            marking = false
            val after = LiveState.markerCount
            val msg = if (after > before) "Marked #$after" else "No GPS fix yet — not marked"
            CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
            invalidate()
        }, 800L)
    }

    private fun controlAction(title: String, act: String, primary: Boolean): Action {
        val a = Action.Builder()
            .setTitle(title)
            .setOnClickListener { Control.apply(carContext, act); invalidate() }
        // FLAG_PRIMARY (host paints it in the accent color) needs a level-4 host; older
        // hosts reject flagged actions rather than ignoring the flag.
        if (primary && carContext.carAppApiLevel >= 4) a.setFlags(Action.FLAG_PRIMARY)
        return a.build()
    }

    private fun tile(value: String, label: String, iconRes: Int): GridItem =
        GridItem.Builder()
            .setTitle(value)
            .setText(label)
            .setImage(icon(iconRes), GridItem.IMAGE_TYPE_ICON)
            .build()

    private fun row(label: String, value: String?, iconRes: Int?): Row {
        val r = Row.Builder().setTitle(label).addText(value ?: "—")
        if (iconRes != null) r.setImage(icon(iconRes))
        return r.build()
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(accent).build()

    /** m:ss under an hour, h:mm:ss beyond — the 1 s tick keeps it live. */
    private fun fmtElapsed(): String {
        val start = LiveState.driveStartedAt
        if (start <= 0L) return "—"
        val s = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        return if (h > 0L) String.format("%d:%02d:%02d", h, m, s % 60L)
        else String.format("%d:%02d", m, s % 60L)
    }

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
}
