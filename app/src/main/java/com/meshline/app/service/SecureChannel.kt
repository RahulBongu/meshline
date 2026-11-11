package com.meshline.app.service

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.router.MeshRouter
import com.meshline.app.security.Identity
import com.meshline.app.security.KeyCache
import com.meshline.app.storage.ContactsRepository
import com.meshline.app.storage.InboxEntity
import com.meshline.app.storage.OutboxState
import com.meshline.app.storage.SecureStores
import com.meshline.app.noise.HandshakeTier
import com.meshline.app.noise.HandshakeTiers
import com.meshline.app.noise.PrivateEnvelope
import com.meshline.app.noise.PrivateMessenger
import com.meshline.app.noise.SessionManager
import com.meshline.app.noise.SecureSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One line in a private chat. */
data class PrivateChatMessage(
    val messageIdHex: String,
    val peerIdHex: String,
    /** True for a message we sent, false for one we received. */
    val outgoing: Boolean,
    val text: String,
    /** QUEUED / SENT / DELIVERED / READ (outgoing only; incoming is shown as DELIVERED). */
    val state: OutboxState,
    val createdAtMs: Long,
)

/** A peer we can open a private chat with. */
data class PrivatePeer(
    val peerIdHex: String,
    val nickname: String,
    val avatarIndex: Int,
    /** True only for QR-verified contacts. */
    val verified: Boolean,
    /** Currently linked over BLE. */
    val online: Boolean,
    /** The Noise session is established (we can encrypt right now). */
    val sessionReady: Boolean,
    /** A key change is waiting for the user's decision (§7.2). */
    val onHold: Boolean,
)

/** A peer presented a key that differs from the one we know. */
data class KeyChangeEvent(
    val peerIdHex: String,
    val nickname: String,
    val oldFingerprint: String,
    val newFingerprint: String,
)

/**
 * The D2 secure channel, wired into the running app.
 *
 * It owns the [SessionManager] (Noise handshakes + transport), the
 * [PrivateMessenger] (the outbox, the retries, the application-level dedupe)
 * and turns router packets into the two things the UI cares about: private
 * chats and key-change warnings.
 *
 * Rules that hold everywhere in here:
 *  - **nothing sensitive is logged**: no keys, no message text, no nicknames;
 *  - a message is only handed to the router when a session exists — until
 *    then it stays **Queued** in the encrypted outbox;
 *  - a key change is never applied silently: the traffic is held until the
 *    user explicitly accepts it (NOISE_DESIGN §7.2).
 */
