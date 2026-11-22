package com.meshline.app.noise

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The **application-level** envelope inside a private message.
 *
 * v1 format:
 * ```
 * messageId(32)   the application-level message id (see below)
 * sentAtMs(8)     when the sender composed it, big-endian
 * text            the message body
 * ```
 *
 * v2 format (Features 4–5: media notes, images, reply-to):
 * ```
 * messageId(32)   the application-level message id
 * sentAtMs(8)     when the sender composed it, big-endian
 * version(1)      = 2
 * flags(1)        bit0 = hasParent, bit1 = hasMedia (other bits must be 0)
 * [parentLen(1) + parent]  if hasParent   parentHexLen: 1..64
 * [mediaTypeLen(1) + mediaType]  if hasMedia   1..16
 * captionLen(2 BE) + caption   the message body: text for text messages,
 *                              the caption (often empty) for media messages
 * [mediaLen(2 BE) + media]  if hasMedia, 0..MAX_MEDIA_BYTES
 * ```
 *
 * Legacy detection: `bytes[HEADER_BYTES] != 2` means a v1 envelope — text only.
 *
 * Why an envelope at all: the transport protects the *bytes*, but the UI needs
 * to know that a message it already showed must not be shown again. The
 * transport nonce cannot serve that purpose — a retry is re-encrypted under
 * the live session with a **fresh nonce** (a nonce must never be reused), so
 * the same logical message arrives with a different nonce every time.
 *
 * The application-level id is therefore carried *inside the ciphertext* (so it
 * is authenticated by the AEAD tag) and is stable across retries. The receiver
 * keeps the ids it has already shown and drops a repeat, which is what makes
 * "delivered exactly once" true even when the mesh duplicates or reorders.
 *
 * The id is 32 bytes so it fits the `messageId(32)` field of the RECEIPT
 * payload (PROTOCOL §6.4) unchanged.
 */
