package com.meshline.app.noise

import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Store-and-forward over a lossy mesh (FR-12, review item 2).
 *
 * The simulator drops **30 %** of the link transmissions. The outbox has to
 * cope with that:
 *
 *  - a message stays in the outbox after "Sent" until a signed receipt
 *    (Delivered) arrives or the 12 h expiry drops it;
 *  - it is retried with an exponential backoff;
 *  - every retry is **re-encrypted** under the live session with a **fresh
 *    nonce** (a nonce must never be reused) while keeping the same
 *    application-level message id;
 *  - the receiver shows the message **exactly once**, even though it may
 *    arrive several times (the mesh duplicates and the sender retries).
 */
class PrivateOutboxTest {

    private data class Keys(val keyPair: KeyPair, val x25519Priv: ByteArray) {
        val ed25519Pk: ByteArray get() = keyPair.public.encoded.copyOfRange(12, 44)
        val senderId: ByteArray get() = MessageIds.idFromPublicKey(ed25519Pk)
    }

    private val keys = mutableMapOf<Int, Keys>()

    private fun keysFor(i: Int): Keys = keys.getOrPut(i) {
        Keys(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
            ByteArray(32).also { SecureRandom().nextBytes(it) },
        )
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** An in-memory outbox (the Room one is instrumented-only). */
    private class MemoryOutbox : Outbox {
        private val rows = LinkedHashMap<Long, OutboxRow>()
        private var nextId = 1L

        override suspend fun queue(peerIdHex: String, envelope: ByteArray, nowMs: Long): Long {
            val id = nextId++
            rows[id] = OutboxRow(id, peerIdHex, envelope.copyOf(), nowMs, OutboxState.QUEUED, 0)
            return id
        }

        override suspend fun rows(): List<OutboxRow> = rows.values.toList()

        override suspend fun markAttempt(id: Long, state: OutboxState, attempts: Int) {
            rows[id]?.let { rows[id] = it.copy(state = state, attempts = attempts) }
        }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val old = rows.values.filter { it.createdAtMs < cutoffMs }
            old.forEach { rows.remove(it.id) }
            return old.size
        }

        fun size(): Int = rows.size
    }

    private data class Node(
        val index: Int,
        val id: ByteArray,
        val router: MeshRouter,
        val manager: SessionManager,
        val jobs: List<Job>,
    )

