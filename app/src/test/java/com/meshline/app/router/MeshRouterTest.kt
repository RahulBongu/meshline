package com.meshline.app.router

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.transport.SimulatorConfig
import com.meshline.app.transport.SimulatorTransport
import com.meshline.app.transport.VirtualNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B router + simulator tests (PROTOCOL.md §8).
 * Jitter is disabled and faults are zero unless a test says otherwise.
 */
class MeshRouterTest {

    private val now = 1_700_000_100_000L
    private fun id(n: Int): ByteArray = VirtualNetwork.idOf(n)

    private fun packet(
        from: Int,
        type: PacketType = PacketType.PUBLIC_MESSAGE,
        ttl: Int = 7,
        payload: ByteArray = "hello".toByteArray(Charsets.UTF_8),
        to: Int? = null,
    ): MeshPacket {
        val recip = if (to == null) PacketCodec.BROADCAST_ID else id(to)
        val flags = (if (to != null) 0x01 else 0).toByte()
        return MeshPacket(PacketCodec.PROTOCOL_VERSION, type, ttl, flags, now, payload, id(from), recip)
    }

    private fun testConfig() = RouterConfig(jitterMinMs = 0, jitterMaxMs = 0, nowProvider = { now }, seed = 1L)

    private class FakeTransport(
        override val localId: ByteArray,
        private val neigh: Set<ByteArray>,
    ) : com.meshline.app.transport.Transport {
        val sentTo = mutableListOf<ByteArray>()
        val broadcastBytes = mutableListOf<ByteArray>()
        override suspend fun sendTo(peer: ByteArray, packet: ByteArray) { sentTo += peer; broadcastBytes += packet }
        override suspend fun broadcast(packet: ByteArray) { broadcastBytes += packet }
        override val incoming: Flow<Pair<ByteArray, ByteArray>> = MutableSharedFlow()
        override val neighbours: Flow<Set<ByteArray>> = MutableStateFlow(neigh)
        override suspend fun connect(peer: ByteArray) {}
        override suspend fun disconnect(peer: ByteArray) {}
        override fun stop() {}
        fun snapshot(): Set<ByteArray> = neigh
    }

