package com.meshline.app.security

/**
 * SecurityModule (PRD §12 / PROTOCOL.md §7, §10). Phase D.
 *
 * Responsibilities (all deferred to Phase D — this file is the contract):
 *  - Generate X25519 + Ed25519 key pairs on first launch; store private keys in
 *    the Android Keystore (never in Room, never in a packet).
 *  - Sign/verify the outer packets that require signatures (§3).
 *  - Run the Noise_XX_25519_ChaChaPoly_SHA256 handshake via a reviewed Noise
 *    library (noise-java) — never hand-rolled. Transport ciphertext uses the
 *    Noise session's AEAD. Re-key per the library's limits.
 *  - Derive senderId (truncated SHA-256 of the Ed25519 pubkey), fingerprints,
 *    and the ephemeral per-session peer id.
 *  - Panic wipe: erase keys, database, peers and nickname (triple-tap gesture).
 *
 * Phase A deliberately contains no crypto beyond SHA-256 in the codec, so the
 * wire format can be fuzz-tested before any secrets exist.
 */
interface SecurityModule {
    /** 8-byte senderId for this node (ephemeral per session when rotation is on). */
    val selfId: ByteArray

    /** Sign the canonical packet bytes (§3: header + payload). 64 bytes. */
    fun sign(bytes: ByteArray): ByteArray

    /** Verify a 64-byte signature over [bytes] with [publicKey] (32 bytes). */
    fun verify(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

    /** Stop signing as this identity and forget everything (Phase D panic wipe). */
    suspend fun wipeAll()
}
