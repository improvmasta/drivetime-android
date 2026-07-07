package org.jupiterns.drivetime

/**
 * Pure decisions for the hybrid shell — which web to load and whether we're standalone —
 * kept out of [WebViewActivity] so they're unit-testable on the JVM (same pattern as
 * [WebAuth] / [ControlParse]). See STANDALONE.md (A1/A3).
 *
 * Two modes:
 *  - **Local (standalone)** — no server configured. The SPA is served from the APK's own
 *    bundled copy via WebViewAssetLoader at [LOCAL_URL] (a secure `https://` origin, so the
 *    service worker + IndexedDB replica work). No login; the phone's own GPS feeds the
 *    on-device replica (A2). This is the "no server ever" endgame.
 *  - **Server** — a server URL is set. Unchanged: silent-login into the hosted SPA at that
 *    origin, which itself carries the offline service-worker cache.
 */
object Shell {
    /** WebViewAssetLoader's default reserved domain — treated as a secure origin by WebView. */
    const val LOCAL_DOMAIN = "appassets.androidplatform.net"

    /** URL of the APK-bundled SPA. The bundle is built with base=/assets/web/ and served by
     *  an AssetsPathHandler registered at /assets/ (so /assets/web/… → assets/web/…). */
    const val LOCAL_URL = "https://$LOCAL_DOMAIN/assets/web/index.html"

    /** Standalone (no-server) mode ⇔ no server URL configured. */
    fun isLocalMode(serverUrl: String): Boolean = serverUrl.isBlank()

    /** The URL the shell should load for the given server setting. */
    fun startUrl(serverUrl: String): String =
        if (isLocalMode(serverUrl)) LOCAL_URL else serverUrl
}
