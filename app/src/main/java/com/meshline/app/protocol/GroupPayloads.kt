package com.meshline.app.protocol

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Group messaging & control payloads (PROTOCOL.md §6.7, GROUPS_DESIGN.md).
 *
 * Wire types:
 *  - GROUP_MESSAGE (0x09): recipientId = 8-byte group tag, HAS_SIGNATURE set.
 *    Payload: epoch(2 BE) | nonce(8) | ciphertext(N).
 *    Associated data: groupTag(8) | epoch(2 BE) | senderId(8) (18 bytes).
 *  - GROUP_CONTROL (0x0A): recipientId = 8-byte group tag, HAS_SIGNATURE set (signed by admin).
 *    Payload: epoch(2 BE) | kind(1) | nameLen(1) | name | rosterLen(1) | roster.
 */
object GroupPayloads {

    const val GROUP_ID_BYTES = 16
    const val GROUP_TAG_BYTES = 8
    const val EPOCH_KEY_BYTES = 32
    const val NONCE_BYTES = 8
    const val MAX_MEMBERS = 20
    const val MAX_GROUP_NAME_CHARS = 32
    val GROUP_TAG_INFO: ByteArray = "meshline-group-tag".toByteArray(Charsets.UTF_8)
    val INVITE_MAGIC: ByteArray = byteArrayOf(0x4D, 0x47, 0x52, 0x50) // "MGRP"

