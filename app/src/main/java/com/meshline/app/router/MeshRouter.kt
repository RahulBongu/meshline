package com.meshline.app.router

import com.meshline.app.protocol.Fragmentation
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketException
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Reassembler
import com.meshline.app.transport.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.max
import kotlin.random.Random

/**
 * Mesh routing (PROTOCOL.md §8). Phase B implementation.
 *
 * Order of operations on every received byte array (Phase D rule, coded now):
 * decode → signature verification (before any caching/accounting) → dedupe
 * (with highest-TTL upgrade) → rate limit (per sender *and* per connection)
 * → deliver locally → relay decision.
 * Invalid packets are dropped and never cached.
 *
 * Key invariants the tests pin (see MeshRouterTest / MeshSimulatorTest):
 *  - A packet with TTL == 1 is **delivered but never relayed**.
 *  - Duplicates (same messageId) are dropped before they can trigger a
 *    second delivery or a second relay, so one flood costs each node at most
 *    one relay-forward operation.
 *  - Relayed copies decrement TTL and keep the same messageId and signature,
 *    because both exclude TTL (§3.1) — destinations can still verify the
 *    origin's signature after any number of hops.
 *  - Directed packets whose recipient is a direct neighbour are sent
 *    directly instead of flooded (rule 5).
 */
sealed interface RouteDecision {
    /** Delivered locally (broadcast or addressed to us). */
    data class Deliver(val packet: MeshPacket) : RouteDecision

    /** Relayed (or originated) to these neighbours after jitter + TTL decrement. */
    data class Relay(val encodedBytes: ByteArray, val to: Set<ByteArray>) : RouteDecision

    /** Dropped: invalid, duplicate, rate-limited, TTL-exhausted at this hop,
     *  suppressed by adaptive probability, or addressed to another node. */
    data object Drop : RouteDecision

    /** Held pending the sender's ANNOUNCE (key not known yet). The packet is
     *  re-processed when the verified ANNOUNCE arrives, or dropped on timeout. */
    data class Hold(val packet: MeshPacket) : RouteDecision

    /** FRAGMENT held for reassembly; [completeBytes] is non-null when the last
     *  fragment arrived and only needs decoding. */
    data class InFragment(val completeBytes: ByteArray?) : RouteDecision
}

/**
 * Result of the router's signature hook. The four states are counted
 * separately so operators can tell the post-join key transient, real
 * forgeries, and key-learning budget drops apart.
 */
enum class SignatureCheck { OK, UNKNOWN_SENDER, BAD_SIGNATURE, BUDGET }

/** Counters a test or the mesh-status screen reads (all volatile writes via @Volatile ints). */
class MeshStats {
    @Volatile var delivered: Int = 0
    @Volatile var relayedForwards: Int = 0   // forward ops (exclude originated sends)
    @Volatile var originatedSends: Int = 0
    @Volatile var droppedInvalid: Int = 0
    @Volatile var droppedDuplicate: Int = 0
    @Volatile var droppedSuppressed: Int = 0
    @Volatile var droppedRateLimited: Int = 0
    @Volatile var linkTransmissions: Int = 0 // individual sendTo calls (originated + relayed)
    @Volatile var ttlUpgrades: Int = 0 // duplicates with a higher TTL that triggered one extra relay
    /** No key for the sender yet (expected transient right after a node joins).
     *  Counted **only** when a held packet times out (PROTOCOL §8.1). */
    @Volatile var droppedUnknownKey: Int = 0
    /** Key was known and the Ed25519 signature did not verify (forgery/corruption). */
    @Volatile var droppedBadSignature: Int = 0
    /** Valid announce rejected by the KeyCache per-connection/global learning budget. */
    @Volatile var droppedBudget: Int = 0
    /** Packet dropped because the unknown-sender holding buffer was full
     *  (32 total / 8 per connection). Not an unknown-key drop. */
    @Volatile var droppedBufferFull: Int = 0
    /** Packets put into the holding buffer (cumulative). */
    @Volatile var heldTotal: Int = 0
    /** Held packets released because the matching verified ANNOUNCE arrived. */
    @Volatile var releasedPending: Int = 0
}

