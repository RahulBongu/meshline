package com.meshline.app.noise

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **official Noise test vectors** for `Noise_XX_25519_ChaChaPoly_SHA256`
 * (NOISE_DESIGN §9.1).
 *
 * A non-conformant Noise implementation cannot be shipped, so this test is a
 * gate: it drives the pinned `noise-java` library through the exact
 * three-message exchange from the Noise specification and asserts byte-exact
 * handshake messages, the handshake hash and the transport ciphertexts.
 *
 * The vectors are the ones published with the Noise specification (the file
 * `xx-25519-chachapoly-sha256.json` under `app/src/test/resources/noise/` is
 * the `Noise_XX_25519_ChaChaPoly_SHA256` entry of the specification's vector
 * set, in the same JSON shape the reference implementation uses).
 */
class NoiseVectorsTest {

    private data class Vector(
        val initPrologue: ByteArray,
        val initStatic: ByteArray,
        val initEphemeral: ByteArray,
        val respPrologue: ByteArray,
        val respStatic: ByteArray,
        val respEphemeral: ByteArray,
        val handshakeHash: ByteArray,
        val messages: List<Pair<ByteArray, ByteArray>>, // payload -> ciphertext
    )

    private fun loadVector(): Vector {
        val stream = javaClass.classLoader!!.getResourceAsStream("noise/xx-25519-chachapoly-sha256.json")
            ?: error("official Noise vector file not found on the test classpath")
        val json = JSONObject(stream.readBytes().decodeToString())
        fun hex(s: String): ByteArray {
            val out = ByteArray(s.length / 2)
            for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            return out
        }
        val messagesArr = json.getJSONArray("messages")
        val messages = ArrayList<Pair<ByteArray, ByteArray>>(messagesArr.length())
        for (i in 0 until messagesArr.length()) {
            val m = messagesArr.getJSONObject(i)
            messages += hex(m.getString("payload")) to hex(m.getString("ciphertext"))
        }
        return Vector(
            initPrologue = hex(json.getString("init_prologue")),
            initStatic = hex(json.getString("init_static")),
            initEphemeral = hex(json.getString("init_ephemeral")),
            respPrologue = hex(json.getString("resp_prologue")),
            respStatic = hex(json.getString("resp_static")),
            respEphemeral = hex(json.getString("resp_ephemeral")),
            handshakeHash = hex(json.getString("handshake_hash")),
            messages = messages,
        )
    }

    @Test
    fun `official vectors for Noise XX 25519 ChaChaPoly SHA256 are byte exact`() {
        val v = loadVector()
        assertEquals("Noise_XX_25519_ChaChaPoly_SHA256", NoiseProtocol.PATTERN)
        assertTrue("the vector file must contain the 3 handshake + 3 transport messages", v.messages.size == 6)

        val initiator = NoiseHandshake.initiator(
            staticPrivateKey = v.initStatic,
            prologue = v.initPrologue,
            fixedEphemeral = v.initEphemeral,
        )
        val responder = NoiseHandshake.responder(
            staticPrivateKey = v.respStatic,
            prologue = v.respPrologue,
            fixedEphemeral = v.respEphemeral,
        )

        // ---- m1: -> e ----
        val (m1Payload, m1Ciphertext) = v.messages[0]
        val m1 = initiator.writeMessage(m1Payload)
        assertArrayEquals("m1 handshake message", m1Ciphertext, m1)
        assertArrayEquals("m1 payload", m1Payload, responder.readMessage(m1))

        // ---- m2: <- e, ee, s, es ----
        val (m2Payload, m2Ciphertext) = v.messages[1]
        val m2 = responder.writeMessage(m2Payload)
        assertArrayEquals("m2 handshake message", m2Ciphertext, m2)
        assertArrayEquals("m2 payload", m2Payload, initiator.readMessage(m2))

        // ---- m3: -> s, se ----
        val (m3Payload, m3Ciphertext) = v.messages[2]
        val m3 = initiator.writeMessage(m3Payload)
        assertArrayEquals("m3 handshake message", m3Ciphertext, m3)
        assertArrayEquals("m3 payload", m3Payload, responder.readMessage(m3))

        // ---- transcript ----
        assertArrayEquals("initiator handshake hash", v.handshakeHash, initiator.handshakeHash())
        assertArrayEquals("responder handshake hash", v.handshakeHash, responder.handshakeHash())

        // ---- transport messages ----
        assertTrue("both sides must be ready to split", initiator.canSplit() && responder.canSplit())
        val initiatorKeys = initiator.split()
        val responderKeys = responder.split()

        val initSend = TransportCipher(initiatorKeys.send)
        val initRecv = TransportCipher(initiatorKeys.recv)
        val respSend = TransportCipher(responderKeys.send)
        val respRecv = TransportCipher(responderKeys.recv)

        // The specification alternates the transport direction: after the
        // three handshake messages the *responder* sends the first transport
        // message (see the reference vector runner in noise-java).
        var initiatorTurn = false
        for (i in 3 until v.messages.size) {
            val (payload, expected) = v.messages[i]
            val send = if (initiatorTurn) initSend else respSend
            val recv = if (initiatorTurn) respRecv else initRecv
            val nonce = send.nonce
            val ciphertext = send.encrypt(payload, ByteArray(0))
            assertArrayEquals("transport message $i", expected, ciphertext)
            assertArrayEquals("transport payload $i", payload, recv.decrypt(ciphertext, ByteArray(0), nonce))
            initiatorTurn = !initiatorTurn
        }
    }
}
