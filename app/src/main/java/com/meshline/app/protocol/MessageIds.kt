package com.meshline.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Message IDs, sender/recipient IDs and fingerprints (PROTOCOL.md §4, §7).
 *
 * Pure JVM (java.security.MessageDigest), no Android dependencies, so unit tests
 * run without an emulator. Hashing here is SHA-256 only — this module never
 * signs, encrypts, or generates keys; that is the SecurityModule's job.
 */
object MessageIds {

    /**
     * `SHA-256( senderId(8) ‖ timestampMs(8 BE) ‖ type(1) ‖ payloadLen(2 BE) ‖ payload )`
     *
     * Returns the full 32-byte hash. The router dedupes on this value.
     * TTL, flags, recipientId and signature are deliberately **not** hash inputs,
     * so a packet relayed hop-by-hop (TTL 7 → 6 → …) keeps one identity (§3.1).
     */
    fun compute(packet: MeshPacket): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(packet.senderId)
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(packet.timestampMs).array())
        digest.update(packet.type.code)
        digest.update(ByteBuffer.allocate(Short.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putShort(packet.payload.size.toShort()).array())
        digest.update(packet.payload)
        return digest.digest()
    }

    /**
     * 8-byte truncated SHA-256 of a 32-byte public key: `SHA-256(pubkey)[0..8)`.
     * This is how senderId / recipientId are derived (PROTOCOL.md §4).
     */
    fun idFromPublicKey(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "public key must be 32 bytes, got ${publicKey.size}" }
        return MessageDigest.getInstance("SHA-256").digest(publicKey).copyOfRange(0, MeshPacket.ID_BYTES)
    }

    /**
     * Display fingerprint: `SHA-256(ed25519Pk ‖ x25519Pk)` as 64 lowercase hex chars.
     * Shown in groups of 4 in the Peers screen (PRD FR-13). Computed for display only.
     */
    fun fingerprint(ed25519PublicKey: ByteArray, x25519PublicKey: ByteArray): String {
        require(ed25519PublicKey.size == 32) { "ed25519 public key must be 32 bytes" }
        require(x25519PublicKey.size == 32) { "x25519 public key must be 32 bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ed25519PublicKey)
        val hash = digest.digest(x25519PublicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
