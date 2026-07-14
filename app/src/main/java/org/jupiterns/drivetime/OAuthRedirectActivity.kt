package org.jupiterns.drivetime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Catches the Google OAuth redirect (the reversed-client-id scheme, see
 * [DriveAuth.redirectFor]), finishes the PKCE code exchange off the main thread, and bounces
 * straight back into the app. Invisible — plain [Activity] like ControlActivity, because
 * Theme.NoDisplay is not a Theme.AppCompat and AppCompatActivity would throw on it.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val code = uri?.getQueryParameter("code")
        val denied = uri?.getQueryParameter("error")
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        when {
            !code.isNullOrBlank() -> Thread {
                val settings = Settings(app)
                val err = DriveAuth.finishAuth(settings, code)
                val msg = if (err == null) {
                    // Drive is now a destination, so the scheduled backup needs the network
                    // constraint it didn't need a moment ago (BackupWorker.constraints).
                    runCatching { BackupWorker.reschedule(app, settings) }
                    EventLog.info("Google Drive connected")
                    "Google Drive connected"
                } else {
                    EventLog.warn("Drive connect failed: $err")
                    "Drive connect failed: $err"
                }
                main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
            }.start()
            else -> {
                EventLog.warn("Drive connect cancelled: ${denied ?: "no code in redirect"}")
                main.post { Toast.makeText(app, "Google Drive sign-in cancelled", Toast.LENGTH_LONG).show() }
            }
        }
        startActivity(
            Intent(this, WebViewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
