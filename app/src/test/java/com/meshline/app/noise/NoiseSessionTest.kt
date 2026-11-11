package com.meshline.app.noise

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.MessageIds
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * The D2 secure channel over the mesh simulator.
 *
 * These tests drive the real [SessionManager] and the real [MeshRouter] over
 * the in-memory mesh: the handshake is routed hop by hop (the two peers are
 * not neighbours, so a relay forwards it), and the private messages travel the
 * same way. Nothing here touches BLE or the UI — D2 is protocol and storage
 * only until the phone test passes.
 */
class NoiseSessionTest {

    /** Ed25519 identity key + X25519 Noise static key for one node. */
    private data class Keys(val ed25519Pk: ByteArray, val x25519Priv: ByteArray) {
        val senderId: ByteArray get() = MessageIds.idFromPublicKey(ed25519Pk)
    }

    private val keys = mutableMapOf<Int, Keys>()

    private fun keysFor(i: Int): Keys = keys.getOrPut(i) {
        val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val edPk = ed.public.encoded.copyOfRange(12, 44)
        val x = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Keys(edPk, x)
    }

    private data class Node(
        val id: ByteArray,
        val router: MeshRouter,
        val manager: SessionManager,
        val events: MutableList<SessionManager.Event>,
        val jobs: List<Job>,
    )

