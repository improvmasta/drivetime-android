package org.jupiterns.drivetime

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.jupiterns.drivetime.databinding.ActivityLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only view of [EventLog] — newest first, shareable, clearable. The app's
 *  answer to "what happened / why did logging stop". Refreshes live while open so a
 *  drive's onset/connect events show as they happen. */
class LogActivity : AppCompatActivity() {

    private lateinit var b: ActivityLogBinding
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.logClose.setOnClickListener { finish() }
        b.logClear.setOnClickListener { confirmClear() }
        b.logShare.setOnClickListener { share() }
        render()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    /** Clearing the log throws away the diagnostic trail — confirm first. */
    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear activity log?")
            .setMessage("This permanently deletes the recorded events — the trail you'd use to see why logging stopped.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> EventLog.clear(); render() }
            .show()
    }

    private fun render() {
        // DEBUG entries are diagnostics for problem reports — the on-screen trail stays
        // coarse. Share (below) still includes them, so a shared log is the full picture.
        val entries = EventLog.recent().filter { it.level != EventLog.Level.DEBUG }
        b.logText.text = if (entries.isEmpty()) "No activity logged yet."
        else entries.joinToString("\n") { e ->
            val sym = when (e.level) {
                EventLog.Level.ERROR -> "✕"   // ✕
                EventLog.Level.WARN -> "⚠"     // ⚠
                else -> "·"                     // ·
            }
            "${fmt.format(Date(e.ts))}  $sym ${e.msg}"
        }
    }

    private fun share() {
        val text = EventLog.recent().joinToString("\n") { "${fmt.format(Date(it.ts))} [${it.level}] ${it.msg}" }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "Share activity log"))
    }
}
