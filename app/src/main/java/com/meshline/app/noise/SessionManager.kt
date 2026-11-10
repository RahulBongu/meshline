package com.meshline.app.noise

import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType

/**
 * The D2 secure-channel engine (NOISE_DESIGN §§2–6).
 *
 * It owns the per-peer sessions and turns Meshline packets into Noise
 * handshakes and encrypted private messages. It is deliberately **transport
 * and UI free**: it produces and consumes `MeshPacket`s, so the simulator can
 * drive it before anything is wired into BLE or the screens (D2 is protocol
 * and storage only until the phone test passes).
 *
 * Flow:
 * ```
 * startHandshake(peer)            -> HANDSHAKE(step 1, binding)
 *   <- HANDSHAKE(step 2, binding)  (we are the initiator)
 *   -> HANDSHAKE(step 3)            (we are the initiator)
 *   => ESTABLISHED
 *   -> PRIVATE_MESSAGE(nonce, ciphertext)
 * ```
 */
class SessionManager(
    /** Our long-term X25519 identity key (Noise static, 32 bytes). */
    private val localStaticX25519: ByteArray,
    /** Our long-term Ed25519 public key (the binding trailer, 32 bytes). */
    private val localEd25519PublicKey: ByteArray,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    /** Blocked peers are dropped before any budget accounting (NOISE_DESIGN §5). */
    private val isBlocked: (ByteArray) -> Boolean = { false },
    /**
     * The peer's known X25519 key (from its ANNOUNCE), if we have one. When it
     * differs from the key the handshake reveals, the peer is impersonating
     * somebody (NOISE_DESIGN §7.1) and the handshake is aborted.
     */
    private val knownX25519: (ByteArray) -> ByteArray? = { null },
) {
    /** Our own id (the outer packets' senderId). */
    val ownId: ByteArray = MessageIds.idFromPublicKey(localEd25519PublicKey)

    private val sessions = LinkedHashMap<String, SecureSession>()

    /** Handshake rate limits and quarantine (§5). */
    val limiter = HandshakeRateLimiter(nowProvider)

    /** Counters for the debug overlay and the health hints. */
    val counters = D2Counters()

    /** What happened to an incoming packet. */
    sealed interface Event {
        /** A packet the caller must send (handshake step or private message). */
        data class Outgoing(val packet: MeshPacket) : Event

        /** A session became usable. */
        data class SessionEstablished(val peerId: ByteArray, val rekey: Boolean) : Event

        /** A decrypted private message. */
        data class Plaintext(val peerId: ByteArray, val bytes: ByteArray) : Event

        /** The handshake could not be completed. */
        data class HandshakeFailed(val peerId: ByteArray?, val reason: String) : Event

        /** A protocol violation (impersonation, replay, garbage). */
        data class Violation(val peerId: ByteArray?, val connectionKey: String, val reason: String) : Event

        /** The session must be replaced by a fresh handshake (§6.1). */
        data class RehandshakeNeeded(val peerId: ByteArray) : Event
    }

    // ------------------------------------------------------------- sessions

    /** The session with [peerId], if any. */
    fun session(peerId: ByteArray): SecureSession? = sessions[hex(peerId)]

    /** All sessions. */
    fun sessions(): List<SecureSession> = sessions.values.toList()

    /** Drops every session (panic wipe). */
    fun wipe() {
        for (s in sessions.values) {
            s.send?.destroy()
            s.recv?.destroy()
            s.handshake?.destroy()
        }
        sessions.clear()
        limiter.reset()
    }

    // ---------------------------------------------------------- handshaking

    /**
     * Starts (or continues) a handshake with [peerId] and returns the packet to
     * send, or null when the handshake cannot be started right now (rate
     * limited, blocked, or already established).
     */
    fun startHandshake(peerId: ByteArray): MeshPacket? {
        val key = hex(peerId)
        val existing = sessions[key]
        if (existing != null) {
            when (existing.state) {
                SecureSession.State.ESTABLISHED -> return null
                SecureSession.State.INITIATING, SecureSession.State.RESP_AWAIT ->
                    // A handshake is already running: retransmit the last message.
                    return existing.pendingHandshakePacket
                else -> Unit
            }
        }
        if (isBlocked(peerId)) {
            counters.blockedDropped++
            return null
        }
        if (!limiter.allowStart(key)) {
            counters.startsRateLimited++
            return null
        }
        // The global work budget: a peer that reconnects under a new BLE
        // address must not be able to make us do endless key agreement.
        if (!limiter.allowDhWork(NoiseProtocol.DH_COST_START)) {
            counters.handshakeBudgetExhausted++
            return null
        }
        val handshake = NoiseHandshake.initiator(localStaticX25519)
        val session = existing ?: SecureSession(
            peerId = peerId,
            remoteEd25519PublicKey = null,
            createdAtMs = nowProvider(),
        ).also { sessions[key] = it }
        session.handshake?.destroy()
        session.handshake = handshake
        session.state = SecureSession.State.INITIATING
        session.handshakeRetries = 0
        session.bindingSent = false
        val packet = handshakePacket(peerId, NoisePayloads.Step.INITIATOR_FIRST, handshake.writeMessage(), localEd25519PublicKey)
        session.pendingHandshakePacket = packet
        session.lastHandshakeSentAtMs = nowProvider()
        counters.handshakesStarted++
        return packet
    }

    /**
     * Handshake packets that are due for a retransmit (NOISE_DESIGN §3.3:
     * every 2 s, 5 times, then the attempt expires).
     */
    fun dueRetransmits(): List<MeshPacket> {
        val now = nowProvider()
        val out = mutableListOf<MeshPacket>()
        for (s in sessions.values) {
            if (s.state != SecureSession.State.INITIATING && s.state != SecureSession.State.RESP_AWAIT) continue
            val pending = s.pendingHandshakePacket ?: continue
            if (now - s.lastHandshakeSentAtMs < NoiseProtocol.HANDSHAKE_RETRANSMIT_MS) continue
            if (s.handshakeRetries >= NoiseProtocol.HANDSHAKE_RETRIES) {
                s.state = SecureSession.State.ABSENT
                s.handshake?.destroy()
                s.handshake = null
                s.pendingHandshakePacket = null
                counters.handshakesExpired++
                continue
            }
            s.handshakeRetries++
            s.lastHandshakeSentAtMs = now
            out += pending
        }
        return out
    }

    // -------------------------------------------------------------- inbound

    /** Handles an incoming packet. Returns the events it produced. */
    fun onPacket(packet: MeshPacket, fromPeer: ByteArray): List<Event> = when (packet.type) {
        PacketType.HANDSHAKE -> onHandshake(packet, fromPeer)
        PacketType.PRIVATE_MESSAGE -> onPrivateMessage(packet, fromPeer)
        else -> emptyList()
    }

    private fun onHandshake(packet: MeshPacket, fromPeer: ByteArray): List<Event> {
        val events = mutableListOf<Event>()
        val connectionKey = hex(fromPeer)
        // Blocked peers are dropped before any budget accounting (§5).
        if (isBlocked(packet.senderId)) {
            counters.blockedDropped++
            return events
        }
        if (limiter.isQuarantined(connectionKey)) {
            counters.quarantines++
            return events
        }
        if (!limiter.allowPacket(connectionKey)) {
            counters.handshakePacketsRateLimited++
            return events
        }
        val payload = try {
            NoisePayloads.decodeHandshake(packet.payload)
        } catch (e: NoisePayloads.MalformedPayload) {
            strike(connectionKey, packet.senderId, "malformed handshake payload", events)
            return events
        }

        // Identity binding (§7.1): the trailer must hash to the claimed id.
        val binding = payload.ed25519Binding
        if (binding != null && !MessageIds.idFromPublicKey(binding).contentEquals(packet.senderId)) {
            limiter.recordViolation(connectionKey)
            strike(connectionKey, packet.senderId, "identity binding mismatch", events)
            return events
        }

        // Global work budget: charge the X25519 operations this step will
        // cost *before* doing them. A peer that reconnects under a new BLE
        // address gets a fresh per-connection bucket, but not a fresh work
        // budget (NOISE_DESIGN §5).
        if (!limiter.allowDhWork(NoiseProtocol.dhCost(payload.step))) {
            counters.handshakeBudgetExhausted++
            return events
        }

        val key = hex(packet.senderId)
        val session = sessions.getOrPut(key) {
            SecureSession(
                peerId = packet.senderId,
                remoteEd25519PublicKey = binding,
                createdAtMs = nowProvider(),
            )
        }
        if (binding != null) session.remoteEd25519PublicKey = binding

        when (payload.step) {
            NoisePayloads.Step.INITIATOR_FIRST -> onPeerStart(session, packet, payload, connectionKey, events)
            NoisePayloads.Step.RESPONDER -> onResponderStep(session, packet, payload, connectionKey, events)
            NoisePayloads.Step.INITIATOR_FINAL -> onInitiatorFinal(session, packet, payload, connectionKey, events)
            else -> Unit
        }
        return events
    }

    /** The peer starts a handshake (step 1). */
    private fun onPeerStart(
        session: SecureSession,
        packet: MeshPacket,
        payload: NoisePayloads.HandshakePayload,
        connectionKey: String,
        events: MutableList<Event>,
    ) {
        // Concurrent start: the node with the lower senderId wins (§3.4).
        if (session.state == SecureSession.State.INITIATING) {
            if (compareIds(ownId, packet.senderId) <= 0) {
                // Our handshake wins: ignore theirs (they will adopt ours).
                counters.tieBreakWon++
                return
            }
            // Their handshake wins: abandon ours and answer them.
            counters.tieBreakLost++
            session.handshake?.destroy()
            session.handshake = null
            session.state = SecureSession.State.ABSENT
        }
        if (session.state == SecureSession.State.ESTABLISHED) return

        val handshake = NoiseHandshake.responder(localStaticX25519)
        session.handshake?.destroy()
        session.handshake = handshake
        session.state = SecureSession.State.RESP_AWAIT
        session.bindingSent = false
        session.handshakeRetries = 0
        try {
            handshake.readMessage(payload.noise)
        } catch (e: NoiseHandshake.HandshakeFailed) {
            session.handshake = null
            session.state = SecureSession.State.ABSENT
            strike(connectionKey, packet.senderId, "invalid handshake start", events)
            return
        }
        counters.handshakesAnswered++
        sendNextHandshakeMessage(session, NoisePayloads.Step.RESPONDER, events)
    }

    /** Step 2: the answer to our step 1. */
    private fun onResponderStep(
        session: SecureSession,
        packet: MeshPacket,
        payload: NoisePayloads.HandshakePayload,
        connectionKey: String,
        events: MutableList<Event>,
    ) {
        val handshake = session.handshake
        if (handshake == null || session.state != SecureSession.State.INITIATING) {
            counters.unexpectedHandshake++
            return
        }
        try {
            handshake.readMessage(payload.noise)
        } catch (e: NoiseHandshake.HandshakeFailed) {
            strike(connectionKey, packet.senderId, "invalid handshake response", events)
            return
        }
        sendNextHandshakeMessage(session, NoisePayloads.Step.INITIATOR_FINAL, events)
    }

    /** Step 3: the initiator's final message. */
    private fun onInitiatorFinal(
        session: SecureSession,
        packet: MeshPacket,
        payload: NoisePayloads.HandshakePayload,
        connectionKey: String,
        events: MutableList<Event>,
    ) {
        val handshake = session.handshake
        if (handshake == null || session.state != SecureSession.State.RESP_AWAIT) {
            counters.unexpectedHandshake++
            return
        }
        try {
            handshake.readMessage(payload.noise)
        } catch (e: NoiseHandshake.HandshakeFailed) {
            strike(connectionKey, packet.senderId, "invalid handshake final", events)
            return
        }
        establish(session, connectionKey, events)
    }

    /**
     * Writes the next handshake message (if the machine wants one) and, when
     * the handshake is complete, splits the transport keys.
     */
    private fun sendNextHandshakeMessage(
        session: SecureSession,
        step: Int,
        events: MutableList<Event>,
    ) {
        val handshake = session.handshake ?: return
        if (handshake.needsWrite()) {
            val noise = handshake.writeMessage()
            // The first message of our side carries the identity binding (§7.1).
            val binding = if (!session.bindingSent) localEd25519PublicKey else null
            session.bindingSent = true
            val packet = handshakePacket(session.peerId, step, noise, binding)
            session.pendingHandshakePacket = packet
            session.lastHandshakeSentAtMs = nowProvider()
            events += Event.Outgoing(packet)
        }
        if (handshake.canSplit()) {
            establish(session, hex(session.peerId), events)
        }
    }

    /** Turns a completed handshake into an established session. */
    private fun establish(session: SecureSession, connectionKey: String, events: MutableList<Event>) {
        val handshake = session.handshake ?: return
        if (!handshake.canSplit()) return

        // Impersonation defence (§7.1): the X25519 key the handshake revealed
        // must match the one we already know for this senderId.
        val remoteStatic = runCatching { remoteStaticKey(handshake) }.getOrNull()
        if (remoteStatic == null) {
            strike(connectionKey, session.peerId, "handshake produced no remote static key", events)
            return
        }
        session.remoteX25519PublicKey = remoteStatic
        val known = knownX25519(session.peerId)
        if (known != null && !known.contentEquals(remoteStatic)) {
            limiter.recordViolation(connectionKey)
            strike(connectionKey, session.peerId, "static key does not match the announced key", events)
            return
        }

        val keys = try {
            handshake.split()
        } catch (e: NoiseHandshake.HandshakeFailed) {
            strike(connectionKey, session.peerId, "handshake split failed", events)
            return
        }
        session.send?.destroy()
        session.recv?.destroy()
        session.send = TransportCipher(keys.send)
        session.recv = TransportCipher(keys.recv)
        session.replayWindow.reset()
        session.state = SecureSession.State.ESTABLISHED
        session.pendingHandshakePacket = null
        session.handshakeRetries = 0
        session.lastUsedAtMs = nowProvider()
        session.firstMessageAtMs = 0
        session.messagesSent = 0
        session.messagesReceived = 0
        limiter.onHandshakeSucceeded(connectionKey)
        counters.sessionsEstablished++
        events += Event.SessionEstablished(session.peerId.copyOf(), rekey = false)
    }

    /** The remote static (X25519) public key the handshake revealed. */
    private fun remoteStaticKey(handshake: NoiseHandshake): ByteArray? {
        val out = ByteArray(32)
        handshake.remoteStaticKey(out)
        return out
    }

    // ------------------------------------------------------------- private

    /** Handles an incoming encrypted private message. */
    private fun onPrivateMessage(packet: MeshPacket, fromPeer: ByteArray): List<Event> {
        val events = mutableListOf<Event>()
        val connectionKey = hex(fromPeer)
        if (isBlocked(packet.senderId)) {
            counters.blockedDropped++
            return events
        }
        val session = sessions[hex(packet.senderId)]
        if (session == null || session.state != SecureSession.State.ESTABLISHED) {
            // Transport ciphertext outside a session: a protocol violation, not
            // a forgery (the peer may simply have restarted).
            counters.protocolViolations++
            limiter.recordViolation(connectionKey)
            events += Event.Violation(packet.senderId.copyOf(), connectionKey, "transport message without a session")
            return events
        }
        val payload = try {
            NoisePayloads.decodeTransport(packet.payload)
        } catch (e: NoisePayloads.MalformedPayload) {
            counters.protocolViolations++
            limiter.recordViolation(connectionKey)
            events += Event.Violation(packet.senderId.copyOf(), connectionKey, "malformed transport payload")
            return events
        }
        // Replay window first (§4.1): never decrypt a nonce twice.
        when (session.replayWindow.accept(payload.nonce)) {
            ReplayWindow.Decision.REPLAY -> {
                counters.replaysRejected++
                events += Event.Violation(packet.senderId.copyOf(), connectionKey, "replayed nonce")
                return events
            }
            ReplayWindow.Decision.TOO_FAR_AHEAD -> {
                counters.protocolViolations++
                limiter.recordViolation(connectionKey)
                // The counter drifted further than the replay window allows:
                // our session state and the peer's have diverged (too many
                // messages lost, or the peer re-keyed). NOISE_DESIGN §6.1 says
                // recover with a *silent re-handshake*, not with an error.
                session.needsRekey = true
                events += Event.RehandshakeNeeded(packet.senderId.copyOf())
                events += Event.Violation(packet.senderId.copyOf(), connectionKey, "nonce beyond the replay window")
                return events
            }
            ReplayWindow.Decision.ACCEPTED -> Unit
        }
        val recv = session.recv
        if (recv == null) {
            events += Event.Violation(packet.senderId.copyOf(), connectionKey, "no receive cipher")
            return events
        }
        val plaintext = try {
            recv.decrypt(payload.ciphertext, PacketCodec.headerAd(packet), payload.nonce)
        } catch (e: Exception) {
            // Authentication failed: stale session state or tampering. The
            // protocol recovers with a silent re-handshake (§6.1).
            counters.decryptFailures++
            session.needsRekey = true
            events += Event.RehandshakeNeeded(packet.senderId.copyOf())
            return events
        }
        session.messagesReceived++
        session.lastUsedAtMs = nowProvider()
        events += Event.Plaintext(packet.senderId.copyOf(), plaintext)
        return events
    }

    /**
     * Encrypts [plaintext] for [peerId]. Returns null when there is no usable
     * session (the caller queues the message) or when the session needs a
     * re-key.
     */
    fun encrypt(peerId: ByteArray, plaintext: ByteArray): MeshPacket? {
        if (plaintext.size > NoiseProtocol.MAX_PLAINTEXT_BYTES) return null
        val session = sessions[hex(peerId)] ?: return null
        if (session.state != SecureSession.State.ESTABLISHED) return null
        if (session.needsRekey) return null
        if (rekeyDue(session)) {
            session.needsRekey = true
            return null
        }
        val send = session.send ?: return null
        // Build the packet first so the associated data covers its header.
        val packet = MeshPacket(
            version = PacketCodec.PROTOCOL_VERSION,
            type = PacketType.PRIVATE_MESSAGE,
            ttl = PacketCodec.MAX_TTL,
            flags = 0x01, // directed, unsigned: Noise authenticates the content
            timestampMs = nowProvider(),
            payload = ByteArray(0),
            senderId = ownId,
            recipientId = peerId.copyOf(),
            signature = null,
        )
        val ad = PacketCodec.headerAd(packet)
        val nonce = send.nonce
        val ciphertext = send.encrypt(plaintext, ad)
        val finalPacket = packet.copy(payload = NoisePayloads.encodeTransport(nonce, ciphertext))
        session.messagesSent++
        session.lastUsedAtMs = nowProvider()
        if (session.firstMessageAtMs == 0L) session.firstMessageAtMs = nowProvider()
        counters.messagesEncrypted++
        return finalPacket
    }

    /** True when the session must be replaced by a fresh handshake (§6). */
    fun rekeyDue(session: SecureSession): Boolean {
        val now = nowProvider()
        if (session.messagesSent >= NoiseProtocol.REKEY_MESSAGES_PER_DIRECTION) return true
        if (session.messagesReceived >= NoiseProtocol.REKEY_MESSAGES_PER_DIRECTION) return true
        if (now - session.createdAtMs >= NoiseProtocol.REKEY_AFTER_AGE_MS) return true
        if (session.firstMessageAtMs > 0 &&
            now - session.firstMessageAtMs >= NoiseProtocol.REKEY_AFTER_FIRST_MESSAGE_MS
        ) return true
        return false
    }

    /** Marks a session as needing a fresh handshake and returns the peer id. */
    fun requestRehandshake(peerId: ByteArray): Boolean {
        val session = sessions[hex(peerId)] ?: return false
        session.needsRekey = true
        session.silentRehandshakes++
        counters.silentRehandshakes++
        return true
    }

    /** Closes a session (peer left, or it went idle). */
    fun closeSession(peerId: ByteArray) {
        val session = sessions.remove(hex(peerId)) ?: return
        session.send?.destroy()
        session.recv?.destroy()
        session.handshake?.destroy()
        session.state = SecureSession.State.CLOSED
    }

    // ------------------------------------------------------------- helpers

    /** Records an invalid handshake: a strike, and a quarantine when it tips over. */
    private fun strike(connectionKey: String, peerId: ByteArray?, reason: String, events: MutableList<Event>) {
        counters.invalidHandshakes++
        val quarantined = limiter.recordInvalid(connectionKey)
        if (quarantined) counters.quarantines++
        events += Event.HandshakeFailed(peerId?.copyOf(), reason)
    }

    private fun handshakePacket(peerId: ByteArray, step: Int, noise: ByteArray, binding: ByteArray?): MeshPacket {
        val payload = NoisePayloads.encodeHandshake(step, noise, binding)
        return MeshPacket(
            version = PacketCodec.PROTOCOL_VERSION,
            type = PacketType.HANDSHAKE,
            ttl = PacketCodec.MAX_TTL,
            flags = 0x01, // directed; HAS_SIGNATURE clear (Noise authenticates it)
            timestampMs = nowProvider(),
            payload = payload,
            senderId = ownId,
            recipientId = peerId.copyOf(),
            signature = null,
        )
    }

    private fun compareIds(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Counters for the debug overlay and the health hints. */
    class D2Counters {
        @Volatile var handshakesStarted: Int = 0
        @Volatile var handshakesAnswered: Int = 0
        @Volatile var sessionsEstablished: Int = 0
        @Volatile var handshakesExpired: Int = 0
        @Volatile var invalidHandshakes: Int = 0
        @Volatile var quarantines: Int = 0
        @Volatile var startsRateLimited: Int = 0
        @Volatile var handshakePacketsRateLimited: Int = 0
        /** Handshakes refused because the global X25519 work budget was spent. */
        @Volatile var handshakeBudgetExhausted: Int = 0
        @Volatile var blockedDropped: Int = 0
        @Volatile var unexpectedHandshake: Int = 0
        @Volatile var tieBreakWon: Int = 0
        @Volatile var tieBreakLost: Int = 0
        @Volatile var messagesEncrypted: Int = 0
        @Volatile var replaysRejected: Int = 0
        @Volatile var decryptFailures: Int = 0
        @Volatile var protocolViolations: Int = 0
        @Volatile var silentRehandshakes: Int = 0
    }
}
