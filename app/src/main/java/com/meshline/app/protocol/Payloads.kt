package com.meshline.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payload shapes for the MVP packet types (PROTOCOL.md §6).
 *
 * These are pure data codecs (no crypto, no Android): encode/decode the *shape*,
 * callers fill in the semantics. Announce carries public keys (not secrets);
 * signature creation/verification is the SecurityModule's Phase D job, so a
 * decoded Announce must still be treated as untrusted display data until then.
 */
object Payloads {

    const val MAX_NICKNAME_CHARS: Int = 32
    const val MAX_PUBLIC_TEXT_CHARS: Int = 500
    const val PUBLIC_KEY_BYTES: Int = 32

    data class Announce(
        val nickname: String,
        val ed25519PublicKey: ByteArray,
        val x25519PublicKey: ByteArray,
        val capabilities: Byte,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Announce) return false
            return nickname == other.nickname &&
                ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
                x25519PublicKey.contentEquals(other.x25519PublicKey) &&
                capabilities == other.capabilities
        }

        override fun hashCode(): Int {
            var h = nickname.hashCode()
            h = 31 * h + ed25519PublicKey.contentHashCode()
            h = 31 * h + x25519PublicKey.contentHashCode()
            h = 31 * h + capabilities.toInt()
            return h
        }
    }

    data class Receipt(val kind: Byte, val messageId: ByteArray) {
        companion object {
            const val KIND_DELIVERED: Byte = 1
            const val KIND_READ: Byte = 2
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Receipt) return false
            return kind == other.kind && messageId.contentEquals(other.messageId)
        }

        override fun hashCode(): Int = 31 * kind.toInt() + messageId.contentHashCode()
    }

    // ---- ANNOUNCE (§6.1) ----
    // nicknameLen(1) | nickname UTF-8 | ed25519Pk(32) | x25519Pk(32) | capabilities(1)

    fun encodeAnnounce(announce: Announce): ByteArray {
        val nickBytes = sanitizeNickname(announce.nickname).toByteArray(Charsets.UTF_8)
        require(nickBytes.isNotEmpty() && nickBytes.size <= 255) { "nickname must be 1..255 UTF-8 bytes" }
        require(announce.ed25519PublicKey.size == PUBLIC_KEY_BYTES) { "ed25519 public key must be 32 bytes" }
        require(announce.x25519PublicKey.size == PUBLIC_KEY_BYTES) { "x25519 public key must be 32 bytes" }
        val buf = ByteBuffer.allocate(1 + nickBytes.size + PUBLIC_KEY_BYTES * 2 + 1).order(ByteOrder.BIG_ENDIAN)
        buf.put(nickBytes.size.toByte())
        buf.put(nickBytes)
        buf.put(announce.ed25519PublicKey)
        buf.put(announce.x25519PublicKey)
        buf.put(announce.capabilities)
        return buf.array()
    }

    fun decodeAnnounce(payload: ByteArray): Announce {
        if (payload.isEmpty()) throw PacketException.InvalidPacket("announce payload empty")
        val len = payload[0].toInt() and 0xFF
        val expected = 1 + len + PUBLIC_KEY_BYTES * 2 + 1
        if (payload.size != expected) {
            throw PacketException.InvalidPacket("announce length mismatch: expected $expected, got ${payload.size}")
        }
        if (len == 0) throw PacketException.InvalidPacket("announce nickname empty")
        val nickname = payload.copyOfRange(1, 1 + len).toString(Charsets.UTF_8)
        val ed = payload.copyOfRange(1 + len, 1 + len + PUBLIC_KEY_BYTES)
        val x = payload.copyOfRange(1 + len + PUBLIC_KEY_BYTES, 1 + len + PUBLIC_KEY_BYTES * 2)
        val caps = payload[1 + len + PUBLIC_KEY_BYTES * 2]
        return Announce(sanitizeNickname(nickname), ed, x, caps)
    }

    // ---- PUBLIC_MESSAGE (§6.2) ----

    /** Validates length, returns UTF-8 bytes. Enforces the 500-char contract. */
    fun encodePublicText(text: String): ByteArray {
        if (text.isEmpty()) throw PacketException.InvalidPacket("public text must not be empty")
        if (text.length > MAX_PUBLIC_TEXT_CHARS) {
            throw PacketException.InvalidPacket("public text ${text.length} chars exceeds max $MAX_PUBLIC_TEXT_CHARS")
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    fun decodePublicText(payload: ByteArray): String {
        if (payload.isEmpty()) throw PacketException.InvalidPacket("public payload empty")
        val text = payload.toString(Charsets.UTF_8)
        if (text.length > MAX_PUBLIC_TEXT_CHARS) {
            throw PacketException.InvalidPacket("public text exceeds max $MAX_PUBLIC_TEXT_CHARS chars")
        }
        return text
    }

    // ---- RECEIPT (§6.4) ----
    // receiptKind(1) | messageId(32)

    fun encodeReceipt(receipt: Receipt): ByteArray {
        require(receipt.messageId.size == 32) { "receipt messageId must be 32 bytes" }
        require(receipt.kind == Receipt.KIND_DELIVERED || receipt.kind == Receipt.KIND_READ) {
            "receipt kind must be DELIVERED(1) or READ(2)"
        }
        return byteArrayOf(receipt.kind) + receipt.messageId
    }

    fun decodeReceipt(payload: ByteArray): Receipt {
        if (payload.size != 33) throw PacketException.InvalidPacket("receipt payload must be 33 bytes, got ${payload.size}")
        val kind = payload[0]
        if (kind != Receipt.KIND_DELIVERED && kind != Receipt.KIND_READ) {
            throw PacketException.InvalidPacket("bad receipt kind: $kind")
        }
        return Receipt(kind, payload.copyOfRange(1, 33))
    }

    // ---- LEAVE (§6.5) ----

    fun encodeLeave(senderId: ByteArray): ByteArray {
        require(senderId.size == MeshPacket.ID_BYTES) { "leave senderId must be 8 bytes" }
        return senderId.copyOf()
    }

    fun decodeLeave(payload: ByteArray): ByteArray {
        if (payload.size != MeshPacket.ID_BYTES) throw PacketException.InvalidPacket("leave payload must be 8 bytes")
        return payload.copyOf()
    }

    /**
     * Nickname sanitisation (display-only): trim, collapse internal whitespace,
     * drop ISO control characters (they can spoof alignment / inject newlines).
     * Enforced on both encode and decode so the wire form is always canonical.
     */
    fun sanitizeNickname(raw: String): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ").filterNot { it.isISOControl() }
        if (cleaned.isEmpty()) throw PacketException.InvalidPacket("nickname empty after sanitisation")
        if (cleaned.length > MAX_NICKNAME_CHARS) {
            throw PacketException.InvalidPacket("nickname ${cleaned.length} chars exceeds max $MAX_NICKNAME_CHARS")
        }
        return cleaned
    }

    /** Capability bits for [Announce.capabilities] (PROTOCOL.md §6.1). */
    object Capabilities {
        const val RELAY_ENABLED: Byte = 0x01
        const val SUPPORTS_NOISE: Byte = 0x02
        const val SUPPORTS_CHANNELS: Byte = 0x04
    }
}
