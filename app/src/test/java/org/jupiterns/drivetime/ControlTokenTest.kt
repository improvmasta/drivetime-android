package org.jupiterns.drivetime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control-token gate on the exported surface (hardening 3.2).
 *
 * [Control.externalAllowed] is the pure half of [Control.applyExternal] — the decision, with
 * no Context — so the whole policy is testable on the JVM. Two properties matter more than the
 * rest, and both are load-bearing:
 *
 *  - **Blank token = open**, for every verb. That is the default and what every existing
 *    routine relies on, so this is the "we didn't break anyone" test.
 *  - **STOP/TOGGLE are gated once a token is set** — the verbs that can silently kill logging,
 *    which is the whole point of the change.
 */
class ControlTokenTest {

    private val secret = "s3cret"

    private val allVerbs = listOf(
        Control.ACTION_START, Control.ACTION_STOP, Control.ACTION_TOGGLE,
        Control.ACTION_MODE_AUTO, Control.ACTION_MODE_DRIVING, Control.ACTION_MODE_ECO,
        Control.ACTION_SET, Control.ACTION_QUERY, Control.ACTION_MARK,
    )

    /** No token configured — the surface stays exactly as open as it has always been. */
    @Test fun blankToken_everyVerbStaysOpen() {
        for (verb in allVerbs) {
            assertTrue("$verb must stay open with no token set",
                Control.externalAllowed(verb, required = "", given = ""))
        }
    }

    /** The point of 3.2: an app on the phone can no longer silently stop your tracking. */
    @Test fun tokenSet_stopAndToggleNeedIt() {
        assertFalse(Control.externalAllowed(Control.ACTION_STOP, secret, given = ""))
        assertFalse(Control.externalAllowed(Control.ACTION_TOGGLE, secret, given = ""))
        assertFalse(Control.externalAllowed(Control.ACTION_STOP, secret, given = "wrong"))
        assertTrue(Control.externalAllowed(Control.ACTION_STOP, secret, given = secret))
        assertTrue(Control.externalAllowed(Control.ACTION_TOGGLE, secret, given = secret))
    }

    /** START/MODE_* can't stop logging, so they stay open — a routine can always recover
     *  tracking without the secret, which is the property the old always-open STOP was
     *  really protecting. */
    @Test fun tokenSet_startAndModeVerbsStayOpen() {
        assertTrue(Control.externalAllowed(Control.ACTION_START, secret, given = ""))
        assertTrue(Control.externalAllowed(Control.ACTION_MODE_AUTO, secret, given = ""))
        assertTrue(Control.externalAllowed(Control.ACTION_MODE_DRIVING, secret, given = ""))
        assertTrue(Control.externalAllowed(Control.ACTION_MODE_ECO, secret, given = ""))
    }

    /** Unchanged from before 3.2 — these were already gated. */
    @Test fun tokenSet_setQueryMarkStillNeedIt() {
        assertFalse(Control.externalAllowed(Control.ACTION_SET, secret, given = ""))
        assertFalse(Control.externalAllowed(Control.ACTION_QUERY, secret, given = ""))
        assertFalse(Control.externalAllowed(Control.ACTION_MARK, secret, given = ""))
        assertTrue(Control.externalAllowed(Control.ACTION_SET, secret, given = secret))
    }

    /** A null/garbage action isn't gated — it simply isn't a verb, and [Control.apply] drops
     *  it on the `else` branch. Rejecting it here would be a lie about why nothing happened. */
    @Test fun unknownActionIsNotGated() {
        assertTrue(Control.externalAllowed(null, secret, given = ""))
        assertTrue(Control.externalAllowed("com.example.NOPE", secret, given = ""))
    }

    /** The token is compared whole — no prefix/blank sloppiness. */
    @Test fun tokenComparisonIsExact() {
        assertFalse(Control.externalAllowed(Control.ACTION_STOP, secret, given = "s3cre"))
        assertFalse(Control.externalAllowed(Control.ACTION_STOP, secret, given = "s3cret "))
        assertFalse(Control.externalAllowed(Control.ACTION_STOP, secret, given = "S3CRET"))
    }

    /** Every gated verb is one that changes state, writes data, or reads it back. If a future
     *  verb lands in TOKEN_GATED, it should be for one of those reasons — and if a destructive
     *  one lands OUTSIDE it, that's the bug this test exists to catch. */
    @Test fun gatedSetIsExactlyTheNonRecoveryVerbs() {
        assertTrue(Control.ACTION_STOP in Control.TOKEN_GATED)
        assertTrue(Control.ACTION_TOGGLE in Control.TOKEN_GATED)
        assertTrue(Control.ACTION_SET in Control.TOKEN_GATED)
        assertTrue(Control.ACTION_QUERY in Control.TOKEN_GATED)
        assertTrue(Control.ACTION_MARK in Control.TOKEN_GATED)
        assertFalse(Control.ACTION_START in Control.TOKEN_GATED)
        assertFalse(Control.ACTION_MODE_AUTO in Control.TOKEN_GATED)
        assertFalse(Control.ACTION_MODE_DRIVING in Control.TOKEN_GATED)
        assertFalse(Control.ACTION_MODE_ECO in Control.TOKEN_GATED)
    }
}
