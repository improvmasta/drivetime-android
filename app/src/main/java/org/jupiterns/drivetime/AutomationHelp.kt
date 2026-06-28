package org.jupiterns.drivetime

/**
 * In-app cheat-sheet for the routine / shortcut surface — shown verbatim in the
 * Settings "AUTOMATION" section so wiring a Samsung Modes & Routines / Tasker /
 * HA recipe doesn't require flipping to the README. Single source of truth: the
 * fixture under app/src/test verifies AUTOMATION.md and this string stay in sync.
 */
object AutomationHelp {
    fun cheatSheet(): String = """
        App Shortcuts (Samsung Modes & Routines → Open app → shortcut):
          • Auto      — auto-detect driving (default)
          • Driving   — force dense logging now
          • Eco       — force light tier only
          • Stop      — turn logging off

        Broadcast intents (Tasker / MacroDroid / HA / RoutinePlus):
          org.jupiterns.drivetime.action.START      → AUTO
          org.jupiterns.drivetime.action.STOP       → OFF
          org.jupiterns.drivetime.action.TOGGLE     → OFF ↔ AUTO
          org.jupiterns.drivetime.action.MODE_AUTO
          org.jupiterns.drivetime.action.MODE_DRIVING
          org.jupiterns.drivetime.action.MODE_ECO
          org.jupiterns.drivetime.action.SET        → see SET keys below
          org.jupiterns.drivetime.action.QUERY      → emits STATE_CHANGED

        SET keys (extras key=<name>, value=<...>):
          mode                            auto | driving | light | off
          interval_sec                    1+    fixes/sec while driving
          idle_interval_sec               1+    fixes/sec at red lights
          light_interval_sec              1+    fixes/sec in light tier
          upload_interval_sec             1+    LIGHT flush cadence
          driving_upload_interval_sec     1+    DRIVING flush cadence
          drive_by_speed                  true|false
          stationary_stop_min             0+    auto-trip backstop
          auto_trip                       true|false
          alerts_enabled                  true|false

        Optional token (extra token=<value>) — if set in Settings,
        SET / QUERY must include it. START/STOP/TOGGLE/mode-actions ignore it.

        State broadcast (subscribe to this in your routine):
          org.jupiterns.drivetime.event.STATE_CHANGED
        Extras: logging, tracking_mode, tier, reason, queue_depth, obd_connected,
                last_fix_at, last_success_at, last_command_source, source, ts
    """.trimIndent()
}