    data class RosterMember(
        val ed25519PublicKey: ByteArray,
        val nickname: String,
        val avatarIndex: Byte,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RosterMember) return false
            return ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
                nickname == other.nickname &&
                avatarIndex == other.avatarIndex
        }

        override fun hashCode(): Int {
            var h = ed25519PublicKey.contentHashCode()
            h = 31 * h + nickname.hashCode()
            h = 31 * h + avatarIndex.toInt()
            return h
        }
    }

    data class GroupMessage(
        val epoch: Int,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupMessage) return false
            return epoch == other.epoch &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var h = epoch
            h = 31 * h + nonce.contentHashCode()
            h = 31 * h + ciphertext.contentHashCode()
            return h
        }
    }

    enum class ControlKind(val code: Byte) {
        LEAVE(1),
        RENAME(2),
        ROSTER_UPDATE(3);

        companion object {
            fun fromCode(code: Byte): ControlKind? = entries.firstOrNull { it.code == code }
        }
    }

    data class GroupControl(
        val epoch: Int,
        val kind: ControlKind,
        val name: String,
        val roster: List<RosterMember>,
    )

    data class GroupInvite(
        val groupId: ByteArray,
        val name: String,
        val icon: Byte,
        val epoch: Int,
        val epochKey: ByteArray,
        val adminPubkey: ByteArray,
        val roster: List<RosterMember>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupInvite) return false
            return groupId.contentEquals(other.groupId) &&
                name == other.name &&
                icon == other.icon &&
                epoch == other.epoch &&
                epochKey.contentEquals(other.epochKey) &&
                adminPubkey.contentEquals(other.adminPubkey) &&
                roster == other.roster
        }

        override fun hashCode(): Int {
            var h = groupId.contentHashCode()
            h = 31 * h + name.hashCode()
            h = 31 * h + icon.toInt()
            h = 31 * h + epoch
            h = 31 * h + epochKey.contentHashCode()
            h = 31 * h + adminPubkey.contentHashCode()
            h = 31 * h + roster.hashCode()
            return h
        }
    }

    data class ChannelMessage(
        val channel: String,
        val text: String,
    )

    /**
     * Derives the 8-byte group tag from the 32-byte epoch key using HKDF-SHA256 (RFC 5869):
     *   tag = HKDF-SHA256(epochKey, info = "meshline-group-tag", length = 8)
     */
    fun deriveGroupTag(epochKey: ByteArray): ByteArray {
        require(epochKey.size == EPOCH_KEY_BYTES) { "epochKey must be $EPOCH_KEY_BYTES bytes" }
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(epochKey, null, GROUP_TAG_INFO))
        val out = ByteArray(GROUP_TAG_BYTES)
        hkdf.generateBytes(out, 0, GROUP_TAG_BYTES)
        return out
    }

    /**
     * Computes the 18-byte AEAD Associated Data for Group Messages (PROTOCOL.md §6.7.1):
     *   ad = groupTag(8) || epoch(2 BE) || senderId(8)
     */
    fun computeAssociatedData(groupTag: ByteArray, epoch: Int, senderId: ByteArray): ByteArray {
        require(groupTag.size == GROUP_TAG_BYTES) { "groupTag must be $GROUP_TAG_BYTES bytes" }
        require(senderId.size == MeshPacket.ID_BYTES) { "senderId must be ${MeshPacket.ID_BYTES} bytes" }
        val buf = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN)
        buf.put(groupTag)
        buf.putShort((epoch and 0xFFFF).toShort())
        buf.put(senderId)
        return buf.array()
    }

    // ---- GROUP_MESSAGE Encoding / Decoding ----

    fun encodeGroupMessage(msg: GroupMessage): ByteArray {
        require(msg.nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        require(msg.ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        val buf = ByteBuffer.allocate(2 + NONCE_BYTES + msg.ciphertext.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((msg.epoch and 0xFFFF).toShort())
        buf.put(msg.nonce)
        buf.put(msg.ciphertext)
        return buf.array()
    }

    fun decodeGroupMessage(payload: ByteArray): GroupMessage {
        if (payload.size < 2 + NONCE_BYTES + 16) {
            throw PacketException.InvalidPacket("group message payload too small (${payload.size} bytes)")
        }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val epoch = buf.short.toInt() and 0xFFFF
        val nonce = ByteArray(NONCE_BYTES)
        buf.get(nonce)
        val ciphertext = ByteArray(payload.size - 2 - NONCE_BYTES)
        buf.get(ciphertext)
        return GroupMessage(epoch, nonce, ciphertext)
    }

    // ---- GROUP_CONTROL Encoding / Decoding ----

    fun encodeGroupControl(ctrl: GroupControl): ByteArray {
        val nameBytes = ctrl.name.trim().toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 255) { "name exceeds 255 bytes" }
        require(ctrl.roster.size <= MAX_MEMBERS) { "roster exceeds max members ($MAX_MEMBERS)" }

        var rosterBytesLen = 0
        val encodedRoster = mutableListOf<ByteArray>()
        for (m in ctrl.roster) {
            val nickBytes = Payloads.sanitizeNickname(m.nickname).toByteArray(Charsets.UTF_8)
            val mBuf = ByteBuffer.allocate(32 + 1 + nickBytes.size + 1).order(ByteOrder.BIG_ENDIAN)
            mBuf.put(m.ed25519PublicKey)
            mBuf.put(nickBytes.size.toByte())
            mBuf.put(nickBytes)
            mBuf.put(m.avatarIndex)
            val b = mBuf.array()
            encodedRoster.add(b)
            rosterBytesLen += b.size
        }

        val total = 2 + 1 + 1 + nameBytes.size + 1 + rosterBytesLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((ctrl.epoch and 0xFFFF).toShort())
        buf.put(ctrl.kind.code)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(ctrl.roster.size.toByte())
        for (b in encodedRoster) buf.put(b)
        return buf.array()
    }

    fun decodeGroupControl(payload: ByteArray): GroupControl {
        if (payload.size < 5) throw PacketException.InvalidPacket("group control too short (${payload.size})")
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val epoch = buf.short.toInt() and 0xFFFF
        val kindCode = buf.get()
        val kind = ControlKind.fromCode(kindCode) ?: throw PacketException.InvalidPacket("unknown control kind $kindCode")
        val nameLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < nameLen + 1) throw PacketException.InvalidPacket("control name truncated")
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val name = nameBytes.toString(Charsets.UTF_8)
        val rosterCount = buf.get().toInt() and 0xFF
        val roster = mutableListOf<RosterMember>()
        for (i in 0 until rosterCount) {
            if (buf.remaining() < 34) throw PacketException.InvalidPacket("control roster entry truncated")
            val edPk = ByteArray(32)
            buf.get(edPk)
            val nickLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nickLen + 1) throw PacketException.InvalidPacket("roster nick truncated")
            val nickBytes = ByteArray(nickLen)
            buf.get(nickBytes)
            val nick = nickBytes.toString(Charsets.UTF_8)
            val avatar = buf.get()
            roster.add(RosterMember(edPk, nick, avatar))
        }
        return GroupControl(epoch, kind, name, roster)
    }

    // ---- Group Invite Encoding / Decoding ----

    fun encodeGroupInvite(invite: GroupInvite): ByteArray {
        val nameBytes = invite.name.trim().toByteArray(Charsets.UTF_8)
        require(invite.groupId.size == GROUP_ID_BYTES) { "groupId must be $GROUP_ID_BYTES bytes" }
        require(invite.epochKey.size == EPOCH_KEY_BYTES) { "epochKey must be $EPOCH_KEY_BYTES bytes" }
        require(invite.adminPubkey.size == 32) { "adminPubkey must be 32 bytes" }

        val encodedMembers = mutableListOf<ByteArray>()
        var membersLen = 0
        for (m in invite.roster) {
            val nickBytes = Payloads.sanitizeNickname(m.nickname).toByteArray(Charsets.UTF_8)
            val mBuf = ByteBuffer.allocate(32 + 1 + nickBytes.size + 1).order(ByteOrder.BIG_ENDIAN)
            mBuf.put(m.ed25519PublicKey)
            mBuf.put(nickBytes.size.toByte())
            mBuf.put(nickBytes)
            mBuf.put(m.avatarIndex)
            val b = mBuf.array()
            encodedMembers.add(b)
            membersLen += b.size
        }

        val total = 4 + GROUP_ID_BYTES + 1 + nameBytes.size + 1 + 2 + EPOCH_KEY_BYTES + 32 + 1 + membersLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(INVITE_MAGIC)
        buf.put(invite.groupId)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(invite.icon)
        buf.putShort((invite.epoch and 0xFFFF).toShort())
        buf.put(invite.epochKey)
        buf.put(invite.adminPubkey)
        buf.put(invite.roster.size.toByte())
        for (b in encodedMembers) buf.put(b)
        return buf.array()
    }

    fun isGroupInvite(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == INVITE_MAGIC[0] &&
            bytes[1] == INVITE_MAGIC[1] &&
            bytes[2] == INVITE_MAGIC[2] &&
            bytes[3] == INVITE_MAGIC[3]
    }

    fun decodeGroupInvite(bytes: ByteArray): GroupInvite {
        if (!isGroupInvite(bytes)) throw PacketException.InvalidPacket("not a group invite")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(4) // skip magic
        val groupId = ByteArray(GROUP_ID_BYTES)
        buf.get(groupId)
        val nameLen = buf.get().toInt() and 0xFF
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val name = nameBytes.toString(Charsets.UTF_8)
        val icon = buf.get()
        val epoch = buf.short.toInt() and 0xFFFF
        val epochKey = ByteArray(EPOCH_KEY_BYTES)
        buf.get(epochKey)
        val adminPk = ByteArray(32)
        buf.get(adminPk)
        val rosterCount = buf.get().toInt() and 0xFF
        val roster = mutableListOf<RosterMember>()
        for (i in 0 until rosterCount) {
            val edPk = ByteArray(32)
            buf.get(edPk)
            val nickLen = buf.get().toInt() and 0xFF
            val nickBytes = ByteArray(nickLen)
            buf.get(nickBytes)
            val nick = nickBytes.toString(Charsets.UTF_8)
            val avatar = buf.get()
            roster.add(RosterMember(edPk, nick, avatar))
        }
        return GroupInvite(groupId, name, icon, epoch, epochKey, adminPk, roster)
    }

    // ---- Channel Message Encoding / Decoding ----

    fun encodeChannelMessage(msg: ChannelMessage): ByteArray {
        val channelBytes = msg.channel.trim().toByteArray(Charsets.UTF_8)
        require(channelBytes.isNotEmpty() && channelBytes.size <= 64) { "channel must be 1..64 bytes" }
        val textBytes = msg.text.toByteArray(Charsets.UTF_8)
        require(textBytes.isNotEmpty()) { "text must not be empty" }
        val buf = ByteBuffer.allocate(1 + channelBytes.size + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(channelBytes.size.toByte())
        buf.put(channelBytes)
        buf.put(textBytes)
        return buf.array()
    }

    fun decodeChannelMessage(payload: ByteArray): ChannelMessage {
        if (payload.size < 2) throw PacketException.InvalidPacket("channel message payload too short")
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val chLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < chLen + 1) throw PacketException.InvalidPacket("channel name truncated")
        val chBytes = ByteArray(chLen)
        buf.get(chBytes)
        val textBytes = ByteArray(buf.remaining())
        buf.get(textBytes)
        return ChannelMessage(chBytes.toString(Charsets.UTF_8), textBytes.toString(Charsets.UTF_8))
    }
}
