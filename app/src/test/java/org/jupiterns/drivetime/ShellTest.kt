package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the hybrid-shell mode decision (STANDALONE.md A1/A3). */
class ShellTest {

    @Test fun noServerIsLocalMode() {
        assertTrue(Shell.isLocalMode(""))
        assertTrue(Shell.isLocalMode("   "))
    }

    @Test fun serverSetIsNotLocalMode() {
        assertFalse(Shell.isLocalMode("https://drivetime.jupiterns.org"))
    }

    @Test fun localModeLoadsBundledSpa() {
        assertEquals(Shell.LOCAL_URL, Shell.startUrl(""))
        assertTrue(Shell.LOCAL_URL.startsWith("https://"))                 // secure origin
        assertTrue(Shell.LOCAL_URL.contains("/assets/web/"))               // matches vite base
    }

    @Test fun serverModeLoadsServerUrl() {
        assertEquals("https://drivetime.jupiterns.org", Shell.startUrl("https://drivetime.jupiterns.org"))
    }
}
