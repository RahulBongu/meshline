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
        h = 31 * h + joinedAtMs.hashCode()
        h = 31 * h + lastActivityMs.hashCode()
        return h
    }
}

@Dao
interface GroupsDao {
    @Query("SELECT * FROM groups ORDER BY lastActivityMs DESC")
    fun observeAll(): Flow<List<GroupRecordEntity>>

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
    val text: String,
    val epoch: Int,
    val timestampMs: Long,
    val isSelf: Boolean,
)

@Dao
interface GroupMessagesDao {
    @Query("SELECT * FROM group_messages WHERE groupIdHex = :groupIdHex ORDER BY timestampMs ASC")
    fun observeForGroup(groupIdHex: String): Flow<List<GroupMessageRecordEntity>>

    @Query("SELECT * FROM group_messages WHERE groupIdHex = :groupIdHex ORDER BY timestampMs ASC")
    suspend fun allForGroup(groupIdHex: String): List<GroupMessageRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: GroupMessageRecordEntity)

    @Query("DELETE FROM group_messages WHERE groupIdHex = :groupIdHex")
    suspend fun deleteForGroup(groupIdHex: String)

    @Query("DELETE FROM group_messages")
    suspend fun deleteAll()
}

@Entity(tableName = "channel_messages")
data class ChannelMessageRecordEntity(
    @PrimaryKey val messageIdHex: String,
    val channel: String,
    val senderIdHex: String,
    val senderNickname: String,
    val text: String,
    val timestampMs: Long,
    val isSelf: Boolean,
)

@Dao
interface ChannelMessagesDao {
    @Query("SELECT * FROM channel_messages WHERE channel = :channel ORDER BY timestampMs ASC")
    fun observeForChannel(channel: String): Flow<List<ChannelMessageRecordEntity>>

    @Query("SELECT * FROM channel_messages WHERE channel = :channel ORDER BY timestampMs ASC")
    suspend fun allForChannel(channel: String): List<ChannelMessageRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChannelMessageRecordEntity)

    @Query("DELETE FROM channel_messages WHERE channel = :channel")
    suspend fun deleteForChannel(channel: String)

    @Query("DELETE FROM channel_messages")
    suspend fun deleteAll()
}
