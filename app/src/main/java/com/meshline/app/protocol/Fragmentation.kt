package com.meshline.app.protocol

/**
 * BLE fragmentation / reassembly (PROTOCOL.md §5).
 *
 * Chunks are slices of the *entire encoded packet*, so reassembly is plain
 * concatenation in index order followed by one normal [PacketCodec.decode]
 * (which also verifies the signature, if any). Tampering with any chunk
 * corrupts that final verification — fragments need no per-chunk crypto.
 *
 * Both parts are pure JVM (no Android, no coroutines) and directly unit-tested.
 * The router/BLE layer owns the queues and timeouts; [Reassembler] only holds
 * bounded state per original message.
 */
object Fragmentation {

    /** Max fragments per original packet (PROTOCOL.md §5). */
    const val MAX_FRAGMENTS: Int = 1000

    /** Fragment payload prefix: messageId(32) + index(2) + total(2) = 36 bytes. */
    const val FRAGMENT_PREFIX_BYTES: Int = 32 + 2 + 2

    /**
     * Fragment [originalPacket] ([encodedBytes] must be its [PacketCodec.encode]
     * output) so every resulting packet fits [maxBytesPerWrite] (typically MTU - 3).
     *
     * If the packet already fits, returns `listOf(originalPacket)` — never a
     * one-fragment wrapper (PROTOCOL.md §5). Callers send each returned packet
     * as one GATT write.
     *
     * @throws PacketException.InvalidPacket if the packet would need more than
     * [MAX_FRAGMENTS] fragments at this chunk size (raise MTU or shrink payload).
     */
    fun split(
        originalPacket: MeshPacket,
        encodedBytes: ByteArray,
        maxBytesPerWrite: Int,
    ): List<MeshPacket> {
        require(maxBytesPerWrite > FRAGMENT_PREFIX_BYTES + PacketCodec.HEADER_BYTES) {
            "maxBytesPerWrite ($maxBytesPerWrite) too small for fragment overhead"
        }
        // Fast path: fits in one write -> send as-is.
        if (encodedBytes.size <= maxBytesPerWrite) return listOf(originalPacket)

        val chunkSize = maxBytesPerWrite - PacketCodec.HEADER_BYTES - FRAGMENT_PREFIX_BYTES
        require(chunkSize > 0) { "chunk size must be > 0" }
        val total = (encodedBytes.size + chunkSize - 1) / chunkSize
        if (total > MAX_FRAGMENTS) {
            throw PacketException.InvalidPacket(
                "packet needs $total fragments (> $MAX_FRAGMENTS); raise MTU or shrink payload"
            )
        }
        // Message ID of the *original* packet, per PROTOCOL.md §5. The reassembler
        // keys on this, so the receiver can tell which original it is completing
        // even before the reassembled bytes decode.
        val originalMessageId = MessageIds.compute(originalPacket)
        return (0 until total).map { index ->
            val from = index * chunkSize
            val to = minOf(from + chunkSize, encodedBytes.size)
            val chunkPayload = ByteArray(FRAGMENT_PREFIX_BYTES + (to - from))
            System.arraycopy(originalMessageId, 0, chunkPayload, 0, 32)
            chunkPayload[32] = ((index shr 8) and 0xFF).toByte()
            chunkPayload[33] = (index and 0xFF).toByte()
            chunkPayload[34] = ((total shr 8) and 0xFF).toByte()
            chunkPayload[35] = (total and 0xFF).toByte()
            System.arraycopy(encodedBytes, from, chunkPayload, FRAGMENT_PREFIX_BYTES, to - from)
            MeshPacket(
                version = PacketCodec.PROTOCOL_VERSION,
                type = PacketType.FRAGMENT,
                ttl = originalPacket.ttl,
                // Fragments are unsigned; HAS_RECIPIENT mirrors the original packet.
                flags = if (!originalPacket.recipientId.contentEquals(PacketCodec.BROADCAST_ID)) 0x01 else 0,
                timestampMs = originalPacket.timestampMs,
                payload = chunkPayload,
                senderId = originalPacket.senderId,
                recipientId = originalPacket.recipientId,
                signature = null, // fragments are unsigned; the reassembled packet is verified
            )
        }
    }

