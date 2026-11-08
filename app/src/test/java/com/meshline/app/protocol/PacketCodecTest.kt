package com.meshline.app.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PacketCodec tests (Phase A acceptance: unit tests + test vectors).
 *
 * Covers: byte-exact test vectors (§1 layout, big-endian), round-trip,
 * every fail-closed decode error in PROTOCOL.md §§2-4, flag consistency,
 * payload-type shapes (announce / public / receipt / leave), and fragmentation.
 *
 * The expectedHex values come from test-vectors.json, which was assembled
 * independently from PROTOCOL.md (not by running the codec), so a systematic
 * codec bug cannot pass by construction.
 */
class PacketCodecTest {

    private val sender = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
    private val recipient = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
    private val now = 1_700_000_100_000L // fixed clock for deterministic tests

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun packet(
        type: PacketType = PacketType.PUBLIC_MESSAGE,
        ttl: Int = 7,
        payload: ByteArray = "hi".toByteArray(Charsets.UTF_8),
        recip: ByteArray = PacketCodec.BROADCAST_ID,
        sig: ByteArray? = null,
        ts: Long = now,
    ) = MeshPacket(
        version = PacketCodec.PROTOCOL_VERSION,
        type = type,
        ttl = ttl,
        flags = ((if (!recip.contentEquals(PacketCodec.BROADCAST_ID)) 0x01 else 0) or
                (if (sig != null) 0x02 else 0)).toByte(),
        timestampMs = ts,
        payload = payload,
        senderId = sender,
        recipientId = recip,
        signature = sig,
    )

