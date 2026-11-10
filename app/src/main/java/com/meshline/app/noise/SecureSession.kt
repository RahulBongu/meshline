package com.meshline.app.noise

import com.meshline.app.protocol.MeshPacket

/**
 * One peer's secure session (NOISE_DESIGN §4).
 *
 * ```
 * ABSENT ──(we start)──▶ INITIATING ──(m2 read)──▶ INITIATING ──(m3)──▶ ESTABLISHED
 *    │                        │                                              │
 *    └──(peer starts)──▶ RESP_AWAIT ──(m3 read)──▶ ESTABLISHED               │
 *                                                                           │
 * ESTABLISHED ──(peer left / idle)──▶ CLOSED ◀──(re-key)───────────────────┘
 * ```
 */
class SecureSession(
    /** The peer's 8-byte id (the outer packet's senderId). */
    val peerId: ByteArray,
    /** The peer's Ed25519 public key, learned from the binding trailer (§7.1). */
    var remoteEd25519PublicKey: ByteArray?,
    /** The peer's X25519 public key, revealed by the handshake (§7.1). */
    var remoteX25519PublicKey: ByteArray? = null,
    /** True once we have sent our identity binding trailer (§7.1). */
    var bindingSent: Boolean = false,
    /** When the session was created. */
    val createdAtMs: Long,
) {
    enum class State { ABSENT, INITIATING, RESP_AWAIT, ESTABLISHED, CLOSED }

    var state: State = State.ABSENT

    /** The Noise handshake machine, while the handshake is running. */
    var handshake: NoiseHandshake? = null

    /** The last handshake packet we produced (for retransmits). */
    var pendingHandshakePacket: MeshPacket? = null

    /** When we last sent a handshake message (retransmit timer). */
    var lastHandshakeSentAtMs: Long = 0

    /** How many times we retransmitted the current handshake message. */
    var handshakeRetries: Int = 0

    /** Transport cipher for messages we send. */
    var send: TransportCipher? = null

    /** Transport cipher for messages we receive. */
    var recv: TransportCipher? = null

    /** Replay window for the receive direction (§4.1). */
    val replayWindow = ReplayWindow()

    var messagesSent: Int = 0
    var messagesReceived: Int = 0

    /** When the first transport message was sent (7-day re-key bound). */
    var firstMessageAtMs: Long = 0

    /** Last activity on this session (idle close, re-key). */
    var lastUsedAtMs: Long = 0

    /** Silent re-handshakes performed on this session (§6.1). */
    var silentRehandshakes: Int = 0

    /** Set when the session must be replaced by a fresh handshake. */
    var needsRekey: Boolean = false

    /** Fingerprint of the peer's identity keys (display only). */
    fun fingerprint(): String? {
        val ed = remoteEd25519PublicKey ?: return null
        return com.meshline.app.protocol.MessageIds.fingerprint(ed, ByteArray(32))
    }
}
