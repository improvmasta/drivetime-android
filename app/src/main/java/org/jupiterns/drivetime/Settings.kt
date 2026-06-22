package org.jupiterns.drivetime

import android.content.Context

/** Server connection settings, backed by SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("drivetime", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "https://drivetime.jupiterns.org") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    /** Seconds between GPS fixes while driving. */
    var intervalSec: Int
        get() = prefs.getInt("interval_sec", 3)
        set(v) = prefs.edit().putInt("interval_sec", v).apply()

    /** Whether the logging service is currently meant to be running. */
    var loggingEnabled: Boolean
        get() = prefs.getBoolean("logging_enabled", false)
        set(v) = prefs.edit().putBoolean("logging_enabled", v).apply()

    /** Paired OBD-II (ELM327) dongle, if configured. */
    var obdMac: String
        get() = prefs.getString("obd_mac", "") ?: ""
        set(v) = prefs.edit().putString("obd_mac", v).apply()
    var obdName: String
        get() = prefs.getString("obd_name", "") ?: ""
        set(v) = prefs.edit().putString("obd_name", v).apply()

    val ingestUrl: String
        get() = "$serverUrl/api/ingest?key=$token"

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()
}
