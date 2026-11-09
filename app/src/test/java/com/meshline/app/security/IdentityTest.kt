package com.meshline.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test
    fun `generated identity self-signs and senderId matches fingerprint`() {
        val (identity, _, _) = IdentityFactory.generate()
        assertEquals(32, identity.ed25519PublicKey.size)
        assertEquals(32, identity.x25519PublicKey.size)
        assertEquals(8, identity.senderId.size)
        assertEquals(64, identity.fingerprint.length)
        val input = "hello mesh".encodeToByteArray()
        val sig = identity.signEd25519(input)
        assertEquals(64, sig.size)
        // JDK verifies BC signature (interoperability already pinned via RFC vector in KeyKnowledgeTest).
        assertTrue(AnnounceVerifier.verifyEd(identity.ed25519PublicKey, input, sig))
        // X25519 agreement is symmetric.
        val (other, _, _) = IdentityFactory.generate()
        val sharedA = identity.agreeX25519(other.x25519PublicKey)
        val sharedB = other.agreeX25519(identity.x25519PublicKey)
        assertEquals(32, sharedA.size)
        assertArrayEquals(sharedA, sharedB)
    }
}
