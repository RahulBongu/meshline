package com.meshline.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary encode/decode for Meshline packets (PROTOCOL.md §1).
 *
 * Big-endian everywhere. Pure JVM — no Android imports — so the codec, the mesh
 * simulator (Phase B) and the unit tests all share exactly this code.
 *
 * Failure mode is always fail-closed: [decode] throws a [PacketException]
 * subclass for anything malformed (wrong version, length mismatch, trailing
 * garbage, oversized payload, bad timestamp, reserved/unsupported flags) and
 * never returns a partially-valid packet. The router's single rule for every
 * failure is "drop it, do not relay it" (PROTOCOL.md §8).
 *
 * Recoverable soft errors (unknown type) are also exceptions so the caller has
 * one error path, but [PacketException.UnknownType] means "ignore quietly"
 * rather than "malformed".
 */
object PacketCodec {

    const val PROTOCOL_VERSION: Int = 1
    const val MAX_TTL: Int = 7

    /** Canonical fixed header size: PROTOCOL.md §1 (32 bytes, always). */
    const val HEADER_BYTES: Int = 32
    const val SIGNATURE_BYTES: Int = 64
    const val MAX_PACKET_BYTES: Int = 64 * 1024
    const val MAX_PAYLOAD_BYTES: Int = MAX_PACKET_BYTES - HEADER_BYTES - SIGNATURE_BYTES

    /** Recipient value meaning "broadcast": 0xFF * 8. */
    val BROADCAST_ID: ByteArray = ByteArray(MeshPacket.ID_BYTES) { 0xFF.toByte() }

    // Offsets inside the 32-byte header (see PROTOCOL.md §1).
    private const val OFF_VERSION = 0
    private const val OFF_TYPE = 1
    private const val OFF_TTL = 2
    private const val OFF_FLAGS = 3
    private const val OFF_TIMESTAMP = 4   // 8 bytes
    private const val OFF_RESERVED = 12   // 2 bytes, must be zero
    private const val OFF_PAYLOAD_LEN = 14 // 2 bytes
    private const val OFF_SENDER = 16   // 8 bytes
    private const val OFF_RECIPIENT = 24 // 8 bytes
    // payload starts at OFF_RECIPIENT + ID_BYTES == 32 == HEADER_BYTES

    /**
     * Encode [packet] to its canonical wire bytes.
     *
     * The encoded flags byte is *derived* from packet state so the flags field
     * can never disagree with the packet content:
     *  - HAS_RECIPIENT set iff recipientId != broadcast.
     *  - HAS_SIGNATURE set iff signature != null.
     * Reserved bits are never set; COMPRESSED/FRAGMENTED are never set (MVP, §3).
     *
     * @throws PacketException.InvalidPacket if ttl / payload size / sender id are illegal.
     */
    fun encode(packet: MeshPacket): ByteArray {
        require(packet.version == PROTOCOL_VERSION) {
            "can only encode version $PROTOCOL_VERSION (got ${packet.version})"
        }
        if (packet.ttl < 0 || packet.ttl > MAX_TTL) {
            throw PacketException.InvalidPacket("ttl ${packet.ttl} out of range 0..$MAX_TTL")
        }
        if (packet.payload.size > MAX_PAYLOAD_BYTES) {
            throw PacketException.InvalidPacket(
                "payload ${packet.payload.size} bytes exceeds max $MAX_PAYLOAD_BYTES; fragment first"
            )
        }
        if (packet.senderId.contentEquals(BROADCAST_ID)) {
            throw PacketException.InvalidPacket("senderId must not be the broadcast value")
        }

        val flags = buildFlags(packet)
        val sigExtra = if (packet.signature != null) SIGNATURE_BYTES else 0
        val total = HEADER_BYTES + packet.payload.size + sigExtra
        if (total > MAX_PACKET_BYTES) {
            throw PacketException.InvalidPacket("packet $total bytes exceeds max $MAX_PACKET_BYTES")
        }

        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(packet.version.toByte())
        buf.put(packet.type.code)
        buf.put(packet.ttl.toByte())
        buf.put(flags)
        buf.putLong(packet.timestampMs)
        buf.putShort(0)                                        // reserved
        buf.putShort(packet.payload.size.toShort())
        buf.put(packet.senderId)
        buf.put(packet.recipientId)
        buf.put(packet.payload)
        if (packet.signature != null) buf.put(packet.signature)
        return buf.array()
    }

