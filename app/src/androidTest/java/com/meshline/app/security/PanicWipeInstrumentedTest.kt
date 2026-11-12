package com.meshline.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshline.app.profile.ProfileStore
import com.meshline.app.service.PanicWiper
import com.meshline.app.storage.ContactsRepository
import com.meshline.app.storage.DatabaseFactory
import com.meshline.app.storage.MessageEntity
import com.meshline.app.storage.MessageRetention
import com.meshline.app.storage.OutboxState
import com.meshline.app.storage.RetentionStore
import com.meshline.app.storage.SessionStore
import com.meshline.app.storage.SeenMessageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The panic wipe, end to end on a real device (PROTOCOL §7, NOISE_DESIGN §7).
 *
 * It proves that one wipe leaves **nothing** behind: the identity and its
 * Keystore alias, the contacts, the Noise sessions, the outbox, the chat
 * history, the receiver's seen ids, the preferences and the database file.
 *
 * Instrumented test — needs a device or emulator:
 * `./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class PanicWipeInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean device state.
        runBlocking {
            val store = AndroidKeystoreIdentityStore(context)
            store.wipe()
            DbPassphrase.wipe(context)
            ProfileStore(context).wipe()
            RetentionStore(context).wipe()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            AndroidKeystoreIdentityStore(context).wipe()
            DbPassphrase.wipe(context)
        }
    }

    private fun keystoreHas(alias: String): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.containsAlias(alias)
    }

    @Test
    fun `the panic wipe removes everything`() = runBlocking {
        val identityStore = AndroidKeystoreIdentityStore(context)
        val profileStore = ProfileStore(context)
        val retentionStore = RetentionStore(context)
        val contacts = ContactsRepository(context)

        // 1. An identity, a profile, a setting.
        val identity = AndroidKeystoreIdentityStore.generateAndStore(context)
        profileStore.save(com.meshline.app.profile.Profile("bob", 3))
        retentionStore.save(MessageRetention.SEVEN_DAYS)
        assertTrue("the identity must exist", identityStore.load() != null)
        assertTrue("the Keystore alias must exist", keystoreHas("meshline-key-wrap"))

        // 2. A contact, a session, an outbox row, a message, a seen id.
        val db = DatabaseFactory.create(context)
        val contactDao = db.contactsDao()
        contactDao.upsert(
            com.meshline.app.storage.ContactEntity(
                senderIdHex = identity.senderId.joinToString("") { "%02x".format(it) },
                nickname = "bob",
                avatarIndex = 0,
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
                fingerprint = "fp",
                verified = true,
                lastSeenMs = 0L,
            )
        )
        SessionStore(db.noiseSessionDao()).save(
            com.meshline.app.storage.SessionSnapshot(
                peerIdHex = "peer",
                ed25519PublicKey = ByteArray(32),
                x25519PublicKey = ByteArray(32),
                fingerprint = "fp",
                state = "ESTABLISHED",
                sendNonce = 0,
                recvNonce = 0,
                replayWindow = ByteArray(24),
                createdAtMs = 1,
                lastUsedAtMs = 2,
                messagesSent = 0,
                messagesReceived = 0,
                transportKey = null,
                verified = true,
            )
        )
        db.outboxDao().insert(
            com.meshline.app.storage.OutboxEntity(
                peerIdHex = "peer",
                plaintext = "secret".toByteArray(),
                createdAtMs = 1L,
                state = OutboxState.QUEUED.name,
            )
        )
        db.messagesDao().upsert(
            MessageEntity(
                messageIdHex = "m1",
                peerIdHex = "peer",
                outgoing = true,
                text = "secret".toByteArray(),
                createdAtMs = 1L,
                state = OutboxState.QUEUED.name,
            )
        )
        SeenMessageStore(db.seenMessageDao()).isNew("peer", "m2", 1L)

        // Everything is really there.
        assertTrue("the contact must exist", contactDao.allOnce().isNotEmpty())
        assertTrue("the session must exist", db.noiseSessionDao().find("peer") != null)
        assertTrue("the outbox row must exist", db.outboxDao().all().isNotEmpty())
        assertTrue("the message must exist", db.messagesDao().all().isNotEmpty())
        assertTrue("the seen id must exist", db.seenMessageDao().find("m2") != null)
        val dbFile = context.getDatabasePath("meshline.db")
        assertTrue("the database file must exist", File(dbFile.absolutePath).exists() || dbFile.parentFile?.listFiles()?.any { it.name.startsWith("meshline.db") } == true)
        db.close()

        // 3. The wipe.
        PanicWiper(
            context = context,
            identityStore = identityStore,
            contacts = contacts,
            profileStore = profileStore,
            retentionStore = retentionStore,
        ).wipe(channel = null)

        // 4. Nothing is left.
        assertNull("the identity must be gone", identityStore.load())
        assertFalse("the identity Keystore alias must be gone", keystoreHas("meshline-key-wrap"))
        assertFalse("the DB Keystore alias must be gone", keystoreHas("meshline-db-wrap"))
        assertEquals("the retention must be back to the default", MessageRetention.OFF, retentionStore.load())
        assertNotNull(
            "the profile must be back to a fresh random one",
            profileStore.load(),
        )
        assertFalse(
            "the database file must be gone",
            context.getDatabasePath("meshline.db").parentFile?.listFiles()?.any { it.name.startsWith("meshline.db") } == true,
        )
        assertEquals(
            "the reset notice must be a panic wipe",
            SecureStorageStatus.Reason.PANIC_WIPE,
            SecureStorageStatus.consume(context)?.reason,
        )

        // The database is empty as well (a fresh database opens empty).
        val fresh = DatabaseFactory.create(context)
        assertTrue("the contacts must be gone", fresh.contactsDao().allOnce().isEmpty())
        assertTrue("the sessions must be gone", fresh.noiseSessionDao().find("peer") == null)
        assertTrue("the outbox must be gone", fresh.outboxDao().all().isEmpty())
        assertTrue("the messages must be gone", fresh.messagesDao().all().isEmpty())
        assertTrue("the seen ids must be gone", fresh.seenMessageDao().find("m2") == null)
        fresh.close()
    }

    @Test
    fun `the periodic purge removes the expired rows`() = runBlocking {
        val db = DatabaseFactory.create(context)
        try {
            val now = 1_700_000_000_000L
            val messages = db.messagesDao()
            messages.upsert(
                MessageEntity("old", "peer", true, "old".toByteArray(), now - 10_000L, OutboxState.DELIVERED.name)
            )
            messages.upsert(
                MessageEntity("fresh", "peer", true, "fresh".toByteArray(), now, OutboxState.DELIVERED.name)
            )
            // The purge the service runs every tick (FR-15, 1 day).
            val removed = messages.deleteOlderThan(now - 24L * 60 * 60 * 1_000)
            assertEquals("the expired message must be purged", 1, removed)
            assertEquals("the fresh message must stay", 1, messages.all().size)
            assertNull(messages.all().firstOrNull { it.messageIdHex == "old" })
        } finally {
            db.close()
        }
    }
}
