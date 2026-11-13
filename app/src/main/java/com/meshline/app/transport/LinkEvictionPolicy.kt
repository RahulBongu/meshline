package com.meshline.app.transport

/**
 * When a link that never proved a peer identity must be closed.
 *
 * The connection budget is six GATT slots ([BleUuids.MAX_CONNECTIONS]). A
 * device that connects and then stays silent — or talks without ever proving
 * who it is — holds a slot that the mesh cannot use, and six such devices
 * starve the node completely. The guard closes the link and gives the slot
 * back.
 *
 * Two limits, because "silent" and "unproven" are different failures:
 *  - [SILENT_LINK_TIMEOUT_MS]: nothing at all arrived on the link. The peer
 *    is not a Meshline node (or it is broken) — nothing will ever make this
 *    link useful.
 *  - [UNPROVEN_LINK_TIMEOUT_MS]: traffic arrived, but the peer never proved
 *    its identity with a verified ANNOUNCE. It may be an impostor, and it is
 *    not addressable either, so it cannot be used for anything.
 *
 * A well-behaved peer proves itself in about a second: it answers the
 * announce we send when the link comes up. The limits are therefore generous
 * enough to survive a lost announce, but short enough that a stuck peer
 * cannot hold the budget for long.
 *
 * Pure JVM — no Android imports — so the policy is unit-tested directly (see
 * LinkEvictionPolicyTest).
 */
object LinkEvictionPolicy {

    /** No traffic at all on the link for this long → close it. */
    const val SILENT_LINK_TIMEOUT_MS: Long = 12_000L

    /** Traffic but no verified identity for this long → close it. */
    const val UNPROVEN_LINK_TIMEOUT_MS: Long = 45_000L

    /**
     * How long a link stays *interesting* for the app layer (it announces
     * again while a peer is unproven). After that the link is left alone: it
     * is either proved or it was closed already.
     */
    const val UNPROVEN_INTEREST_MS: Long = 60_000L

    /**
     * @param openedAtMs when the link came up.
     * @param lastTrafficAtMs when the last packet arrived on it.
     * @param nowMs the current time.
     * @return true when the link must be closed.
     */
    fun shouldEvict(openedAtMs: Long, lastTrafficAtMs: Long, nowMs: Long): Boolean {
        if (nowMs - lastTrafficAtMs >= SILENT_LINK_TIMEOUT_MS) return true
        return nowMs - openedAtMs >= UNPROVEN_LINK_TIMEOUT_MS
    }
}
