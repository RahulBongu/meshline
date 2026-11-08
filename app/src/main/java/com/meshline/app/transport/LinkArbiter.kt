package com.meshline.app.transport

/**
 * Lower-ID-as-central duplicate-link arbitration (Phase C).
 *
 * Why this exists: peer IDs are only known from the ANNOUNCE packet, which
 * arrives *after* a BLE connection exists. Both phones therefore scan and
 * connect to each other at roughly the same time, and briefly BOTH hold a
 * central link (A→B and B→A). The protocol's tie-break — "the peer with the
 * lower senderId acts as central" (PROTOCOL.md §9) — then decides
 * deterministically which of the two links survives, so both nodes converge
 * on exactly one link without extra messages:
 *
 *   - The **lower** ID keeps its central link (its server half is dropped
 *     when the peer closes its own duplicate).
 *   - The **higher** ID closes its central link (logs `duplicate link closed`)
 *     and keeps receiving on the server half the lower ID maintains.
 *
 * [resolveDuplicate] is pure JVM and unit-tested with a fake transport (see
 * LinkArbiterTest). BleTransport applies it whenever both a client and a
 * server link to the same peer are present after the peer's ID is learned.
 */
object LinkArbiter {

    enum class DuplicateDecision {
        /** Keep any local central link (we are the lower ID, or no duplicate). */
        KEEP_LOCAL_CENTRAL,
        /** Close the local central link (we are the higher ID in a duplicate). */
        DROP_LOCAL_CENTRAL,
    }

    /** Unsigned lexicographic compare: negative if a < b, etc. */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }

    /** True if the local node is the lower ID (hence the canonical central). */
    fun localIsLower(localId: ByteArray, peerId: ByteArray): Boolean = compareUnsigned(localId, peerId) < 0

    /**
     * @param duplicateLink true when both a local-central and a peer-as-central
     * link to the same peer currently exist.
     * @return whether the local central link survives. When [duplicateLink] is
     * false this returns true (nothing to arbitrate); when true it returns
     * `localIsLower`, so exactly one side of the pair returns true.
     */
    fun shouldKeepLocalCentral(localId: ByteArray, peerId: ByteArray, duplicateLink: Boolean): Boolean {
        if (!duplicateLink) return true
        return localIsLower(localId, peerId)
    }

    fun resolveDuplicate(localId: ByteArray, peerId: ByteArray, duplicateLink: Boolean): DuplicateDecision {
        return if (shouldKeepLocalCentral(localId, peerId, duplicateLink)) DuplicateDecision.KEEP_LOCAL_CENTRAL
        else DuplicateDecision.DROP_LOCAL_CENTRAL
    }
}
