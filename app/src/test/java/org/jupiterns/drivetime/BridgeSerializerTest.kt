package org.jupiterns.drivetime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `DrivetimeNative` bridge payload — **contract #4**. The bundled SPA reads these keys by name,
 * and so does a WebView still running an older cached snapshot.
 *
 * The failure mode is what makes this worth a test. Drop a key, or rename one, and nothing throws
 * and nothing logs: `getSettings()` still returns valid JSON, the Settings tab still renders, and a
 * row just quietly shows its default forever. A test that merely asserted "the bridge returns
 * JSON" would pass through all of it.
 */
@RunWith(RobolectricTestRunner::class)
class BridgeSerializerTest {

    private lateinit var ctx: Context
    private lateinit var s: Settings

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(Settings.SECRET_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        s = Settings(ctx)
    }

    @Test fun theUpdatesSupportedKeyIsPresentAndFalse() {
        // The trap, and the reason this file is testable at all now.
        //
        // The self-updater is deleted (hardening 3.1 — Play's Device and Network Abuse policy
        // forbids it), so this reports a hardcoded `false` and the SPA hides the whole affordance.
        // But the SPA tests `updates_supported !== false`, so an ABSENT key reads as *supported* —
        // meaning "tidy up that pointless constant" silently puts a dead Check-for-updates button
        // back in front of every user. It is the most deletable-looking line in the serializer and
        // the one that must never be deleted.
        val json = BridgeSerializer.settings(s)

        assertTrue("the key must be PRESENT, not merely falsy", json.has("updates_supported"))
        assertFalse(json.getBoolean("updates_supported"))
    }

    @Test fun theDeviceTokenIsNeverHandedToTheWebView() {
        // AUTH.md: pairing is a native-only flow. The SPA is told *whether* a token exists, never
        // what it is — so a compromised or stale WebView snapshot cannot exfiltrate the credential
        // that identifies this phone to the server.
        s.deviceToken = "dev-tok-super-secret"

        val json = BridgeSerializer.settings(s)

        assertTrue(json.getBoolean("deviceTokenSet"))
        assertFalse("the token itself must not be in the payload", json.toString().contains("dev-tok-super-secret"))
    }

    @Test fun everyKeyTheSpaReadsIsStillThere() {
        // A blunt instrument on purpose. Each of these is a settings row, a toggle or a derived
        // flag the SPA looks up by name; losing one is invisible at runtime, so it is made visible
        // here. Adding a key is free — removing one from this list should require saying out loud
        // that the SPA no longer reads it.
        val expected = setOf(
            "serverUrl", "hasServer", "server_enabled", "isConfigured", "standalone", "deviceTokenSet",
            "trackingMode",
            "interval_sec", "idle_interval_sec", "light_interval_sec",
            "upload_interval_sec", "driving_upload_interval_sec", "stationary_stop_min",
            "drive_by_speed", "motion_onset", "auto_trip", "alerts_enabled", "notif_driving_only",
            // the OS-notification half of every kind…
            "notify_drive_complete", "notify_gas_stop", "notify_digest", "notify_tracking_health",
            "notify_backup_health", "notify_apply_usual", "notify_coverage_gap", "notify_auth_failed",
            // …and the in-app half. NOTIFICATIONS.md's contract is that every kind has both.
            "notify_drive_complete_inapp", "notify_gas_stop_inapp", "notify_apply_usual_inapp",
            "notify_digest_inapp", "notify_check_engine_inapp", "notify_tracking_health_inapp",
            "notify_coverage_gap_inapp", "notify_backup_health_inapp", "notify_auth_failed_inapp",
            "digest_day", "digest_time", "control_token",
            "updates_enabled", "updates_supported",
            "carBtName", "carBtMac", "obdName", "obdMac",
            "versionName", "versionCode",
        )

        val actual = BridgeSerializer.settings(s).keys().asSequence().toSet()

        assertEquals("keys the SPA reads that the bridge stopped sending", emptySet<String>(), expected - actual)
    }

    @Test fun everyNotificationKindIsReportedWithBothOfItsToggles() {
        // NOTIFICATIONS.md's contract: every kind has an OS toggle and an in-app toggle. Adding a
        // kind and forgetting to surface it here would leave the user a notification they cannot
        // turn off from inside the app — so derive the expectation from the kinds themselves rather
        // than restating the list.
        val json = BridgeSerializer.settings(s)
        for (kind in Notify.KINDS) {
            // Two kinds don't take the default key, and both are historical rather than principled:
            // check_engine is gated by the shared `alerts_enabled` master switch instead of a
            // per-kind toggle, and weekly_digest's prefs key predates the kind being named
            // "weekly_digest" (it is just `notify_digest`). Spelled out rather than special-cased
            // away, so the next kind added doesn't quietly inherit a wrong assumption.
            val osKey = when (kind) {
                Notify.KIND_CHECK_ENGINE -> "alerts_enabled"
                Notify.KIND_WEEKLY_DIGEST -> "notify_digest"
                else -> "notify_$kind"
            }
            val inAppKey =
                if (kind == Notify.KIND_WEEKLY_DIGEST) "notify_digest_inapp" else "notify_${kind}_inapp"

            assertTrue("$kind has no OS toggle in the bridge payload", json.has(osKey))
            assertTrue("$kind has no in-app toggle in the bridge payload", json.has(inAppKey))
        }
    }

    @Test fun standaloneIsTheInverseOfConfigured() {
        // The SPA branches its entire empty-state and sync UI on this. A phone with no server is
        // not a broken phone — it is the normal case (STANDALONE.md).
        assertTrue("a fresh install is standalone", BridgeSerializer.settings(s).getBoolean("standalone"))
        assertFalse(BridgeSerializer.settings(s).getBoolean("isConfigured"))

        s.serverUrl = "https://drivetime.jupiterns.org"
        s.serverEnabled = true
        s.deviceToken = "tok"

        val json = BridgeSerializer.settings(s)
        assertTrue(json.getBoolean("isConfigured"))
        assertFalse(json.getBoolean("standalone"))
    }

    @Test fun theEditableTrackerValuesAreTheOnesActuallyStored() {
        s.intervalSec = 3
        s.idleIntervalSec = 25
        s.trackingMode = Settings.MODE_LIGHT
        s.driveBySpeed = false

        val json = BridgeSerializer.settings(s)

        assertEquals(3, json.getInt("interval_sec"))
        assertEquals(25, json.getInt("idle_interval_sec"))
        assertEquals(Settings.MODE_LIGHT, json.getString("trackingMode"))
        assertFalse(json.getBoolean("drive_by_speed"))
    }
}
