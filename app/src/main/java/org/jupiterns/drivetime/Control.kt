package org.jupiterns.drivetime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * External control surface so the user, a shortcut, or a phone routine (Samsung
 * Modes & Routines, Tasker, MacroDroid, Home Assistant) can drive logging.
 *
 * Triggerable two ways, both carrying one of the ACTION_* strings (and, for SET,
 * `key`/`value` extras):
 *   - launch the (invisible) ControlActivity — preferred for *starting* (an Activity
 *     context dodges the background foreground-service-start limit), or
 *   - send a broadcast to ControlReceiver.
 *
 * The verbs map onto the [Settings.trackingMode] the DriveDetector reads:
 *   START / MODE_AUTO → AUTO      (detector decides Light vs Driving)
 *   MODE_DRIVING      → DRIVING   (force the dense tier)
 *   MODE_ECO          → LIGHT     (force the sparse tier)
 *   STOP              → OFF       (stop logging)
 *   TOGGLE            → OFF ↔ AUTO
 *   SET key=…  value=… (or any of the known keys as a top-level extra) — change one
 *                      setting; see [SET_KEYS] for the supported keys
 *   QUERY             → emit a [StateBroadcaster] STATE_CHANGED back (no state change)
 *
 * SET/QUERY are gated by [Settings.controlToken] if it's set — the intent must carry
 * a matching `token` extra. START/STOP/TOGGLE/mode actions remain open even when a
 * token is set so a routine can always *stop* the app regardless of secrets.
 */
object Control {
    const val ACTION_START = "org.jupiterns.drivetime.action.START"
    const val ACTION_STOP = "org.jupiterns.drivetime.action.STOP"
    const val ACTION_TOGGLE = "org.jupiterns.drivetime.action.TOGGLE"
    const val ACTION_MODE_AUTO = "org.jupiterns.drivetime.action.MODE_AUTO"
    const val ACTION_MODE_DRIVING = "org.jupiterns.drivetime.action.MODE_DRIVING"
    const val ACTION_MODE_ECO = "org.jupiterns.drivetime.action.MODE_ECO"
    const val ACTION_SET = "org.jupiterns.drivetime.action.SET"
    const val ACTION_QUERY = "org.jupiterns.drivetime.action.QUERY"

    /** Stamp a marker at the phone's current place and time (MARKERS.md). A side-effect
     *  action like SET/QUERY, NOT a mode switch: it changes no tracking mode, so it must
     *  stay out of the mode `when` below or it would silently set the mode to OFF. */
    const val ACTION_MARK = "org.jupiterns.drivetime.action.MARK"

    const val EXTRA_KEY = "key"
    const val EXTRA_VALUE = "value"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_SOURCE = "source"

    /**
     * SET keys a routine may change. Kept narrow on purpose — the API never adds a
     * parallel config the Settings screen can't show, so every key here is also a
     * field in [Settings]. Values are parsed flexibly (case-insensitive booleans,
     * positive ints).
     */
    val SET_KEYS = setOf(
        "mode",
        "interval_sec", "idle_interval_sec", "light_interval_sec",
        "upload_interval_sec", "driving_upload_interval_sec",
        "drive_by_speed", "stationary_stop_min",
        "auto_trip", "alerts_enabled", "notif_driving_only",
        "motion_onset", "onset_probe_interval_sec", "onset_probe_window_sec",
        "onset_speed_mps", "onset_accel_rms",
    )

    /** Convenience for callers that only have an action string (the activity-recognition
     *  receiver, a routine broadcast). The optional [source] tags the resulting
     *  STATE_CHANGED so a routine can distinguish, say, an activity-recognition
     *  auto-start from a user shortcut. */
    fun apply(context: Context, action: String?, source: String? = null): String {
        val intent = Intent(action)
        if (source != null) intent.putExtra(EXTRA_SOURCE, source)
        return apply(context, intent)
    }

