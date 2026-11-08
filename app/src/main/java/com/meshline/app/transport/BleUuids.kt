package com.meshline.app.transport

import java.util.UUID

/**
 * Frozen BLE wire constants (Phase C).
 *
 * Both UUIDs were generated once as random v4 UUIDs and are now hard-coded
 * here for every Meshline install (both platforms, for interop). Changing
 * either UUID is a wire break — it needs a protocol-version bump and cannot
 * be changed casually. The service UUID is advertised; peers are identified
 * by the ANNOUNCE packet after connecting, never by advertisement bytes
 * (PROTOCOL.md §9).
 */
object BleUuids {
    /** Meshline service UUID (random v4, frozen 2026-11-08). */
    val SERVICE_UUID: UUID = UUID.fromString("a777356e-7ebc-429e-a847-d52eaeeb1331")

    /** Single characteristic: Write Without Response + Notify (random v4, frozen). */
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("cf602b5c-31b5-4b91-aa65-9272bd79cd3f")

    /** Requested MTU on every connection; effective payload = MTU - 3. */
    const val REQUESTED_MTU: Int = 512
    const val MAX_PACKET_PER_WRITE: Int = REQUESTED_MTU - 3

    /** Starting concurrency cap (configurable). */
    const val MAX_CONNECTIONS: Int = 6
}
