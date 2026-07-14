package org.jupiterns.drivetime

import android.app.Application

/**
 * Process-wide bootstrap. Exists for one reason: a crash anywhere (the sticky tracking
 * service, a worker, the WebView activity) must leave a trail in [EventLog] — the tester
 * build has no server and no telemetry, so the activity log a user emails from
 * Settings → Report a problem is the only forensic record a crash gets.
 */
class DrivetimeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        // Before anything can read a Settings: move the credentials into their own prefs file if
        // this install predates the split (they are excluded from Android's backup there — see
        // Settings.migrateSecrets). Single process, so "before onCreate returns" is genuinely
        // before every service, worker, receiver and activity in the app.
        Settings.migrateSecrets(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Log first, then hand off to Android's own handler (crash dialog + process
            // death). EventLog persists synchronously, so the entry survives the kill.
            runCatching {
                val where = e.stackTrace.take(6).joinToString(" < ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
                EventLog.error("CRASH [${thread.name}] $e @ $where")
            }
            if (previous != null) {
                previous.uncaughtException(thread, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