    @Test
    fun `TTL equals 1 is delivered but never relayed`() = runBlocking {
        val transport = FakeTransport(id(1), setOf(id(2), id(3)))
        val router = MeshRouter(id(1), transport, testConfig(), neighboursProvider = { transport.snapshot() })
        val delivered = mutableListOf<MeshPacket>()
        val job = launch { router.delivered.collect { delivered += it } }
        try {
            delay(10) // let collector subscribe before emission (replay=0 flow)
            val decision = router.onReceive(PacketCodec.encode(packet(from = 0, ttl = 1)), id(0))
            assertTrue("expected Deliver for ttl=1, got $decision", decision is RouteDecision.Deliver)
            kotlinx.coroutines.delay(20) // let the collector run (runBlocking is single-threaded)
            assertEquals(1, delivered.size)
            assertTrue("TTL=1 must produce zero link transmissions", transport.broadcastBytes.isEmpty())
            assertEquals(0, router.stats.relayedForwards)
            assertEquals(0, router.stats.linkTransmissions)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `duplicate delivery is deduped and never re-relayed`() = runBlocking {
        val transport = FakeTransport(id(1), setOf(id(2)))
        val router = MeshRouter(id(1), transport, testConfig(), neighboursProvider = { transport.snapshot() })
        val delivered = mutableListOf<MeshPacket>()
        val job = launch { router.delivered.collect { delivered += it } }
        try {
            delay(10)
            val bytes = PacketCodec.encode(packet(from = 0, ttl = 7))
            router.onReceive(bytes, id(0))
            router.onReceive(bytes, id(0))
            router.onReceive(bytes, id(0))
            kotlinx.coroutines.delay(20)
            assertEquals("delivered exactly once", 1, delivered.size)
            assertEquals("relayed exactly once", 1, router.stats.relayedForwards)
            assertEquals(1, router.stats.linkTransmissions)
            assertEquals(2, router.stats.droppedDuplicate)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `directed packet to a neighbour goes direct instead of flooding`() = runBlocking {
        val transport = FakeTransport(id(0), setOf(id(1), id(2)))
        val router = MeshRouter(id(0), transport, testConfig(), neighboursProvider = { transport.snapshot() })
        val decision = router.onLocalSend(packet(from = 0, to = 2))
        assertTrue(decision is RouteDecision.Relay)
        val targets = (decision as RouteDecision.Relay).to
        assertEquals(1, targets.size)
        assertTrue(targets.any { it.contentEquals(id(2)) })
        assertEquals(1, transport.sentTo.size)
    }

    @Test
    fun `relayed copy decrements TTL and keeps messageId and signing input`() = runBlocking {
        val transport = FakeTransport(id(1), setOf(id(2)))
        val router = MeshRouter(id(1), transport, testConfig(), neighboursProvider = { transport.snapshot() })
        val original = packet(from = 0, ttl = 7)
        router.onReceive(PacketCodec.encode(original), id(0))
        assertEquals(1, transport.broadcastBytes.size)
        val relayed = PacketCodec.decode(transport.broadcastBytes[0], nowMs = now)
        assertEquals(6, relayed.ttl)
        assertArrayEquals(original.messageId, relayed.messageId)
        assertArrayEquals(PacketCodec.signingInput(original), PacketCodec.signingInput(relayed))
    }

    // ------------------------------------------------------------- simulator

    private data class MeshNode(
        val id: ByteArray,
        val transport: SimulatorTransport,
        val router: MeshRouter,
        val delivered: MutableList<MeshPacket>,
        val jobs: List<Job>,
    )

    private fun buildNodes(
        n: Int,
        network: VirtualNetwork,
        scope: CoroutineScope,
        routerCfg: RouterConfig = testConfig(),
    ): List<MeshNode> {
        val nodes = mutableListOf<MeshNode>()
        for (i in 0 until n) {
            val nid = VirtualNetwork.idOf(i)
            val t = SimulatorTransport(nid, network, scope)
            // Per-node router seed: relay-suppression draws must be independent
            // across nodes (a shared seed would make every node relay/suppress together).
            val perNodeCfg = routerCfg.copy(seed = routerCfg.seed + i * 7919L)
            val r = MeshRouter(nid, t, perNodeCfg, neighboursProvider = { network.neighboursOf(nid) })
            val delivered = mutableListOf<MeshPacket>()
            val j1 = scope.launch { r.delivered.collect { delivered += it } }
            val j2 = scope.launch { t.incoming.collect { (from, bytes) -> r.onReceive(bytes, from) } }
            nodes += MeshNode(nid, t, r, delivered, listOf(j1, j2))
        }
        return nodes
    }

    @Test
    fun `line of three A-B-C multi-hop delivery and TTL 1 stops at B`() = runBlocking {
        val network = VirtualNetwork.line(3)
        val nodes = buildNodes(3, network, this)
        try {
            nodes[0].router.onLocalSend(packet(from = 0, ttl = 7))
            withTimeout(5_000) { while (nodes[2].delivered.size < 1) kotlinx.coroutines.delay(10) }
            assertEquals(1, nodes[1].delivered.size)
            assertEquals(1, nodes[2].delivered.size)
            assertEquals(0, nodes[0].delivered.size)
            // New timestamp so the second send is a distinct message.
            val seqPacket = packet(from = 0, ttl = 1).copy(timestampMs = now + 1)
            nodes[0].router.onLocalSend(seqPacket)
            withTimeout(5_000) { while (nodes[1].delivered.size < 2) kotlinx.coroutines.delay(10) }
            kotlinx.coroutines.delay(100)
            assertEquals("C must not receive TTL=1 beyond B", 1, nodes[2].delivered.size)
        } finally { nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } } }
    }

    @Test
    fun `simulator duplicates and latency are absorbed by dedupe`() = runBlocking {
        val sim = SimulatorConfig(latencyMinMs = 1, latencyMaxMs = 5, duplicateRate = 1.0, seed = 7L)
        val network = VirtualNetwork.line(3, sim)
        val nodes = buildNodes(3, network, this)
        try {
            nodes[0].router.onLocalSend(packet(from = 0, ttl = 7))
            withTimeout(10_000) { while (nodes[2].delivered.size < 1) kotlinx.coroutines.delay(10) }
            kotlinx.coroutines.delay(150)
            assertEquals(1, nodes[1].delivered.size)
            assertEquals(1, nodes[2].delivered.size)
            assertTrue("duplicates were injected", network.stats.duplicated > 0)
            assertTrue(
                "duplicates were deduped",
                nodes[1].router.stats.droppedDuplicate + nodes[2].router.stats.droppedDuplicate > 0
            )
        } finally { nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } } }
    }

