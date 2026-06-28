package org.jupiterns.drivetime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catch the easy "I added a routine SET key but forgot to document it" mistake —
 * the in-app cheat-sheet is the only piece of API documentation many users will
 * actually read, so its claims about which keys exist must match [Control.SET_KEYS].
 */
class AutomationHelpTest {

    @Test fun cheatSheet_mentionsEverySetKey() {
        val text = AutomationHelp.cheatSheet()
        for (key in Control.SET_KEYS) {
            assertTrue("Cheat-sheet doesn't mention SET key '$key'", text.contains(key))
        }
    }

    @Test fun cheatSheet_mentionsTheStateChangedAction() {
        assertTrue(AutomationHelp.cheatSheet().contains(StateBroadcaster.ACTION_STATE_CHANGED))
    }
}
