package org.jupiterns.drivetime

import org.junit.Assert.assertFalse
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
}
