package com.meshline.app.router

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.transport.SimulatorConfig
import com.meshline.app.transport.SimulatorTransport
import com.meshline.app.transport.Transport
import com.meshline.app.transport.VirtualNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unknown-sender holding buffer (PROTOCOL.md §8.1).
 *
 * A packet whose sender key is not known yet is held (32 total / 8 per
 * connection / 3 s) instead of being dropped, and released when the matching
 * verified ANNOUNCE arrives. `droppedUnknownKey` counts **only** timeouts.
 *
 * The tests drive the real [MeshRouter] over the in-memory simulator:
 *  - (a) data sent 200 ms after the sender's announce still crosses 4 hops,
 *  - (b) an unknown-sender flood cannot exceed the caps nor delay real traffic,
 *  - (c) a held packet that never gets its key times out and is counted,
 *  - (d) the sender waits out the settle window before its first data packet.
 */
class HoldingBufferTest {

    /** Frozen simulated packet clock (the tests drive time explicitly). */
    private val now = 1_700_000_100_000L

    private val keypairs = mutableMapOf<Int, KeyPair>()
    private fun keypair(i: Int): KeyPair = keypairs.getOrPut(i) {
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }

    private fun edPk(i: Int): ByteArray = keypair(i).public.encoded.copyOfRange(12, 44)

    private fun sid(i: Int): ByteArray = MessageIds.idFromPublicKey(edPk(i))

    private fun sign(priv: KeyPair, input: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").apply { initSign(priv.private); update(input) }.sign()

    private fun verifySig(rawPub: ByteArray, input: ByteArray, sig: ByteArray): Boolean = try {
        val x509 = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + rawPub
        val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(x509))
        Signature.getInstance("Ed25519").apply { initVerify(pub); update(input) }.verify(sig)
    } catch (_: Exception) {
        false
    }

