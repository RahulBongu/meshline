package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The chat history — **both** directions, kept apart from the delivery
 * machinery:
 *
 *  - the `messages` table (this file) is the **history**: what was said, in
 *    which direction, and the state the user sees;
 *  - the `outbox` table is the **delivery queue** only (what still has to go
 *    out, and how often we tried);
 *  - the `inbox` table is gone — received messages live here too.
 *
 * The rows hold the plaintext, protected by the SQLCipher database (the
 * database key is wrapped by the Android Keystore, so the rows are ciphertext
 * at rest).
 *
 * The state is the honest one the UI shows:
 * `QUEUED → SENT → DELIVERED → READ`, or `NOT_DELIVERED` when the outbox row
 * expired without a receipt (never left as "Sent").
 */
@Entity(tableName = "messages")
data class MessageEntity(
    /** The application-level message id (hex) — the same id the receiver dedupes on. */
    @PrimaryKey val messageIdHex: String,
    val peerIdHex: String,
    /** True for a message we sent. */
    val outgoing: Boolean,
    val text: ByteArray,
    val createdAtMs: Long,
    /** QUEUED / SENT / DELIVERED / READ / NOT_DELIVERED. */
    val state: String,
    // Feature 4: media attachment (null on text-only messages).
    val media: ByteArray? = null,
    /** MIME type of [media] (null on text messages). */
    val mediaType: String? = null,
    // Feature 5: quoted parent message (null when not a reply).
    /** `messageIdHex` of the replied-to message, null when none. */
    val parentIdHex: String? = null,
    /** Disappearing messages: epoch millis at which the row must be dropped (null = keep). */
    val expiresAtMs: Long? = null,
    /** True once the author deleted the message (MESSAGE_CONTROL); shown as a tombstone. */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    /** When the author last edited the message (null = never edited). */
    val editedAtMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return messageIdHex == other.messageIdHex &&
            peerIdHex == other.peerIdHex &&
            outgoing == other.outgoing &&
            text.contentEquals(other.text) &&
            createdAtMs == other.createdAtMs &&
            state == other.state &&
            (media?.contentEquals(other.media) ?: other.media == null) &&
            mediaType == other.mediaType &&
            parentIdHex == other.parentIdHex &&
            expiresAtMs == other.expiresAtMs &&
            deleted == other.deleted &&
            editedAtMs == other.editedAtMs
    }

    override fun hashCode(): Int {
        var h = messageIdHex.hashCode()
        h = 31 * h + peerIdHex.hashCode()
        h = 31 * h + outgoing.hashCode()
        h = 31 * h + text.contentHashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + (media?.contentHashCode() ?: 0)
        h = 31 * h + (mediaType?.hashCode() ?: 0)
        h = 31 * h + (parentIdHex?.hashCode() ?: 0)
        h = 31 * h + (expiresAtMs?.hashCode() ?: 0)
        h = 31 * h + deleted.hashCode()
        h = 31 * h + (editedAtMs?.hashCode() ?: 0)
        return h
    }
}

@Dao
interface MessagesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY createdAtMs ASC")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageIdHex = :hex")
    suspend fun find(hex: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE peerIdHex = :hex ORDER BY createdAtMs ASC")
    suspend fun forPeer(hex: String): List<MessageEntity>

    @Query("UPDATE messages SET state = :state WHERE messageIdHex = :hex")
    suspend fun setState(hex: String, state: String)

    /** MESSAGE_CONTROL delete: tombstone the row (author-checked by the caller). */
    @Query("UPDATE messages SET deleted = 1 WHERE messageIdHex = :hex")
    suspend fun markDeleted(hex: String)

    /** MESSAGE_CONTROL edit: replace the body and stamp the edit time. */
    @Query("UPDATE messages SET text = :text, editedAtMs = :editedAtMs WHERE messageIdHex = :hex")
    suspend fun editText(hex: String, text: ByteArray, editedAtMs: Long)

    /** "Clear chat": the history of one peer goes. */
    @Query("DELETE FROM messages WHERE peerIdHex = :hex")
    suspend fun clearPeer(hex: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** The periodic purge: everything older than the cutoff goes. */
    @Query("DELETE FROM messages WHERE createdAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /** Disappearing messages: drop rows whose expiry has passed. */
    @Query("DELETE FROM messages WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int
}

/** The chat history, as the D2 layer sees it (JVM-testable). */
interface MessageStore {
    suspend fun upsert(entity: MessageEntity)
    suspend fun all(): List<MessageEntity>
    suspend fun find(messageIdHex: String): MessageEntity?
    suspend fun forPeer(peerIdHex: String): List<MessageEntity>
    suspend fun setState(messageIdHex: String, state: String)
    suspend fun markDeleted(messageIdHex: String)
    suspend fun editText(messageIdHex: String, text: ByteArray, editedAtMs: Long)
    suspend fun clearPeer(peerIdHex: String)
    suspend fun deleteAll()
    suspend fun deleteOlderThan(cutoffMs: Long): Int
    suspend fun deleteExpired(nowMs: Long): Int
}

/** The Room/SQLCipher implementation. */
class RoomMessageStore(private val dao: MessagesDao) : MessageStore {
    override suspend fun upsert(entity: MessageEntity) = dao.upsert(entity)
    override suspend fun all(): List<MessageEntity> = dao.all()
    override suspend fun find(messageIdHex: String): MessageEntity? = dao.find(messageIdHex)
    override suspend fun forPeer(peerIdHex: String): List<MessageEntity> = dao.forPeer(peerIdHex)
    override suspend fun setState(messageIdHex: String, state: String) = dao.setState(messageIdHex, state)
    override suspend fun markDeleted(messageIdHex: String) = dao.markDeleted(messageIdHex)
    override suspend fun editText(messageIdHex: String, text: ByteArray, editedAtMs: Long) =
        dao.editText(messageIdHex, text, editedAtMs)
    override suspend fun clearPeer(peerIdHex: String) = dao.clearPeer(peerIdHex)
    override suspend fun deleteAll() = dao.deleteAll()
    override suspend fun deleteOlderThan(cutoffMs: Long): Int = dao.deleteOlderThan(cutoffMs)
    override suspend fun deleteExpired(nowMs: Long): Int = dao.deleteExpired(nowMs)
}
