package com.meshline.app.groups

import com.meshline.app.protocol.GroupPayloads
import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.security.Identity
import kotlinx.coroutines.flow.first
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Feature 6: group member management (admin removal, admin transfer) and the
 * `GROUP_CONTROL` wire codec (groupId + kind 4/5).
 */
class GroupManagerTest {

    private val random = SecureRandom()

    private fun testIdentity(): Identity {
        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edPair = edGen.generateKeyPair()
        val edPriv = (edPair.private as Ed25519PrivateKeyParameters).encoded
        val edPub = (edPair.public as Ed25519PublicKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xPair = xGen.generateKeyPair()
        val xPriv = (xPair.private as X25519PrivateKeyParameters).encoded
        val xPub = (xPair.public as X25519PublicKeyParameters).encoded

        return Identity(
            ed25519PublicKey = edPub,
            x25519PublicKey = xPub,
            edPrivate = edPriv,
            xPrivate = xPriv,
        )
    }

    private fun hex(id: Identity): String = id.senderId.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun groupControlPacket(admin: Identity, ctrl: GroupPayloads.GroupControl): MeshPacket = MeshPacket(
        version = PacketCodec.PROTOCOL_VERSION,
        type = PacketType.GROUP_CONTROL,
        ttl = PacketCodec.MAX_TTL,
        flags = 0x01,
        timestampMs = System.currentTimeMillis(),
        payload = GroupPayloads.encodeGroupControl(ctrl),
        senderId = admin.senderId,
        recipientId = ctrl.groupId.copyOfRange(0, MeshPacket.ID_BYTES),
    )

    // ---- wire codec ----

    @Test
    fun groupControlSerializationRoundTripsRemoval() {
        val groupId = ByteArray(16).also { random.nextBytes(it) }
        val roster = listOf(
            GroupPayloads.RosterMember(ByteArray(32) { 0x01 }, "A", 3),
            GroupPayloads.RosterMember(ByteArray(32) { 0x02 }, "B", 7),
        )
        val ctrl = GroupPayloads.GroupControl(3, GroupPayloads.ControlKind.REMOVE_MEMBER, groupId, "Ops", roster)

        val decoded = GroupPayloads.decodeGroupControl(GroupPayloads.encodeGroupControl(ctrl))
        assertEquals(3, decoded.epoch)
        assertEquals(GroupPayloads.ControlKind.REMOVE_MEMBER, decoded.kind)
        assertArrayEquals(groupId, decoded.groupId)
        assertEquals("Ops", decoded.name)
        assertEquals(2, decoded.roster.size)
        assertNull(decoded.newAdmin)
    }

    @Test
    fun groupControlSerializationRoundTripsAdminTransfer() {
        val groupId = ByteArray(16).also { random.nextBytes(it) }
        val roster = listOf(GroupPayloads.RosterMember(ByteArray(32) { 0x01 }, "A", 3))
        val newAdmin = ByteArray(32).also { random.nextBytes(it) }
        val ctrl = GroupPayloads.GroupControl(
            epoch = 4,
            kind = GroupPayloads.ControlKind.TRANSFER_ADMIN,
            groupId = groupId,
            name = "Ops",
            roster = roster,
            newAdmin = newAdmin,
        )

        val decoded = GroupPayloads.decodeGroupControl(GroupPayloads.encodeGroupControl(ctrl))
        assertEquals(GroupPayloads.ControlKind.TRANSFER_ADMIN, decoded.kind)
        assertArrayEquals(groupId, decoded.groupId)
        assertArrayEquals(newAdmin, decoded.newAdmin)
    }

    // ---- Feature 6: removal ----

    @Test
    fun removeMemberRotatesEpochAndBroadcastsControl() {
        val idA = testIdentity()
        val idB = testIdentity()
        val idC = testIdentity()
        val gA = GroupManager(idA, { "A" }, { 0.toByte() })
        val (group, invites) = gA.createGroup(
            "Ops", 0,
            listOf(
                GroupPayloads.RosterMember(idB.ed25519PublicKey, "B", 1),
                GroupPayloads.RosterMember(idC.ed25519PublicKey, "C", 2),
            ),
        )
        assertEquals(3, group.roster.size) // A + B + C

        val change = gA.removeMember(group.groupIdHex, hex(idC))
        assertNotNull("removeMember should apply", change)
        val c = change!!

        // Admin state: roster minus the removed member, epoch rotated, new key.
        assertEquals(2, c.updatedGroup.roster.size)
        assertFalse(c.updatedGroup.roster.any { it.ed25519PublicKey.contentEquals(idC.ed25519PublicKey) })
        assertEquals(group.epoch + 1, c.updatedGroup.epoch)
        assertFalse(c.updatedGroup.epochKey.contentEquals(group.epochKey))
        assertEquals(GroupPayloads.ControlKind.REMOVE_MEMBER, c.control.kind)
        assertFalse(c.control.roster.any { it.ed25519PublicKey.contentEquals(idC.ed25519PublicKey) })
        assertArrayEquals(group.groupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), c.control.groupId)

        // Broadcast list: members of the new roster + the removed member (minus self).
        assertTrue(c.controlPeerIds.contains(hex(idC)))
        assertTrue(c.controlPeerIds.contains(hex(idB)))
        assertFalse(c.controlPeerIds.contains(hex(idA)))
        // The new-epoch invite goes to the remaining members.
        assertTrue(c.invites.containsKey(hex(idB)))
        assertFalse(c.invites.containsKey(hex(idC)))
        assertFalse(c.invites.containsKey(hex(idA)))
    }