    /**
     * Canonical signing input (PROTOCOL.md §3.1): the full wire encoding with the
     * TTL byte (offset 2) set to `0x00` and the trailing 64-byte signature stripped.
     *
     * The flags byte keeps `HAS_SIGNATURE` set, as on the wire. Every other header
     * byte and the entire payload are covered. The result is therefore the same
     * for the same packet no matter which TTL the relays have decremented it to —
     * a signature made at TTL 7 still verifies after two relay hops at TTL 5.
     *
     * For a packet that is not yet signed, the returned input is what the signature
     * will cover once the signer creates one; verifiers pass the *decoded* packet
     * (whose signature field is the wire signature) and get the identical bytes.
     * Both paths agree because only the TTL byte is zeroed and only the signature
     * bytes are stripped.
     */
    fun signingInput(packet: MeshPacket): ByteArray {
        // Force HAS_SIGNATURE in the flags byte (a signer always produces a
        // signed wire packet), zero TTL, and strip the signature itself.
        val probe = MeshPacket(
            version = packet.version,
            type = packet.type,
            ttl = 0,
            flags = ((if (packet.isDirected) 0x01 else 0) or 0x02).toByte(),
            timestampMs = packet.timestampMs,
            payload = packet.payload,
            senderId = packet.senderId,
            recipientId = packet.recipientId,
            signature = ByteArray(SIGNATURE_BYTES), // dummy: forces the flag via buildFlags
        )
        return encode(probe).copyOfRange(0, HEADER_BYTES + packet.payload.size)
    }

    /**
     * Signing input straight from *encoded* signed bytes (PROTOCOL.md §3.1).
     * Zeros the TTL byte in a copy and strips the trailing signature. Throws
     * [PacketException.InvalidPacket] if the bytes are too small or claim to be
     * signed but are truncated, so a verifier never silently checks the wrong slice.
     */
    fun signingInput(encodedSignedBytes: ByteArray): ByteArray {
        if (encodedSignedBytes.size < HEADER_BYTES) {
            throw PacketException.InvalidPacket("signingInput: too small (${encodedSignedBytes.size} bytes)")
        }
        val flags = encodedSignedBytes[OFF_FLAGS].toInt()
        val hasSignature = flags and PacketFlags.HAS_SIGNATURE.toInt() != 0
        val unsignedLen = if (hasSignature) encodedSignedBytes.size - SIGNATURE_BYTES else encodedSignedBytes.size
        if (unsignedLen < HEADER_BYTES) {
            throw PacketException.InvalidPacket("signingInput: signed bytes truncated")
        }
        val out = encodedSignedBytes.copyOfRange(0, unsignedLen)
        out[OFF_TTL] = 0
        return out
    }

