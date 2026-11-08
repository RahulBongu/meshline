package com.meshline.app.transport

import com.meshline.app.protocol.MeshPacket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow

/**
 * Transport abstraction (PRD §9): the router talks only to this interface,
 * never to BLE APIs directly, so Phase B can run the whole mesh on an
 * in-memory simulator and Phase C can swap in the real BLE implementation
 * without touching routing logic.
 *
 * Phase A: interface only. Phase B adds [SimulatorTransport]; Phase C adds
 * `BleTransport`. Packets flowing through here are already *encoded fragment
 * chunks* — implementations MUST treat them as opaque and must never assume a
 * single GATT write equals a single packet (fragmentation lives in the codec).
 */
interface Transport {
    /** Unique local node id (the same 8-byte id used as senderId). */
    val localId: ByteArray

    /** Outgoing encoded packets (after fragmentation). Suspends until queued. */
    suspend fun sendTo(peer: ByteArray, packet: ByteArray)

    /** Broadcast to every directly connected peer. */
    suspend fun broadcast(packet: ByteArray)

    /** Incoming encoded packets, tagged with the immediate neighbour they arrived from. */
    val incoming: Flow<Pair<ByteArray, ByteArray>>

    /** Currently connected neighbour ids. */
    val neighbours: Flow<Set<ByteArray>>

    suspend fun connect(peer: ByteArray)
    suspend fun disconnect(peer: ByteArray)
    fun stop()

    companion object {
        /** Buffer policy used by every implementation: drop-oldest, never block the caller. */
        val OVERFLOW = BufferOverflow.DROP_OLDEST
    }
}
