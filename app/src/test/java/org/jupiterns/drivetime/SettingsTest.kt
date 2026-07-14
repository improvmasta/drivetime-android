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
    private lateinit var ctx: android.content.Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Wipe between runs since Robolectric reuses prefs in-process.
        ctx.getSharedPreferences("drivetime", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        s = Settings(ctx)
    }

    private fun prefs() = ctx.getSharedPreferences("drivetime", android.content.Context.MODE_PRIVATE)

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

    // ---- per-vehicle OBD adapters (multi-car) --------------------------------------------
    //
    // The dongle is bolted into ONE car. Once there are two cars, "which adapter do I dial?"
    // has a right answer and several wrong ones, and the wrong ones are quiet: redialling an
    // adapter that is sitting in the other car looks exactly like a dongle that won't connect.

    private val KIA_BT = "AA:BB:CC:11:22:33"
    private val VAN_BT = "DD:EE:FF:44:55:66"
    private val DONGLE = "11:22:33:AA:BB:CC"

    @Test fun obdTarget_emptyRegistryFallsBackToTheLegacySetting() {
        // An install that configured its dongle before vehicles existed must keep reading it.
        s.obdMac = DONGLE
        assertEquals(DONGLE, s.obdTarget(null))
        assertEquals(DONGLE, s.obdTarget(KIA_BT))
    }

    @Test fun obdTarget_dialsTheAdapterOfTheCarWeAreIn() {
        val other = "99:88:77:66:55:44"
        s.vehicleObd = listOf(
            Settings.ObdBinding(DONGLE, "OBDII", listOf(KIA_BT)),
            Settings.ObdBinding(other, "Spare", listOf(VAN_BT)),
        )
        assertEquals("the Kia's Bluetooth ⇒ the Kia's adapter", DONGLE, s.obdTarget(KIA_BT))
        assertEquals("the van's Bluetooth ⇒ the van's adapter", other, s.obdTarget(VAN_BT))
    }

    @Test fun obdTarget_aCarWithNoAdapterIsNotProbedAtAll() {
        // The van is registered and has no dongle. Knowing that is the whole point of listing
        // adapter-less cars: without it we'd redial the Kia's adapter for the entire drive.
        s.obdMac = DONGLE // legacy value present, and must NOT resurrect itself here
        s.vehicleObd = listOf(
            Settings.ObdBinding(DONGLE, "OBDII", listOf(KIA_BT)),
            Settings.ObdBinding("", "", listOf(VAN_BT)),
        )
        assertEquals("driving the van ⇒ nothing to dial", "", s.obdTarget(VAN_BT))
        assertEquals("driving the Kia ⇒ its adapter", DONGLE, s.obdTarget(KIA_BT))
    }

    @Test fun obdTarget_noCarIdentifiedGuessesTheOnlyAdapter() {
        s.vehicleObd = listOf(
            Settings.ObdBinding("", "", listOf(VAN_BT)),
            Settings.ObdBinding(DONGLE, "OBDII", listOf(KIA_BT)),
        )
        assertEquals("no BT connected ⇒ try the one adapter we know", DONGLE, s.obdTarget(null))
    }

    @Test fun obdTarget_isCaseInsensitiveOnTheMac() {
        s.vehicleObd = listOf(Settings.ObdBinding(DONGLE, "OBDII", listOf(KIA_BT)))
        assertEquals(DONGLE, s.obdTarget(KIA_BT.lowercase()))
    }

    @Test fun vehicleObd_roundTripsAndDropsEmptyEntries() {
        s.vehicleObd = listOf(
            Settings.ObdBinding(DONGLE, "OBD|II", listOf(KIA_BT, VAN_BT)),
            Settings.ObdBinding("", "", emptyList()), // no adapter AND no Bluetooth ⇒ tells us nothing
        )
        val back = s.vehicleObd
        assertEquals("the empty entry is dropped", 1, back.size)
        assertEquals(DONGLE, back[0].mac)
        assertEquals("a | in the name can't corrupt the record", "OBD II", back[0].name)
        assertEquals(listOf(KIA_BT, VAN_BT), back[0].carBtMacs)
    }

    // --- cadence bounds: the logger must never be handed an interval it can't act on ---

    @Test fun cadences_clampOnWrite() {
        s.intervalSec = 0                       // would spin GPS at max rate
        s.lightIntervalSec = 2_000_000_000      // a fix every 63 years = drive detection is over
        assertEquals(1, s.intervalSec)
        assertEquals(3600, s.lightIntervalSec)
        assertEquals("the clamped value is what's stored, not just what's read",
            3600, prefs().getInt("light_interval_sec", -1))
    }

    @Test fun cadences_clampOnRead_soAPoisonedPrefCantSurviveAnAppUpdate() {
        // A build before the clamp could persist this. The tester updates the app; nothing
        // rewrites the pref — so if only the setter clamped, their logger stays dead.
        prefs().edit().putInt("light_interval_sec", 2_000_000_000).putInt("interval_sec", 0).commit()
        assertEquals(3600, s.lightIntervalSec)
        assertEquals(1, s.intervalSec)
    }

    @Test fun stationaryStopMin_keepsZeroMeaningDisabled() {
        s.stationaryStopMin = 0
        assertEquals("0 disables the backstop — clamping must not turn it on", 0, s.stationaryStopMin)
    }

    @Test fun settingsImport_clampsAbsurdCadences() {
        // An imported file is untrusted input exactly like a routine SET.
        val applied = SettingsExport.fromJson(
            ctx, s, """{"interval_sec":0,"light_interval_sec":2000000000,"idle_interval_sec":20}"""
        )
        assertEquals("all three keys recognised", 3, applied)
        assertEquals(1, s.intervalSec)
        assertEquals(3600, s.lightIntervalSec)
        assertEquals("a sane value imports untouched", 20, s.idleIntervalSec)
    }

    @Test fun healthNotifications_defaultOn_andTheNagsDefaultOff() {
        // The default-OFF rule is about nags. These two are error reports: a tracker the OEM
        // killed, and a backup that has silently not run for a month. Defaulting them off makes
        // the app complicit in its own worst failures (NOTIFICATIONS.md #4).
        assertTrue("kill warning", s.notifyTrackingHealth)
        assertTrue("failing backups", s.notifyBackupHealth)
        assertFalse("drive-complete prompt is a nag", s.notifyDriveComplete)
        assertFalse("gas-stop prompt is a nag", s.notifyGasStop)
        assertFalse("weekly digest is a nag", s.notifyDigest)
        assertTrue(Notify.enabledFor(s, Notify.KIND_BACKUP_HEALTH))
        s.notifyBackupHealth = false
        assertFalse(Notify.enabledFor(s, Notify.KIND_BACKUP_HEALTH))
    }

    @Test fun backupFailStreak_countsUp_andNeverGoesNegative() {
        assertEquals(0, s.backupFailStreak)
        s.backupFailStreak = s.backupFailStreak + 1
        s.backupFailStreak = s.backupFailStreak + 1
        assertEquals(2, s.backupFailStreak)
        s.backupFailStreak = -5      // a corrupt/poisoned pref can't push the streak below zero
        assertEquals(0, s.backupFailStreak)
    }

    @Test fun routineSet_clampsTheAbsurdAndStillRejectsTheInvalid() {
        // The two halves of the SET contract. An out-of-range-but-valid int is clamped and
        // applied...
        assertTrue(Control.set(ctx, "light_interval_sec", "2000000000", "test"))
        assertEquals(3600, s.lightIntervalSec)
        // ...while 0 / negative are still rejected outright by parsePosInt and never reach the
        // clamp, so a bad SET leaves the cadence exactly as it was. (The import path has no such
        // parse gate — JSONObject.optInt hands 0 straight through — which is why Settings clamps.)
        assertFalse(Control.set(ctx, "interval_sec", "0", "test"))
        assertFalse(Control.set(ctx, "interval_sec", "-1", "test"))
        assertEquals("a rejected SET changes nothing", 3, s.intervalSec)
    }
}
