package com.meshline.app.noise

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver's application-level message-id dedupe (review item 2).
 *
 * The dedupe has to survive a **process restart**: a retry that arrives
 * after the restart must be acknowledged again (the sender needs that receipt
 * to clean its outbox) but must **not** be shown a second time.
 */
class MessageDedupeTest {

    private fun envelope(text: String, id: ByteArray = ByteArray(32) { 7 }): PrivateEnvelope =
        PrivateEnvelope(messageId = id, sentAtMs = 1_700_000_000_000L, text = text.toByteArray())

    @Test
    fun `a retry after a process restart is acknowledged but not shown twice`() = runBlocking {
        // The dedupe store stands in for the SQLCipher table: it survives the
        // "restart" because both messengers share it.
        val dedupe = InMemoryMessageDedupe()
        val outbox = object : Outbox {
            override suspend fun queue(peerIdHex: String, envelope: ByteArray, nowMs: Long) = 1L
            override suspend fun rows(): List<OutboxRow> = emptyList()
            override suspend fun markAttempt(id: Long, state: OutboxState, attempts: Int) = Unit
            override suspend fun delete(id: Long) = Unit
            override suspend fun deleteOlderThan(cutoffMs: Long) = 0
        }
        val peer = "aabbccddeeff0011"
        val first = PrivateMessenger(
            SessionManager(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            outbox,
            dedupe = dedupe,
        )
        val env = envelope("hello")

        // The message arrives and is shown.
        val r1 = first.onPlaintext(peer, env.encode())
        assertNotNull("the first copy must be accepted", r1)
        assertTrue("the first copy is new", r1!!.isNew)

        // "Process restart": a brand new messenger with the same (persisted) store.
        val afterRestart = PrivateMessenger(
            SessionManager(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            outbox,
            dedupe = dedupe,
        )
        // The sender retried: the same application-level id arrives again.
        val r2 = afterRestart.onPlaintext(peer, env.encode())
        assertNotNull("the retry must still be acknowledged", r2)
        assertFalse("the retry must not be shown again", r2!!.isNew)
        assertTrue(
            "the acknowledged id must be the same one",
            env.messageId.contentEquals(r2.envelope.messageId),
        )
    }

    @Test
    fun `the retention is longer than the outbox expiry`() {
        assertTrue(
            "the dedupe must outlive the 12 h outbox expiry",
            MESSAGE_DEDUPE_RETENTION_MS > PrivateMessenger.MAX_AGE_MS,
        )
        assertEquals(30L * 24 * 60 * 60 * 1_000, MESSAGE_DEDUPE_RETENTION_MS)
    }

    @Test
    fun `ids older than the retention are forgotten`() = runBlocking {
        var now = 1_000_000L
        val dedupe = InMemoryMessageDedupe(retentionMs = 1_000, nowProvider = { now })
        assertTrue(dedupe.isNew("peer", "aaaa", now))
        assertFalse(dedupe.isNew("peer", "aaaa", now))
        now += 2_000
        // After the retention the id is forgotten and the message counts as new
        // again (the sender has long since given up by then).
        assertTrue(dedupe.isNew("peer", "aaaa", now))
    }
}
