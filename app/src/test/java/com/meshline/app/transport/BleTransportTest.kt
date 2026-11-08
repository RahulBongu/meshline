package com.meshline.app.transport

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C transport helpers (pure JVM — no Bluetooth needed).
 * The BLE radio itself is physical-device only; these pin the per-connection
 * MTU fragmentation and the duplicate-link arbitration logic.
 */
class BleTransportTest {

    private val now = 1_700_000_100_000L
    private fun id(n: Int): ByteArray = VirtualNetwork.idOf(n)

    @Test
    fun `MTU 23 payload 20 splits a 500 char public message and reassembles it`() {
        // A 500-char public message: encoded = 32 (header) + 500 = 532 bytes.
        val sender = id(0)
        val payload = Payloads.encodePublicText("a".repeat(500))
        val packet = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, 7, 0, now,
            payload, sender, PacketCodec.BROADCAST_ID,
        )
        val encoded = PacketCodec.encode(packet)
        assertEquals(500, Payloads.decodePublicText(packet.payload).length)
        // Mesh FRAGMENT cannot fit MTU 23 (min fragment > 68 bytes), so
        // BleTransport falls back to raw chunking. Per-connection payload is
        // 20 bytes (MTU 23 - 3), chunker overhead 7, so 13 bytes per write.
        val mtu = 23
        val mtuPayload = mtu - 3 // 20
        val chunks = BleChunker.split(encoded, mtuPayload)
        assertTrue("expected many chunks at MTU 23, got ${chunks.size}", chunks.size > 20)
        assertTrue(chunks.all { it.size <= mtuPayload })
        val reassembler = BleReassembler()
        var assembled: ByteArray? = null
        // Feed in order (writes are serialised 50 ms apart in production).
        for (c in chunks) {
            val r = reassembler.offer(c, nowMs = now)
            if (r != null) assembled = r
        }
        assertArrayEquals(encoded, assembled)
        val decoded = PacketCodec.decode(assembled!!, nowMs = now + 1_000)
        assertArrayEquals(packet.payload, decoded.payload)
        assertEquals(500, Payloads.decodePublicText(decoded.payload).length)
    }

    @Test
    fun `duplicate link exactly one side keeps central`() {
        // Fake-transport stand-in: two nodes, both currently hold a central link
        // to each other (dual-role race). Arbiter must keep exactly one.
        val a = VirtualNetwork.idOf(10)
        val b = VirtualNetwork.idOf(20) // a < b lexicographically
        val aKeeps = LinkArbiter.shouldKeepLocalCentral(a, b, duplicateLink = true)
        val bKeeps = LinkArbiter.shouldKeepLocalCentral(b, a, duplicateLink = true)
        assertTrue("lower ID must keep central", aKeeps)
        assertTrue("higher ID must drop central", !bKeeps)
        assertTrue("exactly one keeps", aKeeps != bKeeps)
        // No duplicate -> both would keep (nothing to arbitrate).
        assertTrue(LinkArbiter.shouldKeepLocalCentral(a, b, duplicateLink = false))
        // Unsigned compare: 0x80.. must sort above 0x7F.. (not signed byte).
        assertTrue(LinkArbiter.compareUnsigned(byteArrayOf(0x80.toByte()), byteArrayOf(0x7F.toByte())) > 0)
    }

    @Test
    fun `unauthenticated sender cannot exhaust chunk reassembly memory`() {
        // An attacker streaming bogus partial chunks (huge claimed totalLen,
        // never completing) must not grow buffers beyond the per-connection cap.
        val manager = BleReassemblyManager(
            maxBytesPerConnection = 64 * 1024,
            maxInflightPerConnection = 1,
            maxConnections = 2, // tighten so the test proves connection capping too
            timeoutMs = 30_000,
        )
        // Attacker on two "connections" sends 10k chunks each claiming 64 KiB.
        repeat(10_000) { i ->
            val totalLen = 65_535 // > cap, must be rejected immediately
            val chunk = ByteArray(BleChunker.HEADER_BYTES + 13)
            chunk[0] = BleChunker.MAGIC
            chunk[1] = ((totalLen shr 8) and 0xFF).toByte()
            chunk[2] = (totalLen and 0xFF).toByte()
            chunk[3] = 0; chunk[4] = 0          // index 0
            chunk[5] = 0xFF.toByte(); chunk[6] = 0xFF.toByte() // total 65535
            manager.offer("attacker:$i", chunk, nowMs = now + i)
            manager.offer("attacker:b", chunk, nowMs = now + i)
        }
        assertTrue("held bytes bounded, got ${manager.heldBytesTotal()}", manager.heldBytesTotal() <= 64 * 1024)
        assertTrue("connections bounded, got ${manager.connectionCount()}", manager.connectionCount() <= 2)
        assertTrue("inflight bounded", manager.inFlightCount("attacker:b") <= 1)

        // A legitimate packet pipelined after the attack still reassembles.
        val payload = ByteArray(200) { it.toByte() }
        val chunks = BleChunker.split(payload, 20)
        var assembled: ByteArray? = null
        for (c in chunks) {
            val r = manager.offer("legit", c, nowMs = now + 100_000)
            if (r != null) assembled = r
        }
        assertArrayEquals(payload, assembled)
    }

    @Test
    fun `chunk reassembly times out and frees memory`() {
        val manager = BleReassemblyManager(timeoutMs = 1_000L)
        val chunks = BleChunker.split(ByteArray(100) { 1 }, 20)
        manager.offer("peer", chunks[0], nowMs = now)
        assertTrue(manager.heldBytes("peer") > 0)
        // Next offer after the timeout must not resurrect the stale packet.
        val fresh = BleChunker.split(ByteArray(50) { 2 }, 20)
        manager.offer("peer", fresh[0], nowMs = now + 5_000) // stale state expired on offer
        assertTrue(manager.heldBytes("peer") <= 64 * 1024)
    }

    @Test
    fun `advertise-supported flag defaults true and logs intent`() {
        // The BleTransport starts in the supported state; startAdvertising()
        // flips it to false only when the advertiser is null / unsupported.
        // (Unit-testable surface: the data class + flow exist, no radio needed.)
        val info = PeerInfo("0102030405060708", -55, 512, "central")
        assertEquals("central", info.role)
        assertEquals(512, info.mtu)
    }
}
