package org.jupiterns.drivetime

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the bundled-SPA origin (STANDALONE.md A1). The app always loads this;
 *  a server, when configured, is a cross-origin sync target the SPA reaches, not a page. */
class ShellTest {

    @Test fun bundledSpaIsASecureLocalOrigin() {
        assertTrue(Shell.LOCAL_URL.startsWith("https://"))                 // secure origin
        assertTrue(Shell.LOCAL_URL.contains(Shell.LOCAL_DOMAIN))
        assertTrue(Shell.LOCAL_URL.contains("/assets/web/"))              // matches the vite base
    }
}
