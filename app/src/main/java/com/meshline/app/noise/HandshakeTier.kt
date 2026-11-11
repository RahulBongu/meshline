package com.meshline.app.noise

/**
 * How much a sender may take from the **reserved** half of the global
 * handshake budget.
 *
 * The reserved half exists so that a flood of unknown senders cannot starve
 * the people the user actually talks to. Splitting it per sender stops a
 * single peer from taking all of it, and the ranking makes a QR-verified
 * contact worth more than a plain neighbour:
 *
 * | tier | who | reserved ops / minute / sender | handshakes / minute |
 * |---|---|---:|---:|
 * | `CONTACT` | QR-verified contact | **60** | 12 |
 * | `NEIGHBOUR` | connected **and** it has delivered a verified ANNOUNCE | **30** | 6 |
 * | `JUNK` | everybody else | **0** (general pool only) | — |
 *
 * One XX handshake costs 5 X25519 operations, so the numbers above are
 * "handshakes per minute per peer".
 */
enum class HandshakeTier(
    /** How many X25519 operations per minute this tier may take from the reserved pool. */
    val reservedOpsPerMinute: Int,
) {
    /** QR-verified contact — the highest rank. */
    CONTACT(60),

    /** A connected neighbour that has delivered a verified ANNOUNCE. */
    NEIGHBOUR(30),

    /** Unknown sender: the general pool only, never the reserve. */
    JUNK(0),
}

/**
 * Decides the handshake tier of a sender.
 *
 * The rule the project cares about: **a connected neighbour is protected
 * only after it has delivered a verified ANNOUNCE.** Being connected is not
 * enough — a peer that connects and stays silent must not be able to spend
 * the reserved budget. "Delivered a verified ANNOUNCE" means its key is in
 * the key cache, because the cache only learns keys from verified announces.
 */
object HandshakeTiers {

    /**
     * @param verified the peer is a QR-verified contact.
     * @param announced the peer delivered a verified ANNOUNCE (its key is cached).
     * @param connected the peer is currently linked to us.
     */
    fun tierOf(verified: Boolean, announced: Boolean, connected: Boolean): HandshakeTier = when {
        verified -> HandshakeTier.CONTACT
        announced && connected -> HandshakeTier.NEIGHBOUR
        else -> HandshakeTier.JUNK
    }
}
