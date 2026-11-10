package com.meshline.app.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handshake rate limits and quarantine (NOISE_DESIGN §5).
 *
 * Every bucket is keyed by the **connection** (the immediate neighbour), never
 * by the claimed `senderId`: a spoofed senderId must not be able to spend
 * another peer's budget or quarantine an innocent peer.
 */
class HandshakeRateLimiterTest {

    private var now = 1_000_000L
    private fun advance(ms: Long) { now += ms }

    @Test
    fun `handshake packets are capped per connection`() {
        val limiter = HandshakeRateLimiter { now }
        repeat(5) { assertTrue("packet $it must be allowed", limiter.allowPacket("conn-a")) }
        assertFalse("the 6th packet in the window must be refused", limiter.allowPacket("conn-a"))
        // Another connection has its own budget.
        assertTrue("a second connection has its own budget", limiter.allowPacket("conn-b"))
    }

    @Test
    fun `handshake packets are capped per node`() {
        val limiter = HandshakeRateLimiter { now }
        // 30 per node per 10 s, spread over connections.
        var allowed = 0
        for (i in 0 until 40) {
            if (limiter.allowPacket("conn-$i")) allowed++
        }
        assertEquals("the node budget must bind", NoiseProtocol.HANDSHAKE_PACKETS_PER_NODE_PER_10S, allowed)
    }

    @Test
    fun `the packet budget refills after the window`() {
        val limiter = HandshakeRateLimiter { now }
        repeat(5) { limiter.allowPacket("conn-a") }
        assertFalse(limiter.allowPacket("conn-a"))
        advance(11_000)
        assertTrue("the budget must refill after 10 s", limiter.allowPacket("conn-a"))
    }

    @Test
    fun `handshake starts are capped per hour and cooled down after success`() {
        val limiter = HandshakeRateLimiter { now }
        repeat(NoiseProtocol.HANDSHAKE_STARTS_PER_CONNECTION_PER_HOUR) {
            assertTrue("start $it must be allowed", limiter.allowStart("conn-a"))
        }
        assertFalse("the 21st start in the hour must be refused", limiter.allowStart("conn-a"))

        // After a success the connection cools down for 60 s.
        val other = HandshakeRateLimiter { now }
        other.onHandshakeSucceeded("conn-b")
        assertFalse("a new start must wait for the cooldown", other.allowStart("conn-b"))
        advance(NoiseProtocol.HANDSHAKE_COOLDOWN_MS + 1)
        assertTrue("after the cooldown a new start is allowed", other.allowStart("conn-b"))
    }

    @Test
    fun `three invalid handshakes quarantine the connection for ten minutes`() {
        val limiter = HandshakeRateLimiter { now }
        assertFalse(limiter.recordInvalid("conn-a"))
        assertFalse(limiter.recordInvalid("conn-a"))
        assertFalse("the connection must not be quarantined yet", limiter.isQuarantined("conn-a"))
        assertTrue("the third strike must quarantine the connection", limiter.recordInvalid("conn-a"))
        assertTrue("the connection must be quarantined", limiter.isQuarantined("conn-a"))
        // Another connection is unaffected.
        assertFalse("another connection must not be quarantined", limiter.isQuarantined("conn-b"))
        advance(NoiseProtocol.QUARANTINE_MS + 1)
        assertFalse("the quarantine must expire", limiter.isQuarantined("conn-a"))
    }

    @Test
    fun `strikes expire with the strike window`() {
        val limiter = HandshakeRateLimiter { now }
        limiter.recordInvalid("conn-a")
        limiter.recordInvalid("conn-a")
        // The third strike comes after the window, so the old ones do not count.
        advance(NoiseProtocol.INVALID_STRIKE_WINDOW_MS + 1)
        assertFalse("the third strike must not quarantine", limiter.recordInvalid("conn-a"))
        assertFalse("the connection must not be quarantined", limiter.isQuarantined("conn-a"))
    }

    @Test
    fun `violations are counted per connection and surface above the threshold`() {
        val limiter = HandshakeRateLimiter { now }
        repeat(NoiseProtocol.VIOLATIONS_PER_HOUR) {
            limiter.recordViolation("conn-a")
        }
        assertFalse("the hint must not surface yet", limiter.shouldSurfaceViolations("conn-a"))
        limiter.recordViolation("conn-a")
        assertTrue("the hint must surface above the threshold", limiter.shouldSurfaceViolations("conn-a"))
        assertFalse("another connection has its own count", limiter.shouldSurfaceViolations("conn-b"))
    }
}
