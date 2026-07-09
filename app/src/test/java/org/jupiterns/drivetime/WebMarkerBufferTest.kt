package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the native→SPA marker buffer (MARKERS.md §6). A marker is sparse and
 * precious — the driver pressed a button to make it — so this buffer's two properties are
 * load-bearing in a way [WebFixBuffer]'s are not:
 *
 *  1. **The cursor is at-or-after, not strictly-after.** Two marks can share a second (a
 *     double-tap, or a mark right after another). A `>` cursor would drop one forever.
 *  2. **Re-delivery is safe, loss is not.** A crash between the append and the SPA's drain
 *     must re-deliver the marker; the SPA ignores an `id` it already holds, so duplication
 *     is impossible but a skipped marker is unrecoverable.
 */
class WebMarkerBufferTest {

    private val lines = listOf(
        """{"id":"a","ts":100,"lat":40.1,"lon":-83.2,"origin":"live"}""",
        """{"id":"b","ts":101,"lat":40.11,"lon":-83.21,"origin":"live"}""",
        """{"id":"c","ts":101,"lat":40.12,"lon":-83.22,"origin":"live"}""",  // same second as b
    )

    @Test fun tsOfParsesTimestamp() {
        assertEquals(100L, WebMarkerBuffer.tsOf(lines[0]))
        assertEquals(101L, WebMarkerBuffer.tsOf("""{"lat":1,"ts":101,"lon":2}"""))  // order-independent
        assertNull(WebMarkerBuffer.tsOf("""{"lat":1,"lon":2}"""))
        assertNull(WebMarkerBuffer.tsOf("garbage"))
    }

    @Test fun selectSinceIsAtOrAfter_soTwoMarksInOneSecondBothSurvive() {
        // A `>` cursor parked at 101 (the newest ts) would lose whichever of b/c drained second.
        val json = WebMarkerBuffer.selectSince(lines, 101.0)
        assertTrue(json.contains("\"id\":\"b\""))
        assertTrue(json.contains("\"id\":\"c\""))
        assertTrue(!json.contains("\"id\":\"a\""))
    }

    @Test fun selectSinceReturnsAValidJsonArray() {
        val json = WebMarkerBuffer.selectSince(lines, 0.0)
        assertEquals("[" + lines.joinToString(",") + "]", json)
        assertTrue(json.startsWith("[") && json.endsWith("]"))
    }

    @Test fun rePullReDeliversRatherThanSkipping() {
        // The crash-between-append-and-drain case: the SPA advanced no cursor, so it must see
        // the marker again. (It dedups on `id`, which native minted.)
        val first = WebMarkerBuffer.selectSince(lines, 100.0)
        val again = WebMarkerBuffer.selectSince(lines, 100.0)
        assertEquals(first, again)
        assertTrue(first.contains("\"id\":\"a\""))
    }

    @Test fun selectSincePastEndIsEmptyArray() {
        assertEquals("[]", WebMarkerBuffer.selectSince(lines, 999.0))
    }

    @Test fun selectSinceSkipsBlankAndGarbledLines() {
        val withNoise = listOf(lines[0], "", "not json", lines[1])
        val json = WebMarkerBuffer.selectSince(withNoise, 0.0)
        assertTrue(json.contains("\"id\":\"a\"") && json.contains("\"id\":\"b\""))
        assertTrue(!json.contains("not json"))
    }

    @Test fun trimOldestKeepsNewest() {
        assertEquals(lines.subList(1, 3), WebMarkerBuffer.trimOldest(lines, 2))
        assertEquals(lines, WebMarkerBuffer.trimOldest(lines, 5))      // under cap → unchanged
        assertEquals(lines, WebMarkerBuffer.trimOldest(lines + "", 3)) // blanks dropped, order kept
    }
}
