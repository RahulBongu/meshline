package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "groups")
data class GroupRecordEntity(
    @PrimaryKey val groupIdHex: String,
    val name: String,
    val avatarIndex: Int,
    val epoch: Int,
    val epochKey: ByteArray,
    val adminEd25519Pk: ByteArray,
    val groupTagHex: String,
    /** The roster, encoded with [com.meshline.app.protocol.GroupPayloads.encodeRoster]. */
    val roster: ByteArray,
    val unreadCount: Int,
    val joinedAtMs: Long,
    val lastActivityMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupRecordEntity) return false
        return groupIdHex == other.groupIdHex &&
            name == other.name &&
            avatarIndex == other.avatarIndex &&
            epoch == other.epoch &&
            epochKey.contentEquals(other.epochKey) &&
            adminEd25519Pk.contentEquals(other.adminEd25519Pk) &&
            groupTagHex == other.groupTagHex &&
            roster.contentEquals(other.roster) &&
            unreadCount == other.unreadCount &&
            joinedAtMs == other.joinedAtMs &&
            lastActivityMs == other.lastActivityMs
    }

    override fun hashCode(): Int {
        var h = groupIdHex.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + avatarIndex
        h = 31 * h + epoch
        h = 31 * h + epochKey.contentHashCode()
        h = 31 * h + adminEd25519Pk.contentHashCode()
        h = 31 * h + groupTagHex.hashCode()
        h = 31 * h + roster.contentHashCode()
        h = 31 * h + unreadCount
        h = 31 * h + joinedAtMs.hashCode()
        h = 31 * h + lastActivityMs.hashCode()
        return h
    }
}

@Dao
interface GroupsDao {
    @Query("SELECT * FROM groups ORDER BY lastActivityMs DESC")
    fun observeAll(): Flow<List<GroupRecordEntity>>

    @Query("SELECT * FROM groups ORDER BY lastActivityMs DESC")
    suspend fun allOnce(): List<GroupRecordEntity>

    @Query("SELECT * FROM groups WHERE groupIdHex = :groupIdHex")
    suspend fun getGroup(groupIdHex: String): GroupRecordEntity?

    @Query("SELECT * FROM groups WHERE groupTagHex = :tagHex")
    suspend fun findByTag(tagHex: String): GroupRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupRecordEntity)

    @Query("DELETE FROM groups WHERE groupIdHex = :groupIdHex")
    suspend fun delete(groupIdHex: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAll()
}

@Entity(tableName = "group_messages")
data class GroupMessageRecordEntity(
    @PrimaryKey val messageIdHex: String,
    val groupIdHex: String,
    val senderIdHex: String,
    val senderNickname: String,
    val senderAvatarIndex: Int,
    val text: String,
    val epoch: Int,
    val timestampMs: Long,
    val isSelf: Boolean,
    /** Reply-to quoting: the quoted message's id (null when not a reply). */
    val parentIdHex: String? = null,
    /** Disappearing messages: epoch millis at which the row must be dropped. */
    val expiresAtMs: Long? = null,
    /** True once the author deleted the message (MESSAGE_CONTROL). */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    /** When the author last edited the message (null = never edited). */
    val editedAtMs: Long? = null,
)

@Dao
interface GroupMessagesDao {
    @Query("SELECT * FROM group_messages WHERE groupIdHex = :groupIdHex ORDER BY timestampMs ASC")
    fun observeForGroup(groupIdHex: String): Flow<List<GroupMessageRecordEntity>>

    @Query("SELECT * FROM group_messages WHERE groupIdHex = :groupIdHex ORDER BY timestampMs ASC")
    suspend fun allForGroup(groupIdHex: String): List<GroupMessageRecordEntity>

    @Query("SELECT * FROM group_messages ORDER BY timestampMs ASC")
    suspend fun allOnce(): List<GroupMessageRecordEntity>

    @Query("SELECT * FROM group_messages WHERE messageIdHex = :hex")
    suspend fun find(hex: String): GroupMessageRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: GroupMessageRecordEntity)

    @Query("UPDATE group_messages SET deleted = 1 WHERE messageIdHex = :hex")
    suspend fun markDeleted(hex: String)

    @Query("UPDATE group_messages SET text = :text, editedAtMs = :editedAtMs WHERE messageIdHex = :hex")
    suspend fun editText(hex: String, text: String, editedAtMs: Long)

    @Query("DELETE FROM group_messages WHERE groupIdHex = :groupIdHex")
    suspend fun deleteForGroup(groupIdHex: String)

    @Query("DELETE FROM group_messages WHERE timestampMs < :thresholdMs")
    suspend fun deleteOlderThan(thresholdMs: Long)

    @Query("DELETE FROM group_messages WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM group_messages")
    suspend fun deleteAll()
}

@Entity(tableName = "channel_messages")
data class ChannelMessageRecordEntity(
    @PrimaryKey val messageIdHex: String,
    val channel: String,
    val senderIdHex: String,
    val senderNickname: String,
    val senderAvatarIndex: Int,
    val text: String,
    val timestampMs: Long,
    val isSelf: Boolean,
    /** Reply-to quoting: the quoted message's id (null when not a reply). */
    val parentIdHex: String? = null,
    /** True once the author deleted the message (MESSAGE_CONTROL). */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    /** When the author last edited the message (null = never edited). */
    val editedAtMs: Long? = null,
)

@Dao
interface ChannelMessagesDao {
    @Query("SELECT * FROM channel_messages WHERE channel = :channel ORDER BY timestampMs ASC")
    fun observeForChannel(channel: String): Flow<List<ChannelMessageRecordEntity>>

    @Query("SELECT * FROM channel_messages WHERE channel = :channel ORDER BY timestampMs ASC")
    suspend fun allForChannel(channel: String): List<ChannelMessageRecordEntity>

    @Query("SELECT * FROM channel_messages ORDER BY timestampMs ASC")
    suspend fun allOnce(): List<ChannelMessageRecordEntity>

    @Query("SELECT * FROM channel_messages WHERE messageIdHex = :hex")
    suspend fun find(hex: String): ChannelMessageRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChannelMessageRecordEntity)

    @Query("UPDATE channel_messages SET deleted = 1 WHERE messageIdHex = :hex")
    suspend fun markDeleted(hex: String)

    @Query("UPDATE channel_messages SET text = :text, editedAtMs = :editedAtMs WHERE messageIdHex = :hex")
    suspend fun editText(hex: String, text: String, editedAtMs: Long)

    @Query("DELETE FROM channel_messages WHERE channel = :channel")
    suspend fun deleteForChannel(channel: String)

    @Query("DELETE FROM channel_messages WHERE timestampMs < :thresholdMs")
    suspend fun deleteOlderThan(thresholdMs: Long)

    @Query("DELETE FROM channel_messages")
    suspend fun deleteAll()
}
