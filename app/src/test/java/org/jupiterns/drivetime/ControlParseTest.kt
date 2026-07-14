package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lock down the leniency of the routine-input parsers — every supported alias is
 * an interface contract for Tasker / MacroDroid / HA recipes, and changing them
 * silently would break those recipes.
 */
class ControlParseTest {

    @Test fun parseMode_canonicalValues() {
        assertEquals(Settings.MODE_AUTO, ControlParse.parseMode("auto"))
        assertEquals(Settings.MODE_DRIVING, ControlParse.parseMode("driving"))
        assertEquals(Settings.MODE_LIGHT, ControlParse.parseMode("light"))
        assertEquals(Settings.MODE_OFF, ControlParse.parseMode("off"))
    }

    @Test fun parseMode_isCaseInsensitive_andEcoIsAliasOfLight() {
        assertEquals(Settings.MODE_AUTO, ControlParse.parseMode("AUTO"))
        assertEquals(Settings.MODE_DRIVING, ControlParse.parseMode("Driving"))
        assertEquals(Settings.MODE_LIGHT, ControlParse.parseMode("ECO"))
        assertEquals(Settings.MODE_LIGHT, ControlParse.parseMode("eco"))
    }

    @Test fun parseMode_trimsWhitespace() {
        assertEquals(Settings.MODE_AUTO, ControlParse.parseMode("  auto  "))
    }

    @Test fun parseMode_rejectsGarbage() {
        assertNull(ControlParse.parseMode("on"))
        assertNull(ControlParse.parseMode(""))
        assertNull(ControlParse.parseMode("drive"))
    }

    @Test fun parseBool_acceptsRoutineSpellings() {
        for (truthy in listOf("1", "true", "TRUE", "yes", "YES", "on", " on ")) {
            assertEquals(truthy, true, ControlParse.parseBool(truthy))
        }
        for (falsy in listOf("0", "false", "no", "off", "OFF")) {
            assertEquals(falsy, false, ControlParse.parseBool(falsy))
        }
    }

    @Test fun parseBool_rejectsBareWords() {
        assertNull(ControlParse.parseBool("enabled"))
        assertNull(ControlParse.parseBool("2"))
        assertNull(ControlParse.parseBool(""))
    }

    @Test fun parsePosInt_requiresPositiveByDefault() {
        assertEquals(3, ControlParse.parsePosInt("3"))
        assertEquals(45, ControlParse.parsePosInt(" 45 "))
        assertNull(ControlParse.parsePosInt("0"))
        assertNull(ControlParse.parsePosInt("-1"))
        assertNull(ControlParse.parsePosInt("foo"))
    }

    @Test fun parsePosInt_allowZeroForNullableMinutes() {
        assertEquals(0, ControlParse.parsePosInt("0", allowZero = true))
        assertEquals(5, ControlParse.parsePosInt("5", allowZero = true))
        assertNull(ControlParse.parsePosInt("-2", allowZero = true))
    }

    // --- clampSetting: "positive" is not the same as "usable" ---

    @Test fun clampSetting_holdsCadencesToAnHour() {
        // The bug this exists for: a positive int that parses fine and ends drive detection
        // forever, because a fix every 63 years is indistinguishable from a dead logger.
        assertEquals(3600, ControlParse.clampSetting("light_interval_sec", 2_000_000_000))
        assertEquals(3600, ControlParse.clampSetting("interval_sec", Int.MAX_VALUE))
        assertEquals(1, ControlParse.clampSetting("interval_sec", 0))
        assertEquals(1, ControlParse.clampSetting("idle_interval_sec", -5))
    }

    @Test fun clampSetting_leavesRealValuesAlone() {
        for ((key, v) in listOf(
            "interval_sec" to 3, "idle_interval_sec" to 20, "light_interval_sec" to 60,
            "upload_interval_sec" to 45, "driving_upload_interval_sec" to 10,
            "onset_probe_interval_sec" to 3, "onset_probe_window_sec" to 25,
            "onset_speed_mps" to 4, "onset_accel_rms" to 250, "stationary_stop_min" to 5,
        )) {
            assertEquals("$key default must survive its own clamp", v, ControlParse.clampSetting(key, v))
        }
    }

    @Test fun clampSetting_keepsTheZerosThatMeanDisabled() {
        assertEquals("0 = no stationary backstop", 0, ControlParse.clampSetting("stationary_stop_min", 0))
        assertEquals("0 = no on-foot discriminator", 0, ControlParse.clampSetting("onset_accel_rms", 0))
    }

    @Test fun clampSetting_passesUnknownKeysThrough() {
        // Every SET key routes through this; one without bounds must not be mangled.
        assertEquals(999, ControlParse.clampSetting("drive_by_speed", 999))
        assertNull(ControlParse.boundsOf("mode"))
    }
}