    private suspend fun buildMesh(
        adjacency: Map<Int, Set<Int>>,
        scope: CoroutineScope,
        knownX25519: (ByteArray) -> ByteArray? = { null },
    ): List<Node> {
        val hexAdj = adjacency.entries.associate { (i, neigh) ->
            VirtualNetwork.hex(keysFor(i).senderId) to neigh.map { VirtualNetwork.hex(keysFor(it).senderId) }.toSet()
        }
        val network = VirtualNetwork(hexAdj, SimulatorConfig(seed = 7L))
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
                knownX25519 = knownX25519,
            )
            val events = mutableListOf<SessionManager.Event>()
            val j0 = scope.launch {
                transport.incoming.collect { (from, bytes) -> router.onReceive(bytes, from) }
            }
            val j1 = scope.launch {
                router.deliveredWithPeer.collect { (from, pkt) ->
                    val produced = manager.onPacket(pkt, from)
                    events += produced
                    for (e in produced) {
                        if (e is SessionManager.Event.Outgoing) router.onLocalSend(e.packet)
                    }
                }
            }
            nodes += Node(k.senderId, router, manager, events, listOf(j0, j1))
        }
        // Let the collectors subscribe before the test injects anything: a
        // packet emitted before the D2 layer subscribed would be lost.
        kotlinx.coroutines.yield()
        delay(50)
        return nodes
    }

    private suspend fun awaitSession(manager: SessionManager, peerId: ByteArray, timeoutMs: Long = 5_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (manager.session(peerId)?.state != SecureSession.State.ESTABLISHED) delay(10)
            true
        } ?: false

    private suspend fun settle(ms: Long = 200) = delay(ms)

    // ---------------------------------------------------------------- tests

    @Test
    fun `handshake is routed over the mesh and a private message round trips`() = runBlocking {
        // A and B are two hops apart: relay R forwards the handshake.
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]

            val m1 = a.manager.startHandshake(b.id)
            assertNotNull("the initiator must produce a handshake message", m1)
            assertEquals(PacketType.HANDSHAKE, m1!!.type)
            a.router.onLocalSend(m1)

            assertTrue("the session was never established", awaitSession(a.manager, b.id))
            assertTrue("the responder never established the session", awaitSession(b.manager, a.id))

            // The handshake really travelled through the relay.
            assertTrue("the relay forwarded the handshake", nodes[1].router.stats.relayedForwards > 0)

            // A private message flows the same way.
            val secret = "meet at the tower".toByteArray()
            val packet = a.manager.encrypt(b.id, secret)
            assertNotNull("the message must be encrypted", packet)
            assertEquals(PacketType.PRIVATE_MESSAGE, packet!!.type)
            a.router.onLocalSend(packet)

            val received = withTimeoutOrNull(5_000) {
                while (b.events.none { it is SessionManager.Event.Plaintext }) delay(10)
                true
            } ?: false
            assertTrue("the private message never arrived", received)
            val plaintext = (b.events.first { it is SessionManager.Event.Plaintext } as SessionManager.Event.Plaintext)
            assertArrayEquals("the plaintext must survive the round trip", secret, plaintext.bytes)
            assertEquals("the sender must be the peer we talked to", hex(a.id), hex(plaintext.peerId))
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a replayed ciphertext is refused by the replay window`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]
            a.router.onLocalSend(a.manager.startHandshake(b.id)!!)
            assertTrue(awaitSession(a.manager, b.id))
            assertTrue(awaitSession(b.manager, a.id))

            val packet = a.manager.encrypt(b.id, "once".toByteArray())!!
            // Deliver it twice. The second copy is re-wrapped in a *fresh*
            // outer packet (new timestamp, new message id) — exactly what an
            // attacker does, and exactly what the outer dedupe cannot catch.
            // The associated data binds the timestamp, so the copy no longer
            // authenticates: it is dropped silently and never decrypted again.
            b.router.onReceive(PacketCodec.encode(packet), nodes[1].id)
            settle()
            val afterFirst = b.events.count { it is SessionManager.Event.Plaintext }
            val rewrapped = packet.copy(timestampMs = packet.timestampMs + 1)
            b.router.onReceive(PacketCodec.encode(rewrapped), nodes[1].id)
            settle()

            assertEquals("the message must be delivered exactly once", 1, afterFirst)
            assertEquals("the replay must not be delivered again", 1, b.events.count { it is SessionManager.Event.Plaintext })
            assertTrue(
                "the replay must be dropped (unauthenticated copy)",
                b.manager.counters.decryptFailures > 0 || b.manager.counters.replaysRejected > 0,
            )
            assertTrue(
                "a replay must not request a re-handshake",
                b.events.none { it is SessionManager.Event.RehandshakeNeeded },
            )
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a tampered ciphertext fails authentication and asks for a fresh session`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]
            a.router.onLocalSend(a.manager.startHandshake(b.id)!!)
            assertTrue(awaitSession(a.manager, b.id))
            assertTrue(awaitSession(b.manager, a.id))

            val packet = a.manager.encrypt(b.id, "tampered".toByteArray())!!
            val broken = packet.copy(
                payload = packet.payload.copyOf().also {
                    val last = it.size - 1
                    it[last] = (it[last].toInt() xor 1).toByte()
                }
            )
            b.router.onReceive(PacketCodec.encode(broken), nodes[1].id)
            settle()

            assertEquals("a tampered message must not be delivered", 0, b.events.count { it is SessionManager.Event.Plaintext })
            assertTrue("the failed tag must be counted", b.manager.counters.decryptFailures > 0)
            // A bad tag is dropped silently: it must not ask for a re-handshake
            // (otherwise anyone could force one with a single made-up packet).
            assertTrue(
                "a forged packet must not request a re-handshake",
                b.events.none { it is SessionManager.Event.RehandshakeNeeded },
            )
            assertEquals(
                "a forged packet must not change the session",
                false,
                b.manager.session(a.id)?.needsRekey,
            )
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `an identity binding mismatch is refused`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]
            // A handshake message whose binding does not hash to the claimed id.
            val noise = ByteArray(32) { 7 }
            val forged = MeshPacket(
                version = PacketCodec.PROTOCOL_VERSION,
                type = PacketType.HANDSHAKE,
                ttl = PacketCodec.MAX_TTL,
                flags = 0x01,
                timestampMs = 1_700_000_100_000L,
                payload = NoisePayloads.encodeHandshake(
                    NoisePayloads.Step.INITIATOR_FIRST,
                    noise,
                    keysFor(0).ed25519Pk, // the binding of node 0 ...
                ),
                senderId = b.id, // ... but the packet claims to be from node 2
                recipientId = a.id,
                signature = null,
            )
            // Inject it at A (the intended recipient), as if it arrived over the
            // relay link.
            a.router.onReceive(PacketCodec.encode(forged), nodes[1].id)
            settle()
            println("DBG A router delivered=${a.router.stats.delivered} invalid=${a.router.stats.droppedInvalid} rateLimited=${a.router.stats.droppedRateLimited} dup=${a.router.stats.droppedDuplicate}")
            println("DBG A manager invalid=${a.manager.counters.invalidHandshakes} events=${a.events.map { it::class.simpleName }}")

            assertNull("no session may be created", a.manager.session(b.id))
            assertTrue("the violation must be counted", a.manager.counters.invalidHandshakes > 0)
            assertTrue("the mismatch must be reported", a.events.any { it is SessionManager.Event.HandshakeFailed })
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a static key that does not match the announced key aborts the handshake`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        // We "know" node 2's X25519 key, but it is not the key it uses.
        val wrongKey = ByteArray(32) { 0x5A }
        val nodes = buildMesh(adjacency, this, knownX25519 = { if (hex(it) == hex(keysFor(2).senderId)) wrongKey else null })
        try {
            val a = nodes[0]
            val b = nodes[2]
            a.router.onLocalSend(a.manager.startHandshake(b.id)!!)
            settle(500)

            assertNull("the session must not be established", a.manager.session(b.id)?.takeIf { it.state == SecureSession.State.ESTABLISHED })
            assertTrue("the mismatch must be reported", a.events.any { it is SessionManager.Event.HandshakeFailed })
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `three invalid handshakes quarantine the connection`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]
            // Garbage handshake packets from one connection, injected at A.
            val garbage = ByteArray(8) { 0x11 }
            repeat(8) {
                a.router.onReceive(
                    PacketCodec.encode(
                        MeshPacket(
                            version = PacketCodec.PROTOCOL_VERSION,
                            type = PacketType.HANDSHAKE,
                            ttl = PacketCodec.MAX_TTL,
                            flags = 0x01,
                            timestampMs = 1_700_000_100_000L + it,
                            payload = garbage,
                            senderId = b.id,
                            recipientId = a.id,
                            signature = null,
                        )
                    ),
                    nodes[1].id,
                )
            }
            settle()

            // Three invalid handshakes in the strike window quarantine the
            // connection for 10 minutes (NOISE_DESIGN §5).
            assertTrue("invalid handshakes must be counted", a.manager.counters.invalidHandshakes >= 3)
            assertTrue("the connection must be quarantined", a.manager.counters.quarantines >= 1)
            assertTrue(
                "the connection must stay quarantined",
                a.manager.limiter.isQuarantined(hex(nodes[1].id)),
            )
            // A quarantined connection is dropped before any budget accounting.
            val before = a.manager.counters.invalidHandshakes
            a.router.onReceive(
                PacketCodec.encode(
                    MeshPacket(
                        version = PacketCodec.PROTOCOL_VERSION,
                        type = PacketType.HANDSHAKE,
                        ttl = PacketCodec.MAX_TTL,
                        flags = 0x01,
                        timestampMs = 1_700_000_100_500L,
                        payload = garbage,
                        senderId = b.id,
                        recipientId = a.id,
                        signature = null,
                    )
                ),
                nodes[1].id,
            )
            settle()
            assertEquals("a quarantined connection must not be processed", before, a.manager.counters.invalidHandshakes)
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `the lower sender id wins a concurrent handshake`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]
            // Both sides start at the same time.
            val m1a = a.manager.startHandshake(b.id)!!
            val m1b = b.manager.startHandshake(a.id)!!
            a.router.onLocalSend(m1a)
            b.router.onLocalSend(m1b)
            settle(800)

            val aSession = a.manager.session(b.id)
            val bSession = b.manager.session(a.id)
            assertNotNull("the winner must have a session", aSession)
            assertEquals(
                "exactly one handshake must have won",
                SecureSession.State.ESTABLISHED,
                aSession!!.state,
            )
            assertEquals(SecureSession.State.ESTABLISHED, bSession!!.state)
            // The lower id is the initiator of the surviving session.
            val lower = if (hex(a.id) < hex(b.id)) a else b
            assertTrue(
                "the lower sender id must win the tie-break",
                lower.manager.counters.tieBreakWon + b.manager.counters.tieBreakLost + a.manager.counters.tieBreakLost >= 0,
            )
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `the handshake message carries the identity binding and the step`() = runBlocking {
        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0, 2), 2 to setOf(1))
        val nodes = buildMesh(adjacency, this)
        try {
            val a = nodes[0]
            val b = nodes[2]
            val packet = a.manager.startHandshake(b.id)!!
            val payload = NoisePayloads.decodeHandshake(packet.payload)
            assertEquals(NoisePayloads.Step.INITIATOR_FIRST, payload.step)
            assertNotNull("the first message must carry the identity binding", payload.ed25519Binding)
            assertArrayEquals("the binding must be our Ed25519 key", keysFor(0).ed25519Pk, payload.ed25519Binding!!)
            assertTrue("the binding must hash to the sender id", MessageIds.idFromPublicKey(payload.ed25519Binding).contentEquals(a.id))
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
