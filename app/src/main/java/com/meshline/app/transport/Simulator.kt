package com.meshline.app.transport

import com.meshline.app.protocol.MeshPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * In-memory mesh simulator (Phase B). The same codec + router run here as on
 * real BLE, so the 30-node flood test exercises real flooding/dedupe/TTL logic
 * without a radio.
 *
 * Fault injection is first-class: [SimulatorConfig] controls
 *  - **loss**      — each link transmission drops with probability [SimulatorConfig.lossRate]
 *  - **latency**   — each delivered copy is delayed by a random 0..[SimulatorConfig.latencyMaxMs]
 *  - **duplicate** — each delivered copy is delivered again with probability
 *                    [SimulatorConfig.duplicateRate] (BLE retransmits / SACK-like
 *                    doubles in the wild). Extra copies must be absorbed by dedupe.
 *
 * Determinism: a single seeded [Random] drives every draw, so a test that sets
 * a seed and replays the same sends sees the same loss/latency/duplicate
 * pattern. Tests that need zero faults set all three to 0.
 */
data class SimulatorConfig(
    val lossRate: Double = 0.0,
    val latencyMinMs: Long = 0,
    val latencyMaxMs: Long = 0,
    val duplicateRate: Double = 0.0,
    val seed: Long = 4242L,
) {
    init {
        require(lossRate in 0.0..1.0) { "lossRate 0..1" }
        require(duplicateRate in 0.0..1.0) { "duplicateRate 0..1" }
        require(latencyMinMs >= 0 && latencyMaxMs >= 0 && latencyMaxMs >= latencyMinMs) { "latency range" }
    }

    fun drawLatency(random: Random): Long = when {
        latencyMaxMs == 0L -> 0L
        else -> latencyMinMs + random.nextLong(latencyMaxMs - latencyMinMs + 1)
    }
}

class SimulatorStats {
    @Volatile var transmissions: Int = 0
    @Volatile var delivered: Int = 0
    @Volatile var lost: Int = 0
    @Volatile var duplicated: Int = 0
}

/**
 * Shared virtual network: adjacency + transport registry. Owns the fault-injection
 * draws so every link observes the same seeded stream in tests.
 */
