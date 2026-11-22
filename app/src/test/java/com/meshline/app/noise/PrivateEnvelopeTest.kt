package com.meshline.app.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Envelope shape tests for Features 4–5: media (`mediaType`/`media`/caption)
 * and reply-to (`parentHex`), plus the legacy v1 decode path.
 */
class PrivateEnvelopeTest {

    private fun id(n: Int): ByteArray = ByteArray(32) { (it * 3 + n).toByte() }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `v2 text round trip`() {
        val e = PrivateEnvelope(id(1), 1_700_000_000_000L, "hello world".toByteArray(), null, null, null)
        val d = PrivateEnvelope.decode(e.encode())
        assertArrayEquals(e.messageId, d.messageId)
        assertEquals(e.sentAtMs, d.sentAtMs)
        assertArrayEquals("hello world".toByteArray(), d.text)
        assertNull(d.mediaType)
        assertNull(d.media)
        assertNull(d.parentHex)
    }

    @Test
    fun `v2 media + caption round trip`() {
        val media = ByteArray(1_234) { (it % 251).toByte() }
        val e = PrivateEnvelope(id(2), 1_700_000_000_001L, "a caption".toByteArray(), "audio/aac", media, null)
        val d = PrivateEnvelope.decode(e.encode())
        assertEquals("audio/aac", d.mediaType)
        assertArrayEquals(media, d.media)
        assertArrayEquals("a caption".toByteArray(), d.text)
    }

    @Test
    fun `v2 reply-to round trip`() {
        val parentHex = hex(id(3))
        val e = PrivateEnvelope(id(4), 1_700_000_000_002L, "reply".toByteArray(), null, null, parentHex)
        val d = PrivateEnvelope.decode(e.encode())
        assertEquals("reply", d.text.toString(Charsets.UTF_8))
        assertEquals(parentHex, d.parentHex)
    }

    @Test
    fun `v2 media + caption + reply round trip`() {
        val media = ByteArray(600) { (it * 7 + 1).toByte() }
        val e = PrivateEnvelope(
            id(5), 1_700_000_000_003L, "cap".toByteArray(),
            "image/jpeg", media, hex(id(6)),
        )
        val d = PrivateEnvelope.decode(e.encode())
        assertEquals("image/jpeg", d.mediaType)
        assertArrayEquals(media, d.media)
        assertArrayEquals("cap".toByteArray(), d.text)
        assertEquals(hex(id(6)), d.parentHex)
    }

    @Test
    fun `legacy v1 envelope decodes as text`() {
        val legacy = id(7) +
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(1_700_000_000_004L).array() +
            "legacy text".toByteArray()
        val d = PrivateEnvelope.decode(legacy)
        assertArrayEquals(id(7), d.messageId)
        assertEquals(1_700_000_000_004L, d.sentAtMs)
        assertEquals("legacy text", d.text.toString(Charsets.UTF_8))
        assertNull(d.mediaType)
        assertNull(d.media)
        assertNull(d.parentHex)
    }

    @Test
    fun `encode rejects malformed fields`() {
        // Media over the budget.
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope(id(8), 1L, ByteArray(0), "image/jpeg", ByteArray(PrivateEnvelope.MAX_MEDIA_BYTES + 1), null).encode()
        }
        // Caption over the budget.
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope(id(8), 1L, ByteArray(5_001), null, null, null).encode()
        }
        // Empty / over-long parentHex.
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope(id(8), 1L, ByteArray(0), null, null, "").encode()
        }
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope(id(8), 1L, ByteArray(0), null, null, "z".repeat(65)).encode()
        }
        // Over-long mediaType.
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope(id(8), 1L, ByteArray(0), "x".repeat(17), ByteArray(10), null).encode()
        }
        // Media without a mediaType.
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope(id(8), 1L, ByteArray(0), null, ByteArray(10), null).encode()
        }
    }

    @Test
    fun `decode rejects malformed v2 payloads`() {
        // Reserved flag bit set.
        val reserved = PrivateEnvelope(id(9), 1L, ByteArray(0), null, null, null).encode()
        reserved[PrivateEnvelope.HEADER_BYTES + 1] = (reserved[PrivateEnvelope.HEADER_BYTES + 1].toInt() or 0x04).toByte()
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) { PrivateEnvelope.decode(reserved) }

        // Truncated media (tail cut off a media envelope).
        val full = PrivateEnvelope(id(10), 1L, "cap".toByteArray(), "audio/aac", ByteArray(600) { 0x01 }, null).encode()
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope.decode(full.copyOfRange(0, full.size - 2))
        }
        // Trailing garbage.
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope.decode(full + byteArrayOf(0x00))
        }
        // Garbage body: huge media length.
        val garbage = PrivateEnvelope(id(11), 1L, ByteArray(0), null, null, null).encode()
        assertThrows(PrivateEnvelope.MalformedEnvelope::class.java) {
            PrivateEnvelope.decode(garbage.copyOfRange(0, garbage.size) + byteArrayOf(0x00))
        }
    }
}
