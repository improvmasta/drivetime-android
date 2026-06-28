package org.jupiterns.drivetime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routine API is a contract: every key we advertise in AUTOMATION.md must be
 * in [Control.SET_KEYS], or a routine sending it will get a silent "unknown key"
 * rejection. Keep this list in sync with the docs.
 */
class ControlKeysTest {

    @Test fun setKeys_containsTheDocumentedRoutineParameters() {
        val expected = setOf(
            "mode",
            "interval_sec", "idle_interval_sec", "light_interval_sec",
            "upload_interval_sec", "driving_upload_interval_sec",
            "drive_by_speed", "stationary_stop_min",
            "auto_trip", "alerts_enabled",
        )
        for (k in expected) {
            assertTrue("SET_KEYS missing '$k'", k in Control.SET_KEYS)
        }
    }
}