class VirtualNetwork(
    adjacency: Map<String, Set<String>>, // hexId -> hex neighbour ids
    val config: SimulatorConfig = SimulatorConfig(),
) {
    private val transports = mutableMapOf<String, SimulatorTransport>()
    val stats = SimulatorStats()
    val random = Random(config.seed)

    private val adjacency: Map<String, Set<String>> = adjacency.entries.associate { (k, v) ->
        k.lowercase() to v.map { it.lowercase() }.toSet()
    }

    fun nodeIdsHex(): Set<String> = adjacency.keys

    fun neighboursHex(idHex: String): Set<String> = adjacency[idHex.lowercase()] ?: emptySet()

    fun neighboursOf(id: ByteArray): Set<ByteArray> =
        neighboursHex(key(id)).mapTo(mutableSetOf()) { hexStringToBytes(it) }

    private fun hexStringToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun register(t: SimulatorTransport) {
        transports[key(t.localId)] = t
    }

    fun lookup(id: ByteArray): SimulatorTransport? = transports[key(id)]

    /** Schedule delivery of a copy: apply latency, then emit to the recipient's incoming flow. */
    fun scheduleDelivery(from: ByteArray, to: ByteArray, bytes: ByteArray, scope: CoroutineScope) {
        val target = lookup(to) ?: return // destination left the mesh
        val latency = config.drawLatency(random)
        scope.launch {
            if (latency > 0) delay(latency)
            stats.delivered++
            target.emitIncoming(from, bytes.copyOf())
        }
    }

    fun key(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

    companion object {
        fun hex(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

        /** 8-byte id derived from an integer so tests can write ids readably: idOf(3). */
        fun idOf(n: Int): ByteArray {
            val b = ByteArray(8)
            b[6] = ((n shr 8) and 0xFF).toByte()
            b[7] = (n and 0xFF).toByte()
            return b
        }

        fun fullyConnected(n: Int, config: SimulatorConfig = SimulatorConfig()): VirtualNetwork {
            val ids = (0 until n).map { idOf(it) }
            val map = ids.associate { id ->
                hex(id) to ids.filter { !it.contentEquals(id) }.map { hex(it) }.toSet()
            }
            return VirtualNetwork(map, config)
        }

        fun line(n: Int, config: SimulatorConfig = SimulatorConfig()): VirtualNetwork {
            val ids = (0 until n).map { idOf(it) }
            val map = mutableMapOf<String, Set<String>>()
            ids.forEachIndexed { idx, id ->
                val neigh = mutableSetOf<String>()
                if (idx > 0) neigh += hex(ids[idx - 1])
                if (idx < n - 1) neigh += hex(ids[idx + 1])
                map[hex(id)] = neigh
            }
            return VirtualNetwork(map, config)
        }

        fun grid(rows: Int, cols: Int, config: SimulatorConfig = SimulatorConfig()): VirtualNetwork {
            val map = mutableMapOf<String, Set<String>>()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    val neigh = mutableSetOf<String>()
                    if (r > 0) neigh += hex(idOf((r - 1) * cols + c))
                    if (r < rows - 1) neigh += hex(idOf((r + 1) * cols + c))
                    if (c > 0) neigh += hex(idOf(r * cols + c - 1))
                    if (c < cols - 1) neigh += hex(idOf(r * cols + c + 1))
                    map[hex(idOf(idx))] = neigh
                }
            }
            return VirtualNetwork(map, config)
        }

        /**
         * Random **connected** topology with average degree ≈ [targetDegree]
         * (deterministic per [seed]). A seeded spanning tree guarantees
         * connectivity; random extra edges then raise every degree to at least
         * [targetDegree] (actual degree can exceed it — edges are symmetrical).
         * Used for the 20-seed storm tests; see MeshSimulatorTest.
         */
        fun randomConnected(
            n: Int,
            targetDegree: Int = 6,
            seed: Long = 1L,
            config: SimulatorConfig = SimulatorConfig(),
        ): VirtualNetwork {
            require(n > 0 && targetDegree in 1 until n) { "invalid random topology" }
            val rng = Random(seed)
            val ids = (0 until n).map { idOf(it) }
            // Adjacency keyed by node index (0..<n) so link() types infer.
            val adj: Map<Int, MutableSet<Int>> = (0 until n).associateWith { mutableSetOf<Int>() }.toMutableMap()
            fun link(a: Int, b: Int) {
                if (a == b) return
                adj.getValue(a).add(b)
                adj.getValue(b).add(a)
            }
            // Seeded spanning tree: each new node links to one random earlier node.
            for (i in 1 until n) link(i, rng.nextInt(i))
            // Raise minimum degree: add random edges until every node has targetDegree.
            var guard = 0
            while (adj.values.any { it.size < targetDegree } && guard++ < 100_000) {
                val candidatesU = (0 until n).filter { adj.getValue(it).size < targetDegree }
                val u = candidatesU[rng.nextInt(candidatesU.size)]
                val candidatesV = (0 until n).filter { it != u && it !in adj.getValue(u) }
                val v = candidatesV[rng.nextInt(candidatesV.size)]
                link(u, v)
            }
            val map = ids.withIndex().associate { (idx, id) ->
                hex(id) to adj.getValue(idx).map { hex(ids[it]) }.toSet()
            }
            return VirtualNetwork(map, config)
        }
    }
}

/** One virtual node's transport. Routes through [VirtualNetwork] with fault injection. */
class SimulatorTransport(
    override val localId: ByteArray,
    private val network: VirtualNetwork,
    private val scope: CoroutineScope,
) : Transport {

    /** Single source of truth for faults now lives on the network (same seeded stream). */
    val config get() = network.config

    private val _incoming = MutableSharedFlow<Pair<ByteArray, ByteArray>>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<Pair<ByteArray, ByteArray>> = _incoming.asSharedFlow()

    private val _neighbours = MutableStateFlow<Set<ByteArray>>(emptySet())
    override val neighbours: Flow<Set<ByteArray>> = _neighbours.asStateFlow()

    init {
        refreshNeighbours()
        network.register(this)
    }

    private fun refreshNeighbours() {
        _neighbours.value = network.neighboursHex(VirtualNetwork.hex(localId))
            .map { hexToBytes(it) }.toSet()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    override suspend fun sendTo(peer: ByteArray, packet: ByteArray) {
        network.stats.transmissions++
        if (network.random.nextDouble() < config.lossRate) {
            network.stats.lost++
            return
        }
        network.scheduleDelivery(localId, peer, packet, scope)
        if (network.random.nextDouble() < config.duplicateRate) {
            network.stats.duplicated++
            // Duplicate copy on a slightly later slot (delays reorder if a test wants it).
            network.scheduleDelivery(localId, peer, packet, scope)
        }
    }

    override suspend fun broadcast(packet: ByteArray) {
        for (n in _neighbours.value) sendTo(n, packet)
    }

    internal fun emitIncoming(from: ByteArray, bytes: ByteArray) {
        _incoming.tryEmit(from to bytes)
    }

    override suspend fun connect(peer: ByteArray) = refreshNeighbours()
    override suspend fun disconnect(peer: ByteArray) = refreshNeighbours()
    override fun stop() { /* nothing to tear down in the simulator */ }
}
