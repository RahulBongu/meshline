package com.meshline.app.noise

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.router.MeshRouter
import com.meshline.app.router.RouterConfig
import com.meshline.app.router.SignatureCheck
import com.meshline.app.transport.SimulatorConfig
import com.meshline.app.transport.SimulatorTransport
import com.meshline.app.transport.VirtualNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * Nonce ordering, duplicates and gaps (NOISE_DESIGN §4.1).
 *
 * These are the cases the explicit nonce exists for: the mesh reorders,
 * duplicates and loses packets, so a transport message must not depend on
 * arriving in order.
 *
 * The tests drive the real [SessionManager] over the mesh simulator and
 * control the *arrival order* by choosing which encrypted packets to hand to
 * the receiver.
 */
class NoiseNonceOrderingTest {

    private data class Keys(val ed25519Pk: ByteArray, val x25519Priv: ByteArray) {
        val senderId: ByteArray get() = com.meshline.app.protocol.MessageIds.idFromPublicKey(ed25519Pk)
    }

    private val keys = mutableMapOf<Int, Keys>()

    private fun keysFor(i: Int): Keys = keys.getOrPut(i) {
        val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        Keys(ed.public.encoded.copyOfRange(12, 44), ByteArray(32).also { SecureRandom().nextBytes(it) })
    }

    private data class Node(
        val id: ByteArray,
        val router: MeshRouter,
        val manager: SessionManager,
        val events: MutableList<SessionManager.Event>,
        val jobs: List<Job>,
    )

    private suspend fun buildMesh(adjacency: Map<Int, Set<Int>>, scope: CoroutineScope): List<Node> {
        val hexAdj = adjacency.entries.associate { (i, neigh) ->
            VirtualNetwork.hex(keysFor(i).senderId) to neigh.map { VirtualNetwork.hex(keysFor(it).senderId) }.toSet()
        }
        val network = VirtualNetwork(hexAdj, SimulatorConfig(seed = 11L))
        val nodes = mutableListOf<Node>()
        for (i in adjacency.keys) {
            val k = keysFor(i)
            val transport = SimulatorTransport(k.senderId, network, scope)
            val router = MeshRouter(
                ownId = k.senderId,
                transport = transport,
                config = RouterConfig(jitterMinMs = 0, jitterMaxMs = 0, nowProvider = { 1_700_000_100_000L }, seed = i.toLong()),
                neighboursProvider = { network.neighboursOf(k.senderId) },
                connectedPeersProvider = { network.neighboursOf(k.senderId) },
                signatureVerifier = { _, _ -> SignatureCheck.OK },
            )
            val manager = SessionManager(
                localStaticX25519 = k.x25519Priv,
                localEd25519PublicKey = k.ed25519Pk,
                nowProvider = { 1_700_000_100_000L },
            )
            val events = mutableListOf<SessionManager.Event>()
            val j0 = scope.launch { transport.incoming.collect { (from, bytes) -> router.onReceive(bytes, from) } }
            val j1 = scope.launch {
                router.deliveredWithPeer.collect { (from, pkt) ->
                    val produced = manager.onPacket(pkt, from)
                    events += produced
                    for (e in produced) if (e is SessionManager.Event.Outgoing) router.onLocalSend(e.packet)
                }
            }
            nodes += Node(k.senderId, router, manager, events, listOf(j0, j1))
        }
        kotlinx.coroutines.yield()
        delay(50)
        return nodes
    }

    /** Establishes a session between nodes 0 and 1 and returns them. */
    private suspend fun establish(nodes: List<Node>): Pair<Node, Node> {
        val a = nodes[0]
        val b = nodes[1]
        a.router.onLocalSend(a.manager.startHandshake(b.id)!!)
        // Both sides must be established before the test sends anything: a
        // message that arrives while the responder is still handshaking is
        // (correctly) refused as "transport message without a session".
        val ok = withTimeoutOrNull(5_000) {
            while (a.manager.session(b.id)?.state != SecureSession.State.ESTABLISHED ||
                b.manager.session(a.id)?.state != SecureSession.State.ESTABLISHED
            ) delay(10)
            true
        } ?: false
        check(ok) { "the session was never established" }
        return a to b
    }

    /** Hands a transport packet to the receiver as if it arrived over the link. */
    private suspend fun deliver(receiver: Node, sender: Node, packet: MeshPacket) {
        receiver.router.onReceive(PacketCodec.encode(packet), sender.id)
    }

    private fun plaintexts(node: Node): List<ByteArray> =
        node.events.filterIsInstance<SessionManager.Event.Plaintext>().map { it.bytes }

