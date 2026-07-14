package org.jupiterns.drivetime

import java.net.URI

/**
 * Pure helpers for the WebView shell, kept out of [WebViewActivity] so they're unit
 * testable on the JVM (no Android, no Robolectric) — same pattern as [ControlParse].
 */
object WebAuth {

    /**
     * True if [url] should load *inside* the WebView. **Only the bundled-SPA origin
     * ([Shell.LOCAL_DOMAIN]) ever does.** Everything else — every other host, `mailto:`,
     * `tel:`, and *including the paired server's own host* — is external and hands off to the
     * system browser.
     *
     * The server used to be in-app too, back when the dashboard was a remote page. It isn't
     * one any more: [WebViewActivity.loadDashboard] always loads [Shell.LOCAL_URL], and the
     * SPA reaches the server as a cross-origin *sync target* (absolute URL + Bearer token over
     * the bridge), never as a page. So the only way a server URL could have become the
     * document was a link — and that document would have loaded into the one WebView carrying
     * the `DrivetimeNative` bridge, handing every `@JavascriptInterface` method (settings
     * writes, the device token, backup, tracking control) to whatever that page happened to
     * be. A self-hosted server on plain HTTP, a stale DNS record, a captive portal or a
     * lookalike path is all it would take; a link to the privacy policy on the same host was
     * enough to do it by accident.
     *
     * Confining navigation to the origin the app actually ships closes that with no loss:
     * nothing in the product needs the server rendered as a page, and a link to it now opens
     * in the browser like any other external link (hardening 3.3).
     *
     * **The dev server ([Shell.devHost]) is the sole exception, and it cannot exist in a
     * shipped build.** It is compiled in only when someone builds with `-PdevServer=…` AND the
     * build type is `debug`; Play only ever gets `release`, where [Shell.DEV_URL] is "" and
     * [Shell.devHost] is null, so this collapses back to the single-origin test above. That is
     * what makes hot-reloading the SPA in an emulator (EMULATOR.md) safe to allow at all: the
     * widening is real, but it is unreachable from anything a user can install.
     */
    fun isInAppUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host ?: return false
        if (host.equals(Shell.LOCAL_DOMAIN, ignoreCase = true)) return true
        // Host equality, never a prefix match — see Shell.devHost.
        val dev = Shell.devHost ?: return false
        return host.equals(dev, ignoreCase = true)
    }
}
