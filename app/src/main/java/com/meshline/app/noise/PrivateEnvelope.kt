package com.meshline.app.noise

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The **application-level** envelope inside a private message.
 *
 * ```
 * messageId(32)   the application-level message id (see below)
 * sentAtMs(8)     when the sender composed it, big-endian
 * text            the message body
 * ```
 *
 * Why an envelope at all: the transport protects the *bytes*, but the UI needs
 * to know that a message it already showed must not be shown again. The
 * transport nonce cannot serve that purpose — a retry is re-encrypted under
 * the live session with a **fresh nonce** (a nonce must never be reused), so
 * the same logical message arrives with a different nonce every time.
 *
 * The application-level id is therefore carried *inside* the ciphertext (so it
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
    val text: ByteArray,
) {
    /** Raised when an envelope does not match the expected shape. */
    class MalformedEnvelope(message: String) : Exception(message)

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES + text.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(messageId)
        buf.putLong(sentAtMs)
        buf.put(text)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrivateEnvelope) return false
        return messageId.contentEquals(other.messageId) &&
            sentAtMs == other.sentAtMs &&
            text.contentEquals(other.text)
    }

    override fun hashCode(): Int {
        var h = messageId.contentHashCode()
        h = 31 * h + sentAtMs.hashCode()
        h = 31 * h + text.contentHashCode()
        return h
    }

    companion object {
        const val MESSAGE_ID_BYTES = 32
        const val HEADER_BYTES = MESSAGE_ID_BYTES + 8

        fun decode(bytes: ByteArray): PrivateEnvelope {
            if (bytes.size < HEADER_BYTES) throw MalformedEnvelope("envelope too short (${bytes.size})")
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val id = ByteArray(MESSAGE_ID_BYTES)
            buf.get(id)
            val sentAt = buf.long
            return PrivateEnvelope(id, sentAt, bytes.copyOfRange(HEADER_BYTES, bytes.size))
        }
    }
}