    @Test
    fun `every private message is delivered exactly once over a 30 percent lossy mesh`() = runBlocking {
        val clock = AtomicLong(1_700_000_100_000L)
        val senderOutbox = MemoryOutbox()
        val receiverOutbox = MemoryOutbox()
        val shown = mutableListOf<PrivateEnvelope>()

        // Filled in once the mesh exists.
        var senderMessenger: PrivateMessenger? = null
        var receiverMessenger: PrivateMessenger? = null
        var senderId: ByteArray = ByteArray(8)
        var onPlaintext: suspend (Node, ByteArray, ByteArray) -> Unit = { _, _, _ -> }
        var onReceiptPacket: suspend (Node, MeshPacket) -> Unit = { _, _ -> }

        val adjacency = mapOf(0 to setOf(1), 1 to setOf(0))
        val hexAdj = adjacency.entries.associate { (i, neigh) ->
            VirtualNetwork.hex(keysFor(i).senderId) to neigh.map { VirtualNetwork.hex(keysFor(it).senderId) }.toSet()
        }
        val network = VirtualNetwork(hexAdj, SimulatorConfig(lossRate = 0.30, seed = 99L))
        val nodes = mutableListOf<Node>()
        for (i in adjacency.keys) {
            val k = keysFor(i)
            val transport = SimulatorTransport(k.senderId, network, this)
            val router = MeshRouter(
                ownId = k.senderId,
                transport = transport,
                config = RouterConfig(
                    jitterMinMs = 0, jitterMaxMs = 0,
                    nowProvider = { clock.get() },
                    seed = i.toLong(),
                ),
                neighboursProvider = { network.neighboursOf(k.senderId) },
                connectedPeersProvider = { network.neighboursOf(k.senderId) },
                signatureVerifier = { _, _ -> SignatureCheck.OK },
            )
            val manager = SessionManager(
                localStaticX25519 = k.x25519Priv,
                localEd25519PublicKey = k.ed25519Pk,
                nowProvider = { clock.get() },
            )
            val node = Node(i, k.senderId, router, manager, emptyList())
            val j0 = launch { transport.incoming.collect { (from, bytes) -> router.onReceive(bytes, from) } }
            val j1 = launch {
                router.deliveredWithPeer.collect { (from, pkt) ->
                    when (pkt.type) {
                        PacketType.HANDSHAKE, PacketType.PRIVATE_MESSAGE -> {
                            for (e in manager.onPacket(pkt, from)) {
                                when (e) {
                                    is SessionManager.Event.Outgoing -> router.onLocalSend(e.packet)
                                    is SessionManager.Event.Plaintext -> onPlaintext(node, e.peerId, e.bytes)
                                    else -> Unit
                                }
                            }
                        }
                        PacketType.RECEIPT -> onReceiptPacket(node, pkt)
                        else -> Unit
                    }
                }
            }
            nodes += node.copy(jobs = listOf(j0, j1))
        }
        kotlinx.coroutines.yield()
        delay(50)

        try {
            val a = nodes[0]
            val b = nodes[1]
            senderId = a.id
            senderMessenger = PrivateMessenger(a.manager, senderOutbox, nowProvider = { clock.get() })
            val receiverMessenger = PrivateMessenger(b.manager, receiverOutbox, nowProvider = { clock.get() })

            // The receiver accepts a message, shows it once, and answers with a
            // signed receipt (this is the UI/service layer's job).
            onPlaintext = { node, peerId, plaintext ->
                val result = receiverMessenger.onPlaintext(hex(peerId), plaintext)
                if (result != null) {
                    // Show it only the first time...
                    if (result.isNew) shown += result.envelope
                    // ...but always acknowledge it: the receipt is idempotent
                    // and the sender needs it to clean its outbox.
                    val payload = Payloads.encodeReceipt(
                        Payloads.Receipt(Payloads.Receipt.KIND_DELIVERED, result.envelope.messageId)
                    )
                    val unsigned = MeshPacket(
                        version = PacketCodec.PROTOCOL_VERSION,
                        type = PacketType.RECEIPT,
                        ttl = PacketCodec.MAX_TTL,
                        flags = 0x01,
                        timestampMs = clock.get(),
                        payload = payload,
                        senderId = node.id,
                        recipientId = senderId,
                        signature = null,
                    )
                    val signed = unsigned.copy(
                        flags = 0x03,
                        signature = sign(keysFor(node.index).keyPair, PacketCodec.signingInput(unsigned)),
                    )
                    node.router.onLocalSend(signed)
                }
            }

            // The sender removes the row when the receipt arrives. The receipt
            // comes *from* the peer the row is addressed to.
            onReceiptPacket = { _, packet ->
                val receipt = runCatching { Payloads.decodeReceipt(packet.payload) }.getOrNull()
                if (receipt != null) senderMessenger?.onReceipt(hex(packet.senderId), receipt.messageId)
            }

            // --- establish the session (the handshake also suffers the loss) ---
            var established = false
            for (attempt in 0 until 80) {
                val pkt = a.manager.startHandshake(b.id)
                if (pkt != null) a.router.onLocalSend(pkt, retransmit = true)
                clock.set(clock.get() + 2_500)
                delay(30)
                if (a.manager.session(b.id)?.state == SecureSession.State.ESTABLISHED &&
                    b.manager.session(a.id)?.state == SecureSession.State.ESTABLISHED
                ) {
                    established = true
                    break
                }
            }
            assertTrue("the session was never established under 30% loss", established)

            // --- queue the messages ---
            val count = 20
            val queued = (0 until count).map { i ->
                senderMessenger!!.send(b.id, hex(b.id), "message-$i".toByteArray())
            }
            assertEquals("every message must be queued", count, queued.size)
            assertEquals("the outbox must hold them", count, senderOutbox.size())

            // --- run the retry loop ---
            // A row stays in the outbox after "Sent" (it becomes Delivered),
            // so the loop runs until every row is acknowledged.
            var rounds = 0
            while (rowsAwaitingReceipt(senderOutbox) > 0 && rounds < 300) {
                // A session that aged out needs a re-key; the service would
                // start a fresh handshake here (the queued messages wait for it).
                if (a.manager.session(b.id)?.needsRekey == true) {
                    val hp = a.manager.startHandshake(b.id)
                    if (hp != null) a.router.onLocalSend(hp, retransmit = true)
                }
                val packets = senderMessenger!!.tick()
                for (p in packets) a.router.onLocalSend(p)
                delay(25)
                val next = senderMessenger!!.nextDueAtMs()
                if (next != null && next > clock.get()) clock.set(next)
                rounds++
            }

            assertEquals(
                "every message must be acknowledged",
                0,
                rowsAwaitingReceipt(senderOutbox),
            )
            assertEquals("every message must be shown exactly once", count, shown.size)
            assertEquals(
                "the messages must be distinct",
                count,
                shown.map { it.text.toString(Charsets.UTF_8) }.distinct().size,
            )
            assertEquals(
                "the application-level ids must be distinct",
                count,
                shown.map { hex(it.messageId) }.distinct().size,
            )
            println("D2-OUTBOX rounds=$rounds shown=${shown.size}")
        } finally {
            nodes.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    private fun sign(keyPair: KeyPair, input: ByteArray): ByteArray =
        java.security.Signature.getInstance("Ed25519").apply { initSign(keyPair.private); update(input) }.sign()

    /** The rows that still wait for a receipt (the acknowledged ones stay behind). */
    private suspend fun rowsAwaitingReceipt(outbox: MemoryOutbox): Int =
        outbox.rows().count { it.state == OutboxState.QUEUED || it.state == OutboxState.SENT }
}
