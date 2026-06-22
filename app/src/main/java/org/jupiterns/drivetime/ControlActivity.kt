package org.jupiterns.drivetime

import android.app.Activity
import android.os.Bundle

/**
 * Invisible entry point for routines that "launch" an action (Samsung Modes &
 * Routines, Assistant, Tasker "Launch Activity"). Applies the intent action and
 * finishes immediately — no UI. Starting the foreground service from an Activity
 * context avoids Android's background foreground-service-start restriction.
 */
class ControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Control.apply(this, intent?.action)
        finish()
    }
}
