package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the liveness ledger's one piece of real judgement: [Health.classify].
 *
 * Everything else about the heartbeat is bookkeeping. This function is where the app decides
 * whether to tell the user their phone killed the tracker — and the entire reason [Health] exists
 * is that the app used to get that call wrong, loudly, every time the car sat parked. So the cases
 * that matter here are the ones where a false accusation would be easy to make.
 */
class HealthTest {

    @Test fun aHardKillWithNoRebootIsTheOemBatteryManager() {
        // No onDestroy at all: the process was destroyed outright. This is the one that loses
        // drives, and the only one worth sending the user to the battery settings for.
        assertEquals("killed", Health.classify(hadDestroy = false, endReason = "", rebooted = false))
    }

    @Test fun anOrdinaryRebootIsNotAKill() {
        // The trap: a clean shutdown DOES run onDestroy with logging still enabled, which is
        // indistinguishable from a low-memory kill. Without the reboot check, every reboot would
        // be reported as "your phone killed the tracker" — the exact false alarm we're here to
        // stop telling. Both the graceful and abrupt shutdown paths must read as a reboot.
        assertEquals("reboot", Health.classify(hadDestroy = true, endReason = "system", rebooted = true))
        assertEquals("reboot", Health.classify(hadDestroy = false, endReason = "", rebooted = true))
    }

    @Test fun turningTrackingOffOutranksAReboot() {
        // Switch it off on Friday, reboot on Saturday, switch it back on Monday: the tracker was
        // off because you turned it off. Reporting that as a reboot (or, worse, a fault) would be
        // the app blaming the phone for the user's own decision.
        assertEquals("stop", Health.classify(hadDestroy = true, endReason = "stop", rebooted = true))
        assertEquals("stop", Health.classify(hadDestroy = true, endReason = "stop", rebooted = false))
    }

    @Test fun theOsStoppingTheServiceIsAFault() {
        // onDestroy ran while logging was still meant to be on, and the phone never rebooted:
        // the OS took the service away from us. Drives were lost; say so.
        assertEquals("system", Health.classify(hadDestroy = true, endReason = "system", rebooted = false))
    }

    @Test fun onlyKilledAndSystemAreFaults() {
        // The fault/no-fault split is what the SPA's notification centre gates on, so pin it here
        // rather than leaving it implicit in the row writer.
        val fault = { r: String -> r == "killed" || r == "system" }
        assertTrue(fault(Health.classify(false, "", false)))          // killed
        assertTrue(fault(Health.classify(true, "system", false)))     // system
        assertTrue(!fault(Health.classify(true, "stop", false)))      // the user turned it off
        assertTrue(!fault(Health.classify(false, "", true)))          // the phone rebooted
    }

    // ---- the buffer's cursor, which follows WebMarkerBuffer's at-or-after contract ----

    private val lines = listOf(
        """{"kind":"cond","ts":100,"loc_on":true,"perms":true,"saver":false}""",
        """{"kind":"down","ts":200,"from":100,"to":200,"reason":"killed","fault":true}""",
        """{"kind":"cond","ts":200,"loc_on":false,"perms":true,"saver":false}""",  // same second
    )

    @Test fun tsOfParsesTimestamp() {
        assertEquals(100L, Health.tsOf(lines[0]))
        assertEquals(200L, Health.tsOf(lines[1]))
        assertNull(Health.tsOf("garbage"))
    }

    @Test fun selectSinceIsAtOrAfter_soTwoRowsInOneSecondBothSurvive() {
        // A `down` row and the `cond` row that follows it land in the same second (startLife
        // writes both). A `>` cursor would drop whichever drained second — and the dropped one
        // could be the downtime record itself. The SPA keys rows `kind|ts`, so re-delivery is
        // a no-op but a skipped row is unrecoverable.
        val json = Health.selectSince(lines, 200.0)
        assertTrue(json.contains("\"kind\":\"down\""))
        assertTrue(json.contains("\"kind\":\"cond\",\"ts\":200"))
        assertTrue(!json.contains("\"ts\":100,\"loc_on\""))
    }

    @Test fun selectSinceReturnsAValidJsonArray() {
        assertEquals("[" + lines.joinToString(",") + "]", Health.selectSince(lines, 0.0))
        assertEquals("[]", Health.selectSince(lines, 999.0))
    }

    @Test fun selectSinceSkipsBlankAndGarbledLines() {
        val json = Health.selectSince(listOf(lines[0], "", "not json", lines[1]), 0.0)
        assertTrue(json.contains("\"kind\":\"cond\"") && json.contains("\"kind\":\"down\""))
        assertTrue(!json.contains("not json"))
    }

    @Test fun trimOldestKeepsNewest() {
        assertEquals(lines.subList(1, 3), Health.trimOldest(lines, 2))
        assertEquals(lines, Health.trimOldest(lines, 5))
    }
}
