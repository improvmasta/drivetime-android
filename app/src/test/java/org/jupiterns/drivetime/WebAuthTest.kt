package org.jupiterns.drivetime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the WebView shell's link routing (no Android needed). */
class WebAuthTest {

    private val server = "https://drivetime.jupiterns.org"

    @Test fun sameHostLoadsInApp() {
        assertTrue(WebAuth.isInAppUrl(server, "https://drivetime.jupiterns.org/"))
        assertTrue(WebAuth.isInAppUrl(server, "https://drivetime.jupiterns.org/drive/42"))
        assertTrue(WebAuth.isInAppUrl(server, "https://drivetime.jupiterns.org/api/mileage/export.csv?range=year"))
    }

    @Test fun hostMatchIsCaseInsensitive() {
        assertTrue(WebAuth.isInAppUrl(server, "https://Drivetime.Jupiterns.Org/commute"))
    }

    @Test fun serverUrlWithoutSchemeHostStillWorks() {
        // Settings trims the trailing slash; a plain https root still parses.
        assertTrue(WebAuth.isInAppUrl("https://drivetime.jupiterns.org", "https://drivetime.jupiterns.org/places"))
    }

    @Test fun otherHostsAreExternal() {
        assertFalse(WebAuth.isInAppUrl(server, "https://www.openstreetmap.org/"))
        assertFalse(WebAuth.isInAppUrl(server, "https://evil.example.com/drivetime.jupiterns.org"))
        assertFalse(WebAuth.isInAppUrl(server, "https://sub.drivetime.jupiterns.org/"))
    }

    @Test fun nonHttpSchemesAreExternal() {
        assertFalse(WebAuth.isInAppUrl(server, "mailto:lindsay@jupiterns.org"))
        assertFalse(WebAuth.isInAppUrl(server, "tel:+15551234567"))
        assertFalse(WebAuth.isInAppUrl(server, "geo:40.7,-74.0"))
    }

    @Test fun garbageIsNotInApp() {
        assertFalse(WebAuth.isInAppUrl(server, "not a url"))
        assertFalse(WebAuth.isInAppUrl(server, ""))
    }

    @Test fun bundledSpaOriginIsInApp() {
        // The standalone shell serves the SPA from the appassets origin; its own navigation
        // must stay in the WebView even though there's no server host configured.
        assertTrue(WebAuth.isInAppUrl("", "https://appassets.androidplatform.net/assets/web/index.html"))
        assertTrue(WebAuth.isInAppUrl(server, "https://appassets.androidplatform.net/assets/web/index.html"))
    }

    @Test fun noServerAndNonBundledIsExternal() {
        // Local mode (no server) → only the bundled origin is in-app; everything else opens out.
        assertFalse(WebAuth.isInAppUrl("", "https://drivetime.jupiterns.org/"))
        assertFalse(WebAuth.isInAppUrl("", "https://www.openstreetmap.org/"))
    }
}
