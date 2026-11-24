package com.meshline.app.groups

import com.meshline.app.protocol.GroupPayloads
import com.meshline.app.protocol.PacketException
import com.meshline.app.protocol.MessageIds
import com.meshline.app.security.Identity
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class GroupProtocolTest {

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

    @Test
    fun groupTagDerivationIsDeterministicAndEightBytes() {
        val epochKey = ByteArray(32).also { random.nextBytes(it) }
        val tag1 = GroupPayloads.deriveGroupTag(epochKey)
        val tag2 = GroupPayloads.deriveGroupTag(epochKey)

        assertEquals(8, tag1.size)
        assertArrayEquals(tag1, tag2)

        // Different epoch key yields different tag
        val otherKey = ByteArray(32).also { random.nextBytes(it) }
        val tagOther = GroupPayloads.deriveGroupTag(otherKey)
        assertFalse(tag1.contentEquals(tagOther))
    }

    @Test
    fun associatedDataIsEighteenBytesAndCanonical() {
        val groupTag = ByteArray(8) { 0xAA.toByte() }
        val epoch = 5
        val senderId = ByteArray(8) { 0xBB.toByte() }

        val ad = GroupPayloads.computeAssociatedData(groupTag, epoch, senderId)
        assertEquals(18, ad.size)

        // Check groupTag prefix
        for (i in 0 until 8) assertEquals(0xAA.toByte(), ad[i])
        // Check 2-byte big-endian epoch
        assertEquals(0x00.toByte(), ad[8])
        assertEquals(0x05.toByte(), ad[9])
        // Check senderId suffix
        for (i in 0 until 8) assertEquals(0xBB.toByte(), ad[10 + i])
    }

    @Test
    fun chaCha20Poly1305RoundtripEncryptionDecryption() {
        val epochKey = ByteArray(32).also { random.nextBytes(it) }
        val nonce = GroupCipher.generateNonce()
        val groupTag = GroupPayloads.deriveGroupTag(epochKey)
        val senderId = ByteArray(8).also { random.nextBytes(it) }
        val ad = GroupPayloads.computeAssociatedData(groupTag, 1, senderId)

        val plaintext = "Hello confidential mesh group!".toByteArray(Charsets.UTF_8)

        val ciphertext = GroupCipher.encrypt(plaintext, epochKey, nonce, ad)
        // Ciphertext size must equal plaintext + 16-byte Poly1305 tag
        assertEquals(plaintext.size + 16, ciphertext.size)

        val decrypted = GroupCipher.decrypt(ciphertext, epochKey, nonce, ad)
        assertArrayEquals(plaintext, decrypted)
        assertEquals("Hello confidential mesh group!", decrypted.toString(Charsets.UTF_8))
    }

    @Test
    fun decryptionFailsOnTamperedCiphertextOrBadTag() {
        val epochKey = ByteArray(32).also { random.nextBytes(it) }
        val nonce = GroupCipher.generateNonce()
        val groupTag = GroupPayloads.deriveGroupTag(epochKey)
        val senderId = ByteArray(8).also { random.nextBytes(it) }
        val ad = GroupPayloads.computeAssociatedData(groupTag, 1, senderId)
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)

        val ciphertext = GroupCipher.encrypt(plaintext, epochKey, nonce, ad)

        // Tamper 1 bit in ciphertext
        val tamperedCiphertext = ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        try {
            GroupCipher.decrypt(tamperedCiphertext, epochKey, nonce, ad)
            fail("Expected decryption failure on tampered ciphertext")
        } catch (_: Exception) {
            // Expected Poly1305 MAC failure
        }
    }

    @Test
    fun decryptionFailsOnAssociatedDataMismatch() {
        val epochKey = ByteArray(32).also { random.nextBytes(it) }
        val nonce = GroupCipher.generateNonce()
        val groupTag = GroupPayloads.deriveGroupTag(epochKey)
        val senderId = ByteArray(8).also { random.nextBytes(it) }
        val ad = GroupPayloads.computeAssociatedData(groupTag, 1, senderId)
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)

        val ciphertext = GroupCipher.encrypt(plaintext, epochKey, nonce, ad)

        // Wrong epoch in AD (e.g. replay from previous epoch)
        val wrongEpochAd = GroupPayloads.computeAssociatedData(groupTag, 2, senderId)
        try {
            GroupCipher.decrypt(ciphertext, epochKey, nonce, wrongEpochAd)
            fail("Expected decryption failure on mismatched epoch AD")
        } catch (_: Exception) {
            // Expected Poly1305 MAC failure
        }

        // Wrong senderId in AD (e.g. sender impersonation)
        val fakeSender = ByteArray(8) { 0xFF.toByte() }
        val wrongSenderAd = GroupPayloads.computeAssociatedData(groupTag, 1, fakeSender)
        try {
            GroupCipher.decrypt(ciphertext, epochKey, nonce, wrongSenderAd)
            fail("Expected decryption failure on mismatched sender AD")
        } catch (_: Exception) {
            // Expected Poly1305 MAC failure
        }
    }

    @Test
    fun groupInviteSerializationRoundtrip() {
        val groupId = ByteArray(16).also { random.nextBytes(it) }
        val epochKey = ByteArray(32).also { random.nextBytes(it) }
        val adminPk = ByteArray(32).also { random.nextBytes(it) }
        val memberPk = ByteArray(32).also { random.nextBytes(it) }

        val roster = listOf(
            GroupPayloads.RosterMember(adminPk, "Admin", 3),
            GroupPayloads.RosterMember(memberPk, "Alice", 7),
        )

        val invite = GroupPayloads.GroupInvite(
            groupId = groupId,
            name = "Campers Mesh",
            icon = 3,
            epoch = 1,
            epochKey = epochKey,
            adminPubkey = adminPk,
            roster = roster,
        )

        val encoded = GroupPayloads.encodeGroupInvite(invite)
        assertTrue(GroupPayloads.isGroupInvite(encoded))

        val decoded = GroupPayloads.decodeGroupInvite(encoded)
        assertArrayEquals(invite.groupId, decoded.groupId)
        assertEquals(invite.name, decoded.name)
        assertEquals(invite.icon, decoded.icon)
        assertEquals(invite.epoch, decoded.epoch)
        assertArrayEquals(invite.epochKey, decoded.epochKey)
        assertArrayEquals(invite.adminPubkey, decoded.adminPubkey)
        assertEquals(invite.roster.size, decoded.roster.size)
        assertEquals("Admin", decoded.roster[0].nickname)
        assertEquals("Alice", decoded.roster[1].nickname)
    }

    @Test
    fun rosterStorageCodecRoundtrips() {
        val adminPk = ByteArray(32).also { random.nextBytes(it) }
        val memberPk = ByteArray(32).also { random.nextBytes(it) }
        val roster = listOf(
            GroupPayloads.RosterMember(adminPk, "Admin", 3),
            GroupPayloads.RosterMember(memberPk, "Alice", 7),
        )
        val encoded = GroupPayloads.encodeRoster(roster)
        val decoded = GroupPayloads.decodeRoster(encoded)
        assertEquals(roster.size, decoded.size)
        assertArrayEquals(roster[0].ed25519PublicKey, decoded[0].ed25519PublicKey)
        assertEquals("Admin", decoded[0].nickname)
        assertEquals(3.toByte(), decoded[0].avatarIndex)
        assertEquals("Alice", decoded[1].nickname)
    }

    @Test
    fun rosterStorageCodecHandlesEmptyAndMalformed() {
        assertEquals(0, GroupPayloads.decodeRoster(ByteArray(0)).size)
        // Truncated entry must throw rather than crash.
        val truncated = byteArrayOf(1, 1, 2, 3) // count=1, then too few bytes
        org.junit.Assert.assertThrows(PacketException.InvalidPacket::class.java) {
            GroupPayloads.decodeRoster(truncated)
        }
    }

    // ---- GROUP TEXT (reply-to + disappearing TTL inside group messages) ----

    @Test
    fun groupTextPlainTextStaysLegacyBytes() {
        val text = "hello mesh"
        val encoded = GroupPayloads.encodeGroupText(GroupPayloads.GroupText(text))
        assertEquals(text, String(encoded, Charsets.UTF_8))
        val decoded = GroupPayloads.decodeGroupText(encoded)
        assertEquals(text, decoded.text)
        assertEquals(null, decoded.parentHex)
        assertEquals(null, decoded.ttlSeconds)
    }

    @Test
    fun groupTextWithParentAndTtlRoundtrips() {
        val parent = "a".repeat(64)
        val original = GroupPayloads.GroupText("reply body", parent, 3600)
        val encoded = GroupPayloads.encodeGroupText(original)
        val decoded = GroupPayloads.decodeGroupText(encoded)
        assertEquals("reply body", decoded.text)
        assertEquals(parent, decoded.parentHex)
        assertEquals(3600, decoded.ttlSeconds)
    }

    @Test
    fun groupTextRejectsOverlongTextAndTtl() {
        org.junit.Assert.assertThrows(PacketException.InvalidPacket::class.java) {
            GroupPayloads.encodeGroupText(
                GroupPayloads.GroupText("x".repeat(GroupPayloads.MAX_GROUP_TEXT_BYTES + 1))
            )
        }
        org.junit.Assert.assertThrows(PacketException.InvalidPacket::class.java) {
            GroupPayloads.encodeGroupText(
                GroupPayloads.GroupText("ok", null, GroupPayloads.MAX_GROUP_TTL_SECONDS + 1)
            )
        }
    }

    @Test
    fun groupTextDecodeFallsBackToRawTextOnMalformedVersionedPayload() {
        // Leading 0x02 but truncated structured body: must not lose the message.
        val malformed = byteArrayOf(0x02, 0x01, 0x02, 0x03)
        val decoded = GroupPayloads.decodeGroupText(malformed)
        assertEquals(String(malformed, Charsets.UTF_8), decoded.text)
        assertEquals(null, decoded.parentHex)
    }

    @Test
    fun groupManagerLifecycleAndMessaging() {
        val idAlice = testIdentity()
        val idBob = testIdentity()

        val gAlice = GroupManager(idAlice, { "Alice" }, { 1.toByte() })
        val gBob = GroupManager(idBob, { "Bob" }, { 2.toByte() })

        val bobMember = GroupPayloads.RosterMember(idBob.ed25519PublicKey, "Bob", 2)
        val (createdGroup, invites) = gAlice.createGroup("Hiking Group", 1.toByte(), listOf(bobMember))

        val bobIdHex = idBob.senderId.joinToString("") { "%02x".format(it) }
        val bobInvite = invites[bobIdHex]
        assertNotNull(bobInvite)

        // Bob receives invite
        gBob.offerInvite(bobInvite!!)
        val acceptedGroup = gBob.acceptInvite(createdGroup.groupIdHex)
        assertNotNull(acceptedGroup)
        assertEquals("Hiking Group", acceptedGroup!!.name)
        assertEquals(1, acceptedGroup.epoch)

        // Alice sends message to group
        val packet = gAlice.createOutgoingGroupPacket(createdGroup.groupIdHex, "Meet at waypoint alpha")
        assertNotNull(packet)

        // Bob receives and decrypts message
        val delivered = gBob.onIncomingGroupPacket(packet!!)
        assertTrue(delivered)
    }
}
