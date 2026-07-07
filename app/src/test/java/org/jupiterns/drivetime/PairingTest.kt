package org.jupiterns.drivetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pairing payloads come from an untrusted QR/paste, so lock the three accepted shapes
 *  and the "keep the existing URL" behaviour of a bare token. */
@RunWith(RobolectricTestRunner::class)
class PairingTest {

    @Test fun drivetimeScheme_parsesUrlAndToken() {
        val r = Pairing.parse("drivetime://pair?url=https%3A%2F%2Fdrivetime.example.com&token=abc123")
        assertEquals("https://drivetime.example.com", r.url)
        assertEquals("abc123", r.token)
        assertTrue(r.hasToken)
    }

    @Test fun scheme_trailingSlashTrimmed() {
        val r = Pairing.parse("drivetime://pair?url=https%3A%2F%2Fhost%2F&token=t")
        assertEquals("https://host", r.url)
    }

    @Test fun json_parsesUrlAndToken() {
        val r = Pairing.parse("""{"url":"https://h.example/","token":"deadbeef"}""")
        assertEquals("https://h.example", r.url)
        assertEquals("deadbeef", r.token)
    }

    @Test fun bareToken_keepsExistingUrl() {
        val r = Pairing.parse("  801e74ca5078a712  ")
        assertNull("bare token carries no URL", r.url)
        assertEquals("801e74ca5078a712", r.token)
    }

    @Test fun empty_isEmpty() {
        val r = Pairing.parse("   ")
        assertNull(r.url)
        assertNull(r.token)
        assertTrue(!r.hasToken)
    }

    @Test fun garbageJson_doesNotCrash() {
        val r = Pairing.parse("{not json")
        // starts with '{' → JSON branch → parse fails → empty, not a bare token
        assertNull(r.token)
    }
}
