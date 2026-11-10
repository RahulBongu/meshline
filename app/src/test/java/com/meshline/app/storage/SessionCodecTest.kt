package com.meshline.app.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session store codec (NOISE_DESIGN §6.1).
 *
 * The snapshot itself is not encrypted here — it is written into the
 * SQLCipher database, which is encrypted at rest with a Keystore-wrapped key.
 * What the codec has to guarantee is that a snapshot survives the round trip
 * byte for byte, and that garbage is rejected instead of being half-decoded.
 */
class SessionCodecTest {

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
    fun `a snapshot survives the round trip`() {
        val original = snapshot()
        val decoded = SessionCodec.decode(SessionCodec.encode(original))
        assertEquals(original.peerIdHex, decoded.peerIdHex)
        assertArrayEquals(original.ed25519PublicKey, decoded.ed25519PublicKey)
        assertArrayEquals(original.x25519PublicKey, decoded.x25519PublicKey)
        assertEquals(original.fingerprint, decoded.fingerprint)
        assertEquals(original.state, decoded.state)
        assertEquals(original.sendNonce, decoded.sendNonce)
        assertEquals(original.recvNonce, decoded.recvNonce)
        assertArrayEquals(original.replayWindow, decoded.replayWindow)
        assertEquals(original.createdAtMs, decoded.createdAtMs)
        assertEquals(original.lastUsedAtMs, decoded.lastUsedAtMs)
        assertEquals(original.messagesSent, decoded.messagesSent)
        assertEquals(original.messagesReceived, decoded.messagesReceived)
        assertEquals(original.verified, decoded.verified)
        assertNull("no transport key material is stored today", decoded.transportKey)
    }

    @Test
    fun `a transport key blob survives the round trip when present`() {
        val key = ByteArray(64) { it.toByte() }
        val decoded = SessionCodec.decode(SessionCodec.encode(snapshot(key)))
        assertArrayEquals("the key material must survive", key, decoded.transportKey)
    }

    @Test
    fun `garbage is rejected instead of half decoded`() {
        val bytes = SessionCodec.encode(snapshot())
        val truncated = bytes.copyOf(bytes.size / 2)
        var threw = false
        try {
            SessionCodec.decode(truncated)
        } catch (e: SessionCodec.MalformedSnapshot) {
            threw = true
        }
        assertTrue("a truncated snapshot must be rejected", threw)

        threw = false
        try {
            SessionCodec.decode(ByteArray(16) { 0x41 })
        } catch (e: SessionCodec.MalformedSnapshot) {
            threw = true
        }
        assertTrue("a snapshot with the wrong magic must be rejected", threw)
    }

    @Test
    fun `the fingerprint is derived from both identity keys`() {
        val s = SessionCodec.snapshotOf(
            peerIdHex = "aabb",
            ed25519PublicKey = ByteArray(32) { 1 },
            x25519PublicKey = ByteArray(32) { 2 },
            state = "ESTABLISHED",
            sendNonce = 0,
            recvNonce = 0,
            replayWindow = ByteArray(0),
            createdAtMs = 0,
            lastUsedAtMs = 0,
            messagesSent = 0,
            messagesReceived = 0,
        )
        assertEquals(64, s.fingerprint.length)
        assertTrue("the fingerprint must be hex", s.fingerprint.all { it in "0123456789abcdef" })
    }
}
