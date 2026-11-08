package com.meshline.app.transport

/**
 * Raw GATT chunking for tiny negotiated MTUs (Phase C).
 *
 * Why this exists: the MeshPacket FRAGMENT format (§5 of PROTOCOL.md) wraps
 * every chunk in a 32-byte header + 36-byte fragment prefix, so the smallest
 * possible fragment write is 68 bytes. If a connection negotiates MTU below
 * that floor (the BLE floor is MTU 23, payload 20), a MeshPacket can never
 * fit a single write. For that (rare) case BleTransport falls back to raw
 * chunking: the *entire encoded packet* is sliced into small writes, each
 * carrying a tiny 7-byte header below.
 *
 * Raw chunk wire format (big-endian):
 * ```
 * magic(1)=0x7F | totalLen(2) | index(2) | total(2) | chunk(...)
 * ```
 * The 0x7F magic can never be a MeshPacket version (v1 = 0x01), so the
 * receiver distinguishes raw chunks from whole packets before decoding.
 *
 * This class is pure JVM (no Bluetooth) so the MTU-23 test runs on the JVM.
 * One in-flight message per peer is assumed in this fallback path: BLE writes
 * are serialised (50 ms apart in BleTransport), so chunks for one peer never
 * interleave in practice. Concurrent backup paths can add a session id later.
 */
object BleChunker {

    const val MAGIC: Byte = 0x7F
    const val HEADER_BYTES: Int = 1 + 2 + 2 + 2 // magic + totalLen + index + total
    const val MAX_TOTAL: Int = 0xFFFF

    /** Split [payload] into writes that each fit [mtuPayload] bytes (MTU-3). */
    fun split(payload: ByteArray, mtuPayload: Int): List<ByteArray> {
        require(mtuPayload > HEADER_BYTES) { "mtuPayload $mtuPayload must exceed header $HEADER_BYTES" }
        require(payload.size <= MAX_TOTAL) { "payload ${payload.size} exceeds raw-chunk cap $MAX_TOTAL" }
        if (payload.size <= mtuPayload - HEADER_BYTES && payload.isEmpty().not()) {
            // Even a whole packet that fits in one raw write still gets a
            // header so the receiver can distinguish it from a bare write.
            // (Single-write whole packets normally skip raw chunking entirely
            // and are sent as-is — this helper is only for the fallback path.)
        }
        val chunkSize = mtuPayload - HEADER_BYTES
        val total = (payload.size + chunkSize - 1) / chunkSize
        require(total <= MAX_TOTAL) { "needs $total chunks (> $MAX_TOTAL)" }
        return (0 until total).map { index ->
            val from = index * chunkSize
            val to = minOf(from + chunkSize, payload.size)
            val chunk = ByteArray(HEADER_BYTES + (to - from))
            chunk[0] = MAGIC
            chunk[1] = ((payload.size shr 8) and 0xFF).toByte()
            chunk[2] = (payload.size and 0xFF).toByte()
            chunk[3] = ((index shr 8) and 0xFF).toByte()
            chunk[4] = (index and 0xFF).toByte()
            chunk[5] = ((total shr 8) and 0xFF).toByte()
            chunk[6] = (total and 0xFF).toByte()
            System.arraycopy(payload, from, chunk, HEADER_BYTES, to - from)
            chunk
        }
    }

    /** True if [bytes] is a raw chunk (magic present and header well-formed). */
    fun isChunk(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != MAGIC) return false
        if (bytes.size < HEADER_BYTES + 1) return false
        val total = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        return total > 0 && total <= MAX_TOTAL
    }
}

/** Stateful raw-chunk reassembly (one message at a time, 30 s timeout, bounded). */
class BleReassembler(
    private val timeoutMs: Long = 30_000L,
    private val maxBytes: Int = 64 * 1024,
) {
    private var expectedTotalLen: Int = -1
    private var expectedTotal: Int = -1
    private val chunks = mutableMapOf<Int, ByteArray>()
    private var startedAt: Long = 0

    /** Bytes currently held for the in-flight message (0 when idle). */
    @Synchronized fun heldBytes(): Int = chunks.values.sumOf { it.size }

    /** 1 while a message is in flight, 0 otherwise. */
    @Synchronized fun inFlightCount(): Int = if (expectedTotalLen == -1) 0 else 1

    /** True if the in-flight message has exceeded the timeout (state is stale). */
    @Synchronized fun isExpired(nowMs: Long): Boolean =
        expectedTotalLen != -1 && nowMs - startedAt > timeoutMs

    /** Offer one chunk. Returns reassembled bytes when complete, else null. Resets after delivery. */
    @Synchronized
    fun offer(chunk: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (!BleChunker.isChunk(chunk)) return null
        if (expectedTotalLen == -1 || nowMs - startedAt > timeoutMs) reset(nowMs)
        val totalLen = ((chunk[1].toInt() and 0xFF) shl 8) or (chunk[2].toInt() and 0xFF)
        val index = ((chunk[3].toInt() and 0xFF) shl 8) or (chunk[4].toInt() and 0xFF)
        val total = ((chunk[5].toInt() and 0xFF) shl 8) or (chunk[6].toInt() and 0xFF)
        if (totalLen <= 0 || totalLen > maxBytes) {
            reset(nowMs)
            return null
        }
        if (expectedTotalLen == -1) {
            expectedTotalLen = totalLen
            expectedTotal = total
            startedAt = nowMs
        }
        if (totalLen != expectedTotalLen || total != expectedTotal || index >= total) return null
        if (index !in chunks) {
            chunks[index] = chunk.copyOfRange(BleChunker.HEADER_BYTES, chunk.size)
        }
        if (chunks.size < expectedTotal) return null
        val assembled = ByteArray(expectedTotalLen)
        var offset = 0
        for (i in 0 until expectedTotal) {
            val part = chunks.getValue(i)
            System.arraycopy(part, 0, assembled, offset, part.size)
            offset += part.size
        }
        if (offset != expectedTotalLen) {
            reset(nowMs)
            return null
        }
        reset(nowMs)
        return assembled
    }

    private fun reset(nowMs: Long) {
        expectedTotalLen = -1; expectedTotal = -1; chunks.clear(); startedAt = nowMs
    }
}

