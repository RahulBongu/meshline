package com.meshline.app.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot-exhaustion guard (Phase F): a link that never proved a peer
 * identity must be closed so it cannot hold one of the six GATT slots
 * forever.
 */
class LinkEvictionPolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `a fresh link is kept`() {
        assertFalse(LinkEvictionPolicy.shouldEvict(t0, t0, t0))
    }

    @Test
    fun `a link that stays silent is closed after the silent timeout`() {
        val opened = t0
        val silent = t0 + LinkEvictionPolicy.SILENT_LINK_TIMEOUT_MS - 1
        assertFalse("one millisecond before the timeout it is kept", LinkEvictionPolicy.shouldEvict(opened, opened, silent))
        val expired = t0 + LinkEvictionPolicy.SILENT_LINK_TIMEOUT_MS
        assertTrue("at the timeout it goes", LinkEvictionPolicy.shouldEvict(opened, opened, expired))
    }

    @Test
    fun `traffic keeps a silent link alive only up to the unproven timeout`() {
        // The peer talks but never proves its identity (no verified ANNOUNCE).
        val opened = t0
        // Traffic a moment ago: the silent rule must not fire.
        val recent = opened + LinkEvictionPolicy.SILENT_LINK_TIMEOUT_MS - 100
        assertFalse(LinkEvictionPolicy.shouldEvict(opened, recent, recent))
        // But the link as a whole is older than the unproven timeout.
        val old = opened + LinkEvictionPolicy.UNPROVEN_LINK_TIMEOUT_MS
        assertTrue(LinkEvictionPolicy.shouldEvict(opened, recent, old))
    }

    @Test
    fun `traffic resets the silence timer`() {
        val opened = t0
        // A packet arrived late: the link is not silent any more.
        val lastTraffic = opened + LinkEvictionPolicy.SILENT_LINK_TIMEOUT_MS
        assertFalse(
            "a fresh packet must postpone the eviction",
            LinkEvictionPolicy.shouldEvict(opened, lastTraffic, lastTraffic),
        )
    }

    @Test
    fun `the limits are ordered`() {
        assertTrue(
            "the silence timeout must be shorter than the unproven timeout",
            LinkEvictionPolicy.SILENT_LINK_TIMEOUT_MS < LinkEvictionPolicy.UNPROVEN_LINK_TIMEOUT_MS,
        )
        assertTrue(
            "the unproven timeout must stay inside the interest window",
            LinkEvictionPolicy.UNPROVEN_LINK_TIMEOUT_MS <= LinkEvictionPolicy.UNPROVEN_INTEREST_MS,
        )
    }
}
