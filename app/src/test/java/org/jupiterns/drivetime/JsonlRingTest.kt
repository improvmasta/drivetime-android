package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsonlRing] collapsed four near-identical buffer classes into one (HARDENING.md 5.3). Exactly
 * one behavioural distinction between them survived that collapse as a *parameter*, and it is the
 * one a future tidy-up is most likely to "fix" into a bug:
 *
 *   **the drain cursor is `>` for fixes and `>=` for everything else.**
 *
 * Neither is an accident, and they cannot be unified:
 *
 *  - **Fixes: strictly-after.** One fix per second. Re-delivering the boundary fix is a duplicate.
 *  - **Markers / vehicles / battery: at-or-after.** Two of these CAN share a second, so a cursor
 *    that skipped the boundary would drop one **forever** — while re-delivery costs nothing,
 *    because every one of them is written idempotently by the SPA (on the marker's uuid, on the
 *    drive+vehicle, on the drive).
 *
 * So the failure modes are asymmetric: get it wrong on fixes and you get duplicate GPS points;
 * get it wrong on the other three and you silently lose a marker the driver pressed a button to
 * make. Both are unrecoverable after the fact, and neither shows up as an error. Hence this test.
 */
class JsonlRingTest {

    private fun line(ts: Long, id: String = "x") = """{"id":"$id","ts":$ts,"lat":1,"lon":2}"""

    @Test fun fixes_drainStrictlyAfterTheCursor_soTheBoundaryFixIsNotRedelivered() {
        val lines = listOf(line(100), line(101), line(102))
        val out = JsonlRing.selectSince(lines, 101.0, inclusive = false)
        assertTrue("102 is past the cursor", out.contains("\"ts\":102"))
        assertTrue("the cursor's own fix must NOT come back — that is a duplicate",
            !out.contains("\"ts\":101"))
        assertTrue("nothing older than the cursor", !out.contains("\"ts\":100"))
    }

    @Test fun markers_drainAtOrAfterTheCursor_soASharedSecondIsNeverLost() {
        // The case the whole distinction exists for: two marks stamped in the same second. A
        // strictly-after cursor parked on 101 would hand back neither, and they would be gone.
        val lines = listOf(line(100, "a"), line(101, "b"), line(101, "c"), line(102, "d"))
        val out = JsonlRing.selectSince(lines, 101.0, inclusive = true)
        assertTrue("the mark ON the cursor survives", out.contains("\"id\":\"b\""))
        assertTrue("...and so does the one sharing its second", out.contains("\"id\":\"c\""))
        assertTrue(out.contains("\"id\":\"d\""))
        assertTrue("still nothing older than the cursor", !out.contains("\"id\":\"a\""))
    }

    /** The four rings are wired to the right side of that split. This is the actual regression
     *  guard — the helper above only proves the two modes exist. */
    @Test fun eachBufferIsWiredToTheCursorSemanticsItNeeds() {
        val shared = listOf(line(50, "old"), line(100, "boundary"), line(150, "new"))

        val fixes = WebFixBuffer.selectSince(shared, 100.0)
        assertTrue("fixes must be strictly-after", !fixes.contains("boundary"))
        assertTrue(fixes.contains("new"))

        for ((name, out) in listOf(
            "markers" to WebMarkerBuffer.selectSince(shared, 100.0),
            "vehicles" to WebVehicleBuffer.selectSince(shared, 100.0),
        )) {
            assertTrue("$name must be at-or-after, or a shared second is lost forever",
                out.contains("boundary"))
            assertTrue(out.contains("new"))
            assertTrue("$name still drops what is behind the cursor", !out.contains("old"))
        }
    }

    @Test fun trimDropsTheOldest_keepsOrder_andIgnoresBlankLines() {
        val lines = listOf(line(1), "", line(2), line(3), "")
        val kept = JsonlRing.trimOldest(lines, 2)
        assertEquals(2, kept.size)
        assertEquals(listOf(line(2), line(3)), kept)   // oldest dropped, order preserved

        // Under the cap, blanks are still swept — a trailing newline must not count as a line
        // toward a bound that decides when real data is discarded.
        assertEquals(listOf(line(1), line(2), line(3)), JsonlRing.trimOldest(lines, 10))
    }

    @Test fun aGarbledLineIsSkipped_ratherThanTakingTheWholeDrainDownWithIt() {
        val lines = listOf("not json at all", line(100), """{"no":"ts"}""", line(200))
        val out = JsonlRing.selectSince(lines, 0.0, inclusive = true)
        assertTrue(out.contains("\"ts\":100"))
        assertTrue(out.contains("\"ts\":200"))
        assertTrue("a line with no ts sorts below any cursor and drops out", !out.contains("\"no\""))
        assertEquals("tsOf on garbage is null, not a throw", null, JsonlRing.tsOf("not json"))
    }
}
