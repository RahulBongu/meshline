package com.meshline.app.protocol

/**
 * Wire codes for packet types. See PROTOCOL.md §2.
 *
 * Unknown codes (0x00, 0x09..0xFF) decode to `null` via [fromCode] so the codec
 * can drop them softly without crashing (forward compatibility).
 */
enum class PacketType(val code: Byte) {
    ANNOUNCE(0x01),
    PUBLIC_MESSAGE(0x02),
    PRIVATE_MESSAGE(0x03),
    CHANNEL_MESSAGE(0x04),
    RECEIPT(0x05),
    FRAGMENT(0x06),
    LEAVE(0x07),
    HANDSHAKE(0x08);

    companion object {
        /** Returns null for reserved/unknown codes — never throws. */
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}
