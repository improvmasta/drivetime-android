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
 * ## The control token
 *
 * [Settings.controlToken] gates the **exported** surface — and only that surface. Everything
 * arriving through [ControlActivity] or [ControlReceiver] came from outside this app (any
 * installed app can start an exported activity or send us a broadcast; neither needs a
 * permission), so it lands in [applyExternal], which is where the token is checked. The in-app
 * switches, the resume alarm and the activity-recognition receiver (`exported=false`) call
 * [apply] directly: they are this app talking to itself, and a user must never need a secret
 * to press their own Off switch.
 *
 * With a token set, an external intent must carry a matching `token` extra to use:
 *   - **SET / QUERY / MARK** — as before, and
 *   - **STOP / TOGGLE** — new. These are the verbs that can turn the logger OFF, and "the app
 *     quietly stopped logging" is this project's #1 bug class; a token that protects the
 *     settings but lets any app on the phone silently kill tracking protects the wrong thing.
 *
 * **START and the MODE_\* verbs stay open even with a token set.** They cannot stop logging —
 * the worst they do is start it or change the sampling tier — so leaving them open costs
 * nothing and keeps a routine always able to *recover* tracking without the secret.
 *
 * With a **blank token — the default — every verb stays open exactly as before**, so no
 * existing routine changes behaviour. The cost of opting in is real and worth stating: the
 * built-in **Off** App Shortcut (`res/xml/shortcuts.xml`) is static XML and cannot carry a
 * runtime secret, so once a token is set that shortcut stops working and a STOP recipe must
 * send the `token` extra itself. Driving/Auto/Eco shortcuts are unaffected. A rejected intent
 * is logged to the in-app Activity log, never silently dropped.
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

    /**
     * The verbs an **external** caller must present the token for, once one is set: the ones
     * that change settings ([ACTION_SET]), read state back ([ACTION_QUERY]), write data
     * ([ACTION_MARK]), or **turn the logger off** ([ACTION_STOP], [ACTION_TOGGLE]).
     *
     * START and the MODE_* verbs are absent on purpose — they cannot stop logging, so leaving
     * them open keeps a routine able to recover tracking without the secret.
     */
    val TOKEN_GATED = setOf(ACTION_SET, ACTION_QUERY, ACTION_MARK, ACTION_STOP, ACTION_TOGGLE)

    /**
     * The gate decision, kept **pure** so it's unit-testable on the JVM with no Android and no
     * Context — same idiom as [ControlParse]. [required] is the configured token ("" = none),
     * [given] the `token` extra the intent actually carried.
     *
     * Blank [required] allows everything, which is why setting no token leaves the surface
     * exactly as open as it has always been.
     */
    fun externalAllowed(action: String?, required: String, given: String): Boolean {
        if (required.isBlank()) return true
        if (action.orEmpty() !in TOKEN_GATED) return true
        return given == required
    }

    /**
     * Apply an intent that arrived from **outside** the app. The exported [ControlActivity]
     * and [ControlReceiver] are the only callers, because they are the only untrusted entry
     * points: any installed app can start an exported activity or send us a broadcast, with
     * no permission and no user gesture.
     *
     * This is the **one** place [Settings.controlToken] is enforced. A new exported entry
     * point must route through here, not [apply] — putting the check in the shared applier
     * instead would gate the app's own Off switch behind the user's own secret.
     */
    fun applyExternal(context: Context, intent: Intent?): String {
        val settings = Settings(context)
        val action = intent?.action
        val given = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (!externalAllowed(action, settings.controlToken, given)) {
            EventLog.warn("Control intent $action rejected — bad/missing token")
            return settings.trackingMode
        }
        return apply(context, intent)
    }

    /** Convenience for callers that only have an action string (the activity-recognition
     *  receiver, the in-app switches). The optional [source] tags the resulting
     *  STATE_CHANGED so a routine can distinguish, say, an activity-recognition
     *  auto-start from a user shortcut. */
    fun apply(context: Context, action: String?, source: String? = null): String {
        val intent = Intent(action)
        if (source != null) intent.putExtra(EXTRA_SOURCE, source)
        return apply(context, intent)
    }

    /**
     * Apply a control intent from a **trusted** caller — the in-app switches, the resume
     * alarm, the `exported=false` activity-recognition receiver. Returns the resulting
     * desired [Settings.trackingMode].
     *
     * Deliberately does **no** token check: this app does not need the user's secret to obey
     * the user. Anything from outside goes through [applyExternal] instead.
     */
    fun apply(context: Context, intent: Intent?): String {
        val settings = Settings(context)
        val action = intent?.action

        // The token was already checked at the boundary ([applyExternal]) if this intent came
        // from outside; a caller that reaches here directly is this app itself.

        // QUERY is read-only — emit the snapshot.
        if (action == ACTION_QUERY) {
            StateBroadcaster.emit(context, source(intent, default = "query"))
            return settings.trackingMode
        }

        // MARK is a side effect, not a mode change — it belongs here with SET/QUERY, and
        // NOT in the mode `when` below, whose `else` branch would leave the mode untouched
        // but silently drop the mark. Forwarded to the service, which owns LiveState and the
        // marker buffer. Nothing to mark when we aren't logging, and starting the service
        // from a background broadcast just to mark would be refused on Android 12+ anyway.
        if (action == ACTION_MARK) {
            if (!LocationService.isRunning) {
                EventLog.warn("MARK ignored — not logging")
                return settings.trackingMode
            }
            val svc = Intent(context, LocationService::class.java).setAction(ACTION_MARK)
            runCatching { context.startService(svc) }
                .onFailure { EventLog.warn("MARK refused: ${it.message ?: it.javaClass.simpleName}") }
            return settings.trackingMode
        }

        // SET is parameter-only (the mode-set rare path is special-cased below).
        if (action == ACTION_SET) {
            val src = source(intent, default = "routine")
            applySet(context, intent, src)
            return settings.trackingMode
        }

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
