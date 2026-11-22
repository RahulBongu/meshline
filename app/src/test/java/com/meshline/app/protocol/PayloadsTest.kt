package com.meshline.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shape tests for the Feature 1–3 payloads: REACTION, TYPING, GEO_PING. */
class PayloadsTest {

    private val targetId = ByteArray(32) { (it * 7 + 1).toByte() }

    // ---- REACTION ----

    @Test
    fun `reaction add round trip`() {
        val reaction = Payloads.ReactionPayload(Payloads.ReactionPayload.KIND_ADD, "👍", targetId)
        val decoded = Payloads.decodeReaction(Payloads.encodeReaction(reaction))
        assertEquals(Payloads.ReactionPayload.KIND_ADD, decoded.kind)
        assertEquals("👍", decoded.emoji)
        assertArrayEquals(targetId, decoded.targetId)
    }

    @Test
    fun `reaction remove round trip`() {
        val reaction = Payloads.ReactionPayload(Payloads.ReactionPayload.KIND_REMOVE, "❤️", ByteArray(32))
        val decoded = Payloads.decodeReaction(Payloads.encodeReaction(reaction))
        assertEquals(Payloads.ReactionPayload.KIND_REMOVE, decoded.kind)
        assertEquals("❤️", decoded.emoji)
        assertArrayEquals(ByteArray(32), decoded.targetId)
    }

    @Test
    fun `reaction encode is strict`() {
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.encodeReaction(Payloads.ReactionPayload(Payloads.ReactionPayload.KIND_ADD, "", targetId))
        }
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.encodeReaction(Payloads.ReactionPayload(Payloads.ReactionPayload.KIND_ADD, "a".repeat(9), targetId))
        }
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.encodeReaction(Payloads.ReactionPayload(99.toByte(), "👍", targetId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Payloads.encodeReaction(Payloads.ReactionPayload(Payloads.ReactionPayload.KIND_ADD, "👍", ByteArray(31)))
        }
    }

    @Test
    fun `reaction decode rejects malformed payloads`() {
        // Unknown kind.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeReaction(ByteArray(35).also { it[0] = 99.toByte() })
        }
        // Empty emoji: kind + len=0 + 32 target bytes.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeReaction(ByteArray(34).also { it[0] = Payloads.ReactionPayload.KIND_ADD })
        }
        // Control character inside a multi-byte emoji.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeReaction(
                ByteArray(36).also {
                    it[0] = Payloads.ReactionPayload.KIND_ADD
                    it[1] = 2
                    it[2] = 'a'.code.toByte()
                    it[3] = '\u0001'.code.toByte()
                }
            )
        }
        // Control character as the emoji itself (passes the length checks).
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeReaction(
                ByteArray(35).also {
                    it[0] = Payloads.ReactionPayload.KIND_ADD
                    it[1] = 1
                    it[2] = '\u0001'.code.toByte()
                }
            )
        }
        // Truncated: declares a target shorter than the wire form.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeReaction(
                ByteArray(10).also { it[0] = Payloads.ReactionPayload.KIND_ADD; it[1] = 1; it[2] = 'x'.code.toByte() }
            )
        }
    }

    // ---- TYPING ----

    @Test
    fun `typing encodes as an empty payload`() {
        val bytes = Payloads.encodeTyping()
        assertEquals(0, bytes.size)
        Payloads.decodeTyping(bytes)
    }

    @Test
    fun `typing decode rejects non-empty payloads`() {
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeTyping(byteArrayOf(1))
        }
    }

    // ---- GEO_PING ----

    @Test
    fun `geo ping round trip`() {
        val ping = Payloads.GeoPingPayload("9q8yy", "RH")
        val decoded = Payloads.decodeGeoPing(Payloads.encodeGeoPing(ping))
        assertEquals("9q8yy", decoded.geohash)
        assertEquals("RH", decoded.name)
    }

    @Test
    fun `geo ping round trip with a max-length name`() {
        val name = "A".repeat(Payloads.GeoPingPayload.MAX_NAME_CHARS)
        val ping = Payloads.GeoPingPayload("zz0", name)
        val decoded = Payloads.decodeGeoPing(Payloads.encodeGeoPing(ping))
        assertEquals("zz0", decoded.geohash)
        assertEquals(name, decoded.name)
    }

    @Test
    fun `geo ping encode is strict`() {
        // Empty geohash.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.encodeGeoPing(Payloads.GeoPingPayload("", "RH"))
        }
        // Over-long geohash.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.encodeGeoPing(Payloads.GeoPingPayload("1234567890123", "RH"))
        }
        // Over-long name.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.encodeGeoPing(Payloads.GeoPingPayload("9q8yy", "A".repeat(33)))
        }
    }

    @Test
    fun `geo ping decode rejects malformed payloads`() {
        // Length mismatch: geoLen=2 but the trailing layout disagrees.
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeGeoPing(ByteArray(5).also { it[0] = 2 })
        }
        // Control character in the geohash (passes the length checks).
        assertThrows(PacketException.InvalidPacket::class.java) {
            Payloads.decodeGeoPing(
                ByteArray(3).also {
                    it[0] = 1
                    it[1] = '\u0001'.code.toByte()
                    it[2] = 0
                }
            )
        }
        // Too short.
        assertThrows(PacketException.InvalidPacket::class.java) { Payloads.decodeGeoPing(ByteArray(1)) }
    }
}
