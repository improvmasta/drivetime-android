package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The self-updater's decision logic — the part that decides whether to bother the user —
 * kept honest so a malformed manifest or an equal/older version never nags. JSON parse
 * needs a real org.json, hence Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class UpdaterTest {

    @Test fun parsesFullManifest() {
        val r = Updater.parse("""{"versionCode":42,"versionName":"0.1.42","apk":"drivetime.apk","notes":"fixes"}""")!!
        assertEquals(42, r.versionCode)
        assertEquals("0.1.42", r.versionName)
        assertEquals("drivetime.apk", r.apk)
        assertEquals("fixes", r.notes)
    }

    @Test fun fillsSensibleDefaults() {
        val r = Updater.parse("""{"versionCode":7}""")!!
        assertEquals("7", r.versionName)     // falls back to the code
        assertEquals("drivetime.apk", r.apk) // default filename
        assertNull(r.notes)                  // blank notes → null (no empty paragraph)
    }

    @Test fun rejectsManifestWithoutVersionCode() {
        assertNull(Updater.parse("""{"versionName":"1.0"}"""))
    }

    @Test fun rejectsGarbage() {
        assertNull(Updater.parse("not json"))
        assertNull(Updater.parse(""))
    }

    @Test fun offersOnlyStrictlyNewerBuilds() {
        val r = Updater.Release(10, "0.1.10", "drivetime.apk", null)
        assertTrue(Updater.isNewer(9, r))    // newer → offer
        assertFalse(Updater.isNewer(10, r))  // same → no nag
        assertFalse(Updater.isNewer(11, r))  // installed ahead (local dev) → no downgrade
    }
}