    @Test
    fun `simulator loss is visible and does not hang`() = runBlocking {
        val sim = SimulatorConfig(lossRate = 1.0, seed = 9L)
        val network = VirtualNetwork.line(3, sim)
        val nodes = buildNodes(3, network, this)
        try {
            nodes[0].router.onLocalSend(packet(from = 0, ttl = 7))
            kotlinx.coroutines.delay(200)
            assertEquals(0, nodes[1].delivered.size)
            assertEquals(0, nodes[2].delivered.size)
            assertTrue(network.stats.lost > 0)
        } finally { nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } } }
    }

    @Test
    fun `30-node grid flood delivers everywhere with bounded per-node relays`() = runBlocking {
        // 5x6 = 30 nodes. Origin is the centre node (row 2, col 2 -> index 14):
        // farthest Manhattan distance is 5, so TTL=7 (the hard cap) is enough to
        // cover the whole mesh. (A corner origin would be 9 hops from the
        // opposite corner and could never deliver — that's a TTL bound, not a bug.)
        val origin = 2 * 6 + 2
        val network = VirtualNetwork.grid(5, 6)
        val nodes = buildNodes(30, network, this)
        try {
            nodes[origin].router.onLocalSend(packet(from = origin, ttl = 7))
            withTimeout(15_000) { while (nodes.count { it.delivered.isNotEmpty() } < 29) kotlinx.coroutines.delay(20) }
            kotlinx.coroutines.delay(100)
            assertEquals(0, nodes[origin].delivered.size)
            for (i in 0 until 30) {
                if (i == origin) continue
                assertEquals("node $i delivered once, got ${nodes[i].delivered.size}", 1, nodes[i].delivered.size)
            }
            assertEquals(0, nodes[origin].router.stats.relayedForwards)
            assertEquals(1, nodes[origin].router.stats.originatedSends)
            for (i in 0 until 30) {
                if (i == origin) continue
                assertTrue(
                    "node $i relayed ${nodes[i].router.stats.relayedForwards} times (>1 = storm)",
                    nodes[i].router.stats.relayedForwards <= 1
                )
            }
            // Total relay forwards are bounded by the node count (each node at most
            // one forward per message), not by the number of duplicate arrivals.
            val totalRelayForwards = nodes.sumOf { it.router.stats.relayedForwards }
            assertTrue("total relay forwards $totalRelayForwards exceeds node count", totalRelayForwards <= 29)
            // Grid degree is <= 4 so adaptive probability is 1.0 and nothing is suppressed.
            val suppressed = nodes.sumOf { it.router.stats.droppedSuppressed }
            assertEquals(0, suppressed)
        } finally { nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } } }
    }

    @Test
    fun `TTL pinning TTL 1 first then higher TTL relays once without re-delivery`() = runBlocking {
        // Scenario: an attacker / early relay hands us the flood copy at TTL 1
        // (deliver, never relay). Moments later the real TTL 7 copy arrives from
        // another neighbour. Dedupe stored max-TTL=1, so we relay the rescue
        // copy exactly once — but must not deliver it a second time.
        val transport = FakeTransport(id(1), setOf(id(2)))
        val router = MeshRouter(id(1), transport, testConfig(), neighboursProvider = { transport.snapshot() })
        val delivered = mutableListOf<MeshPacket>()
        val job = launch { router.delivered.collect { delivered += it } }
        try {
            delay(10)
            val low = packet(from = 0, ttl = 1)
            router.onReceive(PacketCodec.encode(low), id(0))
            delay(20)
            assertEquals(1, delivered.size)
            assertEquals(0, router.stats.relayedForwards)
            // Same message (same sender/timestamp/type/payload), higher TTL.
            val high = low.copy(ttl = 7)
            assertArrayEquals(low.messageId, high.messageId)
            val decision = router.onReceive(PacketCodec.encode(high), id(0))
            delay(20)
            assertEquals("higher-TTL copy must not re-deliver", 1, delivered.size)
            assertEquals("rescue relay happened exactly once", 1, router.stats.relayedForwards)
            assertEquals(1, transport.broadcastBytes.size)
            val relayed = PacketCodec.decode(transport.broadcastBytes[0], nowMs = now)
            assertEquals(6, relayed.ttl) // decremented from the rescued TTL 7
            assertEquals(1, router.stats.ttlUpgrades)
            assertTrue("expected Relay decision for the upgrade, got $decision", decision is RouteDecision.Relay)
            // A third copy at an intermediate TTL (<= stored max 7) is a plain duplicate.
            router.onReceive(PacketCodec.encode(low.copy(ttl = 3)), id(0))
            assertEquals(1, delivered.size)
            assertEquals(1, router.stats.relayedForwards)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `invalid signature is dropped and never dedupe-cached`() = runBlocking {
        // Phase D ordering rule, pinned now: verifier runs before dedupe
        // insertion/rate-limit/relay. With a rejecting verifier, the same
        // packet twice must count as two invalids and zero dedupe entries.
        val transport = FakeTransport(id(1), setOf(id(2)))
        val rejecting = MeshRouter(id(1), transport, testConfig(), { transport.snapshot() }, signatureVerifier = { SignatureCheck.INVALID })
        val signed = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, 7, 0x02, now,
            "hello".toByteArray(Charsets.UTF_8), id(0), PacketCodec.BROADCAST_ID,
            ByteArray(64) { 0xAB.toByte() }
        )
        val bytes = PacketCodec.encode(signed)
        rejecting.onReceive(bytes, id(0))
        rejecting.onReceive(bytes, id(0))
        assertEquals(2, rejecting.stats.droppedInvalid)
        assertEquals(0, rejecting.stats.droppedDuplicate)
        assertEquals(0, rejecting.dedupeSize())
        assertEquals(0, rejecting.stats.linkTransmissions)
        assertEquals(0, rejecting.stats.delivered)
    }

    @Test
    fun `unknown sender is counted separately from an invalid signature`() = runBlocking {
        // Review item: droppedUnknownKey measures the post-join transient
        // (key not learned yet), not forged packets.
        fun signedPacket() = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, 7, 0x02, now,
            "hello".toByteArray(Charsets.UTF_8), id(0), PacketCodec.BROADCAST_ID,
            ByteArray(64) { 0xAB.toByte() }
        )

        val unknownTransport = FakeTransport(id(1), setOf(id(2)))
        val unknown = MeshRouter(
            id(1), unknownTransport, testConfig(), { unknownTransport.snapshot() },
            signatureVerifier = { SignatureCheck.UNKNOWN_SENDER },
        )
        unknown.onReceive(PacketCodec.encode(signedPacket()), id(0))
        assertEquals(1, unknown.stats.droppedUnknownKey)
        assertEquals(1, unknown.stats.droppedInvalid)

        val forgedTransport = FakeTransport(id(1), setOf(id(2)))
        val forged = MeshRouter(
            id(1), forgedTransport, testConfig(), { forgedTransport.snapshot() },
            signatureVerifier = { SignatureCheck.INVALID },
        )
        forged.onReceive(PacketCodec.encode(signedPacket()), id(0))
        assertEquals(0, forged.stats.droppedUnknownKey)
        assertEquals(1, forged.stats.droppedInvalid)
    }
}
