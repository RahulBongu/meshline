package com.meshline.app.security

import com.meshline.app.protocol.PacketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrPayloadTest {

    private fun card() = QrPayload.ContactCard(
        ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }, 8, "Rahul"
    )

    @Test
    fun `encode then decode round-trips`() {
        val s = QrPayload.encode(card())
        assert(QrPayload.decode(s) == card())
    }

    @Test
    fun `tampered checksum is rejected`() {
        val good = QrPayload.encode(card())
        // Flip a character in the middle of the payload (affects checksum).
        val idx = good.length / 2
        val bad = good.substring(0, idx) + if (good[idx] == 'A') 'B' else 'A' + good.substring(idx + 1)
        assertThrows(PacketException::class.java) { QrPayload.decode(bad) }
    }

    @Test
    fun `wrong version and bad lengths are rejected`() {
        // Version byte 2 (crafted).
        val card = card()
        val body = ByteArray(1 + 32 + 32 + 1 + 1 + 5)
        body[0] = 2
        card.ed25519PublicKey.copyInto(body, 1)
        card.x25519PublicKey.copyInto(body, 33)
        body[65] = 8
        body[66] = 5
        "Rahul".encodeToByteArray().copyInto(body, 67)
        // Checksum must also be wrong if we don't fix it, but version check fires first.
        val bad = "meshline://c/" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body + ByteArray(4))
        assertThrows(PacketException::class.java) { QrPayload.decode(bad) }
        assertThrows(PacketException::class.java) { QrPayload.decode("meshline://c/short") }
    }

    @Test
    fun `fuzz random base64url never crashes`() {
        val rnd = java.util.Random(42)
        repeat(500) {
            val s = "meshline://c/" + ByteArray(rnd.nextInt(200)) { rnd.nextInt(256).toByte() }.let {
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }
            try {
                QrPayload.decode(s)
            } catch (_: PacketException) {
                // expected
            }
            // Any other throwable is a failure (strict parser must not crash).
        }
    }
}
