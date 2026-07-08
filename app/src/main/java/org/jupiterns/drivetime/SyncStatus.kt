package org.jupiterns.drivetime

import org.json.JSONObject

/**
 * Assembles the connection/upload status the SPA's Sync tab renders — extracted from
 * WebViewActivity so it's plain, JVM-testable JSON/state code with no Activity in sight.
 */
class SyncStatus(private val settings: Settings, private val uploader: Uploader) {

    /** The connection/upload summary: standalone, connected, reconnecting/auth-failed,
     *  or not-connected-yet. */
    fun connStatus(): JSONObject {
        val o = JSONObject()
        if (!settings.hasServer) {
            return o.put("state", "standalone").put("detail", "No server — drives are saved on your phone.")
        }
        val h = uploader.health()
        val now = System.currentTimeMillis()
        when {
            h.lastError != null && h.failures > 0 -> {
                val retry = if (h.backoffUntil > now) " · retry in ${(h.backoffUntil - now) / 1000}s" else ""
                o.put("state", if (h.authFailed) "auth_failed" else "reconnecting")
                o.put("detail", "${h.queued} fix(es) waiting$retry")
                o.put("error", if (h.authFailed) "Uploads are being rejected — re-scan the pairing QR (the token may have rotated)."
                    else "Last error: ${h.lastError}")
            }
            h.lastSuccessAt > 0 -> {
                o.put("state", "connected")
                o.put("detail", "Last upload ${rel(h.lastSuccessAt)} · ${h.queued} queued")
            }
            else -> {
                o.put("state", "idle")
                o.put("detail", "${h.queued} queued · no upload yet — try Test connection")
            }
        }
        return o
    }

    /** The OEM-kill warning string, or null — only while an incident is newer than the last
     *  acknowledgement and within the nag window. */
    fun killWarning(): String? {
        val killAt = settings.lastKillDetectedAt
        if (killAt > settings.killAcknowledgedAt &&
            System.currentTimeMillis() - killAt < KILL_WARNING_TTL_MS) {
            return "Your phone killed logging for a while. ${OemBatteryLinks.help().advice}"
        }
        return null
    }

    private fun rel(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return if (d < 60_000) "${d / 1000}s ago"
        else android.text.format.DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
    }

    companion object {
        /** How long after a suspected OEM kill we keep nagging — a week is enough to notice
         *  and fix; after that there's nothing actionable left and it becomes noise. */
        private const val KILL_WARNING_TTL_MS = 7L * 24 * 60 * 60_000L
    }
}
