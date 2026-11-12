package com.meshline.app.storage

/**
 * The contacts the app knows about.
 *
 * The D2 layer needs three things from them: the X25519 key we trust (the
 * impersonation check, NOISE_DESIGN §7.1), whether the contact is QR-verified
 * (the handshake tier and the badge) and whether the user blocked them. The
 * interface keeps the secure channel free of Android (so it can be tested on
 * the JVM); [ContactsRepository] is the Room-backed implementation.
 */
interface ContactDirectory {
    /** Every contact we know. */
    suspend fun all(): List<ContactEntity>

    /** The X25519 key we trust for this peer (QR contacts), or null. */
    suspend fun x25519For(peerIdHex: String): ByteArray?

    /** True only for contacts the user verified with a QR scan. */
    suspend fun isVerified(peerIdHex: String): Boolean

    /** True when the user blocked this peer. */
    suspend fun isBlocked(peerIdHex: String): Boolean

    /**
     * The user accepted a new identity for this peer: the keys are updated
     * and the contact becomes TOFU (it loses the QR-verified badge, because
     * the scanned fingerprint no longer matches).
     */
    suspend fun acceptNewKeys(peerIdHex: String, ed25519PublicKey: ByteArray, x25519PublicKey: ByteArray)

    /** The user rejected a peer: it is blocked. */
    suspend fun block(peerIdHex: String)

    /** Panic wipe: every contact goes. */
    suspend fun deleteAll()
}

/** The stored Noise sessions (the interface keeps the wipe JVM-testable). */
interface SessionDirectory {
    /** Forgets every stored session. */
    suspend fun wipe()
}
