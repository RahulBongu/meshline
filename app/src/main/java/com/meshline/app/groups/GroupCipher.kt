package com.meshline.app.groups

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * ChaCha20-Poly1305 encryption for group epoch messages (GROUPS_DESIGN.md §2).
 *
 * Each group message is encrypted under the 32-byte epoch key with an explicit
 * 8-byte nonce and 18-byte AEAD Associated Data (groupTag || epoch || senderId).
 */
object GroupCipher {

    private val secureRandom = SecureRandom()

    /** Generates a fresh 8-byte nonce for a group message. */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(8)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /** Formats an 8-byte wire nonce into a 12-byte ChaCha20-Poly1305 IV (4 zeros + 8 nonce bytes). */
    fun formatIv(nonce: ByteArray): ByteArray {
        require(nonce.size == 8) { "nonce must be 8 bytes, got ${nonce.size}" }
        return ByteArray(4) + nonce
    }

    /**
     * Encrypts [plaintext] under [epochKey] with [nonce] (8 bytes) and [ad] associated data.
     * Returns ciphertext + 16-byte Poly1305 tag.
     */
    fun encrypt(plaintext: ByteArray, epochKey: ByteArray, nonce: ByteArray, ad: ByteArray): ByteArray {
        require(epochKey.size == 32) { "epochKey must be 32 bytes" }
        require(nonce.size == 8) { "nonce must be 8 bytes" }

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(epochKey), 128, formatIv(nonce), ad)
        cipher.init(true, params)

        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    /**
     * Decrypts [ciphertext] under [epochKey] with [nonce] (8 bytes) and [ad] associated data.
     * Throws [IllegalArgumentException] on authentication tag failure.
     */
    fun decrypt(ciphertext: ByteArray, epochKey: ByteArray, nonce: ByteArray, ad: ByteArray): ByteArray {
        require(epochKey.size == 32) { "epochKey must be 32 bytes" }
        require(nonce.size == 8) { "nonce must be 8 bytes" }
        if (ciphertext.size < 16) throw IllegalArgumentException("ciphertext too short (< 16 bytes)")

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(epochKey), 128, formatIv(nonce), ad)
        cipher.init(false, params)

        try {
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val finalLen = cipher.doFinal(out, len)
            return out.copyOf(len + finalLen)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw SecurityException("Authentication tag verification failed", e)
        }
    }
}
