package com.meshline.app.noise

/**
 * Sliding replay window over transport nonces (NOISE_DESIGN §4.1).
 *
 * The mesh loses, reorders and duplicates packets, so the receiver keeps a
 * 128-nonce window: `nextExpected` plus a 128-bit bitmap of the nonces that
 * arrived ahead of it. A ciphertext is accepted only once — a second copy
 * (a replay) is refused, and a nonce that is far beyond the window is refused
 * as well (a gap attack would otherwise let an attacker push the window
 * forward and lock out honest traffic).
 *
 * The window is per direction and in-memory only: after a restart the session
 * is re-established, so a pre-restart window is never trusted across a
 * session boundary.
 */
class ReplayWindow(
    /** Window size in nonces (128 in the protocol). */
    val size: Int = NoiseProtocol.REPLAY_WINDOW_SIZE,
) {
    init {
        require(size in 1..65_536) { "window size out of range" }
        require(size % 64 == 0) { "window size must be a multiple of 64" }
    }

    /** The next nonce we expect (all lower ones are replays). */
    var nextExpected: Long = 0
        private set

    // Bitmap of the nonces we have seen at or above nextExpected.
    private val seen = LongArray(size / 64)

    /** The result of offering a nonce to the window. */
    enum class Decision {
        /** Accepted; the window may have advanced. */
        ACCEPTED,

        /** Already seen — a replay. */
        REPLAY,

        /** Beyond the window — refused as a possible gap attack. */
        TOO_FAR_AHEAD,
    }

    /**
     * Offers [nonce] to the window.
     */
    fun accept(nonce: Long): Decision {
        if (nonce < 0) return Decision.REPLAY
        if (nonce < nextExpected) return Decision.REPLAY
        if (nonce >= nextExpected + size) return Decision.TOO_FAR_AHEAD
        val offset = (nonce - nextExpected).toInt()
        val word = offset / 64
        val bit = offset % 64
        if (seen[word] and (1L shl bit) != 0L) return Decision.REPLAY
        seen[word] = seen[word] or (1L shl bit)
        // Slide the window whenever the nonce we expect is already covered:
        // a gap that was filled earlier must not keep the window stuck.
        advance()
        return Decision.ACCEPTED
    }

    /** Slides the window past the contiguous nonces we have already seen. */
    private fun advance() {
        while (seen[0] and 1L == 1L) {
            // Shift the whole bitmap right by one bit (bit i -> bit i-1).
            // Walking from the high word down keeps the source words intact.
            for (i in seen.indices.reversed()) {
                val next = if (i + 1 < seen.size) seen[i + 1] else 0L
                seen[i] = (seen[i] ushr 1) or (next shl 63)
            }
            nextExpected++
        }
    }

    /** Forgets everything (new session). */
    fun reset() {
        nextExpected = 0
        seen.fill(0)
    }

    /** Serialises the window (big-endian: nextExpected, then the bitmap). */
    fun serialize(): ByteArray {
        val out = ByteArray(8 + seen.size * 8)
        for (i in 0 until 8) {
            out[i] = ((nextExpected ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        for (i in seen.indices) {
            for (b in 0 until 8) {
                out[8 + i * 8 + b] = ((seen[i] ushr (8 * (7 - b))) and 0xFF).toByte()
            }
        }
        return out
    }

    /** Restores a serialised window. */
    fun restore(bytes: ByteArray) {
        require(bytes.size == 8 + seen.size * 8) { "serialised window size mismatch" }
        var next = 0L
        for (i in 0 until 8) {
            next = (next shl 8) or (bytes[i].toLong() and 0xFF)
        }
        nextExpected = next
        for (i in seen.indices) {
            var v = 0L
            for (b in 0 until 8) {
                v = (v shl 8) or (bytes[8 + i * 8 + b].toLong() and 0xFF)
            }
            seen[i] = v
        }
    }
}
