package com.meshline.app.noise

/**
 * Handshake rate limits and quarantine (NOISE_DESIGN §5).
 *
 * Handshakes are the cheapest amplification in the protocol (3 messages × mesh
 * fan-out), so they get the strictest limiter. Every bucket is keyed by the
 * **connection** — the immediate neighbour a packet arrived on — and never by
 * the claimed `senderId`, because a claimed header field is untrusted until
 * the Noise static key has been verified. A spoofed senderId must therefore
 * not be able to spend another peer's budget or quarantine an innocent peer.
 *
 * Over-limit handshakes are dropped **silently** (no error reply — errors are
 * amplifiers).
 */
class HandshakeRateLimiter(
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    /** Sliding window buckets: key -> timestamps. */
    private val packetBuckets = HashMap<String, MutableList<Long>>()
    private val startBuckets = HashMap<String, MutableList<Long>>()
    private val strikes = HashMap<String, MutableList<Long>>()
    private val violations = HashMap<String, MutableList<Long>>()
    private val quarantinedUntil = HashMap<String, Long>()
    private val lastSuccessAt = HashMap<String, Long>()

    private val nodePacketBucket = ArrayDeque<Long>()

    /**
     * Global handshake-work budget: the X25519 operations spent in the last
     * minute, across **all** connections.
     *
     * The per-connection buckets reset when a peer reconnects under a new BLE
     * address, so they cannot bound the *work* an attacker can make us do.
     * This bucket is per node and survives reconnects.
     *
     * The budget has two halves: a general one that anybody may spend, and a
     * **reserved** half that only senders in the protected key tier (QR
     * contacts, connected neighbours) may spend. Junk handshakes therefore
     * cannot starve the people you actually talk to.
     */
    private val globalDhOps = ArrayDeque<Long>()
    private val protectedDhOps = ArrayDeque<Long>()

    /**
     * Charges [cost] X25519 operations against the global per-minute budget.
     * Returns false when the budget is exhausted — the caller must then drop
     * the handshake instead of doing the key agreement.
     *
     * [protectedSender] may spend the reserved half as well; an unknown sender
     * may not.
     */
    fun allowDhWork(cost: Int, protectedSender: Boolean = false): Boolean {
        if (cost <= 0) return true
        val now = nowProvider()
        prune(globalDhOps, now, WINDOW_1M)
        prune(protectedDhOps, now, WINDOW_1M)
        val generalCap = NoiseProtocol.MAX_X25519_OPS_PER_MINUTE - NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE
        if (globalDhOps.size + cost <= generalCap) {
            repeat(cost) { globalDhOps.add(now) }
            return true
        }
        if (protectedSender && protectedDhOps.size + cost <= NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE) {
            repeat(cost) { protectedDhOps.add(now) }
            return true
        }
        return false
    }

    /** X25519 operations still available in the current minute. */
    fun remainingDhOps(): Int {
        val now = nowProvider()
        prune(globalDhOps, now, WINDOW_1M)
        prune(protectedDhOps, now, WINDOW_1M)
        val generalCap = NoiseProtocol.MAX_X25519_OPS_PER_MINUTE - NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE
        val general = (generalCap - globalDhOps.size).coerceAtLeast(0)
        val reserved = (NoiseProtocol.RESERVED_X25519_OPS_PER_MINUTE - protectedDhOps.size).coerceAtLeast(0)
        return general + reserved
    }

    /**
     * A handshake *packet* may be processed? (5 per connection per 10 s, 30 per
     * node per 10 s.)
     */
    fun allowPacket(connectionKey: String): Boolean {
        val now = nowProvider()
        prune(nodePacketBucket, now, WINDOW_10S)
        if (nodePacketBucket.size >= NoiseProtocol.HANDSHAKE_PACKETS_PER_NODE_PER_10S) return false
        val bucket = packetBuckets.getOrPut(connectionKey) { mutableListOf() }
        prune(bucket, now, WINDOW_10S)
        if (bucket.size >= NoiseProtocol.HANDSHAKE_PACKETS_PER_CONNECTION_PER_10S) return false
        bucket.add(now)
        nodePacketBucket.add(now)
        return true
    }

    /**
     * A handshake *start* may be made towards [connectionKey]? (20 per hour,
     * plus a 60 s cooldown after the last successful handshake.)
     */
    fun allowStart(connectionKey: String): Boolean {
        val now = nowProvider()
        val last = lastSuccessAt[connectionKey]
        if (last != null && now - last < NoiseProtocol.HANDSHAKE_COOLDOWN_MS) return false
        val bucket = startBuckets.getOrPut(connectionKey) { mutableListOf() }
        prune(bucket, now, WINDOW_1H)
        if (bucket.size >= NoiseProtocol.HANDSHAKE_STARTS_PER_CONNECTION_PER_HOUR) return false
        bucket.add(now)
        return true
    }

    /** Records a successful handshake (arms the cooldown). */
    fun onHandshakeSucceeded(connectionKey: String) {
        lastSuccessAt[connectionKey] = nowProvider()
    }

    /**
     * Records an invalid handshake. Returns true when this strike pushes the
     * connection into quarantine (3 strikes in 5 min → 10 min quarantine).
     */
    fun recordInvalid(connectionKey: String): Boolean {
        val now = nowProvider()
        val bucket = strikes.getOrPut(connectionKey) { mutableListOf() }
        prune(bucket, now, NoiseProtocol.INVALID_STRIKE_WINDOW_MS)
        bucket.add(now)
        if (bucket.size >= NoiseProtocol.INVALID_STRIKES) {
            quarantinedUntil[connectionKey] = now + NoiseProtocol.QUARANTINE_MS
            return true
        }
        return false
    }

    /** Is this connection quarantined? */
    fun isQuarantined(connectionKey: String): Boolean {
        val until = quarantinedUntil[connectionKey] ?: return false
        if (nowProvider() >= until) {
            quarantinedUntil.remove(connectionKey)
            return false
        }
        return true
    }

    /** Records a protocol violation (e.g. a binding mismatch). */
    fun recordViolation(connectionKey: String) {
        val now = nowProvider()
        val bucket = violations.getOrPut(connectionKey) { mutableListOf() }
        prune(bucket, now, WINDOW_1H)
        bucket.add(now)
    }

    /** Violations on this connection in the last hour. */
    fun violationCount(connectionKey: String): Int {
        val now = nowProvider()
        val bucket = violations[connectionKey] ?: return 0
        prune(bucket, now, WINDOW_1H)
        return bucket.size
    }

    /** True when the violation count is high enough to surface as a health hint. */
    fun shouldSurfaceViolations(connectionKey: String): Boolean =
        violationCount(connectionKey) > NoiseProtocol.VIOLATIONS_PER_HOUR

    fun reset() {
        packetBuckets.clear()
        startBuckets.clear()
        strikes.clear()
        violations.clear()
        quarantinedUntil.clear()
        lastSuccessAt.clear()
        nodePacketBucket.clear()
        globalDhOps.clear()
        protectedDhOps.clear()
    }

    private fun prune(bucket: MutableList<Long>, now: Long, windowMs: Long) {
        while (bucket.isNotEmpty() && now - bucket.first() > windowMs) bucket.removeAt(0)
    }

    private fun prune(bucket: ArrayDeque<Long>, now: Long, windowMs: Long) {
        while (bucket.isNotEmpty() && now - bucket.first() > windowMs) bucket.removeFirst()
    }

    private companion object {
        const val WINDOW_10S = 10_000L
        const val WINDOW_1M = 60_000L
        const val WINDOW_1H = 60L * 60 * 1_000
    }
}