class SecureChannel(
    private val identity: Identity,
    private val router: MeshRouter,
    private val keyCache: KeyCache,
    private val contacts: ContactsRepository,
    private val store: SecureStores,
    /** The peers we are currently linked to (hex ids). */
    private val connectedPeers: () -> Set<String>,
) {
    /** The Noise session engine. */
    val sessions = SessionManager(
        localStaticX25519 = identity.x25519PrivateKey(),
        localEd25519PublicKey = identity.ed25519PublicKey,
        nowProvider = { System.currentTimeMillis() },
        isBlocked = { peerId -> blockedPeers.contains(hex(peerId)) },
        handshakeTier = { peerId -> tierOf(peerId) },
        knownX25519 = { peerId -> knownX25519(peerId) },
    )

    /** The private-message layer (outbox, retries, dedupe). */
    private val messenger = PrivateMessenger(
        sessions = sessions,
        outbox = store.outbox,
        nowProvider = { System.currentTimeMillis() },
        dedupe = store.seen,
    )

    private val _messages = MutableStateFlow<List<PrivateChatMessage>>(emptyList())
    val messages: StateFlow<List<PrivateChatMessage>> = _messages

    private val _peers = MutableStateFlow<List<PrivatePeer>>(emptyList())
    val peers: StateFlow<List<PrivatePeer>> = _peers

    private val _keyChange = MutableStateFlow<KeyChangeEvent?>(null)
    /** The key change waiting for the user's decision (§7.2). */
    val keyChange: StateFlow<KeyChangeEvent?> = _keyChange

    /** Peers whose traffic is held until the user accepts the key change. */
    private val heldPeers = mutableSetOf<String>()

    /** Peers the user blocked. */
    private val blockedPeers = mutableSetOf<String>()

    /**
     * What we know about the peers we have contacts for: the X25519 key we
     * trust (the impersonation check, §7.1) and whether the contact is
     * QR-verified (the handshake tier). Cached so the Noise engine's
     * synchronous callbacks never touch the database.
     */
    private val knownKeys = mutableMapOf<String, ByteArray>()
    private val verifiedPeers = mutableSetOf<String>()

    /** Handshake attempts we started, so we do not spam a peer. */
    private val handshakeStartedAt = mutableMapOf<String, Long>()

    // ------------------------------------------------------------- inbound

    /**
     * Handles a packet the router delivered to us.
     *
     * The router has already checked the outer packet; the Noise layer checks
     * the cryptographic truth (the handshake, the AEAD tag, the nonce).
     */
    suspend fun onPacket(packet: MeshPacket, fromPeer: ByteArray) {
        when (packet.type) {
            PacketType.HANDSHAKE, PacketType.PRIVATE_MESSAGE -> {
                for (event in sessions.onPacket(packet, fromPeer)) handle(event, hex(fromPeer))
            }
            PacketType.RECEIPT -> handleReceipt(packet)
            else -> Unit
        }
    }

    private suspend fun handle(event: SessionManager.Event, connectionKey: String) {
        when (event) {
            is SessionManager.Event.Outgoing -> router.onLocalSend(event.packet)
            is SessionManager.Event.Plaintext -> onPlaintext(event.peerId, event.bytes)
            is SessionManager.Event.SessionEstablished -> onSessionEstablished(event.peerId)
            is SessionManager.Event.KeyMismatch -> onKeyMismatch(event, connectionKey)
            is SessionManager.Event.RehandshakeNeeded -> {
                // The session drifted: replace it silently (§6.1). The queued
                // messages wait for the new session.
                sessions.requestRehandshake(event.peerId)
            }
            is SessionManager.Event.Violation -> Unit   // counters only
            is SessionManager.Event.HandshakeFailed -> Unit   // counters only
        }
    }

    /**
     * The peer presented a different key than the one we know (§7.1).
     *
     * For a **verified contact** this is a key change: the traffic is held and
     * the user is asked to accept it explicitly. For anyone else it is an
     * impersonation attempt and the connection is struck.
     */
    private suspend fun onKeyMismatch(event: SessionManager.Event.KeyMismatch, connectionKey: String) {
        val peerHex = hex(event.peerId)
        if (!verifiedPeers.contains(peerHex)) {
            // Not somebody we know: treat it as an impersonation attempt.
            sessions.limiter.recordInvalid(connectionKey)
            return
        }
        if (heldPeers.add(peerHex)) {
            val contact = contacts.all().firstOrNull { it.senderIdHex == peerHex }
            _keyChange.value = KeyChangeEvent(
                peerIdHex = peerHex,
                nickname = contact?.nickname ?: "peer",
                oldFingerprint = contact?.fingerprint?.take(8)?.uppercase() ?: "unknown",
                newFingerprint = fingerprintOf(event.learnedX25519PublicKey).take(8).uppercase(),
            )
        }
    }

    private suspend fun onPlaintext(peerId: ByteArray, plaintext: ByteArray) {
        val peerHex = hex(peerId)
        val result = messenger.onPlaintext(peerHex, plaintext) ?: return
        // The receipt is idempotent: a repeat is acknowledged again, but only
        // the first copy is stored and shown.
        sendReceipt(peerId, result.envelope.messageId, Payloads.Receipt.KIND_DELIVERED)
        if (result.isNew) {
            store.inbox.insert(
                InboxEntity(
                    messageIdHex = hex(result.envelope.messageId),
                    peerIdHex = peerHex,
                    text = result.envelope.text.copyOf(),
                    createdAtMs = result.envelope.sentAtMs,
                    read = false,
                )
            )
            refresh()
        }
    }

    private suspend fun onSessionEstablished(peerId: ByteArray) {
        // The key-change check happens in the engine (it knows the key the
        // handshake revealed); here we only refresh the UI state.
        refresh()
    }

    private suspend fun handleReceipt(packet: MeshPacket) {
        val receipt = runCatching { Payloads.decodeReceipt(packet.payload) }.getOrNull() ?: return
        val peerHex = hex(packet.senderId)
        val state = if (receipt.kind == Payloads.Receipt.KIND_READ) {
            OutboxState.READ
        } else {
            OutboxState.DELIVERED
        }
        messenger.onReceipt(peerHex, receipt.messageId, state)
        refresh()
    }

    // ------------------------------------------------------------ outbound

    /**
     * Queues a private message. It stays **Queued** until a session exists;
     * the tick loop encrypts and sends it (with a fresh nonce) and keeps
     * retrying until the peer acknowledges it.
     */
    suspend fun send(peerIdHex: String, text: String): Boolean {
        val peerId = peerIdHex.hexToBytes()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // A peer whose key changed is held until the user accepts (§7.2): the
        // message is queued and stays Queued.
        messenger.send(peerId, peerIdHex, trimmed.toByteArray())
        refresh()
        return true
    }

    /**
     * The periodic work: handshake retransmits, outbox retries, expiry and
     * re-keys. Called by the service on a timer.
     */
    suspend fun tick() {
        // 1. Handshake retransmits (the same message id, so the receiver
        //    treats a repeat as the idempotent duplicate it is).
        for (packet in sessions.dueRetransmits()) {
            router.onLocalSend(packet, retransmit = true)
        }
        // 2. Outbox retries: encrypt under the live session, fresh nonce.
        for (packet in messenger.tick()) {
            router.onLocalSend(packet)
        }
        // 3. Re-key when a session is due for one.
        for (session in sessions.sessions()) {
            val peerHex = hex(session.peerId)
            if (heldPeers.contains(peerHex)) continue
            val due = session.needsRekey || sessions.rekeyDue(session)
            if (!due) continue
            val last = handshakeStartedAt[peerHex] ?: 0L
            if (System.currentTimeMillis() - last < HANDSHAKE_RETRY_COOLDOWN_MS) continue
            val packet = sessions.startHandshake(session.peerId)
            if (packet != null) {
                handshakeStartedAt[peerHex] = System.currentTimeMillis()
                router.onLocalSend(packet)
            }
        }
        // 4. Expire what nobody ever acknowledged.
        messenger.expire()
        refresh()
    }

    /**
     * Starts a handshake with a peer (the user opened a chat and the session
     * is not up yet).
     */
    suspend fun ensureSession(peerIdHex: String) {
        val peerId = peerIdHex.hexToBytes()
        val session = sessions.session(peerId)
        if (session != null && session.state == SecureSession.State.ESTABLISHED) return
        if (heldPeers.contains(peerIdHex)) return
        val last = handshakeStartedAt[peerIdHex] ?: 0L
        if (System.currentTimeMillis() - last < HANDSHAKE_RETRY_COOLDOWN_MS) return
        val packet = sessions.startHandshake(peerId)
        if (packet != null) {
            handshakeStartedAt[peerIdHex] = System.currentTimeMillis()
            router.onLocalSend(packet)
        }
    }

    /** Marks the peer's messages read and sends the READ receipts. */
    suspend fun markRead(peerIdHex: String) {
        val peerId = peerIdHex.hexToBytes()
        for (row in store.inbox.forPeer(peerIdHex)) {
            if (row.read) continue
            store.inbox.markRead(row.messageIdHex)
            val id = row.messageIdHex.hexToBytes()
            sendReceipt(peerId, id, Payloads.Receipt.KIND_READ)
        }
        refresh()
    }

    /** The user accepts a new key for a peer (§7.2). */
    suspend fun acceptKeyChange(peerIdHex: String) {
        val session = sessions.session(peerIdHex.hexToBytes()) ?: return
        val learned = session.remoteX25519PublicKey ?: return
        val known = knownX25519(peerIdHex.hexToBytes())
        // The scanned fingerprint no longer matches: the contact becomes TOFU.
        contacts.acceptNewKeys(
            peerIdHex = peerIdHex,
            ed25519PublicKey = session.remoteEd25519PublicKey ?: known ?: ByteArray(0),
            x25519PublicKey = learned,
        )
        heldPeers.remove(peerIdHex)
        _keyChange.value = null
        refresh()
    }

    /** The user rejects a new key for a peer: the peer is blocked. */
    suspend fun rejectKeyChange(peerIdHex: String) {
        blockedPeers.add(peerIdHex)
        contacts.block(peerIdHex)
        heldPeers.remove(peerIdHex)
        _keyChange.value = null
        refresh()
    }

    /** Panic wipe: the identity, the contacts, the sessions and every message. */
    suspend fun wipe() {
        sessions.wipe()
        messenger.wipe()
        store.sessions.wipe()
        store.outbox.wipe()
        store.seen.wipe()
        store.inbox.deleteAll()
        contacts.deleteAll()
        heldPeers.clear()
        blockedPeers.clear()
        handshakeStartedAt.clear()
        _messages.value = emptyList()
        _peers.value = emptyList()
    }

    // -------------------------------------------------------------- helpers

    private suspend fun sendReceipt(peerId: ByteArray, messageId: ByteArray, kind: Byte) {
        val payload = Payloads.encodeReceipt(Payloads.Receipt(kind, messageId))
        val unsigned = MeshPacket(
            version = PacketCodec.PROTOCOL_VERSION,
            type = PacketType.RECEIPT,
            ttl = PacketCodec.MAX_TTL,
            flags = 0x01,
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            senderId = identity.senderId,
            recipientId = peerId.copyOf(),
            signature = null,
        )
        val signed = unsigned.copy(
            flags = 0x03,
            signature = identity.signEd25519(PacketCodec.signingInput(unsigned)),
        )
        runCatching { router.onLocalSend(signed) }
    }

    /** The peer's known X25519 key (QR contacts) — the impersonation check. */
    private fun knownX25519(peerId: ByteArray): ByteArray? = knownKeys[hex(peerId)]

    /**
     * The handshake tier of a sender: a QR-verified contact first, then a
     * connected neighbour that has delivered a verified ANNOUNCE.
     */
    private fun tierOf(peerId: ByteArray): HandshakeTier {
        val peerHex = hex(peerId)
        val verified = verifiedPeers.contains(peerHex)
        val announced = keyCache.hasAnnounced(peerId)
        val connected = connectedPeers().contains(peerHex)
        return HandshakeTiers.tierOf(verified = verified, announced = announced, connected = connected)
    }

    private fun fingerprintOf(x25519PublicKey: ByteArray): String =
        com.meshline.app.protocol.MessageIds.fingerprint(identity.ed25519PublicKey, x25519PublicKey)

    /** Reloads the chat list and the peer list for the UI. */
    suspend fun refresh() {
        val outgoing = store.outbox.rows().mapNotNull { row ->
            val envelope = runCatching { PrivateEnvelope.decode(row.envelope) }.getOrNull() ?: return@mapNotNull null
            PrivateChatMessage(
                messageIdHex = hex(envelope.messageId),
                peerIdHex = row.peerIdHex,
                outgoing = true,
                text = envelope.text.toString(Charsets.UTF_8),
                state = row.state,
                createdAtMs = row.createdAtMs,
            )
        }
        val incoming = store.inbox.all().map { row ->
            PrivateChatMessage(
                messageIdHex = row.messageIdHex,
                peerIdHex = row.peerIdHex,
                outgoing = false,
                text = row.text.toString(Charsets.UTF_8),
                state = OutboxState.DELIVERED,
                createdAtMs = row.createdAtMs,
            )
        }
        _messages.value = (outgoing + incoming).sortedBy { it.createdAtMs }

        val connected = connectedPeers()
        val known = mutableMapOf<String, PrivatePeer>()
        // Cache what the Noise engine needs without touching the database.
        knownKeys.clear()
        verifiedPeers.clear()
        for (contact in contacts.all()) {
            knownKeys[contact.senderIdHex] = contact.x25519PublicKey
            if (contact.verified) verifiedPeers.add(contact.senderIdHex)
            known[contact.senderIdHex] = PrivatePeer(
                peerIdHex = contact.senderIdHex,
                nickname = contact.nickname,
                avatarIndex = contact.avatarIndex,
                verified = contact.verified,
                online = connected.contains(contact.senderIdHex),
                sessionReady = sessions.session(contact.senderIdHex.hexToBytes())
                    ?.state == SecureSession.State.ESTABLISHED,
                onHold = heldPeers.contains(contact.senderIdHex),
            )
        }
        for (session in sessions.sessions()) {
            val peerHex = hex(session.peerId)
            if (known.containsKey(peerHex)) continue
            val peer = PrivatePeer(
                peerIdHex = peerHex,
                nickname = "peer ${peerHex.take(8)}",
                avatarIndex = 0xFF,
                verified = false,
                online = connected.contains(peerHex),
                sessionReady = session.state == SecureSession.State.ESTABLISHED,
                onHold = heldPeers.contains(peerHex),
            )
            known[peerHex] = peer
        }
        // Peers we are linked to but have never talked to: they are listed so
        // the user can open a chat with them (the handshake starts then).
        for (peerHex in connected) {
            if (known.containsKey(peerHex)) continue
            val peer = PrivatePeer(
                peerIdHex = peerHex,
                nickname = "peer ${peerHex.take(8)}",
                avatarIndex = 0xFF,
                verified = false,
                online = true,
                sessionReady = sessions.session(peerHex.hexToBytes())
                    ?.state == SecureSession.State.ESTABLISHED,
                onHold = heldPeers.contains(peerHex),
            )
            known[peerHex] = peer
        }
        _peers.value = known.values.sortedWith(compareBy({ it.verified }, { it.nickname }))
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private companion object {
        /** Do not start a new handshake for the same peer more often than this. */
        const val HANDSHAKE_RETRY_COOLDOWN_MS = 20_000L
    }
}
