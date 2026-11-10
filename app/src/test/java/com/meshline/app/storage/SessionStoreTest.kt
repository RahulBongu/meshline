package com.meshline.app.storage

import com.meshline.app.storage.SessionStore.Companion.toEntity
import com.meshline.app.storage.SessionStore.Companion.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session store's transport-key guarantee (review item 4).
 *
 * **Nothing is ever written to `noise_sessions.transportKey`** while the Noise
 * transport keys cannot be exported (NOISE_DESIGN amendment A2). The mapping
 * drops the key deliberately, so the guarantee is structural rather than a
 * promise: a caller that hands in key material still gets a NULL column.
 */
class SessionStoreTest {

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
        val entity = snapshot(ByteArray(64) { it.toByte() }).toEntity()
        assertNull("the transport key must never be persisted", entity.transportKey)

        // And a snapshot without a key stays without one.
        assertNull(snapshot().toEntity().transportKey)
    }

    @Test
    fun `a session round trips through the entity mapping`() {
        val original = snapshot()
        val decoded = original.toEntity().toSnapshot()
        assertEquals(original.peerIdHex, decoded.peerIdHex)
        assertArrayEquals(original.ed25519PublicKey, decoded.ed25519PublicKey)
        assertArrayEquals(original.x25519PublicKey, decoded.x25519PublicKey)
        assertEquals(original.state, decoded.state)
        assertEquals(original.sendNonce, decoded.sendNonce)
        assertEquals(original.recvNonce, decoded.recvNonce)
        assertArrayEquals(original.replayWindow, decoded.replayWindow)
        assertEquals(original.messagesSent, decoded.messagesSent)
        assertEquals(original.messagesReceived, decoded.messagesReceived)
        assertTrue(original.verified)
        assertNull("the column stays NULL", decoded.transportKey)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
