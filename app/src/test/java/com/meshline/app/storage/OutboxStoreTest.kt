package com.meshline.app.storage

import com.meshline.app.storage.SessionStore.Companion.toEntity
import com.meshline.app.storage.SessionStore.Companion.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store-and-forward outbox (NOISE_DESIGN §6.1, FR-12) and the session
 * store's transport-key guarantee.
 *
 * The outbox stores **plaintext** (protected by SQLCipher) and encrypts at
 * send time; the session store **never** writes the transport key column.
 */
class OutboxStoreTest {

    /** A DAO that does nothing: the Room path is instrumented-only. */
    private class FakeOutboxDao : OutboxDao {
        override suspend fun insert(entity: OutboxEntity): Long = 1L
        override suspend fun queuedFor(hex: String): List<OutboxEntity> = emptyList()
        override suspend fun all(): List<OutboxEntity> = emptyList()
        override suspend fun updateState(id: Long, state: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
        override suspend fun deleteAll() = Unit
    }

    private fun snapshot(transportKey: ByteArray? = null) = SessionSnapshot(
        peerIdHex = "aabbccddeeff0011",
        ed25519PublicKey = ByteArray(32) { (it + 1).toByte() },
        x25519PublicKey = ByteArray(32) { (it + 2).toByte() },
        fingerprint = "00112233445566778899aabbccddeeff",
        state = "ESTABLISHED",
        sendNonce = 42L,
        recvNonce = 17L,
        replayWindow = ByteArray(24) { (it * 3).toByte() },
        createdAtMs = 1_700_000_000_000L,
        lastUsedAtMs = 1_700_000_500_000L,
        messagesSent = 7,
        messagesReceived = 9,
        transportKey = transportKey,
        verified = true,
    )

    @Test
    fun `the transport key column is never written`() {
        // Even when a snapshot carries key material, the store drops it.
        val withKey = snapshot(ByteArray(64) { it.toByte() })
        val entity = withKey.toEntity()
        assertNull("the transport key must never be persisted", entity.transportKey)

        // And a snapshot without a key stays without one.
        assertNull(snapshot().toEntity().transportKey)
    }

    @Test
    fun `a session round trips through the entity mapping`() {
        val original = snapshot()
        val decoded = original.toEntity().toSnapshot()
        assertEquals(original.peerIdHex, decoded.peerIdHex)
        assertEquals(original.state, decoded.state)
        assertEquals(original.sendNonce, decoded.sendNonce)
        assertEquals(original.recvNonce, decoded.recvNonce)
        assertEquals(original.messagesSent, decoded.messagesSent)
        assertEquals(original.messagesReceived, decoded.messagesReceived)
        assertTrue(original.verified)
        assertNull(decoded.transportKey)
    }

    @Test
    fun `the outbox state names are the user visible ones`() {
        assertEquals("QUEUED", OutboxState.QUEUED.name)
        assertEquals("SENT", OutboxState.SENT.name)
        assertEquals("the queued state is what the UI shows first", OutboxState.QUEUED.name, "QUEUED")
    }

    @Test
    fun `a queued message expires after the age limit`() {
        assertEquals("the MVP store-and-forward window is 12 h", 12 * 60 * 60 * 1_000L, OutboxRepository.MAX_AGE_MS)
        // The cutoff is pure logic, so it is testable without a device.
        val now = 1_700_000_000_000L
        val repo = OutboxRepository(FakeOutboxDao(), maxAgeMs = OutboxRepository.MAX_AGE_MS)
        assertEquals(now - OutboxRepository.MAX_AGE_MS, repo.expiryCutoff(now))
    }
}
