package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The sender's outbox for private messages (NOISE_DESIGN §6.1, FR-12).
 *
 * **The outbox stores PLAINTEXT**, protected by the SQLCipher database (the
 * database key is wrapped by the Android Keystore, so the rows are ciphertext
 * at rest and useless without the device key). The plaintext is encrypted
 * **at send time**, under the session that is live *then*.
 *
 * Why not store the ciphertext:
 *  - a session is not persistent (the Noise transport keys cannot be exported
 *    — amendment A2), so a stored ciphertext would belong to a session that no
 *    longer exists after a restart;
 *  - a stored ciphertext carries a nonce, and a nonce must never be reused
 *    under the same key — re-encrypting under the live session avoids that
 *    entirely;
 *  - relays never cache private ciphertext across sessions, so the sender is
 *    the only place a message can wait.
 *
 * The cost of storing plaintext is bounded by the database encryption: the
 * rows are as protected as the rest of the store, and a panic wipe erases
 * them.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The peer the message is for. */
    val peerIdHex: String,
    /** The **plaintext** message, protected by the SQLCipher database. */
    val plaintext: ByteArray,
    val createdAtMs: Long,
    /** [OutboxState.QUEUED] or [OutboxState.SENT]. */
    val state: String,
    /** How often we tried to send it. */
    val attempts: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntity) return false
        return id == other.id &&
            peerIdHex == other.peerIdHex &&
            plaintext.contentEquals(other.plaintext) &&
            createdAtMs == other.createdAtMs &&
            state == other.state &&
            attempts == other.attempts
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + peerIdHex.hashCode()
        h = 31 * h + plaintext.contentHashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + attempts
        return h
    }
}

/** The user-visible state of an outbox row (see `com.meshline.app.noise.OutboxState`). */
typealias OutboxState = com.meshline.app.noise.OutboxState

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    /** The rows still waiting for a session, oldest first. */
    @Query("SELECT * FROM outbox WHERE peerIdHex = :hex AND state = 'QUEUED' ORDER BY createdAtMs ASC")
    suspend fun queuedFor(hex: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAtMs ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("UPDATE outbox SET state = :state, attempts = :attempts WHERE id = :id")
    suspend fun updateState(id: Long, state: String, attempts: Int)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /** "Clear chat": the pending deliveries of one peer are cancelled. */
    @Query("DELETE FROM outbox WHERE peerIdHex = :hex")
    suspend fun deleteForPeer(hex: String): Int

    @Query("DELETE FROM outbox WHERE createdAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /** Panic wipe (§7). */
    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

/**
 * The outbox repository: the storage half of store-and-forward.
 *
 * The user-visible flow (NOISE_DESIGN §8):
 * ```
 * user sends  -> QUEUED  ("waiting for a secure session")
 * session up   -> encrypt under the live session -> SENT ("sent to mesh")
 * ```
 * A queued message that waits longer than [MAX_AGE_MS] is dropped (12 h, the
 * MVP value from §6.1) rather than sent as a stale surprise.
 */
class OutboxRepository(
    private val dao: OutboxDao,
    /** How long a queued message may wait before it is dropped. */
    private val maxAgeMs: Long = MAX_AGE_MS,
) : com.meshline.app.noise.Outbox {
    /** The rows waiting for a session for [peerIdHex], oldest first. */
    suspend fun queuedFor(peerIdHex: String): List<OutboxEntity> = dao.queuedFor(peerIdHex)

    /** Marks a row as handed to the router. */
    suspend fun markSent(id: Long) = dao.updateState(id, OutboxState.SENT.name, 1)

    /** Drops a row (expired, or the user cancelled it). */
    suspend fun drop(id: Long) = dao.delete(id)

    /** Drops everything that waited longer than the age limit. */
    suspend fun expireOlderThan(nowMs: Long): Int = dao.deleteOlderThan(expiryCutoff(nowMs))

    /** The cutoff below which a row is too old to send. */
    fun expiryCutoff(nowMs: Long): Long = nowMs - maxAgeMs

    /** Panic wipe (§7): the outbox goes with the identity. */
    suspend fun wipe() = dao.deleteAll()

    // ---- com.meshline.app.noise.Outbox (the private-message layer) ----

    override suspend fun queue(peerIdHex: String, envelope: ByteArray, nowMs: Long): Long =
        dao.insert(
            OutboxEntity(
                peerIdHex = peerIdHex,
                plaintext = envelope.copyOf(),
                createdAtMs = nowMs,
                state = OutboxState.QUEUED.name,
            )
        )

    override suspend fun rows(): List<com.meshline.app.noise.OutboxRow> = dao.all().map { row ->
        com.meshline.app.noise.OutboxRow(
            id = row.id,
            peerIdHex = row.peerIdHex,
            envelope = row.plaintext.copyOf(),
            createdAtMs = row.createdAtMs,
            state = runCatching { com.meshline.app.noise.OutboxState.valueOf(row.state) }
                .getOrDefault(com.meshline.app.noise.OutboxState.QUEUED),
            attempts = row.attempts,
        )
    }

    override suspend fun markAttempt(id: Long, state: com.meshline.app.noise.OutboxState, attempts: Int) {
        dao.updateState(id, state.name, attempts)
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun deleteForPeer(peerIdHex: String): Int = dao.deleteForPeer(peerIdHex)

    override suspend fun deleteOlderThan(cutoffMs: Long): Int = dao.deleteOlderThan(cutoffMs)

    companion object {
        /** 12 h — the MVP store-and-forward window (NOISE_DESIGN §6.1). */
        const val MAX_AGE_MS: Long = 12 * 60 * 60 * 1_000
    }
}
