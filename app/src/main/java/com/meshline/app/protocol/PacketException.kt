package com.meshline.app.protocol

/**
 * Every decode failure is one of these. All of them share one property the router
 * relies on: **drop the packet, do not relay it, do not crash.**
 *
 *  - [UnsupportedVersion]  -> hard error (do not relay, PROTOCOL.md §2)
 *  - [InvalidPacket]       -> malformed / out-of-window / reserved flag (drop)
 *  - [UnknownType]         -> unknown type code (soft error, drop quietly)
 *  - [UnsupportedFlag]     -> COMPRESSED or FRAGMENTED set (drop, PROTOCOL.md §3)
 */
sealed class PacketException(message: String) : Exception(message) {
    class UnsupportedVersion(val version: Int) :
        PacketException("Unsupported version: $version (expected ${PacketCodec.PROTOCOL_VERSION})")

    class InvalidPacket(message: String) : PacketException(message)

    class UnknownType(val code: Int) : PacketException("Unknown packet type code: $code")

    class UnsupportedFlag(val flag: Byte) :
        PacketException("Unsupported flag byte: 0x%02X".format(flag))
}
