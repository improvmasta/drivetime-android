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
        installCrashHandler()
        // Before anything can read a Settings: move the credentials into their own prefs file if
        // this install predates the split (they are excluded from Android's backup there — see
        // Settings.migrateSecrets). Single process, so "before onCreate returns" is genuinely
        // before every service, worker, receiver and activity in the app.
        //
        // Guarded, and ordered after the crash handler, for the same reason: this runs on every
        // launch of every install, and it is the first thing that touches prefs. A throw here —
        // a wrong-typed key, a corrupt prefs file — would take Application.onCreate with it, and
        // an app that dies in onCreate dies on *every* launch: an unrecoverable loop, on a phone
        // whose drives are all still sitting on disk intact. Failing to move a token is a bad
        // day (the app falls back to standalone and retries next launch); failing to start is
        // the end of the app. And installing the handler first is what makes the difference
        // visible at all — before, a throw on this line beat the handler into place and left no
        // trace in the one log a user can actually send us.
        runCatching { Settings.migrateSecrets(this) }
            .onFailure { EventLog.error("Secret migration failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun installCrashHandler() {
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
