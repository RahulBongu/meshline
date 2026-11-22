package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Message reactions (Feature 1).
 *
 * One row per (target public message, reacting peer, emoji): the UI renders
 * `count` = number of distinct senders who reacted with that emoji, and the
 * user can toggle (ADD/REMOVE) their own reaction. All rows live in the
 * encrypted SQLCipher DB; rows are scoped by the 32-byte message id of the
 * targeted public broadcast message.
 */
@Entity(
    tableName = "message_reactions",
    primaryKeys = ["targetIdHex", "senderIdHex", "emoji"],
)
data class ReactionEntity(
    val targetIdHex: String,
    val senderIdHex: String,
    val emoji: String,
    val createdAtMs: Long,
)

@Dao
interface ReactionsDao {
    /** Ignored when this sender already reacted with this emoji (toggle case). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReaction(reaction: ReactionEntity)

    @Query("DELETE FROM message_reactions WHERE targetIdHex = :target AND senderIdHex = :sender AND emoji = :emoji")
    suspend fun deleteReaction(target: String, sender: String, emoji: String)

    @Query(
        "SELECT 1 FROM message_reactions WHERE targetIdHex = :target AND senderIdHex = :sender AND emoji = :emoji LIMIT 1"
    )
    suspend fun hasReaction(target: String, sender: String, emoji: String): Boolean

    @Query("SELECT * FROM message_reactions ORDER BY createdAtMs ASC")
    suspend fun allOnce(): List<ReactionEntity>

    @Query("DELETE FROM message_reactions")
    suspend fun deleteAll()
}
