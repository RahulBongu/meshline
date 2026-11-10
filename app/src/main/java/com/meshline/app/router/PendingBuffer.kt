package com.meshline.app.router

import com.meshline.app.protocol.MeshPacket

/**
 * Bounded holding buffer for packets whose sender key is not known yet
 * (PROTOCOL.md §8.1, "unknown-sender holding buffer").
 *
 * A packet from an unknown sender is *held* for a short while instead of being
 * dropped on the spot: in a mesh the sender's ANNOUNCE and its first data
 * packet routinely race each other (the ANNOUNCE has to travel one more hop,
 * or the relay was busy). Holding bridges that gap.
 *
 * Bounds (all enforced here, never in the router):
 *  - **32 packets total** — the whole buffer, mesh-wide per node.
 *  - **8 packets per connection** — one chatty neighbour cannot fill it.
 *  - **3 s hold** — after that the packet is dropped and counted as
 *    `droppedUnknownKey` (the *only* place that counter moves).
 *
 * A held packet is released as soon as the matching verified ANNOUNCE arrives
 * (the router calls [release] with that sender's id). Packets are keyed by
 * their message id, so a duplicate copy of a held packet is ignored instead of
 * being queued twice (BLE duplicates are normal).
 *
 * The buffer is deliberately *not* a queue: it is a small, bounded waiting
 * area. Anything that does not fit is dropped immediately and counted as
 * `droppedBufferFull` — an unknown-sender flood must never be able to delay
 * or displace legitimate traffic.
 */
class PendingBuffer(
    /** Maximum number of packets held across all connections. */
    private val totalCap: Int = 32,
    /** Maximum number of packets held for a single connection. */
    private val perConnectionCap: Int = 8,
    /** Maximum time a packet may be held before it is dropped. */
    private val holdMs: Long = 3_000,
) {
    init {
        require(totalCap > 0) { "totalCap must be > 0" }
        require(perConnectionCap > 0) { "perConnectionCap must be > 0" }
        require(holdMs > 0) { "holdMs must be > 0" }
    }

    /** One held packet: the packet, the neighbour it arrived on, and when. */
    data class Entry(
        val packet: MeshPacket,
        val fromPeer: ByteArray,
        val connectionKey: String,
        val receivedAtMs: Long,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    // Insertion-ordered so expiry walks the oldest first.
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Try to hold [packet]. Returns true when the packet is held (or was
     * already held), false when the caps are exhausted and the caller must
     * drop it.
     */
    @Synchronized
    fun hold(packet: MeshPacket, fromPeer: ByteArray, connectionKey: String, nowMs: Long): Boolean {
        val id = messageKey(packet)
        // A duplicate of an already-held packet: keep the first copy.
        if (entries.containsKey(id)) return true
        if (entries.size >= totalCap) return false
        if (countForConnectionLocked(connectionKey) >= perConnectionCap) return false
        entries[id] = Entry(
            packet = packet,
            fromPeer = fromPeer.copyOf(),
            connectionKey = connectionKey,
            receivedAtMs = nowMs,
        )
        return true
    }

    /** Removes and returns every packet held for [senderId]. */
    @Synchronized
    fun release(senderId: ByteArray): List<Entry> {
        val target = hex(senderId)
        val hit = entries.filterValues { hex(it.packet.senderId) == target }
        hit.keys.forEach { entries.remove(it) }
        return hit.values.toList()
    }

    /** Removes and returns every packet that has been held for longer than [holdMs]. */
    @Synchronized
    fun expire(nowMs: Long): List<Entry> {
        val stale = entries.filterValues { nowMs - it.receivedAtMs > holdMs }
        stale.keys.forEach { entries.remove(it) }
        return stale.values.toList()
    }

    /** Number of packets currently held. */
    @Synchronized fun size(): Int = entries.size

    /** Number of packets currently held for one connection. */
    @Synchronized fun sizeForConnection(connectionKey: String): Int = countForConnectionLocked(connectionKey)

    /** Oldest receive timestamp of any held packet (null when empty). */
    @Synchronized fun oldestReceivedAtMs(): Long? = entries.values.minOfOrNull { it.receivedAtMs }

    @Synchronized fun clear() = entries.clear()

    private fun countForConnectionLocked(connectionKey: String): Int =
        entries.values.count { it.connectionKey == connectionKey }

    private fun messageKey(packet: MeshPacket): String = hex(packet.messageId)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
