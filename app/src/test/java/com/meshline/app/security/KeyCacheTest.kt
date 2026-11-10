package com.meshline.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the production KeyCache (the simulator in KeyKnowledgeTest
 * mirrors it; this pins the real class, including the budget and expiry rules
 * that feed the router's droppedUnknownKey / droppedBadSignature / droppedBudget
 * counters).
 */
class KeyCacheTest {

    private val now = 1_700_000_000_000L
    private fun sid(n: Int) = ByteArray(8) { (n + 1).toByte() }
    private fun key(n: Int) = ByteArray(32) { (n + 7).toByte() }

    @Test
    fun `get returns null until learned and survives protection`() {
        val cache = KeyCache(nowProvider = { now })
        assertNull(cache.get(sid(1)))
        assertTrue(cache.learn(sid(1), key(1), nowMs = now, connectionKey = "a"))
        assertArrayEquals(key(1), cache.get(sid(1)))
        cache.protect(sid(1), key(1), nowMs = now)
        assertArrayEquals(key(1), cache.get(sid(1), nowMs = now + 10 * 60_000))
    }

    @Test
    fun `per-connection budget rejects the 51st new key`() {
        val cache = KeyCache(nowProvider = { now })
        repeat(50) { i ->
            assertTrue("learn #$i", cache.learn(sid(i), key(i), nowMs = now, connectionKey = "conn-a"))
        }
        assertFalse(cache.learn(sid(50), key(50), nowMs = now, connectionKey = "conn-a"))
        // A different connection has its own budget.
        assertTrue(cache.learn(sid(51), key(51), nowMs = now, connectionKey = "conn-b"))
    }

    @Test
    fun `global cap stops a flood even across connections`() {
        val cache = KeyCache(nowProvider = { now })
        var learned = 0
        for (i in 0 until 120) {
            if (cache.learn(sid(i), key(i), nowMs = now, connectionKey = "conn-${i % 4}")) learned++
        }
        assertEquals(100, learned) // 100/window once the cache is no longer nearly empty
    }

    @Test
    fun `untrusted keys expire but protected keys do not`() {
        val cache = KeyCache(nowProvider = { now })
        cache.learn(sid(1), key(1), nowMs = now, connectionKey = "a")
        cache.protect(sid(2), key(2), nowMs = now)
        assertNull(cache.get(sid(1), nowMs = now + 421_000))
        assertArrayEquals(key(2), cache.get(sid(2), nowMs = now + 421_000))
    }

    @Test
    fun `replay allowance is separate from the normal budget`() {
        val cache = KeyCache(nowProvider = { now })
        repeat(49) { i ->
            assertTrue(cache.learn(sid(i), key(i), nowMs = now, connectionKey = "conn", isReplay = true))
        }
        assertFalse(cache.learn(sid(49), key(49), nowMs = now, connectionKey = "conn", isReplay = true))
        // Normal bucket on the same connection is still available.
        assertTrue(cache.learn(sid(60), key(60), nowMs = now, connectionKey = "conn"))
    }

    @Test
    fun `repeat announce refreshes expiry of a learned key`() {
        val cache = KeyCache(nowProvider = { now })
        assertTrue(cache.learn(sid(1), key(1), nowMs = now, connectionKey = "a"))
        // Peer keeps announcing; at t+400s the same key must refresh the
        // 420s expiry instead of aging out at t+420s.
        assertTrue(cache.learn(sid(1), key(1), nowMs = now + 400_000, connectionKey = "a"))
        assertArrayEquals(key(1), cache.get(sid(1), nowMs = now + 700_000))
    }
}
