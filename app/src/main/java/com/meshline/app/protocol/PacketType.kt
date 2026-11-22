package com.meshline.app.protocol

/**
 * Wire codes for packet types. See PROTOCOL.md §2.
 *
 * Unknown codes (0x00, 0x0B..0xFF) decode to `null` via [fromCode] so the codec
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
    HANDSHAKE(0x08),
    GROUP_MESSAGE(0x09),
    GROUP_CONTROL(0x0A),
    REACTION(0x0B),
    TYPING(0x0C),
    GEO_PING(0x0D);

    companion object {
        /** Returns null for reserved/unknown codes — never throws. */
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}