    /**
     * Decode [bytes] into a [MeshPacket], validating every field (PROTOCOL.md §§1-4).
     *
     * @param nowMs clock for the timestamp window; injectable so tests are deterministic.
     * @throws PacketException on any validation failure (see [PacketException] docs).
     */
    fun decode(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): MeshPacket {
        if (bytes.size < HEADER_BYTES) {
            throw PacketException.InvalidPacket("packet too small: ${bytes.size} bytes (< $HEADER_BYTES header)")
        }
        if (bytes.size > MAX_PACKET_BYTES) {
            throw PacketException.InvalidPacket("packet too large: ${bytes.size} bytes (> $MAX_PACKET_BYTES)")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val version = buf.get(OFF_VERSION).toInt() and 0xFF
        if (version != PROTOCOL_VERSION) throw PacketException.UnsupportedVersion(version)

        val typeCode = buf.get(OFF_TYPE)
        val type = PacketType.fromCode(typeCode)
            ?: throw PacketException.UnknownType(typeCode.toInt() and 0xFF)

        val ttl = buf.get(OFF_TTL).toInt() and 0xFF
        if (ttl > MAX_TTL) throw PacketException.InvalidPacket("ttl $ttl exceeds max $MAX_TTL")

        val flags = buf.get(OFF_FLAGS)
        val flagsInt = flags.toInt()
        if (flagsInt and PacketFlags.RESERVED_MASK.toInt() != 0) {
            throw PacketException.InvalidPacket("reserved flag bits set (flags=0x%02X)".format(flags))
        }
        if (flagsInt and PacketFlags.UNSUPPORTED_MASK.toInt() != 0) {
            throw PacketException.UnsupportedFlag(flags)
        }
        val hasRecipient = flagsInt and PacketFlags.HAS_RECIPIENT.toInt() != 0
        val hasSignature = flagsInt and PacketFlags.HAS_SIGNATURE.toInt() != 0

        val timestampMs = buf.getLong(OFF_TIMESTAMP)
        if (timestampMs <= 0L) throw PacketException.InvalidPacket("timestampMs must be > 0")
        val futureLimit = nowMs + MILLIS_PER_DAY
        val pastLimit = nowMs - MILLIS_PER_WEEK
        if (timestampMs > futureLimit) {
            throw PacketException.InvalidPacket("timestamp ${timestampMs}ms is too far in the future")
        }
        if (timestampMs < pastLimit) {
            throw PacketException.InvalidPacket("timestamp ${timestampMs}ms is too far in the past")
        }

        val reserved = buf.getShort(OFF_RESERVED)
        if (reserved != 0.toShort()) {
            throw PacketException.InvalidPacket("reserved header bytes must be zero (got $reserved)")
        }

        val payloadLen = buf.getShort(OFF_PAYLOAD_LEN).toInt() and 0xFFFF
        if (payloadLen > MAX_PAYLOAD_BYTES) {
            throw PacketException.InvalidPacket("payloadLen $payloadLen exceeds max $MAX_PAYLOAD_BYTES")
        }
        val expectedTotal = HEADER_BYTES + payloadLen + if (hasSignature) SIGNATURE_BYTES else 0
        if (bytes.size != expectedTotal) {
            throw PacketException.InvalidPacket(
                "length mismatch: header declares $expectedTotal bytes (payload $payloadLen" +
                    (if (hasSignature) " + signature" else "") + ") but got ${bytes.size}"
            )
        }

        val senderId = bytes.copyOfRange(OFF_SENDER, OFF_SENDER + MeshPacket.ID_BYTES)
        val recipientId = bytes.copyOfRange(OFF_RECIPIENT, OFF_RECIPIENT + MeshPacket.ID_BYTES)
        if (senderId.contentEquals(BROADCAST_ID)) {
            throw PacketException.InvalidPacket("senderId must not be the broadcast value")
        }
        // HAS_RECIPIENT <-> recipientId cross-check (§3).
        val recipientIsBroadcast = recipientId.contentEquals(BROADCAST_ID)
        if (hasRecipient && recipientIsBroadcast) {
            throw PacketException.InvalidPacket("HAS_RECIPIENT set but recipient is broadcast")
        }
        if (!hasRecipient && !recipientIsBroadcast) {
            throw PacketException.InvalidPacket("HAS_RECIPIENT clear but recipient is not broadcast")
        }

        val payloadStart = HEADER_BYTES
        val payload = bytes.copyOfRange(payloadStart, payloadStart + payloadLen)
        val signature = if (hasSignature) {
            bytes.copyOfRange(payloadStart + payloadLen, payloadStart + payloadLen + SIGNATURE_BYTES)
        } else null

        return MeshPacket(
            version = version,
            type = type,
            ttl = ttl,
            flags = flags,
            timestampMs = timestampMs,
            payload = payload,
            senderId = senderId,
            recipientId = recipientId,
            signature = signature,
        )
    }

    /** Decoded timestamp window (PROTOCOL.md §4): ± drift tolerant, replay unfriendly. */
    private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000
    private const val MILLIS_PER_WEEK: Long = 7L * 24 * 60 * 60 * 1000

    private fun buildFlags(packet: MeshPacket): Byte {
        var flags = 0
        if (packet.isDirected) flags = flags or PacketFlags.HAS_RECIPIENT.toInt()
        if (packet.signature != null) flags = flags or PacketFlags.HAS_SIGNATURE.toInt()
        return flags.toByte()
    }
}
