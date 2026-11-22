package com.meshline.app.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Room migration chain, v1 → v6 (PROTOCOL.md §7).
 *
 * The schemas of every version are exported to `app/schemas/` (the KSP
 * `room.schemaLocation` argument) and shipped as androidTest assets, so this
 * test builds a **real v1 database** and walks it up to the current version
 * with the production migrations.
 *
 * What must survive:
 *  - the **contacts** (they are the user's relationships — losing them on an
 *    app update would be unforgivable);
 *  - the **received messages** (v5's `inbox` rows are carried into the v6
 *    `messages` history).
 *
 * Instrumented test — needs a device or emulator:
 * `./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MeshlineDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateFromV1ToV6KeepsTheContacts() {
        // A v1 database with one contact (the v1 schema has only `contacts`).
        val v1 = helper.createDatabase(TEST_DB, 1)
        v1.execSQL(
            """
            INSERT INTO `contacts`
                (`senderIdHex`, `nickname`, `avatarIndex`, `ed25519PublicKey`,
                 `x25519PublicKey`, `fingerprint`, `verified`, `lastSeenMs`, `blocked`)
            VALUES ('aabbccdd', 'bob', 3, X'0102', X'0304', 'fp-1234', 1, 1700, 0)
            """.trimIndent()
        )
        v1.close()

        // Walk it up: 1 -> 2 -> 3 -> 4 -> 5 -> 6.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 6, false,
            MeshlineDatabase.MIGRATION_1_2,
            MeshlineDatabase.MIGRATION_2_3,
            MeshlineDatabase.MIGRATION_3_4,
            MeshlineDatabase.MIGRATION_4_5,
            MeshlineDatabase.MIGRATION_5_6,
        )
        try {
            migrated.query("SELECT * FROM `contacts`").use { c ->
                assertTrue("the contact must survive the migration", c.moveToFirst())
                assertEquals("aabbccdd", c.getString(c.getColumnIndexOrThrow("senderIdHex")))
                assertEquals("bob", c.getString(c.getColumnIndexOrThrow("nickname")))
                assertEquals(3, c.getInt(c.getColumnIndexOrThrow("avatarIndex")))
                assertEquals("fp-1234", c.getString(c.getColumnIndexOrThrow("fingerprint")))
                assertEquals(1, c.getInt(c.getColumnIndexOrThrow("verified")))
                assertEquals(1700, c.getInt(c.getColumnIndexOrThrow("lastSeenMs")))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("blocked")))
                assertEquals("there must be exactly one contact", 1, c.count)
            }
            // The new tables exist and are empty.
            migrated.query("SELECT COUNT(*) FROM `messages`").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM `outbox`").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM `seen_message_ids`").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM `noise_sessions`").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromV5ToV6MovesTheReceivedMessagesIntoTheHistory() {
        // A v5 database: the contacts plus one received message in `inbox`.
        val v5 = helper.createDatabase(TEST_DB, 5)
        v5.execSQL(
            """
            INSERT INTO `contacts`
                (`senderIdHex`, `nickname`, `avatarIndex`, `ed25519PublicKey`,
                 `x25519PublicKey`, `fingerprint`, `verified`, `lastSeenMs`, `blocked`)
            VALUES ('11223344', 'carol', 1, X'0102', X'0304', 'fp-carol', 1, 1700, 0)
            """.trimIndent()
        )
        v5.execSQL(
            """
            INSERT INTO `inbox` (`messageIdHex`, `peerIdHex`, `text`, `createdAtMs`, `read`)
            VALUES ('msg-1', '11223344', X'68656c6c6f', 1700, 1)
            """.trimIndent()
        )
        v5.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 6, false,
            MeshlineDatabase.MIGRATION_5_6,
        )
        try {
            // The received message is now part of the chat history.
            migrated.query("SELECT * FROM `messages`").use { c ->
                assertTrue("the received message must survive", c.moveToFirst())
                assertEquals("msg-1", c.getString(c.getColumnIndexOrThrow("messageIdHex")))
                assertEquals("11223344", c.getString(c.getColumnIndexOrThrow("peerIdHex")))
                assertEquals("hello", String(c.getBlob(c.getColumnIndexOrThrow("text"))))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("outgoing")))
                assertEquals("READ", c.getString(c.getColumnIndexOrThrow("state")))
                assertEquals("there must be exactly one message", 1, c.count)
            }
            // The contact is untouched.
            migrated.query("SELECT COUNT(*) FROM `contacts`").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // The old table is gone.
            migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='inbox'").use { c ->
                assertTrue("the inbox table must be gone", !c.moveToFirst())
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromV7ToV8CreatesTheReactionTable() {
        // A v7 database (the pre-Feature-1 schema).
        val v7 = helper.createDatabase(TEST_DB, 7)
        v7.close()

        // Walk it up: 7 -> 8.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, false, MeshlineDatabase.MIGRATION_7_8)
        try {
            // The new table exists and is empty.
            migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='message_reactions'").use { c ->
                assertTrue("message_reactions must exist after v7->v8", c.moveToFirst())
            }
            // The pre-existing tables survive the migration.
            migrated.query("SELECT COUNT(*) FROM `contacts`").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM `groups`").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromV8ToV9AddsMediaColumns() {
        val v8 = helper.createDatabase(TEST_DB, 8)
        v8.execSQL(
            """
            INSERT INTO `messages`
                (`messageIdHex`, `peerIdHex`, `outgoing`, `text`, `createdAtMs`, `state`)
            VALUES ('m1', 'peer1', 0, X'68656c6c6f', 1700000000, 'DELIVERED')
            """.trimIndent()
        )
        v8.close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, false, MeshlineDatabase.MIGRATION_8_9)
        try {
            // The migrated history survives.
            migrated.query("SELECT COUNT(*) FROM `messages`").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // The new columns exist, are nullable, and are usable on old rows.
            migrated.query(
                "SELECT `media`, `mediaType`, `parentIdHex` FROM `messages` WHERE `messageIdHex` = 'm1'"
            ).use { c ->
                assertTrue("the row must be found", c.moveToFirst())
                assertTrue(c.isNull(c.getColumnIndexOrThrow("media")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("mediaType")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("parentIdHex")))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateFromV9ToV10RecreatesGroupTables() {
        val v9 = helper.createDatabase(TEST_DB, 9)
        // Before v10 the group/channel tables existed but were never written, so
        // the migration is allowed to recreate them from scratch. Seed one row
        // into `messages` to prove unrelated data survives the table rebuilds.
        v9.execSQL(
            """
            INSERT INTO `messages`
                (`messageIdHex`, `peerIdHex`, `outgoing`, `text`, `createdAtMs`, `state`)
            VALUES ('m1', 'peer1', 0, X'68656c6c6f', 1700000000, 'DELIVERED')
            """.trimIndent()
        )
        v9.close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 10, false, MeshlineDatabase.MIGRATION_9_10)
        try {
            migrated.query("SELECT COUNT(*) FROM `messages`").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // The group tables exist again, now with their v10 columns.
            for (table in listOf("groups", "group_messages", "channel_messages")) {
                migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
                    assertTrue("$table must exist after v9->v10", c.moveToFirst())
                }
            }
            migrated.query("SELECT `roster`, `unreadCount` FROM `groups`").use { /* schema exists */ }
            migrated.query("SELECT `senderAvatarIndex` FROM `group_messages`").use { /* schema exists */ }
            migrated.query("SELECT `senderAvatarIndex` FROM `channel_messages`").use { /* schema exists */ }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