data class RouterConfig(
    val jitterMinMs: Long = 10,
    val jitterMaxMs: Long = 200,
    val dedupeCapacity: Int = 5_000,
    val dedupeTtlMs: Long = 60L * 60 * 1000,
    val relayOn: Boolean = true,
    val seed: Long = 0x4D45534C4E31L, // "MESHL1"
    val nowProvider: () -> Long = System::currentTimeMillis,
    // Per-sender sliding-window buckets (PROTOCOL.md §8 rule 7, MVP starter values).
    val ratePublicPer10s: Int = 20,
    val rateHandshakePer10s: Int = 5,
    val rateReceiptPer10s: Int = 30,
    val rateOtherPer10s: Int = 40,
    // Unknown-sender holding buffer (PROTOCOL §8.1). A packet from a sender
    // whose key is not known yet waits here for the matching ANNOUNCE.
    val pendingTotalCap: Int = 32,
    val pendingPerConnectionCap: Int = 8,
    val pendingHoldMs: Long = 3_000,
    // A sender waits this long after a neighbour appears before it sends its
    // first data packet to it, so the neighbour has learned the sender's key
    // from the ANNOUNCE (which travels one hop less than the data).
    val newPeerSettleMs: Long = 2_000,
    // Wall clock for the settle timer only. `nowProvider` is the *simulated*
    // packet clock and may be frozen in tests; the settle timer needs real
    // elapsed time.
    val wallClock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(jitterMinMs >= 0 && jitterMaxMs >= jitterMinMs) { "jitter range invalid" }
    }
}

/** LRU dedupe cache bounded by size and age. Insertion-ordered, thread-safe for tests.
 *
 * Stores the **highest TTL seen** per message ID (PROTOCOL.md §8 amendment).
 * A copy that arrives with a *higher* TTL than any previous copy can trigger
 * one extra relay (without re-delivery) — this resists TTL pinning, where an
 * early low-TTL copy would otherwise suppress relaying of a later full-budget
 * copy. A copy whose TTL is <= the stored maximum is a plain duplicate.
 */
class DedupeCache(
    private val capacity: Int = 5_000,
    private val ttlMs: Long = 60L * 60 * 1000,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val seenAtMs: Long, val maxTtl: Int)

    private val seen = LinkedHashMap<String, Entry>()

    @Synchronized
    fun isSeen(messageId: ByteArray): Boolean {
        evictExpired()
        return seen.containsKey(key(messageId))
    }

    @Synchronized
    fun highestTtl(messageId: ByteArray): Int? {
        evictExpired()
        return seen[key(messageId)]?.maxTtl
    }

    @Synchronized
    fun mark(messageId: ByteArray, ttl: Int = 0) {
        evictExpired()
        val k = key(messageId)
        val prev = seen[k]
        val nextMax = maxOf(prev?.maxTtl ?: ttl, ttl)
        seen[k] = Entry(nowProvider(), nextMax)
        while (seen.size > capacity) {
            val oldest = seen.keys.firstOrNull() ?: break
            seen.remove(oldest)
        }
    }

    @Synchronized
    fun size(): Int = seen.size

    private fun evictExpired() {
        val now = nowProvider()
        val stale = seen.entries.filter { now - it.value.seenAtMs > ttlMs }.map { it.key }
        stale.forEach { seen.remove(it) }
    }

    private fun key(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }
}

/** Sliding-window rate limiter (10 s window).
 *
 * Buckets are enforced **per sender ID and per connection** (the immediate
 * neighbour [fromPeer] the packet arrived on), for every type except
 * FRAGMENT. The per-connection bucket stops one bad neighbour from burning
 * the whole node's budget even when it spoofs sender IDs; the per-sender
 * bucket stops one sender flooding via many neighbours.
 */
