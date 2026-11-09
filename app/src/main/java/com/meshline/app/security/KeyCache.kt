package com.meshline.app.security

/**
 * Production two-tier key cache (PROTOCOL.md §8.1, D1).
 * Mirrors the simulator model in KeyKnowledgeTest exactly:
 *  - Protected tier (100, never evicted by untrusted) for QR contacts + neighbours.
 *  - Untrusted LRU tier (500, 420 s expiry).
 *  - Per-connection budgets: replay 49 + normal 50 per connection per window;
 *    global 100 per window (149 while nearly empty / cold start).
 */
class KeyCache(
    private val untrustedCap: Int = 500,
    private val protectedCap: Int = 100,
    private val expiryMs: Long = 420_000L,
    private val perConnNewBudget: Int = 50,
    private val replayAllowance: Int = 49,
    private val budgetWindowMs: Long = 300_000L,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val untrusted = LinkedHashMap<String, Pair<ByteArray, Long>>()
    private val protected_ = LinkedHashMap<String, Pair<ByteArray, Long>>()
    private val replayHits = mutableMapOf<String, MutableList<Pair<String, Long>>>()
    private val budgetHits = mutableMapOf<String, MutableList<Pair<String, Long>>>()
    private var globalHits = mutableListOf<Pair<String, Long>>()

    @Synchronized
    fun protect(senderId: ByteArray, key: ByteArray, nowMs: Long = nowProvider()) {
        protected_[hex(senderId)] = key.copyOf() to nowMs
        untrusted.remove(hex(senderId))
        while (protected_.size > protectedCap) {
            val oldest = protected_.keys.firstOrNull() ?: break
            protected_.remove(oldest)
        }
    }

    @Synchronized
    fun learn(
        senderId: ByteArray,
        key: ByteArray,
        nowMs: Long = nowProvider(),
        connectionKey: String,
        isReplay: Boolean = false,
    ): Boolean {
        if (protected_.containsKey(hex(senderId)) || untrusted.containsKey(hex(senderId))) return true
        val hits = if (isReplay) replayHits.getOrPut(connectionKey) { mutableListOf() }
        else budgetHits.getOrPut(connectionKey) { mutableListOf() }
        hits.removeAll { nowMs - it.second > budgetWindowMs }
        val cap = if (isReplay) replayAllowance else perConnNewBudget
        if (hits.size >= cap) return false
        globalHits.removeAll { nowMs - it.second > budgetWindowMs }
        val globalCap = if (size() < 20) 149 else 100
        if (globalHits.size >= globalCap) return false
        hits.add(hex(senderId) to nowMs)
        if (isReplay) replayHits[connectionKey] = hits else budgetHits[connectionKey] = hits
        globalHits.add(hex(senderId) to nowMs)
        untrusted[hex(senderId)] = key.copyOf() to nowMs
        while (untrusted.size > untrustedCap) {
            val oldest = untrusted.keys.firstOrNull() ?: break
            untrusted.remove(oldest)
        }
        return true
    }

    @Synchronized
    fun knows(senderId: ByteArray, nowMs: Long = nowProvider()): Boolean {
        evict(nowMs)
        return protected_.containsKey(hex(senderId)) || untrusted.containsKey(hex(senderId))
    }

    @Synchronized fun size(): Int = protected_.size + untrusted.size
    @Synchronized fun sizeUntrusted(): Int = untrusted.size

    private fun evict(nowMs: Long) {
        val stale = untrusted.entries.filter { nowMs - it.value.second > expiryMs }.map { it.key }
        stale.forEach { untrusted.remove(it) }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