    private fun announcePacket(i: Int, ts: Long = now): MeshPacket {
        val payload = Payloads.encodeAnnounce(
            Payloads.Announce(
                nickname = "node$i",
                ed25519PublicKey = edPk(i),
                x25519PublicKey = ByteArray(32) { (i + it).toByte() },
                capabilities = Payloads.Capabilities.RELAY_ENABLED,
            )
        )
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.ANNOUNCE, 7, 0x00, ts,
            payload, sid(i), PacketCodec.BROADCAST_ID, null,
        )
        return unsigned.copy(
            flags = 0x02,
            signature = sign(keypair(i), PacketCodec.signingInput(unsigned)),
        )
    }

    private fun publicPacket(i: Int, ts: Long, recipient: ByteArray? = null): MeshPacket {
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, 7,
            if (recipient == null) 0x00 else 0x01, ts,
            "hello".toByteArray(), sid(i), recipient ?: PacketCodec.BROADCAST_ID, null,
        )
        return unsigned.copy(
            flags = if (recipient == null) 0x02 else 0x03,
            signature = sign(keypair(i), PacketCodec.signingInput(unsigned)),
        )
    }

    private data class Node(
        val id: ByteArray,
        val router: MeshRouter,
        val deliveredCount: AtomicInteger,
        val delivered: MutableList<MeshPacket>,
        val jobs: List<Job>,
    ) {
        fun hasPublicMessage(): Boolean = delivered.any { it.type == PacketType.PUBLIC_MESSAGE }
    }

    /**
     * Transport wrapper that delays ANNOUNCE packets on the wire. This models a
     * slow announce path: the ANNOUNCE lags the data, which is exactly the
     * race the holding buffer exists for. The delay is applied per hop and
     * does **not** block the caller (the delivery is scheduled instead), so
     * the receive loop stays responsive.
     */
    private class SlowAnnounceTransport(
        private val inner: SimulatorTransport,
        private val scope: CoroutineScope,
        private val delayMs: Long,
    ) : Transport {
        override val localId: ByteArray get() = inner.localId
        override val incoming get() = inner.incoming
        override val neighbours get() = inner.neighbours

        override suspend fun sendTo(peer: ByteArray, packet: ByteArray) {
            if (delayMs > 0 && isAnnounceBytes(packet)) {
                scope.launch { delay(delayMs); inner.sendTo(peer, packet) }
            } else {
                inner.sendTo(peer, packet)
            }
        }

        override suspend fun broadcast(packet: ByteArray) {
            if (delayMs > 0 && isAnnounceBytes(packet)) {
                scope.launch { delay(delayMs); inner.broadcast(packet) }
            } else {
                inner.broadcast(packet)
            }
        }

        override suspend fun connect(peer: ByteArray) = inner.connect(peer)
        override suspend fun disconnect(peer: ByteArray) = inner.disconnect(peer)
        override fun stop() = inner.stop()
    }
    /**
     * Builds a mesh. Nodes know only their direct neighbours (like the D1
     * key-knowledge model); every other key is learned from a verified
     * ANNOUNCE.
     */
    private fun buildNodes(
        adjacency: Map<Int, Set<Int>>,
        scope: CoroutineScope,
        announceDelayMs: Long = 0,
        settleMs: Long = 0,
        nowProvider: () -> Long = { now },
    ): List<Node> {
        val hexAdj = adjacency.entries.associate { (i, neigh) ->
            VirtualNetwork.hex(sid(i)) to neigh.map { VirtualNetwork.hex(sid(it)) }.toSet()
        }
        val network = VirtualNetwork(hexAdj, SimulatorConfig(seed = 1L))
        val nodes = mutableListOf<Node>()
        for (i in adjacency.keys) {
            val nid = sid(i)
            val inner = SimulatorTransport(nid, network, scope)
            val t: Transport = SlowAnnounceTransport(inner, scope, announceDelayMs)
            // Known keys: the node's own plus its direct neighbours.
            val known = mutableMapOf<String, ByteArray>()
            known[hex(nid)] = edPk(i)
            for (n in adjacency.getValue(i)) known[hex(sid(n))] = edPk(n)
            val config = RouterConfig(
                jitterMinMs = 0, jitterMaxMs = 0,
                nowProvider = nowProvider,
                seed = 1000L + i,
                newPeerSettleMs = settleMs,
            )
            val r = MeshRouter(
                ownId = nid,
                transport = t,
                config = config,
                neighboursProvider = { network.neighboursOf(nid) },
                connectedPeersProvider = { network.neighboursOf(nid) },
                signatureVerifier = { pkt, _ -> verifyPacket(pkt, known) },
            )
            val delivered = Collections.synchronizedList(mutableListOf<MeshPacket>())
            val deliveredCount = AtomicInteger(0)
            val j1 = scope.launch { r.delivered.collect { delivered += it; deliveredCount.incrementAndGet() } }
            val j2 = scope.launch {
                inner.incoming.collect { (from, bytes) -> r.onReceive(bytes, from) }
            }
            nodes += Node(nid, r, deliveredCount, delivered, listOf(j1, j2))
        }
        return nodes
    }

    /** The D1 key-knowledge verifier: announce teaches, everything else needs the key. */
    private fun verifyPacket(pkt: MeshPacket, known: MutableMap<String, ByteArray>): SignatureCheck {
        val sig = pkt.signature ?: return SignatureCheck.BAD_SIGNATURE
        if (pkt.type == PacketType.ANNOUNCE) {
            val ann = try {
                Payloads.decodeAnnounce(pkt.payload)
            } catch (_: Exception) {
                return SignatureCheck.BAD_SIGNATURE
            }
            if (!MessageIds.idFromPublicKey(ann.ed25519PublicKey).contentEquals(pkt.senderId)) {
                return SignatureCheck.BAD_SIGNATURE
            }
            if (!verifySig(ann.ed25519PublicKey, PacketCodec.signingInput(pkt), sig)) {
                return SignatureCheck.BAD_SIGNATURE
            }
            known[hex(pkt.senderId)] = ann.ed25519PublicKey
            return SignatureCheck.OK
        }
        val key = known[hex(pkt.senderId)] ?: return SignatureCheck.UNKNOWN_SENDER
        return if (verifySig(key, PacketCodec.signingInput(pkt), sig)) {
            SignatureCheck.OK
        } else {
            SignatureCheck.BAD_SIGNATURE
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private companion object {
        /** Frozen clock used only to decode packets and look at their type. */
        const val DECODE_NOW = 1_700_000_100_000L

        fun isAnnounceBytes(raw: ByteArray): Boolean = try {
            PacketCodec.decode(raw, nowMs = DECODE_NOW).type == PacketType.ANNOUNCE
        } catch (_: Exception) {
            false
        }
    }

    /** Builds a packet from a key pair we have never announced (unknown sender). */
    private fun foreignPacket(key: KeyPair, ts: Long, text: String): MeshPacket {
        val pk = key.public.encoded.copyOfRange(12, 44)
        val unsigned = MeshPacket(
            PacketCodec.PROTOCOL_VERSION, PacketType.PUBLIC_MESSAGE, 7, 0x00, ts,
            text.toByteArray(), MessageIds.idFromPublicKey(pk), PacketCodec.BROADCAST_ID, null,
        )
        return unsigned.copy(flags = 0x02, signature = sign(key, PacketCodec.signingInput(unsigned)))
    }

    /** Lets the delivery collector run (the flow is delivered on another coroutine). */
    private suspend fun settle() = delay(100)

    // ---------------------------------------------------------------- tests

    @Test
    fun `data sent 200 ms after the announce is delivered across 4 hops`() = runBlocking {
        // Line: 0 - 1 - 2 - 3 - 4 (four hops from 0 to 4).
        val adjacency = mapOf(
            0 to setOf(1),
            1 to setOf(0, 2),
            2 to setOf(1, 3),
            3 to setOf(2, 4),
            4 to setOf(3),
        )
        // The ANNOUNCE lags 300 ms per hop, so the data (sent 200 ms after the
        // announce) overtakes it at every hop and has to be held.
        val nodes = buildNodes(adjacency, this, announceDelayMs = 300)
        try {
            nodes[0].router.onLocalSend(announcePacket(0))
            delay(200)
            nodes[0].router.onLocalSend(publicPacket(0, ts = now + 1_000, recipient = sid(4)))

            val deliveredAtFarEnd = withTimeoutOrNull(8_000) {
                while (!nodes[4].hasPublicMessage()) delay(20)
                true
            } ?: false
            assertTrue("public message never reached the far end", deliveredAtFarEnd)

            // The hold happened and was released — nothing timed out.
            val held = nodes.sumOf { it.router.stats.heldTotal }
            val released = nodes.sumOf { it.router.stats.releasedPending }
            assertTrue("no packet was ever held (held=$held)", held > 0)
            assertTrue("held packets were not released (released=$released)", released > 0)
            assertEquals("held packets must not time out", 0, nodes.sumOf { it.router.stats.droppedUnknownKey })
            assertEquals("nothing may be dropped for a full buffer", 0, nodes.sumOf { it.router.stats.droppedBufferFull })
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `unknown sender flood cannot exceed the buffer caps nor delay real traffic`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildNodes(adjacency, this)
        try {
            val victim = nodes[1]
            val foreignKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val from = listOf(nodes[0].id, nodes[2].id)

            // 20 packets from each of two connections, all from a sender we have
            // never heard of (its key is never announced).
            repeat(20) { i ->
                victim.router.onReceive(
                    PacketCodec.encode(foreignPacket(foreignKey, now + i, "spam")),
                    from[i % 2],
                )
            }

            assertTrue("buffer exceeded its total cap", victim.router.pendingSize() <= 32)
            assertTrue(
                "buffer exceeded its per-connection cap",
                from.all { victim.router.pendingSizeForConnection(hex(it)) <= 8 },
            )
            assertEquals("per-connection cap not respected", 8, victim.router.pendingSizeForConnection(hex(from[0])))
            assertEquals("per-connection cap not respected", 8, victim.router.pendingSizeForConnection(hex(from[1])))
            assertEquals("held packets", 16, victim.router.pendingSize())
            assertTrue("overflow was not counted", victim.router.stats.droppedBufferFull > 0)
            assertEquals("nothing timed out yet", 0, victim.router.stats.droppedUnknownKey)
            settle()
            assertEquals("foreign packets must never be delivered", 0, victim.deliveredCount.get())

            // Legitimate traffic from a known sender (node 0 is a direct
            // neighbour, so its key is known) must go straight through.
            val before = victim.deliveredCount.get()
            val started = System.nanoTime()
            victim.router.onReceive(PacketCodec.encode(publicPacket(0, ts = now + 5_000)), nodes[0].id)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            settle()
            assertEquals("legitimate packet was not delivered", before + 1, victim.deliveredCount.get())
            assertTrue("legitimate packet was delayed by ${elapsedMs}ms", elapsedMs < 200)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `held packet that never gets its key times out and is counted as unknown key`() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(now)
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildNodes(adjacency, this, nowProvider = { clock.get() })
        try {
            val victim = nodes[1]
            val foreignKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            victim.router.onReceive(
                PacketCodec.encode(foreignPacket(foreignKey, now, "never")),
                nodes[0].id,
            )

            assertEquals("packet was not held", 1, victim.router.pendingSize())
            assertEquals("held packets must not count as unknown-key drops", 0, victim.router.stats.droppedUnknownKey)

            // Still inside the 3 s hold window.
            clock.set(now + 2_000)
            victim.router.sweepExpiredPending()
            assertEquals("held packet expired too early", 0, victim.router.stats.droppedUnknownKey)
            assertEquals("held packet vanished", 1, victim.router.pendingSize())

            // Past the 3 s hold window.
            clock.set(now + 3_001)
            assertEquals("timeout was not counted", 1, victim.router.sweepExpiredPending())
            assertEquals("droppedUnknownKey must count the timeout", 1, victim.router.stats.droppedUnknownKey)
            assertEquals("buffer must be empty after the sweep", 0, victim.router.pendingSize())
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `sender waits out the settle window before its first data packet to a new peer`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0))
        val settleMs = 400L
        val nodes = buildNodes(adjacency, this, settleMs = settleMs)
        try {
            // The announce is not content: it must go out immediately.
            val announceStart = System.nanoTime()
            nodes[0].router.onLocalSend(announcePacket(0))
            val announceMs = (System.nanoTime() - announceStart) / 1_000_000
            assertTrue("announce was delayed by ${announceMs}ms", announceMs < settleMs)

            // The first data packet waits for the settle window.
            val firstStart = System.nanoTime()
            nodes[0].router.onLocalSend(publicPacket(0, ts = now + 1_000))
            val firstMs = (System.nanoTime() - firstStart) / 1_000_000
            assertTrue("first data packet was not delayed (${firstMs}ms)", firstMs >= settleMs - 50)

            // The second one does not wait again.
            val secondStart = System.nanoTime()
            nodes[0].router.onLocalSend(publicPacket(0, ts = now + 2_000))
            val secondMs = (System.nanoTime() - secondStart) / 1_000_000
            assertTrue("second data packet was delayed again (${secondMs}ms)", secondMs < settleMs / 2)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }
}