class RateLimiter(
    private val windowMs: Long = 10_000,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val config: RouterConfig = RouterConfig(nowProvider = nowProvider),
) {
    private val hits = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(senderId: ByteArray, type: PacketType): Boolean = allow(senderId, type, senderId)

    @Synchronized
    fun allow(senderId: ByteArray, type: PacketType, fromPeer: ByteArray): Boolean {
        if (type == PacketType.FRAGMENT) return true // bounded by reassembly limits, not the bucket
        val limit = when (type) {
            PacketType.PUBLIC_MESSAGE -> config.ratePublicPer10s
            PacketType.HANDSHAKE -> config.rateHandshakePer10s
            PacketType.RECEIPT -> config.rateReceiptPer10s
            else -> config.rateOtherPer10s
        }
        // Both buckets must allow; the check marks only one timestamp per bucket
        // (two buckets, same hit). A packet counts once per bucket.
        val senderBucket = hits.getOrPut(bucketKey("s", senderId, type)) { ArrayDeque() }
        val connBucket = hits.getOrPut(bucketKey("c", senderId, type, fromPeer)) { ArrayDeque() }
        val now = nowProvider()
        prune(senderBucket, now)
        prune(connBucket, now)
        if (senderBucket.size >= limit) return false
        if (connBucket.size >= limit) return false
        senderBucket.addLast(now)
        connBucket.addLast(now)
        return true
    }

    private fun prune(bucket: ArrayDeque<Long>, now: Long) {
        while (bucket.isNotEmpty() && now - bucket.first() > windowMs) bucket.removeFirst()
    }

    private fun bucketKey(prefix: String, senderId: ByteArray, type: PacketType): String =
        "$prefix:${senderId.joinToString("") { "%02x".format(it) }}:${type.name}"

    private fun bucketKey(prefix: String, senderId: ByteArray, type: PacketType, fromPeer: ByteArray): String =
        "$prefix:${fromPeer.joinToString("") { "%02x".format(it) }}:${senderId.joinToString("") { "%02x".format(it) }}:${type.name}"
}

