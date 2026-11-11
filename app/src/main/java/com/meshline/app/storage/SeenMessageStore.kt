package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.meshline.app.noise.MessageDedupe

/**
 * The receiver's record of the application-level message ids it has shown.
 *
 * The rows live in the SQLCipher-encrypted database, so they are ciphertext at
 * rest like everything else. The retention (**30 days**) is deliberately
 * longer than the sender's outbox expiry (**12 h**): a retry that arrives
 * late must still be recognised, acknowledged, and not shown twice.
 */
@Entity(tableName = "seen_message_ids")
data class SeenMessageEntity(
    /** The application-level message id (hex). */
    @PrimaryKey val messageIdHex: String,
    val peerIdHex: String,
    val seenAtMs: Long,
)

@Dao
interface SeenMessageDao {
    /**
     * Inserts the id and ignores the row when it is already there. The
     * returned row id is **-1** when the row already existed, which is how the
     * caller learns that the message is a repeat.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SeenMessageEntity): Long

    @Query("SELECT * FROM seen_message_ids WHERE messageIdHex = :hex")
    suspend fun find(hex: String): SeenMessageEntity?

    @Query("DELETE FROM seen_message_ids WHERE seenAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /** Panic wipe (§7). */
    @Query("DELETE FROM seen_message_ids")
    suspend fun deleteAll()
}

/**
 * The SQLCipher-backed [MessageDedupe].
 *
 * The insert is atomic (`OnConflictStrategy.IGNORE`): two copies of the same
 * message that arrive at the same time cannot both be "new".
 */
class SeenMessageStore(
    private val dao: SeenMessageDao,
    private val retentionMs: Long = com.meshline.app.noise.MESSAGE_DEDUPE_RETENTION_MS,
) : MessageDedupe {

    override suspend fun isNew(peerIdHex: String, messageIdHex: String, nowMs: Long): Boolean {
        val rowId = dao.insert(
            SeenMessageEntity(
                messageIdHex = messageIdHex,
                peerIdHex = peerIdHex,
                seenAtMs = nowMs,
            )
        )
        return rowId != -1L
    }

    override suspend fun deleteOlderThan(cutoffMs: Long): Int = dao.deleteOlderThan(cutoffMs)

    /** Drops the ids that are older than the retention window. */
    suspend fun expire(nowMs: Long): Int = dao.deleteOlderThan(nowMs - retentionMs)

    /** Panic wipe (§7): the record goes with the identity. */
    suspend fun wipe() = dao.deleteAll()
}
