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
        val avatarIndex: Byte = 0xFF.toByte(),
        val intervalUnits: Byte = 6,
    ) {
        init {
            require(intervalUnits.toInt() in listOf(3, 6, 12)) {
                "intervalUnits must be one of {3,6,12} (got ${intervalUnits.toInt()})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Announce) return false
            return nickname == other.nickname &&
                ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
                x25519PublicKey.contentEquals(other.x25519PublicKey) &&
                capabilities == other.capabilities &&
                avatarIndex == other.avatarIndex &&
                intervalUnits == other.intervalUnits
        }

        override fun hashCode(): Int {
            var h = nickname.hashCode()
            h = 31 * h + ed25519PublicKey.contentHashCode()
            h = 31 * h + x25519PublicKey.contentHashCode()
            h = 31 * h + capabilities.toInt()
            h = 31 * h + avatarIndex.toInt()
            h = 31 * h + intervalUnits.toInt()
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
    // nicknameLen(1) | nickname UTF-8 | ed25519Pk(32) | x25519Pk(32) | capabilities(1) | avatarIndex(1) | intervalUnits(1)
    // D1: avatarIndex + intervalUnits are inside the signed payload. Unknown avatar silences to neutral;
    // intervalUnits must be {3,6,12}.

    fun encodeAnnounce(announce: Announce): ByteArray {
        val nickBytes = sanitizeNickname(announce.nickname).toByteArray(Charsets.UTF_8)
        require(nickBytes.isNotEmpty() && nickBytes.size <= 255) { "nickname must be 1..255 UTF-8 bytes" }
        require(announce.ed25519PublicKey.size == PUBLIC_KEY_BYTES) { "ed25519 public key must be 32 bytes" }
        require(announce.x25519PublicKey.size == PUBLIC_KEY_BYTES) { "x25519 public key must be 32 bytes" }
        require(announce.intervalUnits.toInt() in listOf(3, 6, 12)) { "intervalUnits must be one of {3,6,12}" }
        val buf = ByteBuffer.allocate(1 + nickBytes.size + PUBLIC_KEY_BYTES * 2 + 3).order(ByteOrder.BIG_ENDIAN)
        buf.put(nickBytes.size.toByte())
        buf.put(nickBytes)
        buf.put(announce.ed25519PublicKey)
        buf.put(announce.x25519PublicKey)
        buf.put(announce.capabilities)
        buf.put(announce.avatarIndex)
        buf.put(announce.intervalUnits)
        return buf.array()
    }

    fun decodeAnnounce(payload: ByteArray): Announce {
        if (payload.isEmpty()) throw PacketException.InvalidPacket("announce payload empty")
        val len = payload[0].toInt() and 0xFF
        val expected = 1 + len + PUBLIC_KEY_BYTES * 2 + 3
        if (payload.size != expected) {
            throw PacketException.InvalidPacket("announce length mismatch: expected $expected, got ${payload.size}")
        }
        if (len == 0) throw PacketException.InvalidPacket("announce nickname empty")
        val nickname = payload.copyOfRange(1, 1 + len).toString(Charsets.UTF_8)
        val ed = payload.copyOfRange(1 + len, 1 + len + PUBLIC_KEY_BYTES)
        val x = payload.copyOfRange(1 + len + PUBLIC_KEY_BYTES, 1 + len + PUBLIC_KEY_BYTES * 2)
        val caps = payload[1 + len + PUBLIC_KEY_BYTES * 2]
        val avatar = payload[1 + len + PUBLIC_KEY_BYTES * 2 + 1]
        val interval = payload[1 + len + PUBLIC_KEY_BYTES * 2 + 2]
        if ((interval.toInt() and 0xFF) !in listOf(3, 6, 12)) {
            throw PacketException.InvalidPacket("announce intervalUnits must be one of {3,6,12}")
        }
        return Announce(sanitizeNickname(nickname), ed, x, caps, avatar, interval)
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

    // ---- REACTION (§6.8) ----
    // kind(1) | emojiLen(1) | emoji(emojiLen, UTF-8) | targetId(32)
    // Both directed-to-message broadcast; signed, never relayed into the target.
    data class ReactionPayload(val kind: Byte, val emoji: String, val targetId: ByteArray) {
        companion object {
            const val KIND_ADD: Byte = 1
            const val KIND_REMOVE: Byte = 2
            const val MAX_EMOJI_BYTES: Int = 8
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReactionPayload) return false
            return kind == other.kind && emoji == other.emoji && targetId.contentEquals(other.targetId)
        }

        override fun hashCode(): Int =
            31 * (31 * kind.toInt() + emoji.hashCode()) + targetId.contentHashCode()
    }

    fun encodeReaction(reaction: ReactionPayload): ByteArray {
        if (reaction.kind != ReactionPayload.KIND_ADD && reaction.kind != ReactionPayload.KIND_REMOVE) {
            throw PacketException.InvalidPacket("reaction kind must be ADD(1) or REMOVE(2), got ${reaction.kind}")
        }
        require(reaction.targetId.size == 32) { "reaction targetId must be 32 bytes" }
        val emojiBytes = reaction.emoji.toByteArray(Charsets.UTF_8)
        if (emojiBytes.isEmpty() || emojiBytes.size > ReactionPayload.MAX_EMOJI_BYTES) {
            throw PacketException.InvalidPacket(
                "reaction emoji must be 1..${ReactionPayload.MAX_EMOJI_BYTES} UTF-8 bytes, got ${emojiBytes.size}"
            )
        }
        val buf = ByteBuffer.allocate(2 + emojiBytes.size + 32).order(ByteOrder.BIG_ENDIAN)
        buf.put(reaction.kind)
        buf.put(emojiBytes.size.toByte())
        buf.put(emojiBytes)
        buf.put(reaction.targetId)
        return buf.array()
    }

    fun decodeReaction(payload: ByteArray): ReactionPayload {
        if (payload.size < 2 + 1 + 32) {
            throw PacketException.InvalidPacket("reaction payload too short: ${payload.size} bytes")
        }
        val kind = payload[0]
        if (kind != ReactionPayload.KIND_ADD && kind != ReactionPayload.KIND_REMOVE) {
            throw PacketException.InvalidPacket("reaction kind must be ADD(1) or REMOVE(2), got $kind")
        }
        val emojiLen = payload[1].toInt() and 0xFF
        if (emojiLen < 1) throw PacketException.InvalidPacket("reaction emoji must not be empty")
        if (emojiLen > ReactionPayload.MAX_EMOJI_BYTES) {
            throw PacketException.InvalidPacket("reaction emoji exceeds ${ReactionPayload.MAX_EMOJI_BYTES} bytes")
        }
        if (payload.size != 2 + emojiLen + 32) {
            throw PacketException.InvalidPacket(
                "reaction payload length mismatch: expected ${2 + emojiLen + 32}, got ${payload.size}"
            )
        }
        val emoji = payload.copyOfRange(2, 2 + emojiLen).toString(Charsets.UTF_8)
        if (emoji.isEmpty() || emoji.any { it.isISOControl() }) {
            throw PacketException.InvalidPacket("reaction emoji is empty or contains control characters")
        }
        val targetId = payload.copyOfRange(2 + emojiLen, 2 + emojiLen + 32)
        return ReactionPayload(kind, emoji, targetId)
    }

    // ---- TYPING (§6.9) ----
    // Signed, directed to the peer we are typing to. Empty payload; the
    // recipient is in the header (HAS_RECIPIENT).

    fun encodeTyping(): ByteArray = ByteArray(0)

    fun decodeTyping(payload: ByteArray) {
        if (payload.isNotEmpty()) {
            throw PacketException.InvalidPacket("typing payload must be empty, got ${payload.size} bytes")
        }
    }

    // ---- GEO_PING (§6.10) ----
    // geoLen(1) | geohash(geoLen) | nameLen(1) | name(nameLen)
    // Signed, broadcast, TTL 2. Carries only a privacy-safe geohash (no lat/lon).
    data class GeoPingPayload(val geohash: String, val name: String) {
        companion object {
            const val MAX_GEOHASH_CHARS: Int = 12
            const val MAX_NAME_CHARS: Int = 32
        }
    }

    fun encodeGeoPing(ping: GeoPingPayload): ByteArray {
        val geoBytes = ping.geohash.toByteArray(Charsets.UTF_8)
        if (geoBytes.isEmpty() || geoBytes.size > GeoPingPayload.MAX_GEOHASH_CHARS) {
            throw PacketException.InvalidPacket(
                "geo ping geohash must be 1..${GeoPingPayload.MAX_GEOHASH_CHARS} bytes"
            )
        }
        val nameBytes = ping.name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > GeoPingPayload.MAX_NAME_CHARS) {
            throw PacketException.InvalidPacket("geo ping name must be <= ${GeoPingPayload.MAX_NAME_CHARS} bytes")
        }
        val buf = ByteBuffer.allocate(2 + geoBytes.size + nameBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(geoBytes.size.toByte())
        buf.put(geoBytes)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        return buf.array()
    }

    fun decodeGeoPing(payload: ByteArray): GeoPingPayload {
        if (payload.size < 2) throw PacketException.InvalidPacket("geo ping payload too short")
        val geoLen = payload[0].toInt() and 0xFF
        val nameLen = payload[1 + geoLen].toInt() and 0xFF
        if (geoLen < 1 || geoLen > GeoPingPayload.MAX_GEOHASH_CHARS) {
            throw PacketException.InvalidPacket("geo ping geohash length invalid")
        }
        if (payload.size != 2 + geoLen + nameLen) {
            throw PacketException.InvalidPacket(
                "geo ping payload length mismatch: expected ${2 + geoLen + nameLen}, got ${payload.size}"
            )
        }
        val geohash = payload.copyOfRange(1, 1 + geoLen).toString(Charsets.UTF_8)
        if (geohash.any { it.isISOControl() }) {
            throw PacketException.InvalidPacket("geo ping geohash contains control characters")
        }
        val name = payload.copyOfRange(2 + geoLen, 2 + geoLen + nameLen).toString(Charsets.UTF_8)
        return GeoPingPayload(geohash, name)
    }

    // ---- MESSAGE_CONTROL (§6.11) ----
    // kind(1) | targetId(32) | [textLen(2 BE) + text]  (text only for EDIT)
    // Signed, broadcast. Lets the **author** of a message delete or edit it
    // across the mesh. The handler re-checks that the sender is the original
    // author before applying anything.
    data class MessageControlPayload(val kind: Byte, val targetId: ByteArray, val newText: String? = null) {
        companion object {
            const val KIND_DELETE: Byte = 1
            const val KIND_EDIT: Byte = 2
            const val MAX_EDIT_TEXT_CHARS: Int = MAX_PUBLIC_TEXT_CHARS
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MessageControlPayload) return false
            return kind == other.kind && targetId.contentEquals(other.targetId) && newText == other.newText
        }

        override fun hashCode(): Int = 31 * (31 * kind.toInt() + targetId.contentHashCode()) + (newText?.hashCode() ?: 0)
    }

    fun encodeMessageControl(ctrl: MessageControlPayload): ByteArray {
        require(ctrl.targetId.size == 32) { "message control targetId must be 32 bytes" }
        if (ctrl.kind != MessageControlPayload.KIND_DELETE && ctrl.kind != MessageControlPayload.KIND_EDIT) {
            throw PacketException.InvalidPacket("message control kind must be DELETE(1) or EDIT(2)")
        }
        if (ctrl.kind == MessageControlPayload.KIND_EDIT) {
            val text = ctrl.newText ?: throw PacketException.InvalidPacket("EDIT requires newText")
            if (text.isEmpty() || text.length > MessageControlPayload.MAX_EDIT_TEXT_CHARS) {
                throw PacketException.InvalidPacket("edit text must be 1..${MessageControlPayload.MAX_EDIT_TEXT_CHARS} chars")
            }
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(1 + 32 + 2 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
            buf.put(ctrl.kind)
            buf.put(ctrl.targetId)
            buf.putShort(textBytes.size.toShort())
            buf.put(textBytes)
            return buf.array()
        }
        if (ctrl.newText != null) throw PacketException.InvalidPacket("DELETE must not carry newText")
        return byteArrayOf(ctrl.kind) + ctrl.targetId
    }

    fun decodeMessageControl(payload: ByteArray): MessageControlPayload {
        if (payload.size < 1 + 32) throw PacketException.InvalidPacket("message control too short")
        val kind = payload[0]
        val targetId = payload.copyOfRange(1, 33)
        return when (kind) {
            MessageControlPayload.KIND_DELETE -> {
                if (payload.size != 33) throw PacketException.InvalidPacket("DELETE payload must be 33 bytes")
                MessageControlPayload(kind, targetId, null)
            }
            MessageControlPayload.KIND_EDIT -> {
                if (payload.size < 35) throw PacketException.InvalidPacket("EDIT payload truncated")
                val textLen = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getShort(33).toInt() and 0xFFFF
                if (payload.size != 35 + textLen) throw PacketException.InvalidPacket("EDIT length mismatch")
                val text = payload.copyOfRange(35, 35 + textLen).toString(Charsets.UTF_8)
                if (text.isEmpty() || text.length > MessageControlPayload.MAX_EDIT_TEXT_CHARS) {
                    throw PacketException.InvalidPacket("edit text length invalid")
                }
                MessageControlPayload(kind, targetId, text)
            }
            else -> throw PacketException.InvalidPacket("unknown message control kind: $kind")
        }
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
