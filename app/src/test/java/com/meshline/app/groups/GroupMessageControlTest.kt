package com.meshline.app.groups

import com.meshline.app.protocol.MessageIds
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * MESSAGE_CONTROL on group messages: only the original author may edit or
 * delete their own message.
 */
class GroupMessageControlTest {

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

    private fun hex(id: Identity): String =
        MessageIds.idFromPublicKey(id.ed25519PublicKey).joinToString("") { "%02x".format(it) }

    @Test
    fun messageControlOnlyAppliesToTheAuthor() {
        val id = testIdentity()
        val other = testIdentity()
        val g = GroupManager(id, { "Me" }, { 1.toByte() })
        val (group, _) = g.createGroup("Controls", 1.toByte(), emptyList())
        fun snapshot() = kotlinx.coroutines.runBlocking { g.messages.first() }[group.groupIdHex].orEmpty()

        assertNotNull(g.createOutgoingGroupPacket(group.groupIdHex, "original"))
        val msg = snapshot().single()

        // A peer that is not the author can neither edit nor delete it.
        assertFalse(g.applyMessageControl(msg.messageIdHex, hex(other), delete = false, newText = "hacked"))
        assertFalse(g.applyMessageControl(msg.messageIdHex, hex(other), delete = true, newText = null))
        assertEquals("original", snapshot().single().text)

        // The author can edit, then delete.
        assertTrue(g.applyMessageControl(msg.messageIdHex, hex(id), delete = false, newText = "edited"))
        val edited = snapshot().single()
        assertEquals("edited", edited.text)
        assertNotNull(edited.editedAtMs)

        assertTrue(g.applyMessageControl(msg.messageIdHex, hex(id), delete = true, newText = null))
        val deleted = snapshot().single()
        assertTrue(deleted.deleted)
        assertEquals("", deleted.text)

        // An unknown target is not applied.
        assertFalse(g.applyMessageControl("ab".repeat(32), hex(id), delete = true, newText = null))
    }
}
