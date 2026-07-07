package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the native→SPA fix buffer's selection + trim (STANDALONE.md A2). The
 * file I/O is a thin wrapper; the logic that matters — cursor-based selection and bounded
 * trimming — is pure and checked here. `selectSince` output is what the SPA parses and feeds
 * to appendFixes, so it must be a valid JSON array of the fixes newer than the cursor.
 */
class WebFixBufferTest {

    private val lines = listOf(
        """{"ts":100,"lat":40.1,"lon":-83.2,"speed_mph":12.0}""",
        """{"ts":101,"lat":40.11,"lon":-83.21,"speed_mph":24.0}""",
        """{"ts":102,"lat":40.12,"lon":-83.22,"speed_mph":30.0}""",
    )

    @Test fun tsOfParsesTimestamp() {
        assertEquals(100L, WebFixBuffer.tsOf(lines[0]))
        assertEquals(101L, WebFixBuffer.tsOf("""{"lat":1,"ts":101,"lon":2}"""))   // order-independent
        assertNull(WebFixBuffer.tsOf("""{"lat":1,"lon":2}"""))
        assertNull(WebFixBuffer.tsOf("garbage"))
    }

    @Test fun selectSinceReturnsOnlyNewerAsJsonArray() {
        val json = WebFixBuffer.selectSince(lines, 100.0)
        // only ts 101 + 102, and it's a valid array wrapping the raw objects
        assertTrue(json.startsWith("[") && json.endsWith("]"))
        assertTrue(json.contains("\"ts\":101"))
        assertTrue(json.contains("\"ts\":102"))
        assertTrue(!json.contains("\"ts\":100"))
    }

    @Test fun selectSinceZeroReturnsAll() {
        assertEquals("[" + lines.joinToString(",") + "]", WebFixBuffer.selectSince(lines, 0.0))
    }

    @Test fun selectSincePastEndIsEmptyArray() {
        assertEquals("[]", WebFixBuffer.selectSince(lines, 999.0))
    }

    @Test fun selectSinceSkipsBlankAndGarbledLines() {
        val withNoise = listOf(lines[0], "", "not json", lines[1])
        val json = WebFixBuffer.selectSince(withNoise, 0.0)
        assertTrue(json.contains("\"ts\":100") && json.contains("\"ts\":101"))
        assertTrue(!json.contains("not json"))
    }

    @Test fun trimOldestKeepsNewest() {
        assertEquals(lines.subList(1, 3), WebFixBuffer.trimOldest(lines, 2))
        assertEquals(lines, WebFixBuffer.trimOldest(lines, 5))     // under cap → unchanged
        assertEquals(lines, WebFixBuffer.trimOldest(lines + "", 3)) // blanks dropped, order kept
    }
}
