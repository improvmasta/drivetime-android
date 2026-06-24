package org.jupiterns.drivetime

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.jupiterns.drivetime.databinding.ActivityLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only view of [EventLog] — newest first, shareable, clearable. The app's
 *  answer to "what happened / why did logging stop". */
class LogActivity : AppCompatActivity() {

    private lateinit var b: ActivityLogBinding
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.logClear.setOnClickListener { EventLog.clear(); render() }
        b.logShare.setOnClickListener { share() }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val entries = EventLog.recent()
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