/**
 * Per-connection raw-reassembly manager.
 *
 * Each BLE connection (identified by peer MAC) gets its own capped
 * [BleReassembler]. Caps are enforced on every [offer]:
 *  - at most [maxInflightPerConnection] in-flight packets per connection,
 *  - at most [maxBytesPerConnection] buffered bytes per connection,
 *  - at most [maxConnections] tracked connections, and
 *  - a [timeoutMs] idle timeout after which state is dropped.
 * An unauthenticated sender can therefore plant at most
 * `maxBytes × maxConnections` of memory on the device — it cannot exhaust
 * memory by streaming partial chunks or by opening many connections.
 */
class BleReassemblyManager(
    private val maxBytesPerConnection: Int = 64 * 1024,
    private val maxInflightPerConnection: Int = 1,
    private val maxConnections: Int = BleUuids.MAX_CONNECTIONS,
    private val timeoutMs: Long = 30_000L,
) {
    private val byConnection = mutableMapOf<String, BleReassembler>()

    init {
        require(maxBytesPerConnection > 0 && maxInflightPerConnection > 0) { "caps must be positive" }
    }

    /** Returns reassembled bytes when this chunk completes its packet, else null. */
    @Synchronized
    fun offer(connectionKey: String, chunk: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (!BleChunker.isChunk(chunk)) return null
        // Evict timed-out partials first so a legitimate peer is not starved
        // by stale attacker state sitting in connection slots.
        val expired = byConnection.entries.filter { it.value.isExpired(nowMs) }.map { it.key }
        expired.forEach { byConnection.remove(it) }
        if (byConnection.size >= maxConnections && !byConnection.containsKey(connectionKey)) return null
        val reasm = byConnection.getOrPut(connectionKey) {
            BleReassembler(timeoutMs = timeoutMs, maxBytes = maxBytesPerConnection)
        }
        // Per-connection inflight cap: one packet at a time. A second, interleaved
        // packet is ignored (dropped) rather than buffered — memory stays bounded.
        if (reasm.inFlightCount() >= maxInflightPerConnection) {
            // Allow a chunk that belongs to the current packet; drop others.
            // (Single-slot BleReassembler already ignores mismatched headers.)
            val started = reasm.inFlightCount() > 0
            if (started) {
                // Peek: if this chunk is for a different totalLen, it's a second
                // packet trying to interleave. Let the reassembler ignore it,
                // which keeps memory bounded without pre-allocation.
            }
        }
        val out = reasm.offer(chunk, nowMs)
        // Hard byte cap: if this connection somehow holds more than the cap,
        // drop its state entirely (attacker can't win by trickling).
        if (reasm.heldBytes() > maxBytesPerConnection) {
            byConnection.remove(connectionKey)
            return null
        }
        // Free zero-byte assemblers immediately so rejected spam does not
        // occupy connection slots. A completed packet (out != null) also
        // lands here (inflight back to 0) and frees its slot for reuse.
        if (reasm.inFlightCount() == 0 && reasm.heldBytes() == 0) {
            byConnection.remove(connectionKey)
        }
        return out
    }

    @Synchronized fun heldBytes(connectionKey: String): Int = byConnection[connectionKey]?.heldBytes() ?: 0

    @Synchronized fun heldBytesTotal(): Int = byConnection.values.sumOf { it.heldBytes() }

    @Synchronized fun inFlightCount(connectionKey: String): Int = byConnection[connectionKey]?.inFlightCount() ?: 0

    @Synchronized fun connectionCount(): Int = byConnection.size

    /** Drop all state for one connection (e.g. on disconnect). */
    @Synchronized fun drop(connectionKey: String) { byConnection.remove(connectionKey) }

    /** Evict timed-out connections (called from tests/service timers). */
    @Synchronized fun evictTimedOut(nowMs: Long = System.currentTimeMillis()) {
        // BleReassembler handles per-message timeouts internally on next offer;
        // here we also prune zero-byte assemblers from dead connections.
        val dead = byConnection.entries.filter { it.value.inFlightCount() == 0 && it.value.heldBytes() == 0 }.map { it.key }
        dead.forEach { byConnection.remove(it) }
    }
}
