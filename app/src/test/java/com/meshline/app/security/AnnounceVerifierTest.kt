package com.meshline.app.security

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.router.SignatureCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Production verifier tests (D1). Pins the mapping that drives the router
 * counters: unknown key != bad signature != learning-budget drop.
 */
class AnnounceVerifierTest {

    private val now = 1_700_000_000_000L
    private val peer = ByteArray(8) { 0x42 }

    private fun announcePacket(identity: Identity, avatar: Byte = 3, interval: Byte = 3): MeshPacket {
        val payload = Payloads.encodeAnnounce(
            Payloads.Announce(
                nickname = "alice",
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
                capabilities = Payloads.Capabilities.RELAY_ENABLED,
                avatarIndex = avatar,
                intervalUnits = interval,
            )
        )
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.ANNOUNCE, 7, 0x00, now,
            payload, identity.senderId, PacketCodec.BROADCAST_ID,
        )
        val sig = identity.signEd25519(PacketCodec.signingInput(unsigned))
        return unsigned.copy(flags = 0x02, signature = sig)
    }

    private fun publicPacket(identity: Identity): MeshPacket {
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, 7, 0x00, now,
            "hello".encodeToByteArray(), identity.senderId, PacketCodec.BROADCAST_ID,
        )
        val sig = identity.signEd25519(PacketCodec.signingInput(unsigned))
        return unsigned.copy(flags = 0x02, signature = sig)
    }

    @Test
    fun `valid announce is learned and verifies`() {
        val (identity, _, _) = IdentityFactory.generate()
        val cache = KeyCache(nowProvider = { now })
        assertEquals(SignatureCheck.OK, AnnounceVerifier.verify(announcePacket(identity), cache, peer))
        assertArrayEquals(identity.ed25519PublicKey, cache.get(identity.senderId))
    }

    @Test
    fun `tampered announce is a bad signature and never cached`() {
        val (identity, _, _) = IdentityFactory.generate()
        val cache = KeyCache(nowProvider = { now })
        val pkt = announcePacket(identity)
        val tampered = pkt.copy(payload = pkt.payload.copyOf().also { it[it.size - 1] = 6 })
        assertEquals(SignatureCheck.BAD_SIGNATURE, AnnounceVerifier.verify(tampered, cache, peer))
        assertFalse(cache.knows(identity.senderId))
    }

    @Test
    fun `valid announce rejected by budget is BUDGET not BAD_SIGNATURE`() {
        val (identity, _, _) = IdentityFactory.generate()
        val cache = KeyCache(nowProvider = { now }, perConnNewBudget = 0)
        assertEquals(SignatureCheck.BUDGET, AnnounceVerifier.verify(announcePacket(identity), cache, peer))
        assertFalse(cache.knows(identity.senderId))
    }

    @Test
    fun `public message before the announce is UNKNOWN_SENDER`() {
        val (identity, _, _) = IdentityFactory.generate()
        val cache = KeyCache(nowProvider = { now })
        assertEquals(SignatureCheck.UNKNOWN_SENDER, AnnounceVerifier.verify(publicPacket(identity), cache, peer))
    }

    @Test
    fun `public message verifies after the key is learned and fails when tampered`() {
        val (identity, _, _) = IdentityFactory.generate()
        val cache = KeyCache(nowProvider = { now })
        cache.protect(identity.senderId, identity.ed25519PublicKey)
        val pkt = publicPacket(identity)
        assertEquals(SignatureCheck.OK, AnnounceVerifier.verify(pkt, cache, peer))

        val tampered = pkt.copy(payload = "hell0".encodeToByteArray())
        assertEquals(SignatureCheck.BAD_SIGNATURE, AnnounceVerifier.verify(tampered, cache, peer))
    }

    // ------------------------------------------- the identity claim alone
    // The BLE layer binds a *device* to an identity with this check, so it
    // must accept exactly what the announce proves — and nothing else.

    @Test
    fun `a valid announce proves its identity without touching the cache`() {
        val (identity, _, _) = IdentityFactory.generate()
        val cache = KeyCache(nowProvider = { now })
        val verified = AnnounceVerifier.verifyAnnounceIdentity(announcePacket(identity))
        assertArrayEquals(identity.senderId, verified)
        assertFalse("the cache must stay untouched", cache.knows(identity.senderId))
    }

    @Test
    fun `a tampered announce proves nothing`() {
        val (identity, _, _) = IdentityFactory.generate()
        val pkt = announcePacket(identity)
        val tampered = pkt.copy(payload = pkt.payload.copyOf().also { it[it.size - 1] = 6 })
        assertEquals(null, AnnounceVerifier.verifyAnnounceIdentity(tampered))
    }

    @Test
    fun `an announce that claims another id proves nothing`() {
        val (identity, _, _) = IdentityFactory.generate()
        val (other, _, _) = IdentityFactory.generate()
        // The payload carries the real key, but the header claims somebody
        // else's id: the hash check must refuse it.
        val spoofed = announcePacket(identity).copy(senderId = other.senderId)
        assertEquals(null, AnnounceVerifier.verifyAnnounceIdentity(spoofed))
    }

    @Test
    fun `an unsigned announce proves nothing`() {
        val (identity, _, _) = IdentityFactory.generate()
        val unsigned = announcePacket(identity).copy(flags = 0x00, signature = null)
        assertEquals(null, AnnounceVerifier.verifyAnnounceIdentity(unsigned))
    }

    @Test
    fun `a non-announce packet proves nothing`() {
        val (identity, _, _) = IdentityFactory.generate()
        assertEquals(null, AnnounceVerifier.verifyAnnounceIdentity(publicPacket(identity)))
    }
}
