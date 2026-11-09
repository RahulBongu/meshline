package com.meshline.app.security

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Production ANNOUNCE verifier (D1, PROTOCOL §8.1).
 * Before caching/relaying/showing: hash check + intervalUnits in {3,6,12} +
 * per-connection/global budgets (inside KeyCache) + valid Ed25519 signature
 * against the announced edPk. Rejected announces are never cached.
 */
object AnnounceVerifier {

    fun verify(pkt: MeshPacket, cache: KeyCache, connectionKey: String): Boolean {
        if (pkt.type != PacketType.ANNOUNCE) return cache.knows(pkt.senderId, pkt.timestampMs)
        val payload = pkt.payload
        // Interval byte is trailing byte in the D1 wire; legacy packets without it are treated as 6.
        var payloadForDecode = payload
        var intervalOk = true
        try {
            Payloads.decodeAnnounce(payload)
        } catch (_: Exception) {
            if (payload.isNotEmpty()) {
                val interval = payload.last().toInt() and 0xFF
                payloadForDecode = payload.copyOfRange(0, payload.size - 1)
                intervalOk = interval in setOf(3, 6, 12)
            }
        }
        if (!intervalOk) return false
        val ann = try {
            Payloads.decodeAnnounce(payloadForDecode)
        } catch (_: Exception) { return false }
        val hashOk = MessageIds.idFromPublicKey(ann.ed25519PublicKey).contentEquals(pkt.senderId)
        val sigOk = pkt.signature != null && verifyEd(ann.ed25519PublicKey, PacketCodec.signingInput(pkt), pkt.signature!!)
        if (!hashOk || !sigOk) return false
        val learned = cache.learn(pkt.senderId, ann.ed25519PublicKey, nowMs = pkt.timestampMs, connectionKey = connectionKey)
        return learned
    }

    fun verifyEd(rawPub: ByteArray, input: ByteArray, sig: ByteArray): Boolean {
        return try {
            val x509 = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + rawPub
            val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(x509))
            Signature.getInstance("Ed25519").apply { initVerify(pub); update(input) }.verify(sig)
        } catch (_: Exception) { false }
    }
}