    /** Parse a `FRAGMENT` payload header. Returns (messageId, index, total, chunk). */
    fun parseFragmentPayload(payload: ByteArray): FragmentParts {
        if (payload.size <= FRAGMENT_PREFIX_BYTES) {
            throw PacketException.InvalidPacket("fragment payload too small: ${payload.size} bytes")
        }
        val messageId = payload.copyOfRange(0, 32)
        val index = ((payload[32].toInt() and 0xFF) shl 8) or (payload[33].toInt() and 0xFF)
        val total = ((payload[34].toInt() and 0xFF) shl 8) or (payload[35].toInt() and 0xFF)
        if (total <= 1) throw PacketException.InvalidPacket("fragment total must be > 1 (got $total)")
        if (total > MAX_FRAGMENTS) throw PacketException.InvalidPacket("fragment total $total exceeds max $MAX_FRAGMENTS")
        if (index >= total) throw PacketException.InvalidPacket("fragment index $index >= total $total")
        return FragmentParts(messageId, index, total, payload.copyOfRange(FRAGMENT_PREFIX_BYTES, payload.size))
    }
}

/** Parsed view of a `FRAGMENT` packet's payload. */
data class FragmentParts(
    val messageId: ByteArray,
    val index: Int,
    val total: Int,
    val chunk: ByteArray,
)

/**
 * Stateful, bounded reassembly (PROTOCOL.md §5).
 *
 * @param timeoutMs partial reassemblies older than this are discarded (default 30 s).
 * @param maxConcurrent max simultaneous incomplete messages (default 8).
 * @param maxBytes max bytes held per reassembly (default 64 KiB).
 */
class Reassembler(
    private val timeoutMs: Long = 30_000L,
    private val maxConcurrent: Int = 8,
    private val maxBytes: Int = 64 * 1024,
) {
    private class Buffer(
        val chunks: Array<ByteArray?>,
        val total: Int,
        var received: Int,
        val startedAtMs: Long,
        var assembledBytes: Int,
    )

    // LinkedHashMap so eviction of the oldest buffer is O(1) and deterministic in tests.
    private val buffers = LinkedHashMap<String, Buffer>()

    /** Offer one fragment. Returns the reassembled packet bytes when complete, else null. */
    @Synchronized
    fun offer(fragment: MeshPacket, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        require(fragment.type == PacketType.FRAGMENT) { "Reassembler only accepts FRAGMENT packets" }
        val parts = Fragmentation.parseFragmentPayload(fragment.payload)
        val key = parts.messageId.joinToString("") { "%02x".format(it) }

        // Evict expired buffers first (bounded state is the point).
        evictExpired(nowMs)
        val existing = buffers[key]
        val buffer = if (existing != null) {
            if (existing.total != parts.total) {
                throw PacketException.InvalidPacket("fragment total changed mid-message")
            }
            existing
        } else {
            if (buffers.size >= maxConcurrent) {
                // Oldest buffer is evicted to make room; the caller will simply miss that message.
                val oldest = buffers.keys.first()
                buffers.remove(oldest)
            }
            Buffer(Array(parts.total) { null }, parts.total, 0, nowMs, 0).also { buffers[key] = it }
        }

        if (buffer.chunks[parts.index] != null) return null // duplicate fragment: ignore
        if (buffer.assembledBytes + parts.chunk.size > maxBytes) {
            buffers.remove(key)
            throw PacketException.InvalidPacket("reassembly exceeds $maxBytes bytes")
        }
        buffer.chunks[parts.index] = parts.chunk.copyOf()
        buffer.assembledBytes += parts.chunk.size
        buffer.received += 1

        if (buffer.received < buffer.total) return null

        // Complete: concatenate in index order and hand the bytes back for decode().
        val partsInOrder = buffer.chunks.requireNoNulls()
        val assembled = ByteArray(buffer.assembledBytes)
        var offset = 0
        for (chunk in partsInOrder) {
            System.arraycopy(chunk, 0, assembled, offset, chunk.size)
            offset += chunk.size
        }
        buffers.remove(key)
        return assembled
    }

    /** True if a reassembly for [messageId] is in flight (completed ones are removed). */
    @Synchronized
    fun isInFlight(messageId: ByteArray): Boolean =
        buffers.containsKey(messageId.joinToString("") { "%02x".format(it) })

    /** Number of incomplete reassemblies currently held (for tests / diagnostics). */
    @Synchronized
    fun inFlightCount(): Int = buffers.size

    private fun evictExpired(nowMs: Long) {
        val expired = buffers.entries.filter { nowMs - it.value.startedAtMs > timeoutMs }.map { it.key }
        expired.forEach { buffers.remove(it) }
    }
}
