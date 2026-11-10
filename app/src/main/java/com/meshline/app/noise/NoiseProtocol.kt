package com.meshline.app.noise

/**
 * D2 constants for the Noise private-chat layer (NOISE_DESIGN.md, PROTOCOL.md §6.5).
 *
 * Nothing in here is negotiated: both sides compile the same values, so a
 * mismatch is a version error, not a runtime negotiation.
 */
object NoiseProtocol {

    /** The Noise pattern (NOISE_DESIGN §2): mutual static authentication. */
    const val PATTERN: String = "Noise_XX_25519_ChaChaPoly_SHA256"

    /**
     * Prologue. It is hashed into the handshake transcript, so both sides must
     * agree byte for byte. It binds the handshake to this protocol and
     * version; changing it is a wire change (new handshake, new session).
     */
    val PROLOGUE: ByteArray = "Meshline-D2-NoiseXX-v1".toByteArray(Charsets.UTF_8)

    /** Version byte of the D2 payload framing (handshake and transport). */
    const val PAYLOAD_VERSION: Int = 1

    /** The Ed25519 binding trailer length (NOISE_DESIGN §7.1). */
    const val BINDING_BYTES: Int = 32

    /** Largest handshake message we accept (bytes of Noise payload). */
    const val MAX_HANDSHAKE_BYTES: Int = 512

    /** Largest transport plaintext we accept in one message. */
    const val MAX_PLAINTEXT_BYTES: Int = 4_096

    // ---- transport nonces (NOISE_DESIGN §4.1) ----

    /** Replay window size in nonces (128 = the standard AEAD skip-list window). */
    const val REPLAY_WINDOW_SIZE: Int = 128

    /**
     * Hard cap on messages per direction before a re-key. The library's own
     * limit is 2^14; 1 000 is the Meshline belt-and-braces bound.
     */
    const val REKEY_MESSAGES_PER_DIRECTION: Int = 1_000

    /** Re-key after 24 h of session age. */
    const val REKEY_AFTER_AGE_MS: Long = 24L * 60 * 60 * 1_000

    /** Re-key after 7 days since the session's first transport message. */
    const val REKEY_AFTER_FIRST_MESSAGE_MS: Long = 7L * 24 * 60 * 60 * 1_000

    // ---- handshake rate limits (NOISE_DESIGN §5) ----

    /** Handshake packets accepted per connection per 10 s. */
    const val HANDSHAKE_PACKETS_PER_CONNECTION_PER_10S: Int = 5

    /** Handshake *starts* allowed per connection per hour. */
    const val HANDSHAKE_STARTS_PER_CONNECTION_PER_HOUR: Int = 20

    /** Cooldown after a successful handshake before a new start is allowed. */
    const val HANDSHAKE_COOLDOWN_MS: Long = 60_000

    /** Handshake packets accepted per node (all connections) per 10 s. */
    const val HANDSHAKE_PACKETS_PER_NODE_PER_10S: Int = 30

    /** Invalid handshakes in the strike window before a connection is quarantined. */
    const val INVALID_STRIKES: Int = 3

    /** Strike window. */
    const val INVALID_STRIKE_WINDOW_MS: Long = 5 * 60_000

    /** Quarantine applied to a connection after too many invalid handshakes. */
    const val QUARANTINE_MS: Long = 10 * 60_000

    /** Protocol violations per connection per hour that are surfaced as a hint. */
    const val VIOLATIONS_PER_HOUR: Int = 10

    // ---- handshake lifecycle (NOISE_DESIGN §3.3, §4) ----

    /** Retransmit interval for the next handshake message. */
    const val HANDSHAKE_RETRANSMIT_MS: Long = 2_000

    /** Retransmits before the attempt expires. */
    const val HANDSHAKE_RETRIES: Int = 5

    /** A session with no traffic for this long is closed. */
    const val SESSION_IDLE_MS: Long = 15 * 60_000

    /** Silent re-handshakes allowed per hour before the UI is told (§6.1). */
    const val SILENT_REHANDSHAKES_PER_HOUR: Int = 5
}
