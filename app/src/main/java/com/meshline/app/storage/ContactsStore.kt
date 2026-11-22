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
        MessageEntity::class,
        GroupRecordEntity::class,
        GroupMessageRecordEntity::class,
        ChannelMessageRecordEntity::class,
        ReactionEntity::class,
    ],
    version = 10,
    // The schema of every version is exported to app/schemas/ (see the KSP
    // arguments in build.gradle.kts) so the instrumented migration test can
    // build a v1 database and walk it up to the current version.
    exportSchema = true,
)
abstract class MeshlineDatabase : RoomDatabase() {
    abstract fun contactsDao(): ContactsDao
    abstract fun noiseSessionDao(): NoiseSessionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun messagesDao(): MessagesDao
    abstract fun groupsDao(): GroupsDao
    abstract fun groupMessagesDao(): GroupMessagesDao
    abstract fun channelMessagesDao(): ChannelMessagesDao
    abstract fun reactionsDao(): ReactionsDao

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
        /**
         * v4 → v5 adds the `inbox` table: the private messages we received
         * (plaintext, protected by SQLCipher). It is folded into `messages` by
         * MIGRATION_5_6.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `inbox` (
                        `messageIdHex` TEXT NOT NULL,
                        `peerIdHex` TEXT NOT NULL,
                        `text` BLOB NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `read` INTEGER NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v5 → v6 replaces the `inbox` table with the `messages` table: the
         * chat history now holds **both** directions, while the `outbox` keeps
         * the delivery queue. The migration copies the received messages into
         * the new table before dropping the old one, so nothing is lost.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `messageIdHex` TEXT NOT NULL,
                        `peerIdHex` TEXT NOT NULL,
                        `outgoing` INTEGER NOT NULL,
                        `text` BLOB NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
                // Carry the received messages over (a received message that was
                // read stays "read").
                runCatching {
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `messages`
                            (`messageIdHex`, `peerIdHex`, `outgoing`, `text`, `createdAtMs`, `state`)
                        SELECT `messageIdHex`, `peerIdHex`, 0, `text`, `createdAtMs`,
                               CASE WHEN `read` = 1 THEN 'READ' ELSE 'DELIVERED' END
                        FROM `inbox`
                        """.trimIndent()
                    )
                }
                db.execSQL("DROP TABLE IF EXISTS `inbox`")
            }
        }

        /**
         * v6 → v7 adds `groups`, `group_messages`, and `channel_messages` tables (Phase E & Channels).
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `groupIdHex` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `avatarIndex` INTEGER NOT NULL,
                        `epoch` INTEGER NOT NULL,
                        `epochKey` BLOB NOT NULL,
                        `adminEd25519Pk` BLOB NOT NULL,
                        `groupTagHex` TEXT NOT NULL,
                        `joinedAtMs` INTEGER NOT NULL,
                        `lastActivityMs` INTEGER NOT NULL,
                        PRIMARY KEY(`groupIdHex`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_messages` (
                        `messageIdHex` TEXT NOT NULL,
                        `groupIdHex` TEXT NOT NULL,
                        `senderIdHex` TEXT NOT NULL,
                        `senderNickname` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `epoch` INTEGER NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `isSelf` INTEGER NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `channel_messages` (
                        `messageIdHex` TEXT NOT NULL,
                        `channel` TEXT NOT NULL,
                        `senderIdHex` TEXT NOT NULL,
                        `senderNickname` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `isSelf` INTEGER NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v7 → v8 adds `message_reactions` (Feature 1: emoji reactions on
         * public broadcasts). Plain CREATE TABLE: contacts, messages and
         * groups survive untouched.
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_reactions` (
                        `targetIdHex` TEXT NOT NULL,
                        `senderIdHex` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`targetIdHex`, `senderIdHex`, `emoji`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v8 → v9 adds three nullable `messages` columns: `media` (Feature 4),
         * `mediaType` (Feature 4) and `parentIdHex` (Feature 5). All nullable
         * — text messages keep working unchanged; nothing is destroyed.
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `media` BLOB")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `mediaType` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `parentIdHex` TEXT")
            }
        }

        /**
         * v9 → v10 turns the group/channel tables into live storage.
         *
         * Before v10 nothing ever wrote to `groups`, `group_messages` or
         * `channel_messages` — the entities existed but the managers were
         * purely in-memory, so a restart lost every group (including its
         * epoch key) and all group/channel history. The tables are therefore
         * guaranteed empty, and recreating them is both safe and exact:
         *
         *  - `groups` gains `roster` (the membership list is what authorises a
         *    sender and identifies the admin) and `unreadCount`;
         *  - `group_messages` and `channel_messages` gain `senderAvatarIndex`
         *    so restored history renders with the right avatar.
         */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `groups`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `groupIdHex` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `avatarIndex` INTEGER NOT NULL,
                        `epoch` INTEGER NOT NULL,
                        `epochKey` BLOB NOT NULL,
                        `adminEd25519Pk` BLOB NOT NULL,
                        `groupTagHex` TEXT NOT NULL,
                        `roster` BLOB NOT NULL,
                        `unreadCount` INTEGER NOT NULL,
                        `joinedAtMs` INTEGER NOT NULL,
                        `lastActivityMs` INTEGER NOT NULL,
                        PRIMARY KEY(`groupIdHex`)
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS `group_messages`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_messages` (
                        `messageIdHex` TEXT NOT NULL,
                        `groupIdHex` TEXT NOT NULL,
                        `senderIdHex` TEXT NOT NULL,
                        `senderNickname` TEXT NOT NULL,
                        `senderAvatarIndex` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `epoch` INTEGER NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `isSelf` INTEGER NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS `channel_messages`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `channel_messages` (
                        `messageIdHex` TEXT NOT NULL,
                        `channel` TEXT NOT NULL,
                        `senderIdHex` TEXT NOT NULL,
                        `senderNickname` TEXT NOT NULL,
                        `senderAvatarIndex` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `isSelf` INTEGER NOT NULL,
                        PRIMARY KEY(`messageIdHex`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

