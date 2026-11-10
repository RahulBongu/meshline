package com.meshline.app.noise

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState

/**
 * The Noise handshake machine (NOISE_DESIGN §2/§3, pattern
 * `Noise_XX_25519_ChaChaPoly_SHA256`).
 *
 * This is a thin, explicit wrapper around the pinned `noise-java` library: the
 * pattern, the key agreement, the transcript hashing and the transport key
 * derivation all happen inside the library. We never implement key agreement
 * ourselves.
 *
 * XX is a 3-message handshake:
 * ```
 * initiator -> e                    (m1)
 * responder -> e, ee, s, es        (m2)
 * initiator -> s, se                (m3)
 * ```
 * Both sides prove possession of their long-term X25519 identity key; the
 * static keys stay hidden until the second message.
 *
 * The wrapper keeps the library's state machine explicit (write / read /
 * split) so the session layer can drive it over a lossy mesh, where messages
 * arrive out of order or not at all.
 */
class NoiseHandshake private constructor(
    /** Which side we are: the initiator sends m1, the responder answers it. */
    val role: Role,
    private val hs: HandshakeState,
) {

    enum class Role { INITIATOR, RESPONDER }

    /** Raised when the peer sends something the handshake cannot accept. */
    class HandshakeFailed(message: String) : Exception(message)

    /**
     * Creates an initiator. [staticPrivateKey] is our long-term X25519
     * identity key (32 bytes); it never leaves the device.
     *
     * [prologue] and [fixedEphemeral] exist for the official Noise test
     * vectors (NOISE_DESIGN §9.1): the vectors fix both, so the handshake
     * messages come out byte-exact. Production always uses the default
     * prologue and a random ephemeral.
     */
    companion object {
        fun initiator(
            staticPrivateKey: ByteArray,
            prologue: ByteArray = NoiseProtocol.PROLOGUE,
            fixedEphemeral: ByteArray? = null,
        ): NoiseHandshake {
            require(staticPrivateKey.size == 32) { "X25519 private key must be 32 bytes" }
            val hs = HandshakeState(NoiseProtocol.PATTERN, HandshakeState.INITIATOR)
            hs.setPrologue(prologue, 0, prologue.size)
            hs.getLocalKeyPair().setPrivateKey(staticPrivateKey, 0)
            if (fixedEphemeral != null) {
                require(fixedEphemeral.size == 32) { "ephemeral must be 32 bytes" }
                hs.getFixedEphemeralKey().setPrivateKey(fixedEphemeral, 0)
            }
            hs.start()
            return NoiseHandshake(Role.INITIATOR, hs)
        }

        /** Creates a responder (it learns the peer's static key from m1/m3). */
        fun responder(
            staticPrivateKey: ByteArray,
            prologue: ByteArray = NoiseProtocol.PROLOGUE,
            fixedEphemeral: ByteArray? = null,
        ): NoiseHandshake {
            require(staticPrivateKey.size == 32) { "X25519 private key must be 32 bytes" }
            val hs = HandshakeState(NoiseProtocol.PATTERN, HandshakeState.RESPONDER)
            hs.setPrologue(prologue, 0, prologue.size)
            hs.getLocalKeyPair().setPrivateKey(staticPrivateKey, 0)
            if (fixedEphemeral != null) {
                require(fixedEphemeral.size == 32) { "ephemeral must be 32 bytes" }
                hs.getFixedEphemeralKey().setPrivateKey(fixedEphemeral, 0)
            }
            hs.start()
            return NoiseHandshake(Role.RESPONDER, hs)
        }
    }

    /** True when the machine wants to send the next handshake message. */
    fun needsWrite(): Boolean = hs.action == HandshakeState.WRITE_MESSAGE

    /** True when the machine wants to read the next handshake message. */
    fun needsRead(): Boolean = hs.action == HandshakeState.READ_MESSAGE

    /** True when the handshake is finished and the transport keys can be split. */
    fun canSplit(): Boolean = hs.action == HandshakeState.SPLIT

    /** The final transcript hash (both sides agree on it). */
    fun handshakeHash(): ByteArray = hs.handshakeHash

    /**
     * The peer's static (X25519) public key, once the handshake has revealed
     * it. Returns false when the peer has not sent its static key yet.
     *
     * This is the key NOISE_DESIGN §7.1 compares against the announced key:
     * a peer that presents a different static key for a known senderId is
     * impersonating somebody.
     */
    fun remoteStaticKey(out: ByteArray): Boolean {
        val remote = hs.remotePublicKey
        if (remote == null || !remote.hasPublicKey()) return false
        if (out.size < remote.publicKeyLength) return false
        remote.getPublicKey(out, 0)
        return true
    }

    /**
     * Writes the next handshake message. The payload is normally empty: the
     * Ed25519 binding trailer of NOISE_DESIGN §7.1 travels *outside* the
     * Noise message, in the Meshline payload framing. The official test
     * vectors use payloads, so the parameter exists for them.
     */
    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        if (!needsWrite()) throw HandshakeFailed("handshake does not want to write (action=${hs.action})")
        val out = ByteArray(NoiseProtocol.MAX_HANDSHAKE_BYTES)
        val len = hs.writeMessage(out, 0, payload, 0, payload.size)
        return out.copyOf(len)
    }

    /** Reads the next handshake message and returns its (empty) payload. */
    fun readMessage(message: ByteArray): ByteArray {
        if (!needsRead()) throw HandshakeFailed("handshake does not want to read (action=${hs.action})")
        val plaintext = ByteArray(NoiseProtocol.MAX_HANDSHAKE_BYTES)
        val len = try {
            hs.readMessage(message, 0, message.size, plaintext, 0)
        } catch (e: Exception) {
            throw HandshakeFailed("handshake message rejected: ${e.message}")
        }
        return plaintext.copyOf(len)
    }

    /**
     * Splits the handshake into the two transport cipher states.
     *
     * `send` is always the cipher we encrypt with and `recv` the one we
     * decrypt with: the library already swaps the pair for the responder, so
     * `CipherStatePair.getSender()` is role-independent.
     */
    fun split(): TransportKeys {
        if (!canSplit()) throw HandshakeFailed("handshake is not complete (action=${hs.action})")
        val pair: CipherStatePair = hs.split()
        return TransportKeys(send = pair.sender, recv = pair.receiver)
    }

    /** Wipes the library's ephemeral state. */
    fun destroy() {
        hs.destroy()
    }
}

/** The two transport cipher states of an established session. */
data class TransportKeys(val send: CipherState, val recv: CipherState)