    @Test
    fun `message N+1 is decrypted before N`() = runBlocking {
        val nodes = buildMesh(mapOf(0 to setOf(1), 1 to setOf(0)), this)
        try {
            val (a, b) = establish(nodes)

            val first = a.manager.encrypt(b.id, "first".toByteArray())!!
            val second = a.manager.encrypt(b.id, "second".toByteArray())!!

            // The newer message arrives first.
            deliver(b, a, second)
            delay(120)
            assertEquals("the later message must be decrypted", listOf("second"), plaintexts(b).map { it.toString(Charsets.UTF_8) })

            // The older one arrives afterwards and must still decrypt: the
            // nonce is explicit, so the receiver does not need the order.
            deliver(b, a, first)
            delay(120)
            assertEquals(
                "both messages must be decrypted",
                listOf("second", "first"),
                plaintexts(b).map { it.toString(Charsets.UTF_8) },
            )
            assertEquals("no replay may be counted", 0, b.manager.counters.replaysRejected)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a duplicate is rejected by the replay window`() = runBlocking {
        val nodes = buildMesh(mapOf(0 to setOf(1), 1 to setOf(0)), this)
        try {
            val (a, b) = establish(nodes)
            val packet = a.manager.encrypt(b.id, "once".toByteArray())!!

            deliver(b, a, packet)
            delay(120)
            assertEquals(1, plaintexts(b).size)

            // The same ciphertext again — re-wrapped in a fresh outer packet so
            // the router's dedupe cannot catch it.
            deliver(b, a, packet.copy(timestampMs = packet.timestampMs + 1))
            delay(120)
            assertEquals("the duplicate must not be decrypted twice", 1, plaintexts(b).size)
            assertTrue("the duplicate must be counted", b.manager.counters.replaysRejected > 0)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a gap of 100 lost messages is tolerated inside the window`() = runBlocking {
        val nodes = buildMesh(mapOf(0 to setOf(1), 1 to setOf(0)), this)
        try {
            val (a, b) = establish(nodes)
            // 101 messages; the first 100 are "lost".
            val packets = (0 until 101).map { i ->
                a.manager.encrypt(b.id, "msg-$i".toByteArray())!!
            }
            val last = packets.last()
            deliver(b, a, last)
            delay(150)

            assertEquals("the message after the gap must be decrypted", 1, plaintexts(b).size)
            assertArrayEquals("the wrong message was decrypted", "msg-100".toByteArray(), plaintexts(b).first())
            assertEquals("the gap must not be a protocol violation", 0, b.manager.counters.protocolViolations)
            assertTrue(
                "a gap inside the window must not force a re-handshake",
                b.events.none { it is SessionManager.Event.RehandshakeNeeded },
            )
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a gap beyond the window forces a clean re-handshake`() = runBlocking {
        val nodes = buildMesh(mapOf(0 to setOf(1), 1 to setOf(0)), this)
        try {
            val (a, b) = establish(nodes)
            // 200 messages; the gap is larger than the 128-nonce window.
            val packets = (0 until 200).map { i ->
                a.manager.encrypt(b.id, "msg-$i".toByteArray())!!
            }
            deliver(b, a, packets.last())
            delay(150)

            assertEquals("the message must not be decrypted", 0, plaintexts(b).size)
            assertTrue("the drift must be counted", b.manager.counters.protocolViolations > 0)
            assertTrue(
                "a gap beyond the window must request a re-handshake",
                b.events.any { it is SessionManager.Event.RehandshakeNeeded },
            )
            // The session is marked for a re-key, so the sender will not use it.
            assertTrue("the session must be marked for a re-key", b.manager.session(a.id)!!.needsRekey)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `the sender refuses to use a session that needs a re-key`() = runBlocking {
        val nodes = buildMesh(mapOf(0 to setOf(1), 1 to setOf(0)), this)
        try {
            val (a, b) = establish(nodes)
            // Force the *sender's* session into the re-key state.
            a.manager.requestRehandshake(b.id)
            assertEquals("no message may be encrypted on a stale session", null, a.manager.encrypt(b.id, "nope".toByteArray()))
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a transport message without a session is a protocol violation`() = runBlocking {
        val nodes = buildMesh(mapOf(0 to setOf(1), 1 to setOf(0)), this)
        try {
            val a = nodes[0]
            val b = nodes[1]
            // A message from a peer we never shook hands with.
            val foreign = MeshPacket(
                version = PacketCodec.PROTOCOL_VERSION,
                type = PacketType.PRIVATE_MESSAGE,
                ttl = PacketCodec.MAX_TTL,
                flags = 0x01,
                timestampMs = 1_700_000_100_000L,
                payload = NoisePayloads.encodeTransport(0, ByteArray(32)),
                senderId = a.id,
                recipientId = b.id,
                signature = null,
            )
            deliver(b, a, foreign)
            delay(120)
            assertEquals("the message must not be delivered", 0, plaintexts(b).size)
            assertTrue("it must be counted as a violation", b.manager.counters.protocolViolations > 0)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }
}
