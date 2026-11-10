package com.meshline.app.noise

/**
 * Wire framing for the D2 payloads (PROTOCOL.md §6.5).
 *
 * Two payload shapes ride inside ordinary Meshline packets:
 *
 * ```
 * HANDSHAKE payload                          PRIVATE_MESSAGE payload
 * ┌────────┬───────┬──────┬────────────┬──────┐  ┌────────┬───────┬────────┬─────────────┐
 * │version │ flags │ step │ noiseLen(2)│ noise│  │version │ flags │nonce(8)│ ciphertext   │
 * │  1 B   │  1 B  │ 1 B  │    BE      │n byte│  │  1 B   │  1 B  │  BE    │  n+16 bytes │
 * └────────┴───────┴──────┴────────────┴──────┘  └────────┴───────┴────────┴─────────────┘
 *              └────────── optional ──────────┐              └─ AEAD ciphertext ─────┘
 *                 ed25519Pk (32 B)            │
 * ```
 *
 * `flags` bit 0 marks the presence of the Ed25519 **binding trailer**
 * (NOISE_DESIGN §7.1): the first handshake message from each side carries the
 * signer's long-term Ed25519 public key so the receiver can check that
 * `SHA-256(ed25519Pk)[0..8)` equals the outer packet's `senderId`. Without
 * that binding a peer could claim somebody else's id.
 *
 * `step` says which Noise handshake message this is (1 = `-> e`, 2 = `<- e, ee,
 * s, es`, 3 = `-> s, se`). It is needed because a Noise read that fails
 * **destroys the handshake state machine**, so the receiver must never guess:
 * the step tells it exactly which message to feed in. The step byte is not
 * secret and not authenticated — a wrong step only makes the Noise read fail
 * (the packet is then rejected and counted as an invalid handshake), it can
 * never make a bad message look good.
 *
 * The transport nonce is explicit (NOISE_DESIGN §4.1) because the mesh
 * reorders and duplicates packets: the receiver needs to know which nonce a
 * ciphertext used so it can run the replay window before decrypting.
 */
object NoisePayloads {

    /** `flags` bit 0: an Ed25519 binding trailer follows the Noise bytes. */
    const val FLAG_HAS_BINDING: Int = 0x01

    /** Handshake steps of the XX pattern. */
    object Step {
        /** `-> e` — the initiator's first message. */
        const val INITIATOR_FIRST = 1

        /** `<- e, ee, s, es` — the responder's answer. */
        const val RESPONDER = 2

        /** `-> s, se` — the initiator's final message. */
        const val INITIATOR_FINAL = 3
    }

    /** Raised when a payload does not match the expected shape. */
    class MalformedPayload(message: String) : Exception(message)

    // ------------------------------------------------------------ handshake

    /**
     * Encodes a handshake payload: the step, the Noise message and the
     * optional Ed25519 binding trailer.
     */
    fun encodeHandshake(step: Int, noise: ByteArray, ed25519Binding: ByteArray?): ByteArray {
        require(noise.size <= NoiseProtocol.MAX_HANDSHAKE_BYTES) {
            "handshake message too large: ${noise.size}"
        }
        require(step in Step.INITIATOR_FIRST..Step.INITIATOR_FINAL) { "bad handshake step $step" }
        val binding = ed25519Binding
        require(binding == null || binding.size == NoiseProtocol.BINDING_BYTES) {
            "binding must be ${NoiseProtocol.BINDING_BYTES} bytes"
        }
        val out = ByteArray(5 + noise.size + (binding?.size ?: 0))
        out[0] = NoiseProtocol.PAYLOAD_VERSION.toByte()
        out[1] = (if (binding != null) FLAG_HAS_BINDING else 0).toByte()
        out[2] = step.toByte()
        out[3] = ((noise.size ushr 8) and 0xFF).toByte()
        out[4] = (noise.size and 0xFF).toByte()
        noise.copyInto(out, 5)
        binding?.copyInto(out, 5 + noise.size)
        return out
    }

    /** A decoded handshake payload. */
    data class HandshakePayload(
        val step: Int,
        val noise: ByteArray,
        val ed25519Binding: ByteArray?,
    )

    /** Decodes a handshake payload; throws [MalformedPayload] on garbage. */
    fun decodeHandshake(payload: ByteArray): HandshakePayload {
        if (payload.size < 5) throw MalformedPayload("handshake payload too short (${payload.size})")
        val version = payload[0].toInt() and 0xFF
        if (version != NoiseProtocol.PAYLOAD_VERSION) {
            throw MalformedPayload("unsupported payload version $version")
        }
        val flags = payload[1].toInt() and 0xFF
        if (flags and FLAG_HAS_BINDING.inv() != 0) {
            throw MalformedPayload("unknown flags 0x%02X".format(flags))
        }
        val step = payload[2].toInt() and 0xFF
        if (step !in Step.INITIATOR_FIRST..Step.INITIATOR_FINAL) {
            throw MalformedPayload("unknown handshake step $step")
        }
        val len = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
        if (payload.size < 5 + len) throw MalformedPayload("truncated handshake payload")
        val hasBinding = flags and FLAG_HAS_BINDING != 0
        val expected = 5 + len + if (hasBinding) NoiseProtocol.BINDING_BYTES else 0
        if (payload.size != expected) {
            throw MalformedPayload("handshake payload size ${payload.size} != expected $expected")
        }
        val noise = payload.copyOfRange(5, 5 + len)
        val binding = if (hasBinding) payload.copyOfRange(5 + len, expected) else null
        return HandshakePayload(step, noise, binding)
    }

    // ------------------------------------------------------------ transport

    /**
     * Encodes a transport payload: the explicit nonce plus the AEAD
     * ciphertext (which already carries its 16-byte Poly1305 tag).
     */
    fun encodeTransport(nonce: Long, ciphertext: ByteArray): ByteArray {
        require(nonce >= 0) { "nonce must not be negative" }
        val out = ByteArray(10 + ciphertext.size)
        out[0] = NoiseProtocol.PAYLOAD_VERSION.toByte()
        out[1] = 0
        for (i in 0 until 8) {
            out[2 + i] = ((nonce ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        ciphertext.copyInto(out, 10)
        return out
    }

    /** A decoded transport payload. */
    data class TransportPayload(
        val nonce: Long,
        val ciphertext: ByteArray,
    )

    /** Decodes a transport payload; throws [MalformedPayload] on garbage. */
    fun decodeTransport(payload: ByteArray): TransportPayload {
        if (payload.size < 10) throw MalformedPayload("transport payload too short (${payload.size})")
        val version = payload[0].toInt() and 0xFF
        if (version != NoiseProtocol.PAYLOAD_VERSION) {
            throw MalformedPayload("unsupported payload version $version")
        }
        val flags = payload[1].toInt() and 0xFF
        if (flags != 0) throw MalformedPayload("unknown transport flags 0x%02X".format(flags))
        var nonce = 0L
        for (i in 0 until 8) {
            nonce = (nonce shl 8) or (payload[2 + i].toLong() and 0xFF)
        }
        return TransportPayload(nonce, payload.copyOfRange(10, payload.size))
    }
}