    /** Apply a control intent. Returns the resulting desired [Settings.trackingMode]. */
    fun apply(context: Context, intent: Intent?): String {
        val settings = Settings(context)
        val action = intent?.action

        // QUERY is read-only; honour token if set, then emit the snapshot.
        if (action == ACTION_QUERY) {
            if (!tokenOk(settings, intent)) return settings.trackingMode
            StateBroadcaster.emit(context, source(intent, default = "query"))
            return settings.trackingMode
        }

        // MARK is a side effect, not a mode change — it belongs here with SET/QUERY, and
        // NOT in the mode `when` below, whose `else` branch would leave the mode untouched
        // but silently drop the mark. Forwarded to the service, which owns LiveState and the
        // marker buffer. Nothing to mark when we aren't logging, and starting the service
        // from a background broadcast just to mark would be refused on Android 12+ anyway.
        if (action == ACTION_MARK) {
            if (!tokenOk(settings, intent)) return settings.trackingMode
            if (!LocationService.isRunning) {
                EventLog.warn("MARK ignored — not logging")
                return settings.trackingMode
            }
            val svc = Intent(context, LocationService::class.java).setAction(ACTION_MARK)
            runCatching { context.startService(svc) }
                .onFailure { EventLog.warn("MARK refused: ${it.message ?: it.javaClass.simpleName}") }
            return settings.trackingMode
        }

        // SET is parameter-only (mode-set rare path is special-cased below); honour token.
        if (action == ACTION_SET) {
            if (!tokenOk(settings, intent)) return settings.trackingMode
            val src = source(intent, default = "routine")
            applySet(context, intent, src)
            return settings.trackingMode
        }

        // Mode-changing actions: always open (per AUTOMATION.md — START/STOP must work even
        // when SET is locked).
        val mode = when (action) {
            ACTION_START, ACTION_MODE_AUTO -> Settings.MODE_AUTO
            ACTION_STOP -> Settings.MODE_OFF
            ACTION_MODE_DRIVING -> Settings.MODE_DRIVING
            ACTION_MODE_ECO -> Settings.MODE_LIGHT
            ACTION_TOGGLE ->
                if (settings.trackingMode == Settings.MODE_OFF) Settings.MODE_AUTO else Settings.MODE_OFF
            else -> return settings.trackingMode
        }
        val src = source(intent, default = sourceFromAction(action))
        applyMode(context, settings, mode, src)
        return mode
    }

    /** Parse and apply a SET intent. Supports either {key, value} extras or a
     *  top-level extra named after a known SET key. */
    private fun applySet(context: Context, intent: Intent?, source: String) {
        if (intent == null) return
        val (key, value) = resolveKv(intent) ?: return
        set(context, key, value, source)
    }

