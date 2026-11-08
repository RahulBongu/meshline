package com.meshline.app.protocol

/**
 * Flag bits inside the header's flags byte. See PROTOCOL.md §3.
 *
 * MVP policy (fail-closed):
 *  - [HAS_RECIPIENT] meaningful; broadcast when clear.
 *  - [HAS_SIGNATURE] trailing 64-byte Ed25519 signature present.
 *  - [COMPRESSED] and [FRAGMENTED] are RESERVED in v1: encoder never sets them,
 *    decoder rejects them as PacketException.UnsupportedFlag (the packet is
 *    dropped, never relayed, never delivered).
 *  - Bits 4..7 (reserved) must be zero on the wire; the decoder rejects them
 *    as InvalidPacket so a future version can claim them safely.
 *
 * Values are literal Byte constants (no toByte()/or in const initialisers).
 */
object PacketFlags {
    const val HAS_RECIPIENT: Byte = 0x01
    const val HAS_SIGNATURE: Byte = 0x02
    const val COMPRESSED: Byte = 0x04
    const val FRAGMENTED: Byte = 0x08

    /** Mask of bits 4..7 that must always be zero in v1 (0x70). */
    const val RESERVED_MASK: Byte = 0x70

    /** Bits that v1 refuses (COMPRESSED | FRAGMENTED = 0x0C). */
    const val UNSUPPORTED_MASK: Byte = 0x0C
}
