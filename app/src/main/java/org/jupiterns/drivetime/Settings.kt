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

    val ingestUrl: String
        get() = "$serverUrl/api/ingest?key=$token"

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()
}
