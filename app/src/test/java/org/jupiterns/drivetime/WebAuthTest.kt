package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the WebView shell's link routing (no Android needed).
 *
 * This is a security boundary, not a UX preference: the WebView these URLs load into carries
 * the `DrivetimeNative` bridge, so "loads in-app" means "gets the bridge". Only the bundled
 * SPA origin may (hardening 3.3).
 */
class WebAuthTest {

    @Test fun bundledSpaOriginIsInApp() {
        assertTrue(WebAuth.isInAppUrl("https://appassets.androidplatform.net/assets/web/index.html"))
        assertTrue(WebAuth.isInAppUrl("https://appassets.androidplatform.net/assets/web/drive/42"))
        assertTrue(WebAuth.isInAppUrl("https://APPASSETS.ANDROIDPLATFORM.NET/assets/web/index.html"))
    }

    /** The change 3.3 made. The server is a cross-origin sync target, never a page — so a link
     *  to it opens in the browser instead of loading into the bridge's WebView. These used to
     *  be in-app, which meant a compromised / MITM'd / lookalike server page would have been
     *  handed every @JavascriptInterface method the bridge exposes. */
    @Test fun theServerIsExternalNow() {
        assertFalse(WebAuth.isInAppUrl("https://drivetime.jupiterns.org/"))
        assertFalse(WebAuth.isInAppUrl("https://drivetime.jupiterns.org/drive/42"))
        // Including the privacy-policy link the About card points at — same host, and the most
        // likely way this would have been tripped by accident rather than by an attacker.
        assertFalse(WebAuth.isInAppUrl("https://drivetime.jupiterns.org/privacy"))
        // And a self-hosted server on plain HTTP, where MITM is trivial.
        assertFalse(WebAuth.isInAppUrl("http://192.168.1.10:8200/"))
    }

    @Test fun otherHostsAreExternal() {
        assertFalse(WebAuth.isInAppUrl("https://www.openstreetmap.org/"))
        assertFalse(WebAuth.isInAppUrl("https://evil.example.com/appassets.androidplatform.net"))
        assertFalse(WebAuth.isInAppUrl("https://appassets.androidplatform.net.evil.example.com/"))
        assertFalse(WebAuth.isInAppUrl("https://sub.appassets.androidplatform.net/"))
    }

    @Test fun nonHttpSchemesAreExternal() {
        assertFalse(WebAuth.isInAppUrl("mailto:lindsay@jupiterns.org"))
        assertFalse(WebAuth.isInAppUrl("tel:+15551234567"))
        assertFalse(WebAuth.isInAppUrl("geo:40.7,-74.0"))
        assertFalse(WebAuth.isInAppUrl("javascript:alert(1)"))
        assertFalse(WebAuth.isInAppUrl("intent://foo#Intent;scheme=http;end"))
        assertFalse(WebAuth.isInAppUrl("file:///android_asset/web/index.html"))
    }

    @Test fun garbageIsNotInApp() {
        assertFalse(WebAuth.isInAppUrl("not a url"))
        assertFalse(WebAuth.isInAppUrl(""))
    }

    /**
     * The dev-server escape hatch (EMULATOR.md), pinned in **both** directions so the test is
     * correct however the suite is invoked — including `gradle test -PdevServer=…`.
     *
     * `-PdevServer=…` compiles a second trusted origin into a DEBUG build so the SPA can
     * hot-reload in an emulator, and that origin gets the `DrivetimeNative` bridge. The branch
     * here is keyed off `BuildConfig.DEV_SERVER_URL` — the same thing the app keys off — so:
     *
     *  - **No opt-in** (CI, `ship.sh`'s gate, a plain `./dev.sh`): the widening must be inert —
     *    no dev host, the shell boots the bundled SPA, LAN URLs stay external. A red here means
     *    a build nobody opted in acquired a second trusted origin, which is the whole thing the
     *    fence prevents.
     *  - **Opt-in** (`./dev.sh --dev`): the configured origin — and only it, by parsed host —
     *    becomes in-app, and the shell boots it. A prefix-spoof of that host stays external.
     *
     * Neither branch can touch a shipped build: `release` has no `DEV_SERVER_URL` and
     * `Shell.DEV_URL` re-gates on `BuildConfig.DEBUG`, which is false there.
     */
    @Test fun theDevOriginExistsOnlyWhenExplicitlyCompiledIn() {
        val configured = BuildConfig.DEV_SERVER_URL
        if (configured.isEmpty()) {
            assertEquals("no -PdevServer: DEV_URL must be empty", "", Shell.DEV_URL)
            assertNull("no -PdevServer: no dev host", Shell.devHost)
            assertEquals("the shell must boot the bundled SPA", Shell.LOCAL_URL, Shell.startUrl)
            assertFalse(WebAuth.isInAppUrl("http://10.1.1.15:5173/"))
            assertFalse(WebAuth.isInAppUrl("http://localhost:5173/"))
        } else {
            val host = java.net.URI(configured).host
            assertEquals("the compiled-in dev host is trusted", host, Shell.devHost)
            assertEquals("the shell boots the dev server", configured, Shell.startUrl)
            assertTrue("the dev origin is in-app", WebAuth.isInAppUrl("$configured/index.html"))
            // …but only by real host equality, never a prefix.
            assertFalse(WebAuth.isInAppUrl("http://$host.evil.example.com/"))
        }
    }
}
