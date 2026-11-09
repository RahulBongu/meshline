package com.meshline.app.security

import com.meshline.app.protocol.PacketException
import com.meshline.app.protocol.Payloads
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * QR contact payload (PROTOCOL.md §6.6, D1).
 *
 * Public data only — never private keys, never signatures.
 * Wire: version(1)=1 | ed25519Pk(32) | x25519Pk(32) | avatarIndex(1) |
 *       nickLen(1) | nickname UTF-8 (<=24 bytes) | checksum(4) = SHA-256(prefix)[0..4)
 * Form: base64url (no padding), prefix meshline://c/
 *
 * Strict parser: rejects wrong version, wrong lengths, bad checksum,
 * unsanitised nickname, trailing bytes. Fuzz-tested in QrPayloadTest.
 */
object QrPayload {

    const val PREFIX = "meshline://c/"
    const val VERSION: Int = 1
    const val MAX_NICK_BYTES: Int = 24
    const val KEY_BYTES: Int = 32

    data class ContactCard(
        val ed25519PublicKey: ByteArray,
        val x25519PublicKey: ByteArray,
        val avatarIndex: Byte,
        val nickname: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ContactCard) return false
            return ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
                x25519PublicKey.contentEquals(other.x25519PublicKey) &&
                avatarIndex == other.avatarIndex && nickname == other.nickname
        }

        override fun hashCode(): Int {
            var h = ed25519PublicKey.contentHashCode()
            h = 31 * h + x25519PublicKey.contentHashCode()
            h = 31 * h + avatarIndex.toInt()
            h = 31 * h + nickname.hashCode()
            return h
        }
    }

    fun encode(card: ContactCard): String {
        require(card.ed25519PublicKey.size == KEY_BYTES) { "ed25519 key must be 32 bytes" }
        require(card.x25519PublicKey.size == KEY_BYTES) { "x25519 key must be 32 bytes" }
        val nick = Payloads.sanitizeNickname(card.nickname).toByteArray(StandardCharsets.UTF_8)
        require(nick.isNotEmpty() && nick.size <= MAX_NICK_BYTES) { "nickname must be 1..$MAX_NICK_BYTES bytes" }
        val body = ByteArray(1 + KEY_BYTES * 2 + 1 + 1 + nick.size)
        var off = 0
        body[off++] = VERSION.toByte()
        card.ed25519PublicKey.copyInto(body, off); off += KEY_BYTES
        card.x25519PublicKey.copyInto(body, off); off += KEY_BYTES
        body[off++] = card.avatarIndex
        body[off++] = nick.size.toByte()
        nick.copyInto(body, off); off += nick.size
        val checksum = MessageDigest.getInstance("SHA-256").digest(body).copyOfRange(0, 4)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body + checksum)
    }

    fun decode(text: String): ContactCard {
        if (!text.startsWith(PREFIX)) throw PacketException.InvalidPacket("QR must start with $PREFIX")
        val b64 = text.substring(PREFIX.length)
        val decoded = try {
            Base64.getUrlDecoder().decode(b64)
        } catch (_: Exception) {
            throw PacketException.InvalidPacket("QR is not valid base64url")
        }
        // Layout minimum: 1+32+32+1+1+1+4 = 72 (nick >=1). Maximum: 1+32+32+1+1+24+4 = 95.
        if (decoded.size < 72 || decoded.size > 95) throw PacketException.InvalidPacket("QR length out of range")
        val version = decoded[0].toInt() and 0xFF
        if (version != VERSION) throw PacketException.InvalidPacket("QR version must be $VERSION")
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val body = decoded.copyOfRange(0, decoded.size - 4)
        val expected = MessageDigest.getInstance("SHA-256").digest(body).copyOfRange(0, 4)
        if (!checksum.contentEquals(expected)) throw PacketException.InvalidPacket("QR checksum mismatch")
        val nickLen = decoded[66].toInt() and 0xFF // 1+32+32+1 = 66 offset of nickLen
        if (nickLen == 0 || nickLen > MAX_NICK_BYTES) throw PacketException.InvalidPacket("QR nickLen invalid")
        if (decoded.size != 1 + KEY_BYTES * 2 + 1 + 1 + nickLen + 4) throw PacketException.InvalidPacket("QR length mismatch")
        val nickname = body.copyOfRange(67, 67 + nickLen).toString(StandardCharsets.UTF_8)
        // Reuses the same sanitisation as ANNOUNCE nicknames.
        val cleanNick = Payloads.sanitizeNickname(nickname)
        if (cleanNick != nickname) throw PacketException.InvalidPacket("QR nickname not sanitised")
        return ContactCard(
            decoded.copyOfRange(1, 33),
            decoded.copyOfRange(33, 65),
            decoded[65],
            nickname,
        )
    }
}
