package com.meshline.app.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replay window (NOISE_DESIGN §4.1): a 128-nonce sliding window per
 * direction that refuses replays and refuses nonces that are too far ahead.
 */
class ReplayWindowTest {

    @Test
    fun `nonces are accepted in order and the window advances`() {
        val w = ReplayWindow()
        for (i in 0L until 200L) {
            assertEquals("nonce $i must be accepted", ReplayWindow.Decision.ACCEPTED, w.accept(i))
        }
        assertEquals("the window must advance with the stream", 200L, w.nextExpected)
    }

    @Test
    fun `a replay is refused`() {
        val w = ReplayWindow()
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(0))
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(1))
        // The same nonce again is a replay.
        assertEquals(ReplayWindow.Decision.REPLAY, w.accept(1))
        assertEquals(ReplayWindow.Decision.REPLAY, w.accept(0))
        // And an old nonce is a replay too.
        w.accept(2)
        assertEquals(ReplayWindow.Decision.REPLAY, w.accept(0))
    }

    @Test
    fun `out of order nonces inside the window are accepted once`() {
        val w = ReplayWindow()
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(0))
        // Accepting the expected nonce slides the window forward by one.
        assertEquals("the window must advance past nonce 0", 1L, w.nextExpected)
        // Jump ahead: nonce 5 arrives before 1..4.
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(5))
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(3))
        // 3 is a replay now.
        assertEquals(ReplayWindow.Decision.REPLAY, w.accept(3))
        // The window has not advanced yet: nonce 1 is still expected.
        assertEquals(1L, w.nextExpected)
        // Filling the gap slides the window forward: 1 and 2 are new, 3 was
        // already seen, 4 is new, 5 was already seen.
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(1))
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(2))
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(4))
        assertEquals("the window must slide over the filled gap", 6L, w.nextExpected)
        // Everything below the window is now a replay.
        assertEquals(ReplayWindow.Decision.REPLAY, w.accept(0))
        assertEquals(ReplayWindow.Decision.REPLAY, w.accept(5))
    }

    @Test
    fun `a nonce beyond the window is refused`() {
        val w = ReplayWindow()
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(0))
        // The window now covers [1, 128]; 129 is the first nonce too far ahead.
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(128))
        assertEquals(ReplayWindow.Decision.TOO_FAR_AHEAD, w.accept(129))
    }

    @Test
    fun `the window survives a serialise and restore round trip`() {
        val w = ReplayWindow()
        for (i in 0L until 10L) w.accept(i)
        w.accept(20) // a hole in the stream
        val bytes = w.serialize()
        val restored = ReplayWindow()
        restored.restore(bytes)
        assertEquals("the next expected nonce must survive", w.nextExpected, restored.nextExpected)
        // The restored window still knows that 20 was seen.
        assertEquals(ReplayWindow.Decision.REPLAY, restored.accept(20))
        // And it accepts what comes next.
        assertEquals(ReplayWindow.Decision.ACCEPTED, restored.accept(10))
    }

    @Test
    fun `a window of 128 holds 128 future nonces`() {
        val w = ReplayWindow()
        assertEquals(128, w.size)
        // After nonce 0 the window covers [1, 128].
        w.accept(0)
        for (i in 1L until 128L) {
            assertEquals("nonce $i must fit in the window", ReplayWindow.Decision.ACCEPTED, w.accept(i))
        }
        assertEquals(128L, w.nextExpected)
        assertEquals(ReplayWindow.Decision.ACCEPTED, w.accept(128))
        assertEquals(129L, w.nextExpected)
        // A nonce 128 ahead of the expectation is refused.
        assertEquals(ReplayWindow.Decision.TOO_FAR_AHEAD, w.accept(129 + 128))
    }
}
