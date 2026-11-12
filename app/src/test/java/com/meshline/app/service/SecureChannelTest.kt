package com.meshline.app.service

import com.meshline.app.noise.InMemoryMessageDedupe
import com.meshline.app.noise.MessageDedupe
import com.meshline.app.noise.Outbox
import com.meshline.app.noise.OutboxRow
import com.meshline.app.noise.OutboxState
import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.router.MeshRouter
import com.meshline.app.router.RouterConfig
import com.meshline.app.router.SignatureCheck
import com.meshline.app.security.Identity
import com.meshline.app.security.KeyCache
import com.meshline.app.storage.ContactDirectory
import com.meshline.app.storage.ContactEntity
import com.meshline.app.storage.MessageEntity
import com.meshline.app.storage.MessageStore
import com.meshline.app.storage.SessionDirectory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * The D2 secure channel, driven end to end over the mesh simulator.
 *
 * Everything here runs on the JVM: the channel has no Android dependency (the
 * stores are interfaces), so the whole private-chat flow — handshake, message,
 * receipt, the states, the key-change gate and the panic wipe — is exercised
 * without a phone.
 */
class SecureChannelTest {

    // ---- in-memory stores (the Room ones are instrumented-only) ----

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
        override suspend fun delete(id: Long) { rows.remove(id) }
        override suspend fun deleteForPeer(peerIdHex: String): Int {
            val hit = rows.values.filter { it.peerIdHex == peerIdHex }
            hit.forEach { rows.remove(it.id) }
            return hit.size
        }
        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val hit = rows.values.filter { it.createdAtMs < cutoffMs }
            hit.forEach { rows.remove(it.id) }
            return hit.size
        }
    }

    private class MemoryMessages : MessageStore {
        private val rows = LinkedHashMap<String, MessageEntity>()
        override suspend fun upsert(entity: MessageEntity) { rows[entity.messageIdHex] = entity }
        override suspend fun all(): List<MessageEntity> = rows.values.toList()
        override suspend fun forPeer(peerIdHex: String): List<MessageEntity> =
            rows.values.filter { it.peerIdHex == peerIdHex }
        override suspend fun setState(messageIdHex: String, state: String) {
            rows[messageIdHex]?.let { rows[messageIdHex] = it.copy(state = state) }
        }
        override suspend fun clearPeer(peerIdHex: String) {
            rows.values.filter { it.peerIdHex == peerIdHex }.forEach { rows.remove(it.messageIdHex) }
        }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val hit = rows.values.filter { it.createdAtMs < cutoffMs }
            hit.forEach { rows.remove(it.messageIdHex) }
            return hit.size
        }
    }

    private class MemoryContacts : ContactDirectory {
        val contacts = mutableListOf<ContactEntity>()
        override suspend fun all(): List<ContactEntity> = contacts.toList()
        override suspend fun x25519For(peerIdHex: String): ByteArray? =
            contacts.firstOrNull { it.senderIdHex == peerIdHex }?.x25519PublicKey
        override suspend fun isVerified(peerIdHex: String): Boolean =
            contacts.firstOrNull { it.senderIdHex == peerIdHex }?.verified == true
        override suspend fun isBlocked(peerIdHex: String): Boolean =
            contacts.firstOrNull { it.senderIdHex == peerIdHex }?.blocked == true
        override suspend fun acceptNewKeys(
            peerIdHex: String, ed25519PublicKey: ByteArray, x25519PublicKey: ByteArray,
        ) {
            val c = contacts.firstOrNull { it.senderIdHex == peerIdHex } ?: return
            contacts.remove(c)
            contacts += c.copy(
                ed25519PublicKey = ed25519PublicKey,
                x25519PublicKey = x25519PublicKey,
                fingerprint = MessageIds.fingerprint(ed25519PublicKey, x25519PublicKey),
                verified = false,
            )
        }
        override suspend fun block(peerIdHex: String) {
            val c = contacts.firstOrNull { it.senderIdHex == peerIdHex } ?: return
            contacts.remove(c)
            contacts += c.copy(blocked = true)
        }
        override suspend fun deleteAll() = contacts.clear()
    }

    private class MemorySessions : SessionDirectory {
        var wiped = false
        override suspend fun wipe() { wiped = true }
    }

    // ---- the harness ----

    private data class Peer(
        val index: Int,
        val id: ByteArray,
        val identity: Identity,
        val router: MeshRouter,
        val channel: SecureChannel,
        val jobs: List<Job>,
    )

    private val identities = mutableMapOf<Int, Identity>()

    private fun identityOf(index: Int): Identity = identities.getOrPut(index) {
        val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val edPk = ed.public.encoded.copyOfRange(12, 44)
        val edPriv = ed.private.encoded.copyOfRange(0, 32)
        val xPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val xPub = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(xPriv, 0)
            .generatePublicKey().encoded
        com.meshline.app.security.Identity(
            ed25519PublicKey = edPk,
            x25519PublicKey = xPub,
            edPrivate = edPriv,
            xPrivate = xPriv,
        )
    }

    private suspend fun build(
        scope: CoroutineScope,
        adjacency: Map<Int, Set<Int>>,
        nowProvider: () -> Long,
        contacts: MemoryContacts = MemoryContacts(),
        dedupe: MessageDedupe = InMemoryMessageDedupe(),
    ): List<Peer> {
        val hexAdj = adjacency.entries.associate { (i, neigh) ->
            VirtualNetwork.hex(identityOf(i).senderId) to neigh.map { VirtualNetwork.hex(identityOf(it).senderId) }.toSet()
        }
        val network = VirtualNetwork(hexAdj, SimulatorConfig(seed = 3L))
        val peers = mutableListOf<Peer>()
        for (i in adjacency.keys) {
            val identity = identityOf(i)
            val transport = SimulatorTransport(identity.senderId, network, scope)
            val keyCache = KeyCache(nowProvider = nowProvider)
            val router = MeshRouter(
                ownId = identity.senderId,
                transport = transport,
                config = RouterConfig(jitterMinMs = 0, jitterMaxMs = 0, nowProvider = nowProvider, seed = i.toLong()),
                neighboursProvider = { network.neighboursOf(identity.senderId) },
                connectedPeersProvider = { network.neighboursOf(identity.senderId) },
                signatureVerifier = { _, _ -> SignatureCheck.OK },
            )
            val channel = SecureChannel(
                identity = identity,
                router = router,
                keyCache = keyCache,
                contacts = contacts,
                outbox = MemoryOutbox(),
                dedupe = dedupe,
                messageStore = MemoryMessages(),                sessionDirectory = MemorySessions(),
                connectedPeers = { network.neighboursOf(identity.senderId).map { hex(it) }.toSet() },
                nowProvider = nowProvider,
            )
            val jobs = listOf(
                scope.launch { transport.incoming.collect { (from, bytes) -> router.onReceive(bytes, from) } },
                scope.launch {
                    router.deliveredWithPeer.collect { (from, pkt) -> channel.onPacket(pkt, from) }
                },
            )
            peers += Peer(i, identity.senderId, identity, router, channel, jobs)
        }
        kotlinx.coroutines.yield()
        delay(50)
        return peers
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    // ---- the tests ----

    @Test
    fun `handshake then private message then receipt leaves the outbox Delivered`() = runBlocking {
        val now = { 1_700_000_100_000L }
        val peers = build(this, mapOf(0 to setOf(1), 1 to setOf(0)), now)
        try {
            val a = peers[0]
            val b = peers[1]

            // A starts the handshake (the user opened the chat).
            a.channel.ensureSession(hex(b.id))
            delay(200)
            assertNotNull("the session must be established", a.channel.sessions.session(b.id))

            // A sends a private message.
            a.channel.send(hex(b.id), "hello from A")
            delay(100)
            assertEquals("the message starts Queued", "QUEUED", a.channel.messages.value.first().state)

            // The tick sends it (the session is up).
            a.channel.tick()
            delay(200)
            val afterSend = a.channel.messages.value.first().state
            assertTrue("the message must have left the queue ($afterSend)", afterSend == "SENT" || afterSend == "DELIVERED")

            // B received it and acknowledged it.
            val received = b.channel.messages.value.firstOrNull { !it.outgoing }
            assertNotNull("B must have received the message", received)
            assertEquals("hello from A", received!!.text)

            // The receipt travels back: A's message is Delivered.
            delay(300)
            a.channel.tick()
            delay(200)
            assertEquals("the message is Delivered", "DELIVERED", a.channel.messages.value.first().state)
        } finally {
            peers.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a message to an absent peer stays Queued and goes out when it returns`() = runBlocking {
        val now = { 1_700_000_100_000L }
        val peers = build(this, mapOf(0 to setOf(1), 1 to setOf(0)), now)
        try {
            val a = peers[0]
            val b = peers[1]

            // A queues a message for B before any session exists.
            a.channel.send(hex(b.id), "are you there?")
            a.channel.tick()
            delay(100)
            assertEquals("the message must wait", "QUEUED", a.channel.messages.value.first().state)

            // Now the chat is opened: the handshake starts.
            a.channel.ensureSession(hex(b.id))
            delay(300)
            a.channel.tick()
            delay(300)
            val afterSend = a.channel.messages.value.first().state
            assertTrue(
                "the message goes out once the session is up ($afterSend)",
                afterSend == "SENT" || afterSend == "DELIVERED",
            )
            assertNotNull("B must have received it", b.channel.messages.value.firstOrNull { !it.outgoing })
        } finally {
            peers.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a key mismatch for a verified contact holds the traffic until it is accepted`() = runBlocking {
        val now = { 1_700_000_100_000L }
        val contacts = MemoryContacts()
        val peers = build(this, mapOf(0 to setOf(1), 1 to setOf(0)), now, contacts)
        try {
            val a = peers[0]
            val b = peers[1]

            // A knows B as a QR-verified contact — but with the WRONG X25519 key
            // (the classic impersonation setup).
            contacts.contacts += ContactEntity(
                senderIdHex = hex(b.id),
                nickname = "bob",
                avatarIndex = 0,
                ed25519PublicKey = b.identity.ed25519PublicKey,
                x25519PublicKey = ByteArray(32) { 0x42 },
                fingerprint = MessageIds.fingerprint(b.identity.ed25519PublicKey, ByteArray(32) { 0x42 }),
                verified = true,
                lastSeenMs = 0L,
            )
            a.channel.refresh()

            a.channel.ensureSession(hex(b.id))
            delay(300)
            a.channel.tick()
            delay(300)

            // The mismatch is reported and the traffic is held.
            val event = a.channel.keyChange.value
            assertNotNull("the key change must be reported", event)
            assertEquals(hex(b.id), event!!.peerIdHex)
            assertTrue("the peer must be on hold", a.channel.peers.value.first { it.peerIdHex == hex(b.id) }.onHold)

            // Accepting: the contact becomes TOFU and loses the badge.
            a.channel.acceptKeyChange(hex(b.id))
            delay(100)
            assertFalse(
                "the badge must be gone after accepting a new key",
                contacts.contacts.first { it.senderIdHex == hex(b.id) }.verified,
            )
            assertFalse("the hold must be lifted", a.channel.peers.value.first { it.peerIdHex == hex(b.id) }.onHold)
        } finally {
            peers.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `rejecting a key change blocks the peer`() = runBlocking {
        val now = { 1_700_000_100_000L }
        val contacts = MemoryContacts()
        val peers = build(this, mapOf(0 to setOf(1), 1 to setOf(0)), now, contacts)
        try {
            val a = peers[0]
            val b = peers[1]
            contacts.contacts += ContactEntity(
                senderIdHex = hex(b.id),
                nickname = "bob",
                avatarIndex = 0,
                ed25519PublicKey = b.identity.ed25519PublicKey,
                x25519PublicKey = ByteArray(32) { 0x42 },
                fingerprint = "deadbeef",
                verified = true,
                lastSeenMs = 0L,
            )
            a.channel.refresh()
            a.channel.ensureSession(hex(b.id))
            delay(300)
            a.channel.tick()
            delay(200)
            assertNotNull("the key change must be reported", a.channel.keyChange.value)

            a.channel.rejectKeyChange(hex(b.id))
            delay(100)
            assertTrue("the peer must be blocked", contacts.isBlocked(hex(b.id)))
            assertNull("the dialog must be dismissed", a.channel.keyChange.value)
        } finally {
            peers.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }

    @Test
    fun `a panic wipe mid conversation leaves nothing behind`() = runBlocking {
        val now = { 1_700_000_100_000L }
        val contacts = MemoryContacts()
        val peers = build(this, mapOf(0 to setOf(1), 1 to setOf(0)), now, contacts)
        try {
            val a = peers[0]
            val b = peers[1]
            a.channel.ensureSession(hex(b.id))
            delay(300)
            a.channel.send(hex(b.id), "secret message")
            a.channel.tick()
            delay(300)
            assertTrue("there must be something to wipe", a.channel.messages.value.isNotEmpty())

            a.channel.wipe()

            assertTrue("no messages may survive", a.channel.messages.value.isEmpty())
            assertTrue("no peers may survive", a.channel.peers.value.isEmpty())
            assertNull("no session may survive", a.channel.sessions.session(b.id))
            assertTrue("the contacts must be gone", contacts.contacts.isEmpty())
        } finally {
            peers.forEach { it.jobs.forEach { j -> j.cancelAndJoin() } }
        }
    }
}
