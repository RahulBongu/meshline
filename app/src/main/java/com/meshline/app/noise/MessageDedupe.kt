package com.meshline.app.noise

/**
 * The receiver's record of the application-level message ids it has already
 * shown.
 *
 * It has to **survive a process restart**: a retry that arrives after the
 * restart must be acknowledged again (so the sender can clean its outbox) but
 * must **not** be shown a second time. The transport's replay window cannot do
 * this job — a retry is re-encrypted with a fresh nonce, so the receiver sees
 * a perfectly valid new transport message.
 *
 * The retention must be **longer than the sender's outbox expiry** (12 h), or
 * a late retry would be shown twice. The store keeps the ids for 30 days.
 */
interface MessageDedupe {

    /**
     * Records [messageIdHex] and returns **true when it is new** (the message
     * has not been shown before). A repeat returns false — and must still be
     * acknowledged by the caller.
     */
    suspend fun isNew(peerIdHex: String, messageIdHex: String, nowMs: Long): Boolean

    /** Drops the ids older than the cutoff; returns how many went. */
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}

/** How long the receiver remembers an application-level message id. */
const val MESSAGE_DEDUPE_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1_000

/**
 * The in-memory implementation (used before the database is wired, and by the
 * tests). A real installation uses the SQLCipher-backed store.
 */
class InMemoryMessageDedupe(
    private val retentionMs: Long = MESSAGE_DEDUPE_RETENTION_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) : MessageDedupe {

    private val seen = LinkedHashMap<String, Long>()

    override suspend fun isNew(peerIdHex: String, messageIdHex: String, nowMs: Long): Boolean =
        synchronized(seen) {
            val now = nowProvider()
            prune(now)
            if (seen.containsKey(messageIdHex)) return@synchronized false
            seen[messageIdHex] = now
            true
        }

    override suspend fun deleteOlderThan(cutoffMs: Long): Int = synchronized(seen) {
        val stale = seen.filterValues { it < cutoffMs }.keys.toList()
        stale.forEach { seen.remove(it) }
        stale.size
    }

    /** How many ids are remembered. */
    fun size(): Int = synchronized(seen) { seen.size }

    private fun prune(now: Long) {
        val cutoff = now - retentionMs
        val stale = seen.filterValues { it < cutoff }.keys.toList()
        stale.forEach { seen.remove(it) }
    }
}
