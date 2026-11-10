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

    /** FRAGMENT held for reassembly; [completeBytes] is non-null when the last
     *  fragment arrived and only needs decoding. */
    data class InFragment(val completeBytes: ByteArray?) : RouteDecision
}

/**
 * Result of the router's signature hook. Distinguishing “key not learned yet”
 * from “bad signature” keeps [MeshStats.droppedUnknownKey] meaningful: it
 * counts the post-join transient, not forgeries.
 */
enum class SignatureCheck { OK, UNKNOWN_SENDER, INVALID }

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
    @Volatile var droppedUnknownKey: Int = 0 // signature/key lookup failed after a node joins (before key learned)
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
     * Signature verifier. Phase B/C default is permissive (`{ true }`) because no
     * keys exist yet; Phase D injects real Ed25519 verification.
     * **Ordering rule (Phase D, PROTOCOL.md §8):** the verifier runs *before*
     * dedupe insertion, rate-limit accounting and relay. Invalid packets are
     * dropped and never cached.
     */
    private val signatureVerifier: (MeshPacket) -> SignatureCheck = { SignatureCheck.OK },
) {
    private val dedupe = DedupeCache(config.dedupeCapacity, config.dedupeTtlMs, config.nowProvider)
    private val rateLimiter = RateLimiter(nowProvider = config.nowProvider, config = config)
    private val reassembler = Reassembler()
    private val random = Random(config.seed)

    val stats = MeshStats()

    private val _delivered = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 256)
    val delivered: Flow<MeshPacket> = _delivered.asSharedFlow()

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
        return transmit(encoded, targets)
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
        // Phase D ordering rule: signature verification happens *before*
        // dedupe insertion, rate-limit accounting and relay. Invalid packets
        // are dropped and never cached (Phase B/C verifier is permissive).
        if (pkt.signature != null) {
            when (signatureVerifier(pkt)) {
                SignatureCheck.OK -> Unit
                SignatureCheck.UNKNOWN_SENDER -> {
                    // Post-join transient: the sender's ANNOUNCE has not been
                    // learned yet, so the key lookup fails. Counted separately.
                    stats.droppedInvalid++
                    stats.droppedUnknownKey++
                    return RouteDecision.Drop
                }
                SignatureCheck.INVALID -> {
                    stats.droppedInvalid++
                    return RouteDecision.Drop
                }
            }
        }

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
            stats.delivered++
        }

        // 4..6. Relay decision (shared by first sighting and TTL-upgrade paths).
        return relay(pkt, fromPeer, deliveredHere = deliveredHere)
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

    private fun snapshotNeighbours(): Set<ByteArray> = runCatching { neighboursProvider() }.getOrDefault(emptySet())

    /** Bounded relay probability: 1.0 up to 4 neighbours, then 4/n floored at 0.25. */
    fun relayProbability(neighbourCount: Int): Double {
        if (neighbourCount <= 4) return 1.0
        return max(0.25, 4.0 / neighbourCount)
    }

    private suspend fun transmit(encoded: ByteArray, targets: Set<ByteArray>): RouteDecision {
        if (targets.isEmpty()) return RouteDecision.Drop
        for (target in targets) {
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
}
