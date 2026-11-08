package com.meshline.app.protocol

/**
 * In-memory representation of one Meshline packet (PROTOCOL.md §1).
 *
 * This is a plain data holder — encode/decode lives in [PacketCodec] so the
 * router and tests (which are pure JVM) never need Android classes.
 *
 * @param version    protocol version, always [PacketCodec.PROTOCOL_VERSION] on encode.
 * @param type       packet type (§2).
 * @param ttl        hops remaining, 0..[PacketCodec.MAX_TTL].
 * @param flags      bitfield, see [PacketFlags]. The codec derives HAS_RECIPIENT /
 *                   HAS_SIGNATURE from [recipientId] / [signature] presence on encode
 *                   and checks them for consistency on decode.
 * @param timestampMs Unix epoch milliseconds, big-endian unsigned on the wire (§4).
 * @param payload    opaque payload bytes; higher layers define the shape per type (§6).
 * @param senderId   8-byte truncated SHA-256 of the sender's Ed25519 public key.
 * @param recipientId 8-byte recipient, or [PacketCodec.BROADCAST_ID] for broadcast.
 * @param signature  64-byte Ed25519 signature, or null when unsigned (§3).
 */
data class MeshPacket(
    val version: Int,
    val type: PacketType,
    val ttl: Int,
    val flags: Byte,
    val timestampMs: Long,
    val payload: ByteArray,
    val senderId: ByteArray,
    val recipientId: ByteArray,
    val signature: ByteArray? = null,
) {
    init {
        require(senderId.size == ID_BYTES) { "senderId must be $ID_BYTES bytes, got ${senderId.size}" }
        require(recipientId.size == ID_BYTES) { "recipientId must be $ID_BYTES bytes, got ${recipientId.size}" }
        require(signature == null || signature.size == SIGNATURE_BYTES) {
            "signature must be null or $SIGNATURE_BYTES bytes"
        }
        check(ttl in 0..PacketCodec.MAX_TTL) { "ttl $ttl out of range 0..${PacketCodec.MAX_TTL}" }
        check(payload.size <= PacketCodec.MAX_PAYLOAD_BYTES) {
            "payload ${payload.size} bytes exceeds max ${PacketCodec.MAX_PAYLOAD_BYTES} (fragment first)"
        }
        check(timestampMs > 0L) { "timestampMs must be > 0" }
        // flags must match the state the codec derives from the packet (see PacketCodec.buildFlags).
        val expected = (if (!recipientId.contentEquals(PacketCodec.BROADCAST_ID)) 0x01 else 0) or
            (if (signature != null) 0x02 else 0)
        check(flags.toInt() == expected) {
            "flags 0x%02X must equal derived 0x%02X (HAS_RECIPIENT=%s, HAS_SIGNATURE=%s)".format(
                flags, expected, isDirected, signature != null
            )
        }
    }

    /** True if this packet is addressed to a specific peer (not broadcast). */
    val isDirected: Boolean get() = !recipientId.contentEquals(PacketCodec.BROADCAST_ID)

    /** Stable message ID for dedupe (§4). Full 32-byte SHA-256, see [MessageIds]. */
    val messageId: ByteArray get() = MessageIds.compute(this)

    // ByteArray-aware equals/hashCode (data class would use referential equality).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return version == other.version &&
            type == other.type &&
            ttl == other.ttl &&
            flags == other.flags &&
            timestampMs == other.timestampMs &&
            payload.contentEquals(other.payload) &&
            senderId.contentEquals(other.senderId) &&
            recipientId.contentEquals(other.recipientId) &&
            ((signature == null && other.signature == null) ||
                (signature != null && other.signature != null && signature.contentEquals(other.signature)))
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + type.hashCode()
        h = 31 * h + ttl
        h = 31 * h + flags.toInt()
        h = 31 * h + timestampMs.hashCode()
        h = 31 * h + payload.contentHashCode()
        h = 31 * h + senderId.contentHashCode()
        h = 31 * h + recipientId.contentHashCode()
        h = 31 * h + (signature?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        const val ID_BYTES = 8
        const val SIGNATURE_BYTES = 64
    }
}