    @Test
    fun removeMemberIsRejectedWhenCallerIsNotAdmin() {
        val idA = testIdentity()
        val idB = testIdentity()
        val gA = GroupManager(idA, { "A" }, { 0.toByte() })
        val (group, _) = gA.createGroup("Ops", 0, listOf(GroupPayloads.RosterMember(idB.ed25519PublicKey, "B", 1)))

        val gB = GroupManager(idB, { "B" }, { 1.toByte() })
        assertNull("non-admin must not remove members", gB.removeMember(group.groupIdHex, hex(idA)))
    }

    @Test
    fun removedMemberLeavesLocallyWhenControlArrives() {
        val idA = testIdentity()
        val idB = testIdentity()
        val idC = testIdentity()
        val gA = GroupManager(idA, { "A" }, { 0.toByte() })
        val (group, invites) = gA.createGroup(
            "Ops", 0,
            listOf(
                GroupPayloads.RosterMember(idB.ed25519PublicKey, "B", 1),
                GroupPayloads.RosterMember(idC.ed25519PublicKey, "C", 2),
            ),
        )

        // Both B and C have the group.
        val gB = GroupManager(idB, { "B" }, { 1.toByte() })
        gB.offerInvite(invites[hex(idB)]!!)
        assertNotNull(gB.acceptInvite(group.groupIdHex))
        val gC = GroupManager(idC, { "C" }, { 2.toByte() })
        gC.offerInvite(invites[hex(idC)]!!)
        assertNotNull(gC.acceptInvite(group.groupIdHex))
        assertNotNull(gB.getGroup(group.groupIdHex))
        assertNotNull(gC.getGroup(group.groupIdHex))

        // The admin removes C; both B and C learn about it via the control.
        val change = gA.removeMember(group.groupIdHex, hex(idC))
        assertNotNull(change)

        gB.onIncomingGroupControl(groupControlPacket(idA, change!!.control))
        val bGroup = gB.getGroup(group.groupIdHex)
        assertNotNull(bGroup)
        assertEquals(2, bGroup!!.roster.size)
        assertFalse(bGroup.roster.any { it.ed25519PublicKey.contentEquals(idC.ed25519PublicKey) })
        assertEquals(group.epoch + 1, bGroup.epoch)

        // C was removed → its local group is gone.
        gC.onIncomingGroupControl(groupControlPacket(idA, change.control))
        assertNull(gC.getGroup(group.groupIdHex))
    }

    // ---- Feature 6: admin transfer ----

