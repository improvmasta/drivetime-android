package org.jupiterns.drivetime

import java.net.URI

/**
 * Constants for the hybrid shell. The app ALWAYS loads its own bundled SPA from a secure
 * local origin ([LOCAL_URL]) — one storage origin, usable offline from first launch
 * (STANDALONE.md A1). A configured server is reached by the SPA as a cross-origin *sync
 * target* (absolute URL + Basic auth handed over the `DrivetimeNative` bridge), never
 * loaded as the page — so the UI is identical with or without a server, and whether the
 * app runs standalone is the SPA's call (via `Settings.isConfigured`), not a page choice.
 *
 * The one exception is the **dev server** ([DEV_URL]), which exists so the SPA can hot-reload
 * in an emulator with no APK rebuild. It is fenced on both sides — see [devHost].
 */
object Shell {
    /** WebViewAssetLoader's default reserved domain — treated as a secure origin by WebView. */
    const val LOCAL_DOMAIN = "appassets.androidplatform.net"

    /** URL of the APK-bundled SPA. The bundle is built with base=/assets/web/ and served by
     *  an AssetsPathHandler registered at /assets/ (so /assets/web/… → assets/web/…). */
    const val LOCAL_URL = "https://$LOCAL_DOMAIN/assets/web/index.html"

    /**
     * The Vite dev server to load INSTEAD of the bundled snapshot, or "" — which is what every
     * build that isn't a developer's own produces.
     *
     * Set it by building with `-PdevServer=http://<host>:5173` (see `dev.sh`, EMULATOR.md).
     * The shell then loads the dev server, Vite pushes edits over HMR, and the whole
     * sync-and-rebuild loop disappears for UI work. The `DrivetimeNative` bridge keeps working
     * because this origin is let through [WebAuth.isInAppUrl].
     *
     * **Two independent gates, and both must hold.** `BuildConfig.DEBUG` is false in the
     * `release` build type — the only thing Play ever receives (`bundlePlayRelease`) — and
     * `DEV_SERVER_URL` defaults to "" in `defaultConfig`, so it is empty in every build that
     * did not explicitly ask for it, debug builds included. A shipped build therefore cannot
     * carry a dev origin even if someone passes the property, and R8 folds this to "".
     */
    val DEV_URL: String
        get() = if (BuildConfig.DEBUG) BuildConfig.DEV_SERVER_URL else ""

    /** What the WebView loads: the dev server when one is compiled in, else the bundled SPA. */
    val startUrl: String
        get() = DEV_URL.ifEmpty { LOCAL_URL }

    /**
     * Host of [DEV_URL], or null when there isn't one — **the only widening of the shell's
     * origin fence, and the reason it is expressed as a parsed host rather than a prefix.**
     *
     * [WebAuth.isInAppUrl] decides which documents may become this WebView's page, and this
     * WebView carries the `DrivetimeNative` bridge: settings writes, the device token, backup,
     * tracking control. A sloppy widening hands all of that away. Matching the parsed *host* is
     * what keeps it tight — a `url.startsWith(DEV_URL)` test would happily accept
     * `http://10.1.1.15:5173.evil.example.com/`, which begins with the dev URL and is not the
     * dev server. Null on an unparseable value: never guess.
     */
    val devHost: String?
        get() {
            val dev = DEV_URL
            if (dev.isEmpty()) return null
            return runCatching { URI(dev).host }.getOrNull()
        }
}