data class PrivateEnvelope(
    val messageId: ByteArray,
    val sentAtMs: Long,
    /** The message body: the text of a text message, or the caption of a media message. */
    val text: ByteArray,
    /** MIME type of [media] (`"audio/aac"`, `"image/jpeg"`, …). Null on text messages. */
    val mediaType: String? = null,
    /** Media payload (voice notes, small images). Null on text messages. */
    val media: ByteArray? = null,
    /** `messageIdHex` of the replied-to message (Feature 5: reply-to quoting). Null when none. */
    val parentHex: String? = null,
) {
    /** Raised when an envelope does not match the expected shape. */
    class MalformedEnvelope(message: String) : Exception(message)

    fun encode(): ByteArray {
        if (text.size > MAX_CAPTION_BYTES) {
            throw MalformedEnvelope("caption ${text.size} bytes exceeds max $MAX_CAPTION_BYTES")
        }
        val parentBytes = parentHex?.toByteArray(Charsets.UTF_8)
        if (parentHex != null && (parentBytes == null || parentBytes.isEmpty() || parentBytes.size > 64)) {
            throw MalformedEnvelope("parentHex must be 1..64 bytes")
        }
        val mediaTypeBytes = mediaType?.toByteArray(Charsets.UTF_8)
        if (media != null && (mediaType == null || mediaTypeBytes == null || mediaTypeBytes.size !in 1..16)) {
            throw MalformedEnvelope("mediaType must be 1..16 bytes when media is present")
        }
        if (mediaType != null && media == null) {
            throw MalformedEnvelope("mediaType without media is not allowed")
        }
        if (media != null && media.size > MAX_MEDIA_BYTES) {
            throw MalformedEnvelope("media ${media.size} bytes exceeds max $MAX_MEDIA_BYTES")
        }
        val flags = ((if (parentHex != null) FLAG_HAS_PARENT else 0) or (if (media != null) FLAG_HAS_MEDIA else 0)).toByte()
        val total = HEADER_BYTES + 2 +                                  // id + sentAtMs + version + flags
            (if (parentHex != null) 1 + (parentBytes?.size ?: 0) else 0) +  // parentLen + parent
            (if (mediaType != null) 1 + (mediaTypeBytes?.size ?: 0) else 0) + // mediaTypeLen + mediaType
            2 + text.size +                                             // captionLen + caption
            (media?.let { 2 + it.size } ?: 0)                            // mediaLen + media
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(messageId)
        buf.putLong(sentAtMs)
        buf.put(V2_VERSION.toByte())
        buf.put(flags)
        if (parentHex != null && parentBytes != null) {
            buf.put(parentBytes.size.toByte())
            buf.put(parentBytes)
        }
        if (mediaType != null && mediaTypeBytes != null) {
            buf.put(mediaTypeBytes.size.toByte())
            buf.put(mediaTypeBytes)
        }
        buf.putShort(text.size.toShort())
        buf.put(text)
        if (media != null) {
            buf.putShort(media.size.toShort())
            buf.put(media)
        }
        return buf.array()
    }

    private fun ByteArray?.eq(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && contentEquals(other)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrivateEnvelope) return false
        return messageId.contentEquals(other.messageId) &&
            sentAtMs == other.sentAtMs &&
            text.contentEquals(other.text) &&
            mediaType == other.mediaType &&
            media.eq(other.media) &&
            parentHex == other.parentHex
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + sentAtMs.hashCode()
        h = 31 * h + text.contentHashCode()
        h = 31 * h + (mediaType?.hashCode() ?: 0)
        h = 31 * h + (media?.contentHashCode() ?: 0)
        h = 31 * h + (parentHex?.hashCode() ?: 0)
        return h
    }

    companion object {
        const val MESSAGE_ID_BYTES = 32
        /** messageId(32) ‖ sentAtMs(8) — the legacy prefix of the envelope. */
        const val HEADER_BYTES = MESSAGE_ID_BYTES + 8
        private const val V2_VERSION = 2
        private const val FLAG_HAS_PARENT = 0x01
        private const val FLAG_HAS_MEDIA = 0x02
        private const val RESERVED_FLAGS_MASK = 0xFC
        /** Max media payload bytes (voice notes, compressed images). */
        const val MAX_MEDIA_BYTES = 55_000
        const val MAX_CAPTION_BYTES: Int = 4_000

        fun decode(bytes: ByteArray): PrivateEnvelope {
            if (bytes.size < HEADER_BYTES + 2) throw MalformedEnvelope("envelope too short (${bytes.size})")
            if (bytes[HEADER_BYTES].toInt() != V2_VERSION) {
                // Legacy v1 envelope: messageId(32) ‖ sentAtMs(8) ‖ text
                return PrivateEnvelope(
                    messageId = bytes.copyOfRange(0, MESSAGE_ID_BYTES),
                    sentAtMs = readLongAt(bytes, MESSAGE_ID_BYTES),
                    text = bytes.copyOfRange(HEADER_BYTES, bytes.size),
                    mediaType = null,
                    media = null,
                    parentHex = null,
                )
            }
            val flags = bytes[HEADER_BYTES + 1].toInt() and 0xFF
            if (flags and RESERVED_FLAGS_MASK != 0) {
                throw MalformedEnvelope("unsupported envelope flags 0x%02X".format(flags))
            }
            val hasParent = flags and FLAG_HAS_PARENT != 0
            val hasMedia = flags and FLAG_HAS_MEDIA != 0
            var pos = HEADER_BYTES + 2
            var parentHex: String? = null
            if (hasParent) {
                val len = bytes[pos].toInt() and 0xFF
                if (len !in 1..64) throw MalformedEnvelope("bad parentHex length: $len")
                pos++
                parentHex = bytes.copyOfRange(pos, pos + len).toString(Charsets.UTF_8)
                pos += len
            }
            var mediaType: String? = null
            if (hasMedia) {
                val len = bytes[pos].toInt() and 0xFF
                if (len !in 1..16) throw MalformedEnvelope("bad mediaType length: $len")
                pos++
                mediaType = bytes.copyOfRange(pos, pos + len).toString(Charsets.UTF_8)
                pos += len
            }
            if (pos + 2 > bytes.size) throw MalformedEnvelope("truncated caption length")
            val captionLen = readShortAt(bytes, pos).toInt() and 0xFFFF
            pos += 2
            if (pos + captionLen > bytes.size) throw MalformedEnvelope("truncated caption")
            val text = bytes.copyOfRange(pos, pos + captionLen)
            pos += captionLen
            var media: ByteArray? = null
            if (hasMedia) {
                if (pos + 2 > bytes.size) throw MalformedEnvelope("truncated media length")
                val len = readShortAt(bytes, pos).toInt() and 0xFFFF
                pos += 2
                if (len > MAX_MEDIA_BYTES) throw MalformedEnvelope("media $len bytes exceeds max $MAX_MEDIA_BYTES")
                if (pos + len != bytes.size) throw MalformedEnvelope("trailing bytes after media")
                media = bytes.copyOfRange(pos, pos + len)
            } else if (pos != bytes.size) {
                throw MalformedEnvelope("trailing bytes after caption")
            }
            return PrivateEnvelope(
                messageId = bytes.copyOfRange(0, MESSAGE_ID_BYTES),
                sentAtMs = readLongAt(bytes, MESSAGE_ID_BYTES),
                text = text,
                mediaType = mediaType,
                media = media,
                parentHex = parentHex,
            )
        }

        private fun readLongAt(bytes: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong(offset)

        private fun readShortAt(bytes: ByteArray, offset: Int): Short =
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getShort(offset)
    }
}
