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
 * [Settings.migrateSecrets] — moving the credentials out of `drivetime.xml` and into
 * `drivetime_secrets.xml` on an install that predates the split (hardening 3.4).
 *
 * This runs exactly once on a phone that is already working, and the failure it must never have
 * is **losing the device token**, which would silently unpair that phone. So the tests are mostly
 * about the unhappy paths: a partially-migrated install, a re-run, and the guarantee that a value
 * already in the secrets file is never overwritten by a stale one left behind in the old file.
 */
@RunWith(RobolectricTestRunner::class)
class SecretsMigrationTest {

    private lateinit var ctx: Context

    private fun main() = ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
    private fun secrets() = ctx.getSharedPreferences(Settings.SECRET_PREFS, Context.MODE_PRIVATE)

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        main().edit().clear().commit()
        secrets().edit().clear().commit()
    }

    /** The pre-split install: every credential sitting in the main prefs file. */
    private fun seedLegacyInstall() {
        main().edit()
            .putString("device_token", "dev-tok-123")
            .putString("username", "lindsay")
            .putString("password", "hunter2")
            .putString("control_token", "ctl-tok")
            .putString("backup_drive_refresh_token", "refresh-abc")
            .putString("backup_drive_access_token", "access-xyz")
            .putLong("backup_drive_token_expiry", 1_700_000_000_000L)
            // …alongside ordinary settings, which must NOT move.
            .putString("server_url", "https://drivetime.jupiterns.org")
            .putInt("interval_sec", 7)
            .commit()
    }

    @Test fun migrate_movesEveryCredentialAndLeavesTheSettings() {
        seedLegacyInstall()

        Settings.migrateSecrets(ctx)

        // Landed in the secrets file, values intact (the Long included — it is not a String).
        assertEquals("dev-tok-123", secrets().getString("device_token", null))
        assertEquals("hunter2", secrets().getString("password", null))
        assertEquals("refresh-abc", secrets().getString("backup_drive_refresh_token", null))
        assertEquals(1_700_000_000_000L, secrets().getLong("backup_drive_token_expiry", 0L))

        // Gone from the file that gets backed up — the entire point of the exercise.
        for (key in Settings.SECRET_KEYS) {
            assertFalse("'$key' is still in the backed-up prefs file", main().contains(key))
        }

        // Ordinary settings stayed put, so a cloud restore still brings a configured phone back.
        assertEquals("https://drivetime.jupiterns.org", main().getString("server_url", null))
        assertEquals(7, main().getInt("interval_sec", 0))

        // And the app reads the same values it did before, through the same accessors.
        val s = Settings(ctx)
        assertEquals("dev-tok-123", s.deviceToken)
        assertEquals("ctl-tok", s.controlToken)
        assertEquals("refresh-abc", s.backupDriveRefreshToken)
        assertEquals("https://drivetime.jupiterns.org", s.serverUrl)
    }

    /** Every launch calls this. It must be free after the first. */
    @Test fun migrate_isIdempotent() {
        seedLegacyInstall()
        Settings.migrateSecrets(ctx)
        Settings.migrateSecrets(ctx)
        Settings.migrateSecrets(ctx)

        assertEquals("dev-tok-123", Settings(ctx).deviceToken)
        assertFalse(main().contains("device_token"))
    }

    /**
     * The dangerous case. A migration that copied but died before clearing leaves the credential
     * in BOTH files; if the user then re-pairs, the secrets file holds the new token and the old
     * file still holds the dead one. The next launch must not resurrect the corpse.
     */
    @Test fun migrate_neverOverwritesANewerSecretWithAStaleLeftover() {
        main().edit().putString("device_token", "OLD-dead-token").commit()
        secrets().edit().putString("device_token", "NEW-live-token").commit()

        Settings.migrateSecrets(ctx)

        assertEquals("NEW-live-token", Settings(ctx).deviceToken)
        assertFalse("the stale copy must still be swept out", main().contains("device_token"))
    }

    /** A fresh install has nothing to move; a post-migration install has nothing left. */
    @Test fun migrate_onACleanInstallDoesNothing() {
        Settings.migrateSecrets(ctx)
        assertTrue(Settings(ctx).deviceToken.isEmpty())
        assertFalse(secrets().contains("device_token"))
    }

    /** A half-populated legacy install (paired, but Drive never connected) migrates what it has
     *  and doesn't invent the rest. */
    @Test fun migrate_handlesAPartiallyPopulatedInstall() {
        main().edit().putString("device_token", "only-this-one").commit()

        Settings.migrateSecrets(ctx)

        assertEquals("only-this-one", Settings(ctx).deviceToken)
        assertTrue(Settings(ctx).backupDriveRefreshToken.isEmpty())
        assertFalse(secrets().contains("backup_drive_refresh_token"))
    }

    /** Settings must be the ONLY thing that knows where a secret lives — if a key is added to
     *  SECRET_KEYS the accessor has to move with it, and vice versa. This catches the half-done
     *  version: a key declared secret but still written to the backed-up file. */
    @Test fun everyDeclaredSecretIsActuallyWrittenToTheSecretsFile() {
        val s = Settings(ctx)
        s.deviceToken = "a"
        s.username = "b"
        s.password = "c"
        s.controlToken = "d"
        s.backupDriveRefreshToken = "e"
        s.backupDriveAccessToken = "f"
        s.backupDriveTokenExpiry = 99L

        for (key in Settings.SECRET_KEYS) {
            assertTrue("'$key' should be in the secrets file", secrets().contains(key))
            assertFalse("'$key' leaked into the backed-up prefs file", main().contains(key))
        }
    }
}
