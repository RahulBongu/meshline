package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The **inbox**: the private messages we received.
 *
 * The rows hold the plaintext, protected by the SQLCipher database (the
 * database key is wrapped by the Android Keystore, so the rows are ciphertext
 * at rest). The `messageIdHex` is the application-level id from
 * NOISE_DESIGN §6.1 — it is the primary key, so the same message can never
 * be stored twice (the receiver's dedupe already refuses to show it twice).
 *
 * The `read` flag is what the sender's READ receipt is based on.
 */
@Entity(tableName = "inbox")
data class InboxEntity(
    /** The application-level message id (hex). */
    @PrimaryKey val messageIdHex: String,
    val peerIdHex: String,
    /** The plaintext message, protected by SQLCipher. */
    val text: ByteArray,
    val createdAtMs: Long,
    /** True once the user opened the chat and the READ receipt went out. */
    val read: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxEntity) return false
        return messageIdHex == other.messageIdHex &&
            peerIdHex == other.peerIdHex &&
            text.contentEquals(other.text) &&
            createdAtMs == other.createdAtMs &&
            read == other.read
    }

    override fun hashCode(): Int {
        var h = messageIdHex.hashCode()
        h = 31 * h + peerIdHex.hashCode()
        h = 31 * h + text.contentHashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + read.hashCode()
        return h
    }
}

@Dao
interface InboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: InboxEntity): Long

    @Query("SELECT * FROM inbox ORDER BY createdAtMs ASC")
    suspend fun all(): List<InboxEntity>

    @Query("SELECT * FROM inbox WHERE peerIdHex = :hex ORDER BY createdAtMs ASC")
    suspend fun forPeer(hex: String): List<InboxEntity>

    @Query("SELECT * FROM inbox WHERE messageIdHex = :hex")
    suspend fun find(hex: String): InboxEntity?

    @Query("UPDATE inbox SET read = 1 WHERE messageIdHex = :hex")
    suspend fun markRead(hex: String)

    @Query("DELETE FROM inbox")
    suspend fun deleteAll()
}
