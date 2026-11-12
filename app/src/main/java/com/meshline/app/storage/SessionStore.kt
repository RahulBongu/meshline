package com.meshline.app.storage

/**
 * The session store (NOISE_DESIGN §6.1, PROTOCOL.md §7).
 *
 * It is the only place that writes the `noise_sessions` table, and it makes
 * one guarantee explicit:
 *
 * **Nothing is ever written to `noise_sessions.transportKey`.**
 *
 * `noise-java` 0.1.1 cannot export the Noise transport key material (there
 * is no key getter on `CipherState` and `SymmetricState` is package-private),
 * so there is nothing meaningful to store — the column would only hold key
 * material the app cannot use, and it would be a second secret to erase in a
 * panic wipe. The mapping below therefore **always writes NULL**, even when
 * the snapshot handed in carries a key. The column exists only so a future
 * library with an export path can fill it in without a schema change.
 */
class SessionStore(private val dao: NoiseSessionDao) : SessionDirectory {

    /** Stores (or replaces) a session. */
    suspend fun save(snapshot: SessionSnapshot) {
        dao.upsert(snapshot.toEntity())
    }

    /** Loads a session, or null when we have none for that peer. */
    suspend fun load(peerIdHex: String): SessionSnapshot? = dao.find(peerIdHex)?.toSnapshot()

    /** Every stored session, most recently used first. */
    suspend fun all(): List<SessionSnapshot> = dao.all().map { it.toSnapshot() }

    /** Forgets one session (peer blocked, key changed, ...). */
    suspend fun delete(peerIdHex: String) = dao.delete(peerIdHex)

    /** Panic wipe (§7): every session goes. */
    override suspend fun wipe() {
        dao.deleteAll()
    }

    companion object {
        /**
         * Maps a snapshot to a row. The transport key is dropped on purpose —
         * see the class documentation.
         */
        fun SessionSnapshot.toEntity(): NoiseSessionEntity = NoiseSessionEntity(
            peerIdHex = peerIdHex,
            ed25519PublicKey = ed25519PublicKey.copyOf(),
            x25519PublicKey = x25519PublicKey.copyOf(),
            fingerprint = fingerprint,
            state = state,
            sendNonce = sendNonce,
            recvNonce = recvNonce,
            replayWindow = replayWindow.copyOf(),
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            // Deliberately NULL: the transport key cannot be exported, so it is
            // never written. See the class documentation.
            transportKey = null,
            verified = verified,
        )

        /** Maps a row back to a snapshot. */
        fun NoiseSessionEntity.toSnapshot(): SessionSnapshot = SessionSnapshot(
            peerIdHex = peerIdHex,
            ed25519PublicKey = ed25519PublicKey.copyOf(),
            x25519PublicKey = x25519PublicKey.copyOf(),
            fingerprint = fingerprint,
            state = state,
            sendNonce = sendNonce,
            recvNonce = recvNonce,
            replayWindow = replayWindow.copyOf(),
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            // The column is always NULL (see toEntity), but read it as-is so a
            // row written by an older build can never be mistaken for usable
            // key material.
            transportKey = transportKey,
            verified = verified,
        )
    }
}
