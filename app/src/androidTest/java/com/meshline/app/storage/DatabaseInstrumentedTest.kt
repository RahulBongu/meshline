package com.meshline.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented SQLCipher test (device/emulator required).
 *
 * Proves the real `net.zetetic:sqlcipher-android` path:
 *  - a DB created with a passphrase reopens with the same passphrase,
 *  - rows persist across close/reopen,
 *  - a wrong passphrase fails to read (never silently returns empty data).
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val passphrase = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun clean() = deleteDb()

    @After
    fun cleanup() = deleteDb()

    private fun deleteDb() {
        listOf("meshline.db", "meshline.db-wal", "meshline.db-shm").forEach {
            File(ctx.getDatabasePath(it).absolutePath).delete()
        }
    }

    private fun contact(hex: String) = ContactEntity(
        senderIdHex = hex,
        nickname = "alice",
        avatarIndex = 3,
        ed25519PublicKey = ByteArray(32) { 0x11 },
        x25519PublicKey = ByteArray(32) { 0x22 },
        fingerprint = "ab".repeat(32),
        verified = true,
        lastSeenMs = 1_700_000_000_000L,
    )

    @Test
    fun `right passphrase reopens, wrong passphrase fails`() = runBlocking {
        val db = DatabaseFactory.create(ctx, passphrase)
        assertNull(db.contactsDao().find("aa"))
        db.contactsDao().upsert(contact("aa"))
        assertEquals("alice", db.contactsDao().find("aa")?.nickname)
        db.close()

        val reopened = DatabaseFactory.create(ctx, passphrase)
        assertEquals("alice", reopened.contactsDao().find("aa")?.nickname)
        reopened.close()

        // A different 32-byte key must not open the same file.
        val wrong = DatabaseFactory.create(ctx, ByteArray(32) { 0x7F })
        val result = runCatching { runBlocking { wrong.contactsDao().find("aa") } }
        assertTrue("wrong passphrase must fail, got $result", result.isFailure)
        runCatching { wrong.close() }
    }
}
