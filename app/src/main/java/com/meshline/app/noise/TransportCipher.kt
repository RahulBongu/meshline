package com.meshline.app.noise

import com.southernstorm.noise.protocol.CipherState

/**
 * One direction of an established Noise transport session.
 *
 * The Noise cipher state keeps its own counter, but NOISE_DESIGN §4.1 wants
 * the nonce to be **explicit on the wire** (the mesh reorders and duplicates
 * packets, so the receiver must know which nonce a ciphertext used before it
 * can run the replay window). We therefore own the counter here and set it on
 * the cipher state before every operation.
 *
 * The counter is a 64-bit value; it must never wrap. The re-key rule
 * (1 000 messages per direction, NOISE_DESIGN §6) keeps us far below that,
 * but the counter is checked anyway and the session fails closed instead of
 * reusing a nonce.
 */
class TransportCipher(
    private val state: CipherState,
    /** The nonce to use for the next message. */
    var nonce: Long = 0,
) {
    /** True once the nonce space is (practically) exhausted. */
    val exhausted: Boolean get() = nonce < 0

    /**
     * Encrypts [plaintext] with [ad] as associated data and returns the
     * ciphertext (plaintext length + 16-byte Poly1305 tag).
     */
    fun encrypt(plaintext: ByteArray, ad: ByteArray): ByteArray {
        if (exhausted) throw IllegalStateException("nonce space exhausted")
        state.setNonce(nonce)
        val out = ByteArray(plaintext.size + 16)
        val len = state.encryptWithAd(ad, plaintext, 0, out, 0, plaintext.size)
        nonce++
        return out.copyOf(len)
    }

    /**
     * Decrypts [ciphertext] with [ad] as associated data, using the explicit
     * [nonce] the message carried. The caller must have accepted that nonce
     * in the replay window first.
     *
     * The nonce is passed in rather than taken from the counter: the receive
     * direction may see messages out of order, and the ciphertext says which
     * nonce it used.
     */
    fun decrypt(ciphertext: ByteArray, ad: ByteArray, nonce: Long): ByteArray {
        if (ciphertext.size < 16) throw IllegalArgumentException("ciphertext too short")
        state.setNonce(nonce)
        val out = ByteArray(ciphertext.size - 16)
        // The library expects the *full* ciphertext length (tag included); it
        // subtracts the 16-byte tag itself.
        val len = state.decryptWithAd(ad, ciphertext, 0, out, 0, ciphertext.size)
        return out.copyOf(len)
    }

    /** Wipes the cipher state (used when a session is replaced or closed). */
    fun destroy() {
        state.destroy()
    }
}
