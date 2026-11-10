package com.meshline.app.security

import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.protocol.Payloads
import com.meshline.app.router.SignatureCheck

/**
 * Production signature verifier (D1, PROTOCOL §8.1).
 *
 * ANNOUNCE: strict payload decode → `SHA256(edPk)[0..8) == senderId` → Ed25519
 * signature → KeyCache learning. A learn rejection is reported as
 * [SignatureCheck.BUDGET] (valid packet, per-connection/global budget exhausted),
 * never as a bad signature.
 *
 * Other signed types: the sender's key must already be in the cache. Missing
 * key is [SignatureCheck.UNKNOWN_SENDER] (post-join transient), a failed
 * Ed25519 check is [SignatureCheck.BAD_SIGNATURE].
 *
 * Rejected announces are never cached or relayed.
 */
object AnnounceVerifier {

    fun verify(pkt: MeshPacket, cache: KeyCache, fromPeer: ByteArray): SignatureCheck {
        val sig = pkt.signature ?: return SignatureCheck.BAD_SIGNATURE

        if (pkt.type != PacketType.ANNOUNCE) {
            val key = cache.get(pkt.senderId) ?: return SignatureCheck.UNKNOWN_SENDER
            return if (verifyEd(key, PacketCodec.signingInput(pkt), sig)) {
                SignatureCheck.OK
            } else {
                SignatureCheck.BAD_SIGNATURE
            }
        }

        val ann = try {
            Payloads.decodeAnnounce(pkt.payload)
        } catch (_: Exception) {
            return SignatureCheck.BAD_SIGNATURE
        }
        val hashOk = MessageIds.idFromPublicKey(ann.ed25519PublicKey).contentEquals(pkt.senderId)
        if (!hashOk) return SignatureCheck.BAD_SIGNATURE
        if (!verifyEd(ann.ed25519PublicKey, PacketCodec.signingInput(pkt), sig)) {
            return SignatureCheck.BAD_SIGNATURE
        }
        // Budget clocks must be local receive time, not the sender's timestamp.
        val learned = cache.learn(
            senderId = pkt.senderId,
            key = ann.ed25519PublicKey,
            connectionKey = hex(fromPeer),
        )
        return if (learned) SignatureCheck.OK else SignatureCheck.BUDGET
    }

    fun verifyEd(rawPub: ByteArray, input: ByteArray, sig: ByteArray): Boolean {
        return try {
            // BouncyCastle, not JCA: platform Ed25519 only exists on Android 13+,
            // and minSdk is 26. This matches Identity.signEd25519 exactly.
            val pub = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(rawPub, 0)
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, pub)
            verifier.update(input, 0, input.size)
            verifier.verifySignature(sig)
        } catch (_: Exception) {
            false
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
