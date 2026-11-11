package com.meshline.app.security

import com.meshline.app.protocol.MessageIds

/**
 * Local identity (D1, NOISE_DESIGN.md §7).
 * Two long-term pairs: Ed25519 for signing, X25519 for Noise key agreement.
 * Private keys are wrapped with a Keystore AES-GCM key on-device; on the
 * JVM (unit tests) the same wrapping shape is modelled with BouncyCastle AES-GCM.
 */
class Identity(
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
    private val edPrivate: ByteArray,
    private val xPrivate: ByteArray,
) {
    val senderId: ByteArray get() = MessageIds.idFromPublicKey(ed25519PublicKey)

    val fingerprint: String get() = MessageIds.fingerprint(ed25519PublicKey, x25519PublicKey)

    /** First 4 hex chars of the fingerprint (display: "fruit nickname - 7F3A"). */
    val fingerprintShort: String get() = fingerprint.substring(0, 4).uppercase()

    /** Ed25519 sign over [input] using BC (production path; JVM-testable). */
    fun signEd25519(input: ByteArray): ByteArray {
        val priv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(edPrivate)
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, priv)
        signer.update(input, 0, input.size)
        return signer.generateSignature()
    }

    /** BC X25519 agreement with a peer public key (for the D2 handshake). */
    fun agreeX25519(peerPublic: ByteArray): ByteArray {
        val priv = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(xPrivate)
        val pub = org.bouncycastle.crypto.params.X25519PublicKeyParameters(peerPublic)
        val agreement = org.bouncycastle.crypto.agreement.X25519Agreement()
        agreement.init(priv)
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pub, out, 0)
        return out
    }

    /**
     * The long-term **X25519 private key** — the Noise static key.
     *
     * The D2 secure channel needs it to run the Noise_XX handshake; it never
     * leaves the device and is never logged.
     */
    fun x25519PrivateKey(): ByteArray = xPrivate.copyOf()
}
