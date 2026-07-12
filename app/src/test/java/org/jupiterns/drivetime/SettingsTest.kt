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

    @Test fun serverUrl_defaultsEmptyForLocalFirst() {
        // Fresh install → no server → standalone/local mode (STANDALONE.md A3).
        assertEquals("", s.serverUrl)
        assertFalse("no server configured by default", s.hasServer)
        s.serverUrl = "https://example.com"
        assertTrue(s.hasServer)
    }

    @Test fun isConfigured_requiresServerAndACredential() {
        assertFalse(s.isConfigured)
        s.serverUrl = "https://example.com"
        assertFalse("URL alone is not configured", s.isConfigured)
        s.deviceToken = "tok"
        assertTrue("URL + device token is configured", s.isConfigured)
    }

    @Test fun isConfigured_acceptsLegacyLogin() {
        s.serverUrl = "https://example.com"
        s.username = "u"; s.password = "p"
        assertTrue("URL + legacy username/password still configures (migration)", s.isConfigured)
    }

    @Test fun authHeader_prefersBearerDeviceToken() {
        assertEquals("blank when standalone", "", s.authHeader)
        // Legacy login → Basic (kept for migration).
        s.username = "u"; s.password = "p"
        assertTrue(s.authHeader.startsWith("Basic "))
        // A device token supersedes it → Bearer.
        s.deviceToken = "abc123"
        assertEquals("Bearer abc123", s.authHeader)
    }

    @Test fun serverUrl_stripsTrailingSlash() {
        s.serverUrl = "https://example.com/"
        assertEquals("https://example.com", s.serverUrl)
    }

    // A wrong server may fail to sync; it must never be able to crash the app. OkHttp's
    // Request.Builder().url() throws on anything scheme-less/unparseable, so the pref is
    // normalized to something callable (or to "" = standalone) on both write and read.
    @Test fun serverUrl_defaultsSchemelessHostsToHttps() {
        s.serverUrl = "drivetime.example.org"
        assertEquals("https://drivetime.example.org", s.serverUrl)
        s.serverUrl = "10.1.1.15:8200"
        assertEquals("https://10.1.1.15:8200", s.serverUrl)
        s.serverUrl = "http://10.1.1.15:8200"          // an explicit scheme is kept
        assertEquals("http://10.1.1.15:8200", s.serverUrl)
    }

    @Test fun serverUrl_rejectsWhatOkHttpCannotCall() {
        s.serverUrl = "not a url"
        assertEquals("unparseable → standalone", "", s.serverUrl)
        assertFalse(s.hasServer)
    }

    @Test fun serverUrl_getterHealsALegacyStoredRawValue() {
        // An older build stored the user's input verbatim; the getter must still never
        // hand a scheme-less value to a Request.Builder.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("drivetime", android.content.Context.MODE_PRIVATE)
            .edit().putString("server_url", "drivetime.example.org").commit()
        assertEquals("https://drivetime.example.org", Settings(ctx).serverUrl)
    }
}