    /**
     * Apply one setting change directly (no intent/token gating) — the shared path for the
     * in-app Settings tabs' bridge writes (`DrivetimeNative.setSetting`) and, via [applySet],
     * the routine SET intent. Same key set + side effects (mode, workers) so the UI and the
     * routine API can never drift. Returns true when the value was recognised and applied.
     */
    fun set(context: Context, key: String, value: String, source: String): Boolean {
        val settings = Settings(context)
        if (key !in SET_KEYS) {
            EventLog.warn("SET ignored — unknown key '$key'")
            return false
        }
        val applied = when (key) {
            "mode" -> {
                val mode = parseMode(value) ?: run {
                    EventLog.warn("SET mode ignored — bad value '$value'"); return false
                }
                applyMode(context, settings, mode, source)
                true
            }
            "interval_sec" -> setPosInt(key, value) { settings.intervalSec = it }
            "idle_interval_sec" -> setPosInt(key, value) { settings.idleIntervalSec = it }
            "light_interval_sec" -> setPosInt(key, value) { settings.lightIntervalSec = it }
            "upload_interval_sec" -> setPosInt(key, value) { settings.uploadIntervalSec = it }
            "driving_upload_interval_sec" -> setPosInt(key, value) { settings.drivingUploadIntervalSec = it }
            "stationary_stop_min" -> setPosInt(key, value, allowZero = true) { settings.stationaryStopMin = it }
            "drive_by_speed" -> setBool(value) { settings.driveBySpeed = it }
            "auto_trip" -> setBool(value) {
                settings.autoTrip = it
                if (it) runCatching { TripDetector.enable(context) }
                else runCatching { TripDetector.disable(context) }
            }
            "alerts_enabled" -> setBool(value) {
                settings.alertsEnabled = it
                // Check-engine alerts are on-device now — the running service reads this
                // live. Kill any legacy server-poll worker a prior build scheduled.
                Notify.cancelRetiredAlertPoll(context)
            }
            "notif_driving_only" -> setBool(value) {
                settings.notifDrivingOnly = it
                // Re-post the card now rather than at the next fix — an idle tier can be a
                // minute between fixes, and a preference the user just flipped should look
                // like it took. A no-action start on a running service re-applies the tier,
                // which rebuilds the notification on the right channel.
                if (LocationService.isRunning) {
                    runCatching { context.startService(Intent(context, LocationService::class.java)) }
                }
            }
            "motion_onset" -> setBool(value) { settings.motionOnset = it }
            "onset_probe_interval_sec" -> setPosInt(key, value) { settings.onsetProbeIntervalSec = it }
            "onset_probe_window_sec" -> setPosInt(key, value) { settings.onsetProbeWindowSec = it }
            "onset_speed_mps" -> setPosInt(key, value) { settings.onsetSpeedMps = it }
            "onset_accel_rms" -> setPosInt(key, value, allowZero = true) { settings.onsetAccelRms = it }
            else -> false
        }
        if (applied) {
            settings.lastCommandSource = source
            EventLog.info("SET $key=$value (from $source)")
            StateBroadcaster.emit(context, source)
        } else {
            EventLog.warn("SET $key ignored — bad value '$value'")
        }
        return applied
    }

    private fun resolveKv(intent: Intent): Pair<String, String>? {
        val key = intent.getStringExtra(EXTRA_KEY)
        if (key != null) {
            val value = intent.getStringExtra(EXTRA_VALUE) ?: return null
            return key.lowercase() to value
        }
        // Convenience form: a routine may pass the key as a top-level extra (e.g.
        // `mode=driving`) without nesting it under key/value. Pick the first match.
        for (k in SET_KEYS) {
            val v = intent.extras?.get(k)
            if (v != null) return k to v.toString()
        }
        return null
    }

    private fun parseMode(value: String): String? = ControlParse.parseMode(value)

    /** Parse a numeric SET value, then hold it to [ControlParse]'s bounds for that key. The clamp
     *  is announced: a routine that asked for `interval_sec=0` and quietly got 1 would otherwise
     *  have no way to find out its recipe doesn't do what it says. ([Settings] clamps too — this
     *  is where the *user* hears about it.) */
    private inline fun setPosInt(key: String, value: String, allowZero: Boolean = false, apply: (Int) -> Unit): Boolean {
        val n = ControlParse.parsePosInt(value, allowZero) ?: return false
        val bounded = ControlParse.clampSetting(key, n)
        if (bounded != n) {
            EventLog.warn("SET $key=$n out of range (${ControlParse.boundsOf(key)}) — clamped to $bounded")
        }
        apply(bounded)
        return true
    }

    private inline fun setBool(value: String, apply: (Boolean) -> Unit): Boolean {
        val v = ControlParse.parseBool(value) ?: return false
        apply(v)
        return true
    }

    private fun source(intent: Intent?, default: String): String =
        intent?.getStringExtra(EXTRA_SOURCE)?.trim()?.takeIf { it.isNotEmpty() } ?: default

    private fun sourceFromAction(action: String?): String = when (action) {
        ACTION_START, ACTION_MODE_AUTO -> "shortcut"
        ACTION_STOP -> "shortcut"
        ACTION_TOGGLE -> "shortcut"
        ACTION_MODE_DRIVING, ACTION_MODE_ECO -> "shortcut"
        else -> "routine"
    }

    private fun tokenOk(settings: Settings, intent: Intent?): Boolean {
        val required = settings.controlToken
        if (required.isBlank()) return true
        val given = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (given == required) return true
        EventLog.warn("Control intent ${intent?.action} rejected — bad/missing token")
        return false
    }

