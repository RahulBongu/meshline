package com.meshline.app.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The D2 stores on a real device: the outbox (with its states), the inbox and
 * the receiver's application-level dedupe.
 *
 * Instrumented test — needs a device or emulator (BLE does not work on
 * emulators, but the database does):
 * `./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class SecureStoresInstrumentedTest {

    private lateinit var db: MeshlineDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = DatabaseFactory.create(context, "test-passphrase-0123456789".toByteArray())
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    @Test
    fun outboxKeepsTheStateAndThePlaintext() = runBlocking {
        val outbox = OutboxRepository(db.outboxDao())
        val id = outbox.queue("peer1", "hello".toByteArray(), 1_000L)
        val row = outbox.rows().single()
        assertEquals(id, row.id)
        assertEquals("peer1", row.peerIdHex)
        assertEquals("hello", String(row.envelope))
        assertEquals(OutboxState.QUEUED, row.state)

        outbox.markAttempt(id, OutboxState.SENT, 1)
        assertEquals(OutboxState.SENT, outbox.rows().single().state)

        outbox.markAttempt(id, OutboxState.DELIVERED, 1)
        val delivered = outbox.rows().single()
        assertEquals(OutboxState.DELIVERED, delivered.state)
        assertEquals("the plaintext survives", "hello", String(delivered.envelope))

        outbox.delete(id)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun theInboxStoresReceivedMessages() = runBlocking {
        val inbox = db.inboxDao()
        val row = InboxEntity("id1", "peer1", "hi".toByteArray(), 2_000L, read = false)
        assertTrue(inbox.insert(row) > 0)
        // The same id again is ignored (the message is shown once).
        assertEquals(-1L, inbox.insert(row.copy(read = true)))
        assertEquals(1, inbox.all().size)
        assertFalse(inbox.find("id1")!!.read)
        inbox.markRead("id1")
        assertTrue(inbox.find("id1")!!.read)
        inbox.deleteAll()
        assertTrue(inbox.all().isEmpty())
    }

    @Test
    fun theSeenIdsSurviveAndOnlyReportNewOnes() = runBlocking {
        val seen = SeenMessageStore(db.seenMessageDao())
        assertTrue("the first sighting is new", seen.isNew("peer1", "id1", 1_000L))
        assertFalse("the second sighting is a repeat", seen.isNew("peer1", "id1", 1_001L))
        assertTrue("a different id is new", seen.isNew("peer1", "id2", 1_002L))
    }

    @Test
    fun theSessionStoreNeverWritesTheTransportKey() = runBlocking {
        val sessions = SessionStore(db.noiseSessionDao())
        val snapshot = SessionSnapshot(
            peerIdHex = "peer1",
            ed25519PublicKey = ByteArray(32) { 1 },
            x25519PublicKey = ByteArray(32) { 2 },
            fingerprint = "fp",
            state = "ESTABLISHED",
            sendNonce = 1,
            recvNonce = 2,
            replayWindow = ByteArray(24),
            createdAtMs = 1,
            lastUsedAtMs = 2,
            messagesSent = 0,
            messagesReceived = 0,
            transportKey = ByteArray(64) { 9 },
            verified = true,
        )
        sessions.save(snapshot)
        val stored = sessions.load("peer1")
        assertTrue("the session was stored", stored != null)
        assertNull("the transport key must never be written", stored!!.transportKey)
        sessions.wipe()
        assertNull(sessions.load("peer1"))
    }
}
