package org.jupiterns.drivetime

/**
 * Constants for the hybrid shell. The app ALWAYS loads its own bundled SPA from a secure
 * local origin ([LOCAL_URL]) — one storage origin, usable offline from first launch
 * (STANDALONE.md A1). A configured server is reached by the SPA as a cross-origin *sync
 * target* (absolute URL + Basic auth handed over the `DrivetimeNative` bridge), never
 * loaded as the page — so the UI is identical with or without a server, and whether the
 * app runs standalone is the SPA's call (via `Settings.isConfigured`), not a page choice.
 */
object Shell {
    /** WebViewAssetLoader's default reserved domain — treated as a secure origin by WebView. */
    const val LOCAL_DOMAIN = "appassets.androidplatform.net"

    /** URL of the APK-bundled SPA. The bundle is built with base=/assets/web/ and served by
     *  an AssetsPathHandler registered at /assets/ (so /assets/web/… → assets/web/…). */
    const val LOCAL_URL = "https://$LOCAL_DOMAIN/assets/web/index.html"
}
