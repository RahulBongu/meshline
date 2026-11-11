package com.meshline.app.noise

import com.meshline.app.protocol.MeshPacket

/**
 * The sender's outbox, as the private-message layer sees it.
 *
 * The rows hold the **plaintext** envelope (protected by the SQLCipher
 * database — see `storage.OutboxStore`), never transport ciphertext: the
 * ciphertext is produced at send time, under the session that is live then.
 */
interface Outbox {

    /** Queues an envelope for a peer; returns the row id. */
    suspend fun queue(peerIdHex: String, envelope: ByteArray, nowMs: Long): Long

    /** Every row, oldest first. */
    suspend fun rows(): List<OutboxRow>

    /** Records that a row was (re)sent: the new state and the attempt count. */
    suspend fun markAttempt(id: Long, state: OutboxState, attempts: Int)

    /** Removes a row (the receipt arrived, or it expired). */
    suspend fun delete(id: Long)

    /** Removes the rows older than the cutoff; returns how many went. */
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}

/** One queued message. */
data class OutboxRow(
    val id: Long,
    val peerIdHex: String,
    /** The encoded [PrivateEnvelope] (plaintext, SQLCipher-protected). */
    val envelope: ByteArray,
    val createdAtMs: Long,
    val state: OutboxState,
    val attempts: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxRow) return false
        return id == other.id &&
            peerIdHex == other.peerIdHex &&
            envelope.contentEquals(other.envelope) &&
            createdAtMs == other.createdAtMs &&
            state == other.state &&
            attempts == other.attempts
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + peerIdHex.hashCode()
        h = 31 * h + envelope.contentHashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + attempts
        return h
    }
}

/** The user-visible state of an outbox row. */
enum class OutboxState {
    /** Waiting for a usable session (nothing on the wire yet). */
    QUEUED,

    /** Handed to the router; waiting for the receipt. */
    SENT,
}

/**
 * The private-message layer: the outbox on the sending side, the
 * application-level dedupe on the receiving side.
 *
 * Sending (FR-12):
 * ```
 * user sends            -> outbox row (QUEUED), envelope encrypted later
 * session available     -> encrypt the envelope under the live session
 *                          (a FRESH nonce every time) -> hand to the router
 *                          -> row becomes SENT and STAYS in the outbox
 * receipt (Delivered)   -> the row is deleted
 * 12 h without a receipt -> the row is dropped
 * ```
 *
 * A row stays in the outbox after "Sent" on purpose: BLE offers no delivery
 * guarantee, so "Sent" only means "handed to the mesh". The message is
 * retried with an exponential backoff until a receipt arrives or it expires,
 * and every retry is re-encrypted under the live session with a **fresh
 * nonce** (a nonce must never be reused) while keeping the same
 * application-level id — so the receiver shows the message exactly once.
 */
class PrivateMessenger(
    private val sessions: SessionManager,
    private val outbox: Outbox,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val randomBytes: (Int) -> ByteArray = { size ->
        java.security.SecureRandom().let { rnd -> ByteArray(size).also { rnd.nextBytes(it) } }
    },
    /**
     * The receiver's record of the message ids it already showed. It must
     * survive a process restart, otherwise a retry that arrives after the
     * restart would be shown a second time.
     */
    private val dedupe: MessageDedupe = InMemoryMessageDedupe(),
) {
    /** The backoff schedule for retries. */
    private val retrySchedule = HashMap<Long, Long>()

    /** Queues a message for [peerId] and returns the outbox row id. */
    suspend fun send(peerId: ByteArray, peerIdHex: String, text: ByteArray): Long {
        val envelope = PrivateEnvelope(
            messageId = randomBytes(PrivateEnvelope.MESSAGE_ID_BYTES),
            sentAtMs = nowProvider(),
            text = text.copyOf(),
        )
        val id = outbox.queue(peerIdHex, envelope.encode(), nowProvider())
        retrySchedule[id] = nowProvider()
        return id
    }

    /**
     * Encrypts and returns the packets that are due for a (re)send.
     *
     * A row is due when its backoff has elapsed. Every attempt is encrypted
     * **again**, under the session that is live now, with a fresh nonce.
     */
    suspend fun tick(): List<MeshPacket> {
        val now = nowProvider()
        val packets = mutableListOf<MeshPacket>()
        for (row in outbox.rows()) {
            val due = retrySchedule[row.id] ?: now
            if (due > now) continue
            val peerId = row.peerIdHex.hexToBytes()
            val packet = sessions.encrypt(peerId, row.envelope) ?: continue
            val attempts = row.attempts + 1
            outbox.markAttempt(row.id, OutboxState.SENT, attempts)
            retrySchedule[row.id] = now + backoffMs(attempts)
            packets += packet
        }
        return packets
    }

    /**
     * Handles a receipt: the row is done and leaves the outbox.
     * Returns true when a row was actually removed.
     */
    suspend fun onReceipt(peerIdHex: String, messageId: ByteArray): Boolean {
        val id = hex(messageId)
        val row = outbox.rows().firstOrNull { r ->
            r.peerIdHex == peerIdHex && runCatching { hex(PrivateEnvelope.decode(r.envelope).messageId) }
                .getOrNull() == id
        } ?: return false
        outbox.delete(row.id)
        retrySchedule.remove(row.id)
        return true
    }

    /**
     * What came out of a decrypted private message.
     *
     * [isNew] is false when the application-level id was already shown — the
     * message must not be shown twice, but it **must still be acknowledged**:
     * the receipt is idempotent, and the sender needs it to clean its outbox.
     */
    data class PlaintextResult(val envelope: PrivateEnvelope, val isNew: Boolean)

    /**
     * Accepts a decrypted private message from a peer.
     *
     * Returns the envelope the first time an application-level id is seen and
     * a repeat (with [PlaintextResult.isNew] false) for a message we already
     * showed — that is what makes the message appear exactly once. The dedupe
     * record is persisted, so a retry that arrives after a **process restart**
     * is recognised as well.
     */
    suspend fun onPlaintext(peerIdHex: String, plaintext: ByteArray): PlaintextResult? {
        val envelope = try {
            PrivateEnvelope.decode(plaintext)
        } catch (e: PrivateEnvelope.MalformedEnvelope) {
            return null
        }
        val isNew = dedupe.isNew(peerIdHex, hex(envelope.messageId), nowProvider())
        return PlaintextResult(envelope, isNew)
    }

    /**
     * When the next retry is due (the earliest scheduled attempt), or null when
     * nothing is queued.
     */    suspend fun nextDueAtMs(): Long? {
        val now = nowProvider()
        return outbox.rows().mapNotNull { retrySchedule[it.id] }.filter { it > now }.minOrNull()
    }

    /** Drops the rows that waited longer than the age limit. */
    suspend fun expire(nowMs: Long = nowProvider()): Int = outbox.deleteOlderThan(nowMs - MAX_AGE_MS)

    /** The exponential backoff for the n-th attempt. */
    fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 16)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** Forgets every session and every outbox row (panic wipe). */
    suspend fun wipe() {
        retrySchedule.clear()
        outbox.deleteOlderThan(Long.MAX_VALUE)
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    companion object {
        /** 12 h — the MVP store-and-forward window (NOISE_DESIGN §6.1). */
        const val MAX_AGE_MS: Long = 12 * 60 * 60 * 1_000

        /** The first retry waits this long. */
        const val BASE_BACKOFF_MS: Long = 2_000

        /** The backoff never grows past this. */
        const val MAX_BACKOFF_MS: Long = 30 * 60 * 1_000
    }
}
