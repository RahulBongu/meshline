package com.meshline.app.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protected handshake tier and the per-sender allowances of the reserved
 * budget (review item 1).
 *
 * A connected neighbour is protected **only after it has delivered a verified
 * ANNOUNCE**, and QR-verified contacts rank above plain neighbours. The
 * reserved half of the global budget is split per sender, so one peer cannot
 * take all of it.
 */
class HandshakeTierTest {

    private var now = 1_000_000L
    private fun advance(ms: Long) { now += ms }

    @Test
    fun `a connected neighbour is protected only after a verified announce`() {
        // Connected but silent: not protected.
        assertEquals(
            "a connected peer that never announced must stay in the junk tier",
            HandshakeTier.JUNK,
            HandshakeTiers.tierOf(verified = false, announced = false, connected = true),
        )
        // Announced but not connected (it left): not protected either.
        assertEquals(
            "a peer that announced but is gone must not be protected",
            HandshakeTier.JUNK,
            HandshakeTiers.tierOf(verified = false, announced = true, connected = false),
        )
        // Connected and announced: protected.
        assertEquals(
            HandshakeTier.NEIGHBOUR,
            HandshakeTiers.tierOf(verified = false, announced = true, connected = true),
        )
        // A QR-verified contact outranks a plain neighbour, connected or not.
        assertEquals(
            HandshakeTier.CONTACT,
            HandshakeTiers.tierOf(verified = true, announced = false, connected = false),
        )
        assertEquals(
            HandshakeTier.CONTACT,
            HandshakeTiers.tierOf(verified = true, announced = true, connected = true),
        )
    }

    @Test
    fun `one peer cannot starve a contact's handshake`() {
        val limiter = HandshakeRateLimiter { now }
        val generalCap = NoiseProtocol.MAX_X25519_OPS_PER_MINUTE - NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE

        // A malicious connected neighbour drains the *general* pool…
        var spent = 0
        while (spent < generalCap) {
            assertTrue(limiter.allowDhWork(1, HandshakeTier.NEIGHBOUR, "malicious"))
            spent++
        }
        // …and then tries to take the whole reserved half.
        var reserved = 0
        while (reserved < HandshakeTier.NEIGHBOUR.reservedOpsPerMinute) {
            assertTrue(
                "the neighbour may use its own allowance",
                limiter.allowDhWork(1, HandshakeTier.NEIGHBOUR, "malicious"),
            )
            reserved++
        }
        assertFalse(
            "the neighbour must not take more than its allowance",
            limiter.allowDhWork(1, HandshakeTier.NEIGHBOUR, "malicious"),
        )

        // The contact still gets through: the reserve is per sender.
        assertTrue(
            "a QR contact must not be starved by a malicious neighbour",
            limiter.allowDhWork(1, HandshakeTier.CONTACT, "contact"),
        )
        repeat(HandshakeTier.CONTACT.reservedOpsPerMinute - 1) {
            assertTrue("the contact has its own allowance", limiter.allowDhWork(1, HandshakeTier.CONTACT, "contact"))
        }
        assertFalse(
            "the contact's allowance is finite too",
            limiter.allowDhWork(1, HandshakeTier.CONTACT, "contact"),
        )
    }

    @Test
    fun `a junk sender never touches the reserved half`() {
        val limiter = HandshakeRateLimiter { now }
        val generalCap = NoiseProtocol.MAX_X25519_OPS_PER_MINUTE - NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE
        repeat(generalCap) { assertTrue(limiter.allowDhWork(1, HandshakeTier.JUNK, "junk")) }
        assertFalse("a junk sender must not reach the reserve", limiter.allowDhWork(1, HandshakeTier.JUNK, "junk"))
        // A neighbour may still use the reserve.
        assertTrue(limiter.allowDhWork(1, HandshakeTier.NEIGHBOUR, "neighbour"))
    }

    @Test
    fun `the reserved pool is bounded overall`() {
        val limiter = HandshakeRateLimiter { now }
        val generalCap = NoiseProtocol.MAX_X25519_OPS_PER_MINUTE - NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE
        repeat(generalCap) { limiter.allowDhWork(1, HandshakeTier.JUNK, "junk") }
        // Several contacts, each within its own allowance, still share one pool.
        var total = 0
        for (i in 0 until 10) {
            repeat(HandshakeTier.CONTACT.reservedOpsPerMinute) {
                if (limiter.allowDhWork(1, HandshakeTier.CONTACT, "contact-$i")) total++
            }
        }
        assertTrue(
            "the reserve must be bounded ($total)",
            total <= NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE,
        )
        assertEquals(0, limiter.remainingDhOps())
    }
}
