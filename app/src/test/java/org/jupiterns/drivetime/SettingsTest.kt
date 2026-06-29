package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings is a SharedPreferences wrapper, but every field has implicit defaults
 * the rest of the app depends on (e.g. uploader cadence assumes a non-zero
 * `uploadIntervalSec`). Locking the defaults here means a future "just bump the
 * pref name" never silently changes behaviour. */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private lateinit var s: Settings

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Wipe between runs since Robolectric reuses prefs in-process.
        ctx.getSharedPreferences("drivetime", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        s = Settings(ctx)
    }

    @Test fun defaults_areTheCadencesTheServiceAssumes() {
        assertEquals("driving fix cadence default", 3, s.intervalSec)
        assertEquals("idle (red-light) cadence default", 20, s.idleIntervalSec)
        assertEquals("light tier cadence default", 60, s.lightIntervalSec)
        assertEquals("LIGHT upload cadence default", 45, s.uploadIntervalSec)
        assertEquals("DRIVING upload cadence default", 10, s.drivingUploadIntervalSec)
        assertEquals("auto mode is the routine default", Settings.MODE_AUTO, s.trackingMode)
        assertTrue("speed backstop on by default", s.driveBySpeed)
    }

    @Test fun motionOnset_defaultsOnWithSaneProbeKnobs() {
        assertTrue("motion-onset fast start on by default", s.motionOnset)
        assertEquals("probationary GPS cadence default", 3, s.onsetProbeIntervalSec)
        assertEquals("probation window default", 25, s.onsetProbeWindowSec)
        assertEquals("vehicular Doppler threshold default", 4, s.onsetSpeedMps)
        assertEquals("accel on-foot threshold default", 250, s.onsetAccelRms)
    }

    @Test fun isConfigured_requiresServerAndCredentials() {
        assertFalse(s.isConfigured)
        s.serverUrl = "https://example.com"
        assertFalse(s.isConfigured)
        s.username = "u"; s.password = "p"
        assertTrue(s.isConfigured)
    }

    @Test fun authHeader_isBlankUntilCredentialsAreSet() {
        assertEquals("", s.authHeader)
        s.username = "u"; s.password = "p"
        assertTrue(s.authHeader.startsWith("Basic "))
    }

    @Test fun serverUrl_stripsTrailingSlash() {
        s.serverUrl = "https://example.com/"
        assertEquals("https://example.com", s.serverUrl)
    }
}
