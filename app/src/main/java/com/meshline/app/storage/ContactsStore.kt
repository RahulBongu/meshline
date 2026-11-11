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

@Database(
    entities = [
        ContactEntity::class,
        NoiseSessionEntity::class,
        OutboxEntity::class,
        SeenMessageEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MeshlineDatabase : RoomDatabase() {
    abstract fun contactsDao(): ContactsDao
    abstract fun noiseSessionDao(): NoiseSessionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun seenMessageDao(): SeenMessageDao

    companion object {
        /**
         * v1 → v2 adds the `noise_sessions` table (D2). It is a plain
         * `CREATE TABLE`, so the contacts survive the upgrade — the fallback
         * destructive migration would have thrown them away.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `noise_sessions` (
                        `peerIdHex` TEXT NOT NULL,
                        `ed25519PublicKey` BLOB NOT NULL,
                        `x25519PublicKey` BLOB NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `sendNonce` INTEGER NOT NULL,
                        `recvNonce` INTEGER NOT NULL,
                        `replayWindow` BLOB NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `lastUsedAtMs` INTEGER NOT NULL,
                        `messagesSent` INTEGER NOT NULL,
                        `messagesReceived` INTEGER NOT NULL,
                        `transportKey` BLOB,
                        `verified` INTEGER NOT NULL,
                        PRIMARY KEY(`peerIdHex`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 → v3 adds the `outbox` table (D2 store-and-forward). The rows
         * hold **plaintext** protected by SQLCipher, never ciphertext.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `outbox` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `peerIdHex` TEXT NOT NULL,
                        `plaintext` BLOB NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `attempts` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        /**
         * v3 → v4 adds the `seen_message_ids` table: the receiver's record of
         * the application-level message ids it has shown. The retention (30
         * days) is longer than the sender's outbox expiry (12 h), so a late
         * retry is acknowledged but not shown twice.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `seen_message_ids` (
                        `messageIdHex` TEXT NOT NULL,
                        `peerIdHex` TEXT NOT NULL,
                        `seenAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