    @Test
    fun `test vectors are byte-exact`() {
        val jsonText = javaClass.getResourceAsStream("/test-vectors.json")!!.bufferedReader().readText()
        val root = JSONObject(jsonText)
        assertEquals(1, root.getInt("protocolVersion"))
        val vectors = root.getJSONArray("vectors")
        assertEquals(5, vectors.length())
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val expected = unhex(v.getString("expectedHex"))
            val recipBytes = unhex(v.getString("recipientIdHex"))
            val sigBytes = if (v.isNull("signatureHex")) null else unhex(v.getString("signatureHex"))
            val pkt = MeshPacket(
                version = v.getInt("version"),
                type = PacketType.valueOf(v.getString("type")),
                ttl = v.getInt("ttl"),
                flags = ((if (!recipBytes.contentEquals(PacketCodec.BROADCAST_ID)) 0x01 else 0) or
                        (if (sigBytes != null) 0x02 else 0)).toByte(),
                timestampMs = v.getLong("timestampMs"),
                payload = unhex(v.getString("payloadHex")),
                senderId = unhex(v.getString("senderIdHex")),
                recipientId = recipBytes,
                signature = sigBytes,
            )
            // Encode must hit the independent expected bytes exactly.
            val encoded = PacketCodec.encode(pkt)
            assertEquals("vector $name encode", hex(expected), hex(encoded))
            // Decode must recover every field and re-encode identically.
            val decoded = PacketCodec.decode(expected, nowMs = 1_700_000_200_000L)
            assertEquals("vector $name type", pkt.type, decoded.type)
            assertEquals("vector $name ttl", pkt.ttl, decoded.ttl)
            assertEquals("vector $name ts", pkt.timestampMs, decoded.timestampMs)
            assertArrayEquals("vector $name payload", pkt.payload, decoded.payload)
            assertArrayEquals("vector $name sender", pkt.senderId, decoded.senderId)
            assertArrayEquals("vector $name recipient", pkt.recipientId, decoded.recipientId)
            if (pkt.signature == null) assertNull(decoded.signature) else assertArrayEquals(pkt.signature, decoded.signature)
            assertEquals("vector $name round-trip", hex(encoded), hex(PacketCodec.encode(decoded)))
            // Pin the signing input when the vector carries one (§3.1).
            if (v.has("signingInputHex") && !v.isNull("signingInputHex")) {
                assertEquals("vector $name signingInput", v.getString("signingInputHex"), hex(PacketCodec.signingInput(pkt)))
            }
        }
    }

    @Test
    fun `same packet at two TTLs shares messageId and signingInput`() {
        // PROTOCOL.md §3.1: TTL decrements per hop but the signature (and dedupe
        // identity) must not change. The signing input and message ID therefore
        // exclude TTL.
        val sig = ByteArray(64) { it.toByte() }
        val atSeven = packet(type = PacketType.PUBLIC_MESSAGE, ttl = 7, sig = sig)
        val atThree = packet(type = PacketType.PUBLIC_MESSAGE, ttl = 3, sig = sig)
        val wireSeven = PacketCodec.encode(atSeven)
        val wireThree = PacketCodec.encode(atThree)
        // Wire bytes differ in exactly one byte: offset 2 (TTL).
        assertEquals(wireSeven.size, wireThree.size)
        val diffs = wireSeven.indices.filter { wireSeven[it] != wireThree[it] }
        assertEquals(listOf(2), diffs)
        assertEquals(0x07.toByte(), wireSeven[2])
        assertEquals(0x03.toByte(), wireThree[2])
        // Message ID and signing input are identical...
        assertArrayEquals(atSeven.messageId, atThree.messageId)
        assertArrayEquals(PacketCodec.signingInput(atSeven), PacketCodec.signingInput(atThree))
        assertArrayEquals(PacketCodec.signingInput(wireSeven), PacketCodec.signingInput(wireThree))
        // ...and both equal the bytes-level input (TTL zeroed, signature stripped).
        val fromPacket = PacketCodec.signingInput(atSeven)
        assertEquals(PacketCodec.HEADER_BYTES + atSeven.payload.size, fromPacket.size)
        assertEquals(0.toByte(), fromPacket[2]) // TTL zeroed
        assertEquals(0x02.toByte(), fromPacket[3]) // HAS_SIGNATURE stays set on the wire shape
    }

    @Test
    fun `header byte offsets and header size are canonical`() {
        val encoded = PacketCodec.encode(packet())
        assertEquals("header is 32 bytes even without recipient/signature", 32 + 2, encoded.size)
        assertEquals(0x01.toByte(), encoded[0])                          // version
        assertEquals(PacketType.PUBLIC_MESSAGE.code, encoded[1])         // type
        assertEquals(7.toByte(), encoded[2])                             // ttl
        assertEquals(0x00.toByte(), encoded[3])                          // flags: broadcast+unsigned
        assertArrayEquals(byteArrayOf(0x00, 0x00), encoded.copyOfRange(12, 14)) // reserved
        assertEquals("payload len BE", 2, ((encoded[14].toInt() and 0xFF) shl 8) or (encoded[15].toInt() and 0xFF))
        assertArrayEquals(sender, encoded.copyOfRange(16, 24))
        assertArrayEquals(PacketCodec.BROADCAST_ID, encoded.copyOfRange(24, 32))
    }

    @Test
    fun `directed packet sets HAS_RECIPIENT and signed sets HAS_SIGNATURE`() {
        val sig = ByteArray(64) { it.toByte() }
        val directed = PacketCodec.encode(packet(recip = recipient))
        assertEquals(0x01.toByte(), directed[3])
        val signed = PacketCodec.encode(packet(sig = sig))
        assertEquals(0x02.toByte(), signed[3])
        val both = PacketCodec.encode(packet(recip = recipient, sig = sig))
        assertEquals(0x03.toByte(), both[3])
    }

    @Test
    fun `round-trip for every type`() {
        val sig = ByteArray(64) { 0xAB.toByte() }
        for (type in PacketType.entries) {
            val sigForType = if (type in listOf(PacketType.ANNOUNCE, PacketType.PUBLIC_MESSAGE, PacketType.RECEIPT, PacketType.LEAVE)) sig else null
            val recip = if (type == PacketType.PRIVATE_MESSAGE || type == PacketType.RECEIPT) recipient else PacketCodec.BROADCAST_ID
            val pkt = packet(type = type, recip = recip, sig = sigForType, payload = byteArrayOf(1, 2, 3))
            val encoded = PacketCodec.encode(pkt)
            val decoded = PacketCodec.decode(encoded, nowMs = now)
            assertEquals("$type round-trip", pkt, decoded)
            assertEquals("$type re-encode", hex(encoded), hex(PacketCodec.encode(decoded)))
        }
    }

    // ---- Decode failures: fail-closed, never relay (PROTOCOL.md §8) ----

    @Test
    fun `rejects wrong version as hard error`() {
        val bad = PacketCodec.encode(packet()).also { it[0] = 0x02 }
        val e = assertThrows(PacketException.UnsupportedVersion::class.java) { PacketCodec.decode(bad, nowMs = now) }
        assertEquals(2, e.version)
    }

    @Test
    fun `rejects unknown type as soft error`() {
        val bad = PacketCodec.encode(packet()).also { it[1] = 0x42 }
        val e = assertThrows(PacketException.UnknownType::class.java) { PacketCodec.decode(bad, nowMs = now) }
        assertEquals(0x42, e.code)
    }

    @Test
    fun `rejects ttl above max`() {
        val bad = PacketCodec.encode(packet()).also { it[2] = 0x08 }
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(bad, nowMs = now) }
    }

    @Test
    fun `rejects reserved and unsupported flags`() {
        val reserved = PacketCodec.encode(packet()).also { it[3] = 0x10 }
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(reserved, nowMs = now) }
        val compressed = PacketCodec.encode(packet()).also { it[3] = 0x04 }
        assertThrows(PacketException.UnsupportedFlag::class.java) { PacketCodec.decode(compressed, nowMs = now) }
    }

    @Test
    fun `rejects length mismatch and trailing garbage`() {
        val good = PacketCodec.encode(packet())
        val truncated = good.copyOf(good.size - 1)
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(truncated, nowMs = now) }
        val trailing = good + byteArrayOf(0x00)
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(trailing, nowMs = now) }
        val headerLie = good.clone().also { it[14] = 0x00; it[15] = 0x05 } // says 5 payload bytes, has 2
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(headerLie, nowMs = now) }
    }

    @Test
    fun `rejects non-zero reserved bytes, broadcast sender, flag inconsistency`() {
        fun expectInvalid(mutate: (ByteArray) -> Unit) {
            val bad = PacketCodec.encode(packet()).also(mutate)
            assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(bad, nowMs = now) }
        }
        expectInvalid { it[12] = 0x01 }                                       // reserved
        expectInvalid { for (i in 16 until 24) it[i] = 0xFF.toByte() }        // broadcast sender
        expectInvalid { it[3] = 0x01 }                                        // says recipient, but recipient is broadcast
        // And the mirror: recipient present but flag clear.
        val directed = PacketCodec.encode(packet(recip = recipient))
        directed[3] = 0x00
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(directed, nowMs = now) }
    }

    @Test
    fun `timestamp window is loose but bounded`() {
        val good = PacketCodec.encode(packet())
        // 2 days in the past: allowed (drift + store-and-forward). 10 days: rejection.
        PacketCodec.decode(good, nowMs = now) // sanity
        val farFuture = PacketCodec.encode(packet(ts = now + 48L * 3600 * 1000))
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(farFuture, nowMs = now) }
        val stale = PacketCodec.encode(packet(ts = now - 8L * 24 * 3600 * 1000))
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(stale, nowMs = now) }
        // Within ±tolerance succeeds.
        PacketCodec.decode(PacketCodec.encode(packet(ts = now - 2L * 24 * 3600 * 1000)), nowMs = now)
    }

    @Test
    fun `oversized packet is rejected not truncated`() {
        // MeshPacket's init check refuses it (fail fast at construction time):
        // callers must fragment first. IllegalStateException is what `check`
        // throws in Kotlin; IllegalArgumentException would also be acceptable
        // but the test pins today's behaviour.
        assertThrows(IllegalStateException::class.java) {
            packet(payload = ByteArray(PacketCodec.MAX_PAYLOAD_BYTES + 1) { 0x00 })
        }
        // Wire-level guard too: an oversized byte array on decode is invalid.
        val wire = ByteArray(PacketCodec.MAX_PACKET_BYTES + 1)
        assertThrows(PacketException.InvalidPacket::class.java) { PacketCodec.decode(wire, nowMs = now) }
    }

    // ---- Payload shapes ----

    @Test
    fun `announce round-trip and signature covers header plus payload`() {
        val announce = Payloads.Announce(
            nickname = "anon4821",
            ed25519PublicKey = ByteArray(32) { 0x11 },
            x25519PublicKey = ByteArray(32) { 0x22 },
            capabilities = (Payloads.Capabilities.RELAY_ENABLED.toInt() or
                Payloads.Capabilities.SUPPORTS_NOISE.toInt()).toByte(),
        )
        val payload = Payloads.encodeAnnounce(announce)
        assertEquals(announce, Payloads.decodeAnnounce(payload))
        // The signature must cover exactly header(32) + payload — changing one payload
        // byte must (in Phase D) fail verification. Here we pin the slice the signer uses.
        val pkt = packet(type = PacketType.ANNOUNCE, payload = payload, sig = ByteArray(64))
        val encoded = PacketCodec.encode(pkt)
        assertEquals(PacketCodec.HEADER_BYTES + payload.size + 64, encoded.size)
    }

    @Test
    fun `public text enforces 500 char cap and nickname is sanitised`() {
        Payloads.encodePublicText("a".repeat(500))
        assertThrows(PacketException.InvalidPacket::class.java) { Payloads.encodePublicText("a".repeat(501)) }
        assertThrows(PacketException.InvalidPacket::class.java) { Payloads.encodePublicText("") }
        assertEquals("a b", Payloads.sanitizeNickname("  a   b\n"))
        assertThrows(PacketException.InvalidPacket::class.java) { Payloads.sanitizeNickname("x".repeat(33)) }
    }

    @Test
    fun `receipt and leave round-trip`() {
        val messageId = ByteArray(32) { 0x07 }
        val receipt = Payloads.encodeReceipt(Payloads.Receipt(Payloads.Receipt.KIND_DELIVERED, messageId))
        assertEquals(Payloads.Receipt(Payloads.Receipt.KIND_DELIVERED, messageId), Payloads.decodeReceipt(receipt))
        assertThrows(PacketException.InvalidPacket::class.java) { Payloads.decodeReceipt(byteArrayOf(9, 0)) }
        assertArrayEquals(sender, Payloads.decodeLeave(Payloads.encodeLeave(sender)))
    }

    @Test
    fun `messageId is stable and payload-sensitive`() {
        val a = packet(payload = "same".toByteArray())
        val b = packet(payload = "same".toByteArray())
        val c = packet(payload = "different".toByteArray())
        assertEquals(32, a.messageId.size)
        assertArrayEquals(a.messageId, b.messageId)
        assertTrue(!a.messageId.contentEquals(c.messageId))
    }

    @Test
    fun `ids and fingerprint helpers are 8 bytes and 64 hex`() {
        val id = MessageIds.idFromPublicKey(ByteArray(32) { 0x5A })
        assertEquals(8, id.size)
        val fp = MessageIds.fingerprint(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ---- Fragmentation ----

    @Test
    fun `small packet is not fragmented`() {
        val pkt = packet(payload = "hi".toByteArray())
        val encoded = PacketCodec.encode(pkt)
        val parts = Fragmentation.split(pkt, encoded, maxBytesPerWrite = 512)
        assertEquals(1, parts.size)
        assertEquals(pkt, parts[0])
    }

    @Test
    fun `large packet fragments and reassembles exactly`() {
        val payload = ByteArray(2000) { (it % 250).toByte() }
        val pkt = packet(payload = payload)
        val encoded = PacketCodec.encode(pkt)
        val mtuPayload = 509 // MTU 512 - 3
        val fragments = Fragmentation.split(pkt, encoded, mtuPayload)
        assertTrue("expected multiple fragments, got ${fragments.size}", fragments.size > 1)
        assertTrue(fragments.all { it.type == PacketType.FRAGMENT })
        for (f in fragments) {
            val wire = PacketCodec.encode(f)
            assertTrue("fragment wire ${wire.size} > mtu $mtuPayload", wire.size <= mtuPayload)
        }
        // Reassemble (in shuffled order to prove order-independence) and decode.
        val reassembler = Reassembler()
        var assembled: ByteArray? = null
        // Deterministic "shuffle": reverse order.
        for (f in fragments.reversed()) {
            val result = reassembler.offer(PacketCodec.decode(PacketCodec.encode(f), nowMs = now), nowMs = now)
            if (result != null) assembled = result
        }
        assertNotNull(assembled)
        val decoded = PacketCodec.decode(assembled!!, nowMs = now)
        assertArrayEquals(encoded, assembled)
        assertArrayEquals(payload, decoded.payload)
        assertEquals(pkt, decoded)
    }

    @Test
    fun `fragment parser rejects bad index and size`() {
        val badTotal = ByteArray(Fragmentation.FRAGMENT_PREFIX_BYTES + 1)
        badTotal[34] = 0; badTotal[35] = 1 // total = 1
        assertThrows(PacketException.InvalidPacket::class.java) { Fragmentation.parseFragmentPayload(badTotal) }
        assertThrows(PacketException.InvalidPacket::class.java) { Fragmentation.parseFragmentPayload(byteArrayOf(1, 2)) }
    }
}
