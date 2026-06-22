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
import org.jupiterns.drivetime.Control
import org.jupiterns.drivetime.LiveState

/**
 * Glanceable in-car dashboard: live speed / RPM / coolant / battery and a
 * Start/Stop toggle. Refreshes itself every couple seconds while visible.
 */
class DriveScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 2000) }
    }

    init { lifecycle.addObserver(this) }

    override fun onResume(owner: LifecycleOwner) { handler.post(tick) }
    override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(tick) }

    override fun onGetTemplate(): Template {
        val logging = LiveState.logging
        val pane = Pane.Builder()
            .addRow(row("Speed", LiveState.speedMph?.let { "$it mph" }))
            .addRow(row("RPM", LiveState.rpm?.toString()))
            .addRow(row("Coolant", LiveState.coolantC?.let { "$it°C" }))
            .addRow(row("Battery", LiveState.voltage?.let { String.format("%.1f V", it) }))
            .addAction(
                Action.Builder()
                    .setTitle(if (logging) "Stop" else "Start")
                    .setOnClickListener {
                        Control.apply(
                            carContext,
                            if (logging) Control.ACTION_STOP else Control.ACTION_START
                        )
                        invalidate()
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.APP_ICON)
            .setTitle(if (logging) "drivetime · logging" else "drivetime · idle")
            .build()
    }

    private fun row(label: String, value: String?): Row =
        Row.Builder().setTitle(label).addText(value ?: "—").build()
}
