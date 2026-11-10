package com.meshline.app.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Contacts DB (D1): pinned public keys in the encrypted DB.
 * A later Noise handshake whose keys differ triggers the key-change warning
 * from NOISE_DESIGN.md §7.2 (hooked in D2; the pinned row is what we compare
 * against). Private keys are NEVER stored here.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val senderIdHex: String,
    val nickname: String,
    val avatarIndex: Int,
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val fingerprint: String,
    val verified: Boolean,
    val lastSeenMs: Long,
    val blocked: Boolean = false,
)

@Dao
interface ContactsDao {
    @Query("SELECT * FROM contacts ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY lastSeenMs DESC")
    suspend fun allOnce(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE senderIdHex = :hex")
    suspend fun find(hex: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET blocked = :blocked WHERE senderIdHex = :hex")
    suspend fun setBlocked(hex: String, blocked: Boolean)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

@Database(entities = [ContactEntity::class], version = 1, exportSchema = false)
abstract class MeshlineDatabase : RoomDatabase() {
    abstract fun contactsDao(): ContactsDao
}