    private fun applyMode(context: Context, settings: Settings, mode: String, source: String) {
        settings.lastCommandSource = source
        // Any pending "off for N hours" auto-resume is moot the moment the mode is set
        // explicitly (user toggle, routine, or the resume alarm itself firing START).
        cancelResumeAlarm(context)
        settings.snoozeUntil = 0L
        if (mode == Settings.MODE_OFF) {
            settings.trackingMode = Settings.MODE_OFF
            settings.loggingEnabled = false
            Watchdog.cancel(context)
            context.stopService(Intent(context, LocationService::class.java))
            EventLog.info("Tracking stopped (from $source)")
            StateBroadcaster.emit(context, source)
            return
        }
        // No server gate: tracking is server-optional (STANDALONE.md). The phone logs to its
        // own on-device buffer/replica (WebFixBuffer) and segments its own drives with no
        // server at all; the uploader simply no-ops until one is configured. So a START must
        // always start the service — standalone is a first-class mode, not an unfinished setup.
        // Record desired state + arm the watchdog before starting, so logging resumes
        // even if this background FGS-start is throttled/refused. The (re)start re-applies
        // the tier the detector now resolves, so a forced mode takes effect immediately.
        settings.trackingMode = mode
        settings.loggingEnabled = true
        Watchdog.schedule(context)
        startTrackingService(context)
        EventLog.info("Tracking mode = $mode (from $source)")
        StateBroadcaster.emit(context, source)
    }

    /**
     * Start the logging foreground service, absorbing the two ways Android can refuse:
     * ForegroundServiceStartNotAllowedException (a background broadcast START on 12+
     * without the battery exemption) and a location-permission SecurityException at
     * startForeground time (14+ enforces the FGS type's prerequisites). The watchdog is
     * always armed by callers first, so a refused start is retried rather than fatal.
     * The one shared incantation — Control, Watchdog and BootReceiver each had their own
     * copy with divergent error handling (only one of the three caught anything).
     */
    fun startTrackingService(context: Context): Boolean {
        val svc = Intent(context, LocationService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc)
            else context.startService(svc)
            true
        } catch (e: Exception) {
            EventLog.warn("Service start refused: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ---- snooze: "turn off tracking for N minutes", then auto-resume to AUTO ----

    private const val RESUME_REQUEST = 7301

    /** Turn logging off now and schedule an auto-resume [minutes] out. The resume is an
     *  exact alarm firing ACTION_START at [ControlReceiver] — which lands on time through
     *  Doze and carries the brief FGS-start allowance, with the watchdog as backstop. */
    fun snooze(context: Context, minutes: Int) {
        if (minutes <= 0) return
        val settings = Settings(context)
        // Stops the service and (via applyMode) clears any prior snooze + its alarm.
        applyMode(context, settings, Settings.MODE_OFF, "snooze")
        val at = System.currentTimeMillis() + minutes.toLong() * 60_000L
        settings.snoozeUntil = at
        scheduleResumeAlarm(context, at)
        EventLog.info("Tracking off for ${minutes}m — resumes ${java.util.Date(at)}")
        StateBroadcaster.emit(context, "snooze")
    }

    /** Reboot drops scheduled alarms; if a snooze was pending, resume now (past due) or
     *  re-arm the alarm. Called from [BootReceiver]. */
    fun resumeAfterReboot(context: Context) {
        val settings = Settings(context)
        val at = settings.snoozeUntil
        if (at <= 0L) return
        if (System.currentTimeMillis() >= at) applyMode(context, settings, Settings.MODE_AUTO, "snooze")
        else scheduleResumeAlarm(context, at)
    }

    private fun resumePendingIntent(context: Context): PendingIntent {
        val i = Intent(context, ControlReceiver::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_SOURCE, "snooze")
        // minSdk 26 → FLAG_IMMUTABLE (API 23) is always available.
        return PendingIntent.getBroadcast(
            context, RESUME_REQUEST, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleResumeAlarm(context: Context, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = resumePendingIntent(context)
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked at runtime → fall back to inexact (may drift
            // in deep Doze, but still resumes).
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelResumeAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(resumePendingIntent(context))
    }
}
