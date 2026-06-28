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
}
