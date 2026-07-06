package org.jupiterns.drivetime

import java.net.URI

/**
 * Pure helpers for the WebView shell, kept out of [WebViewActivity] so they're unit
 * testable on the JVM (no Android, no Robolectric) — same pattern as [ControlParse].
 */
object WebAuth {

    /**
     * True if [url] should load *inside* the WebView (same host as the dashboard),
     * false if it's an external link (other host, `mailto:`, `tel:`, …) that should
     * hand off to the system browser/app. Non-http(s) schemes are always external.
     */
    fun isInAppUrl(serverUrl: String, url: String): Boolean {
        val serverHost = hostOf(serverUrl) ?: return false
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host ?: return false
        return host.equals(serverHost, ignoreCase = true)
    }

    private fun hostOf(s: String): String? = runCatching { URI(s).host }.getOrNull()
}
