package org.jupiterns.drivetime

import java.net.URI

/**
 * Pure helpers for the WebView shell, kept out of [WebViewActivity] so they're unit
 * testable on the JVM (no Android, no Robolectric) — same pattern as [ControlParse].
 */
object WebAuth {

    /**
     * True if [url] should load *inside* the WebView, false if it's an external link
     * (other host, `mailto:`, `tel:`, …) that should hand off to the system browser/app.
     * Non-http(s) schemes are always external. In-app = the dashboard's own host **or** the
     * bundled-SPA origin ([Shell.LOCAL_DOMAIN]) — so standalone/local mode, which has no
     * server host, still keeps its own navigation inside the WebView.
     */
    fun isInAppUrl(serverUrl: String, url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host ?: return false
        if (host.equals(Shell.LOCAL_DOMAIN, ignoreCase = true)) return true
        val serverHost = hostOf(serverUrl) ?: return false
        return host.equals(serverHost, ignoreCase = true)
    }

    private fun hostOf(s: String): String? = runCatching { URI(s).host }.getOrNull()
}