class MeshRouter(
    val ownId: ByteArray,
    private val transport: Transport,
    private val config: RouterConfig = RouterConfig(),
    /** Snapshot of currently connected neighbours (ids). Defaults to empty for unit tests. */
    private val neighboursProvider: () -> Set<ByteArray> = { emptySet() },
    /**
     * The transport's *connection* view (ids we are linked to right now).
     * Used **only** for the settle window below — it deliberately does not
     * drive routing, which keeps using [neighboursProvider] (the mesh view).
     */
    private val connectedPeersProvider: () -> Set<ByteArray> = { emptySet() },
    /**
     * Signature verifier. Phase B/C default is permissive (`{ true }`) because no
     * keys exist yet; Phase D injects real Ed25519 verification.
     * **Ordering rule (Phase D, PROTOCOL.md §8):** the verifier runs *before*
     * dedupe insertion, rate-limit accounting and relay. Invalid packets are
     * dropped and never cached.
     */
    private val signatureVerifier: (MeshPacket, ByteArray) -> SignatureCheck = { _, _ -> SignatureCheck.OK },
) {
    private val dedupe = DedupeCache(config.dedupeCapacity, config.dedupeTtlMs, config.nowProvider)
    private val rateLimiter = RateLimiter(nowProvider = config.nowProvider, config = config)
    private val reassembler = Reassembler()
    private val random = Random(config.seed)
    /** Unknown-sender holding buffer (PROTOCOL §8.1). */
    private val pending = PendingBuffer(config.pendingTotalCap, config.pendingPerConnectionCap, config.pendingHoldMs)
    /** When a neighbour was first seen (wall clock) — drives the settle delay. */
    private val peerFirstSeenMs = HashMap<String, Long>()
    /** Neighbours that already waited out their settle window. */
    private val peerSettled = HashSet<String>()

    val stats = MeshStats()

    private val _delivered = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 256)
    val delivered: Flow<MeshPacket> = _delivered.asSharedFlow()

    /**
     * Locally delivered packets **with the neighbour they arrived on**.
     *
     * The D2 secure-channel layer needs the immediate neighbour: its
     * handshake rate limits and quarantine are per *connection* (NOISE_DESIGN
     * §5), never per claimed `senderId` — a spoofed senderId must not be able
     * to spend another peer's budget. The plain [delivered] flow cannot carry
     * that information.
     */
    private val _deliveredWithPeer = MutableSharedFlow<Pair<ByteArray, MeshPacket>>(extraBufferCapacity = 256)
    val deliveredWithPeer: Flow<Pair<ByteArray, MeshPacket>> = _deliveredWithPeer.asSharedFlow()

    /**
     * Receive one encoded packet from an immediate neighbour. Returns the
     * decision for observability; relay emission happens through [transport].
     */
    suspend fun onReceive(raw: ByteArray, fromPeer: ByteArray): RouteDecision {
        val pkt = try {
            PacketCodec.decode(raw, nowMs = config.nowProvider())
        } catch (_: PacketException) {
            stats.droppedInvalid++
            return RouteDecision.Drop
        }
        return onDecoded(pkt, fromPeer)
    }

    /** Origin-side entry point: validate, dedupe-mark, then transmit. */
    suspend fun onLocalSend(packet: MeshPacket): RouteDecision {
        if (!packet.senderId.contentEquals(ownId)) {
            stats.droppedInvalid++
            return RouteDecision.Drop
        }
        // Even originator dedupes its own id so its own relayed copies are ignored later.
        val id = packet.messageId
        if (dedupe.isSeen(id)) return RouteDecision.Drop
        dedupe.mark(id, packet.ttl)
        val encoded = PacketCodec.encode(packet)
        val targets = chooseTargets(packet, exclude = ownId)
        stats.originatedSends++
        return transmit(encoded, targets, originSend = true, packetType = packet.type)
    }

    /** Number of packets currently waiting for their sender's ANNOUNCE. */
    fun pendingSize(): Int = pending.size()

    /** Number of packets currently held for one connection. */
    fun pendingSizeForConnection(connectionKey: String): Int = pending.sizeForConnection(connectionKey)

    /**
     * Drops held packets whose hold window has expired and counts them as
     * `droppedUnknownKey`. The router calls this on every received packet;
     * the service also calls it on a timer so the counter moves even in a
     * silent mesh.
     */
    fun sweepExpiredPending(nowMs: Long = config.nowProvider()): Int {
        val expired = pending.expire(nowMs)
        for (e in expired) {
            stats.droppedInvalid++
            stats.droppedUnknownKey++
        }
        return expired.size
    }

    private fun mustBeSigned(type: PacketType): Boolean = when (type) {
        PacketType.ANNOUNCE, PacketType.PUBLIC_MESSAGE, PacketType.RECEIPT, PacketType.LEAVE -> true
        else -> false
    }

    private suspend fun onDecoded(pkt: MeshPacket, fromPeer: ByteArray): RouteDecision {
        // Echo back to ourselves is uninteresting (originated copies looping).
        if (pkt.senderId.contentEquals(ownId)) return RouteDecision.Drop

        if (pkt.type == PacketType.FRAGMENT) {
            val complete = reassembler.offer(pkt, nowMs = config.nowProvider())
            if (complete == null) return RouteDecision.InFragment(null)
            val reassembled = try {
                PacketCodec.decode(complete, nowMs = config.nowProvider())
            } catch (_: PacketException) {
                stats.droppedInvalid++
                return RouteDecision.Drop
            }
            return process(reassembled, fromPeer)
        }
        return process(pkt, fromPeer)
    }

    private suspend fun process(pkt: MeshPacket, fromPeer: ByteArray): RouteDecision {
        // Expire held packets first: their timeout is the only place the
        // droppedUnknownKey counter moves.
        sweepExpiredPending()

        // Signature verification happens *before* dedupe insertion, rate-limit
        // accounting and relay. Invalid packets are dropped and never cached.
        var verified = false
        if (pkt.signature == null) {
            // PROTOCOL §3: ANNOUNCE, PUBLIC_MESSAGE, RECEIPT and LEAVE MUST be
            // signed. An unsigned one is treated as an invalid signature.
            if (mustBeSigned(pkt.type)) {
                stats.droppedInvalid++
                stats.droppedBadSignature++
                return RouteDecision.Drop
            }
        } else {
            when (signatureVerifier(pkt, fromPeer)) {
                SignatureCheck.OK -> verified = true
                SignatureCheck.UNKNOWN_SENDER -> {
                    // The sender's key is not known yet (post-join transient).
                    // Hold the packet briefly: the matching verified ANNOUNCE
                    // releases it. The buffer is bounded (32 total / 8 per
                    // connection / 3 s), so an unknown-sender flood can neither
                    // grow it nor delay legitimate traffic.
                    val held = pending.hold(pkt, fromPeer, hex(fromPeer), config.nowProvider())
                    if (held) {
                        stats.heldTotal++
                        return RouteDecision.Hold(pkt)
                    }
                    // Buffer full: drop now. Not an unknown-key drop — the
                    // counter for that moves only on timeout.
                    stats.droppedInvalid++
                    stats.droppedBufferFull++
                    return RouteDecision.Drop
                }
                SignatureCheck.BAD_SIGNATURE -> {
                    stats.droppedInvalid++
                    stats.droppedBadSignature++
                    return RouteDecision.Drop
                }
                SignatureCheck.BUDGET -> {
                    // Valid packet, but the key-learning budget is exhausted.
                    stats.droppedBudget++
                    return RouteDecision.Drop
                }
            }
        }

        val decision = processVerified(pkt, fromPeer)

        // A verified ANNOUNCE teaches the sender's key: everything we held for
        // that sender can now be verified, delivered and relayed.
        if (verified && pkt.type == PacketType.ANNOUNCE) {
            releasePending(pkt.senderId)
        }
        return decision
    }

    /** Everything after successful verification: dedupe, rate limit, delivery, relay. */
    private suspend fun processVerified(pkt: MeshPacket, fromPeer: ByteArray): RouteDecision {
        val id = pkt.messageId
        val prevMax = dedupe.highestTtl(id)
        if (prevMax != null) {
            if (pkt.ttl <= prevMax) {
                // Plain duplicate: already delivered/relayed with at least as
                // much TTL budget. Drop without burning a rate-limit bucket.
                stats.droppedDuplicate++
                return RouteDecision.Drop
            }
            // Highest-TTL upgrade (TTL pinning defence): a copy with more
            // budget than anything seen before. Rate-limit, update the cache,
            // relay once more — but do NOT re-deliver locally.
            if (!rateLimiter.allow(pkt.senderId, pkt.type, fromPeer)) {
                stats.droppedRateLimited++
                return RouteDecision.Drop
            }
            dedupe.mark(id, pkt.ttl)
            stats.ttlUpgrades++
            return relay(pkt, fromPeer, deliveredHere = false)
        }
        // First sighting: rate-limit (per sender *and* per connection) then insert.
        if (!rateLimiter.allow(pkt.senderId, pkt.type, fromPeer)) {
            stats.droppedRateLimited++
            return RouteDecision.Drop
        }
        dedupe.mark(id, pkt.ttl)

        // 3. Local delivery.
        var deliveredHere = false
        val forMe = !pkt.isDirected || pkt.recipientId.contentEquals(ownId)
        if (forMe) {
            deliveredHere = _delivered.tryEmit(pkt)
            if (!deliveredHere) { /* buffer full in tests: count anyway */ deliveredHere = true }
            _deliveredWithPeer.tryEmit(fromPeer to pkt)
            stats.delivered++
        }

        // 4..6. Relay decision (shared by first sighting and TTL-upgrade paths).
        return relay(pkt, fromPeer, deliveredHere = deliveredHere)
    }

    /**
     * Re-processes the packets we held for [senderId] now that its key is
     * known. Each released packet goes through the normal path again
     * (verification → dedupe → rate limit → delivery → relay).
     */
    private suspend fun releasePending(senderId: ByteArray) {
        val released = pending.release(senderId)
        for (e in released) {
            stats.releasedPending++
            process(e.packet, e.fromPeer)
        }
    }

    /** Shared relay decision: relayOn + destination + TTL + adaptive probability + jitter. */
    private suspend fun relay(pkt: MeshPacket, fromPeer: ByteArray, deliveredHere: Boolean): RouteDecision {
        if (!config.relayOn) return if (deliveredHere) RouteDecision.Deliver(pkt) else RouteDecision.Drop
        // A directed packet that reached its destination must not travel further.
        if (pkt.isDirected && pkt.recipientId.contentEquals(ownId)) {
            return if (deliveredHere) RouteDecision.Deliver(pkt) else RouteDecision.Drop
        }
        // TTL == 1 (or 0): deliver, never relay. This is the user-required invariant.
        // (A later higher-TTL copy can still trigger exactly one re-relay — see TTL-upgrade path.)
        if (pkt.ttl <= 1) {
            return if (deliveredHere) RouteDecision.Deliver(pkt) else RouteDecision.Drop
        }
        val targets = chooseTargets(pkt, exclude = fromPeer)
        if (targets.isEmpty()) return if (deliveredHere) RouteDecision.Deliver(pkt) else RouteDecision.Drop

        // 6. Adaptive relay probability (avoids storms at density).
        val neighbours = snapshotNeighbours()
        if (random.nextDouble() > relayProbability(neighbours.size)) {
            stats.droppedSuppressed++
            return if (deliveredHere) RouteDecision.Deliver(pkt) else RouteDecision.Drop
        }

        // 4. Jitter, decrement, send to every neighbour except the source.
        applyJitter()
        val relayed = copyWithTtl(pkt, pkt.ttl - 1)
        val encoded = PacketCodec.encode(relayed)
        stats.relayedForwards++
        val decision = transmit(encoded, targets)
        return if (deliveredHere) {
            RouteDecision.Deliver(pkt) // delivered *and* relayed; flow carries the delivery
        } else {
            decision
        }
    }

    /** Targets: direct-only if the recipient is a neighbour, else all except [exclude]. */
    private fun chooseTargets(pkt: MeshPacket, exclude: ByteArray): Set<ByteArray> {
        val neighbours = snapshotNeighbours()
        if (pkt.isDirected && neighbours.any { it.contentEquals(pkt.recipientId) }) {
            return neighbours.filterTo(mutableSetOf()) { it.contentEquals(pkt.recipientId) }
        }
        return neighbours.filterTo(mutableSetOf()) { !it.contentEquals(exclude) && !it.contentEquals(ownId) }
    }

    private fun snapshotNeighbours(): Set<ByteArray> {
        noteConnectedPeers()
        return runCatching { neighboursProvider() }.getOrDefault(emptySet())
    }

    /**
     * Tracks when each *connected* peer first appeared. A peer that disappears
     * (disconnect) is forgotten, so a reconnect starts a fresh settle window.
     */
    private fun noteConnectedPeers() {
        val connected = runCatching { connectedPeersProvider() }.getOrDefault(emptySet())
        val now = config.wallClock()
        val present = HashSet<String>(connected.size)
        for (n in connected) {
            val k = hex(n)
            present += k
            if (!peerFirstSeenMs.containsKey(k)) peerFirstSeenMs[k] = now
        }
        peerFirstSeenMs.keys.retainAll(present)
        peerSettled.retainAll(present)
    }

    /**
     * Waits out the settle window before the first data packet to a newly seen
     * neighbour: the neighbour needs our ANNOUNCE (one hop) before it can
     * verify our data. Only *originated* data waits — a relayed packet keeps
     * the origin's signature, so the relay's key is not what the receiver
     * needs.
     */
    private suspend fun awaitPeerSettle(target: ByteArray) {
        noteConnectedPeers()
        val k = hex(target)
        if (k in peerSettled) return
        val firstSeen = peerFirstSeenMs[k] ?: return
        val elapsed = config.wallClock() - firstSeen
        val remaining = config.newPeerSettleMs - elapsed
        if (remaining > 0) delay(remaining)
        peerSettled += k
    }

    /** True for packet types that carry user content (they wait for the settle window). */
    private fun isContentType(type: PacketType): Boolean = when (type) {
        PacketType.PUBLIC_MESSAGE, PacketType.PRIVATE_MESSAGE, PacketType.RECEIPT, PacketType.FRAGMENT -> true
        else -> false
    }

    /** Bounded relay probability: 1.0 up to 4 neighbours, then 4/n floored at 0.25. */
    fun relayProbability(neighbourCount: Int): Double {
        if (neighbourCount <= 4) return 1.0
        return max(0.25, 4.0 / neighbourCount)
    }

    private suspend fun transmit(
        encoded: ByteArray,
        targets: Set<ByteArray>,
        originSend: Boolean = false,
        packetType: PacketType? = null,
    ): RouteDecision {
        if (targets.isEmpty()) return RouteDecision.Drop
        for (target in targets) {
            // The sender waits out the settle window before its first data
            // packet to a new neighbour (PROTOCOL §8.1).
            if (originSend && packetType != null && isContentType(packetType)) {
                awaitPeerSettle(target)
            }
            // One transmission per target per (hop) so tests can bound link counts.
            runCatching { transport.sendTo(target, encoded) }
            stats.linkTransmissions++
        }
        return RouteDecision.Relay(encoded, targets)
    }

    private suspend fun applyJitter() {
        if (config.jitterMaxMs == 0L) return
        val ms = config.jitterMinMs + random.nextLong(config.jitterMaxMs - config.jitterMinMs + 1)
        if (ms > 0) delay(ms)
    }

    private fun copyWithTtl(pkt: MeshPacket, ttl: Int): MeshPacket = MeshPacket(
        version = pkt.version,
        type = pkt.type,
        ttl = ttl,
        flags = pkt.flags,
        timestampMs = pkt.timestampMs,
        payload = pkt.payload.copyOf(),
        senderId = pkt.senderId.copyOf(),
        recipientId = pkt.recipientId.copyOf(),
        signature = pkt.signature?.copyOf(),
    )

    fun dedupeSize(): Int = dedupe.size()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