    @Test
    fun transferAdminRotatesEpochAndGrantsRightsToNewAdmin() {
        val idA = testIdentity()
        val idB = testIdentity()
        val gA = GroupManager(idA, { "A" }, { 0.toByte() })
        val (group, invites) = gA.createGroup("Ops", 0, listOf(GroupPayloads.RosterMember(idB.ed25519PublicKey, "B", 1)))

        val change = gA.transferAdmin(group.groupIdHex, hex(idB))
        assertNotNull("transfer should apply", change)
        val c = change!!

        // Admin A steps down; the epoch still rotates on their side.
        val aGroup = gA.getGroup(group.groupIdHex)
        assertNotNull(aGroup)
        assertFalse(aGroup!!.isAdmin)
        assertArrayEquals(idB.ed25519PublicKey, aGroup.adminEd25519Pk)
        assertEquals(group.epoch + 1, aGroup.epoch)

        // B picks up the transfer through the epoch-update invite.
        val gB = GroupManager(idB, { "B" }, { 1.toByte() })
        gB.offerInvite(invites[hex(idB)]!!)                  // initial invite (epoch 1)
        assertNotNull(gB.acceptInvite(group.groupIdHex))
        gB.offerInvite(change!!.invites[hex(idB)]!!)          // transfer invite (epoch 2)
        val bGroup = gB.getGroup(group.groupIdHex)
        assertNotNull(bGroup)
        assertTrue("B becomes admin", bGroup!!.isAdmin)
        assertArrayEquals(idB.ed25519PublicKey, bGroup.adminEd25519Pk)
        assertEquals(group.epoch + 1, bGroup.epoch)
        assertTrue(bGroup.roster.any { it.ed25519PublicKey.contentEquals(idA.ed25519PublicKey) })
    }

    // ---- message lifecycle (disappearing, edit, delete) ----

    @Test
    fun disappearingGroupMessageIsSweptAtItsExpiry() {
        val id = testIdentity()
        val g = GroupManager(id, { "Me" }, { 1.toByte() })
        val (group, _) = g.createGroup("Ephemeral", 1.toByte(), emptyList())
        fun snapshot() = kotlinx.coroutines.runBlocking { g.messages.first() }[group.groupIdHex].orEmpty()

        assertNotNull(g.createOutgoingGroupPacket(group.groupIdHex, "burn after reading", ttlSeconds = 60))
        val expiresAt = snapshot().single().expiresAtMs
        assertNotNull(expiresAt)

        // One millisecond before the deadline the message is still visible.
        g.sweepExpired(expiresAt!! - 1)
        assertEquals(1, snapshot().size)

        // At the deadline it is dropped from memory.
        g.sweepExpired(expiresAt)
        assertEquals(0, snapshot().size)
    }

    @Test
    fun staleGroupControlEpochCannotRollBackTheRoster() {
        val idA = testIdentity()
        val idB = testIdentity()
        val idC = testIdentity()
        val gA = GroupManager(idA, { "Admin" }, { 1.toByte() })
        val roster = listOf(
            GroupPayloads.RosterMember(idB.ed25519PublicKey, "Bob", 2),
            GroupPayloads.RosterMember(idC.ed25519PublicKey, "Cara", 3),
        )
        val (group, invites) = gA.createGroup("Ops", 1.toByte(), roster)

        val gB = GroupManager(idB, { "Bob" }, { 2.toByte() })
        gB.offerInvite(invites[hex(idB)]!!)
        assertNotNull(gB.acceptInvite(group.groupIdHex))

        // The admin removes Cara: Bob moves to epoch 2 through the invite.
        val removal = gA.removeMember(group.groupIdHex, hex(idC))
        assertNotNull(removal)
        gB.offerInvite(removal!!.invites[hex(idB)]!!)
        val current = gB.getGroup(group.groupIdHex)
        assertNotNull(current)
        assertEquals(2, current!!.epoch)

        // A replayed epoch-1 control (removing Bob) must not roll Bob back and
        // kick him out of the group.
        val stale = groupControlPacket(
            idA,
            GroupPayloads.GroupControl(
                epoch = 1,
                kind = GroupPayloads.ControlKind.REMOVE_MEMBER,
                groupId = hexToBytes(group.groupIdHex),
                name = group.name,
                roster = listOf(
                    GroupPayloads.RosterMember(idA.ed25519PublicKey, "Admin", 1),
                    GroupPayloads.RosterMember(idC.ed25519PublicKey, "Cara", 3),
                ),
            ),
        )
        gB.onIncomingGroupControl(stale)

        val after = gB.getGroup(group.groupIdHex)
        assertNotNull(after)
        assertEquals(2, after!!.epoch)
        assertTrue(after.roster.any { it.ed25519PublicKey.contentEquals(idB.ed25519PublicKey) })
    }
}
