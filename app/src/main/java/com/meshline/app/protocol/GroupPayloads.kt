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
 *    Payload: epoch(2 BE) | kind(1) | groupId(16) | nameLen(1) | name | rosterLen(1) | roster
 *    [ | newAdmin(32) for kind=TRANSFER_ADMIN]. Kinds: 1 leave, 2 rename, 3 roster-update,
 *    4 remove-member, 5 transfer-admin (PROTOCOL.md §6.7, GROUPS_DESIGN.md §6).
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
        ROSTER_UPDATE(3),
        /** Admin-only: removes one roster member (new epoch + new key, Feature 6). */
        REMOVE_MEMBER(4),
        /** Admin-only: transfers admin rights to another roster member (new epoch, Feature 6). */
        TRANSFER_ADMIN(5);

        companion object {
            fun fromCode(code: Byte): ControlKind? = entries.firstOrNull { it.code == code }
        }
    }

    data class GroupControl(
        val epoch: Int,
        val kind: ControlKind,
        /** Identifies the group the control belongs to (v1+; the group tag rotates per epoch). */
        val groupId: ByteArray,
        val name: String,
        val roster: List<RosterMember>,
        /** For [ControlKind.TRANSFER_ADMIN]: the new admin's Ed25519 public key. */
        val newAdmin: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupControl) return false
            return epoch == other.epoch &&
                kind == other.kind &&
                groupId.contentEquals(other.groupId) &&
                name == other.name &&
                roster == other.roster &&
                newAdmin.contentEqualsOrNull(other.newAdmin)
        }

        override fun hashCode(): Int {
            var h = epoch
            h = 31 * h + kind.hashCode()
            h = 31 * h + groupId.contentHashCode()
            h = 31 * h + name.hashCode()
            h = 31 * h + roster.hashCode()
            h = 31 * h + (newAdmin?.contentHashCode() ?: 0)
            return h
        }

        private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean {
            if (this == null && other == null) return true
            if (this == null || other == null) return false
            return contentEquals(other)
        }
    }

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
        require(ctrl.groupId.size == GROUP_ID_BYTES) { "groupId must be $GROUP_ID_BYTES bytes" }
        val nameBytes = ctrl.name.trim().toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 255) { "name exceeds 255 bytes" }
        require(ctrl.roster.size <= MAX_MEMBERS) { "roster exceeds max members ($MAX_MEMBERS)" }
        if (ctrl.kind == ControlKind.TRANSFER_ADMIN) {
            require(ctrl.newAdmin != null && ctrl.newAdmin!!.size == 32) {
                "TRANSFER_ADMIN requires the new admin's 32-byte Ed25519 pk"
            }
        } else {
            require(ctrl.newAdmin == null) { "newAdmin only valid for TRANSFER_ADMIN" }
        }

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

        val total = 2 + 1 + GROUP_ID_BYTES + 1 + nameBytes.size + 1 + rosterBytesLen +
            (if (ctrl.newAdmin != null) 32 else 0)
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((ctrl.epoch and 0xFFFF).toShort())
        buf.put(ctrl.kind.code)
        buf.put(ctrl.groupId)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(ctrl.roster.size.toByte())
        for (b in encodedRoster) buf.put(b)
        ctrl.newAdmin?.let { buf.put(it) }
        return buf.array()
    }

    fun decodeGroupControl(payload: ByteArray): GroupControl {
        if (payload.size < 2 + 1 + GROUP_ID_BYTES + 1) throw PacketException.InvalidPacket("group control too short (${payload.size})")
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val epoch = buf.short.toInt() and 0xFFFF
        val kind = ControlKind.fromCode(buf.get()) ?: throw PacketException.InvalidPacket("unknown control kind")
        val groupId = ByteArray(GROUP_ID_BYTES)
        buf.get(groupId)
        val nameLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < nameLen + 2) throw PacketException.InvalidPacket("control name truncated")
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
        val newAdmin: ByteArray? = if (kind == ControlKind.TRANSFER_ADMIN) {
            if (buf.remaining() < 32) throw PacketException.InvalidPacket("transfer admin pk truncated")
            ByteArray(32).also { buf.get(it) }
        } else {
            null
        }
        return GroupControl(epoch, kind, groupId, name, roster, newAdmin)
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

    // ---- Roster storage encoding ----
    //
    // The roster has to survive a restart (the epoch key alone is not enough:
    // membership is what authorises a sender, and the admin badge comes from
    // the roster's admin key). This is the **local** encoding used by the
    // encrypted `groups` table, not a wire format: it keeps the same per-member
    // entry layout as GROUP_CONTROL (`pk(32) | nickLen(1) | nick | avatar(1)`)
    // so the two cannot drift, but prefixes the list with a one-byte count
    // instead of embedding it in a control frame.

    fun encodeRoster(roster: List<RosterMember>): ByteArray {
        require(roster.size <= MAX_MEMBERS) { "roster exceeds max members ($MAX_MEMBERS)" }
        val entries = roster.map { m ->
            require(m.ed25519PublicKey.size == 32) { "roster member key must be 32 bytes" }
            val nickBytes = Payloads.sanitizeNickname(m.nickname).toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(32 + 1 + nickBytes.size + 1).order(ByteOrder.BIG_ENDIAN)
            buf.put(m.ed25519PublicKey)
            buf.put(nickBytes.size.toByte())
            buf.put(nickBytes)
            buf.put(m.avatarIndex)
            buf.array()
        }
        val total = 1 + entries.sumOf { it.size }
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(roster.size.toByte())
        for (entry in entries) buf.put(entry)
        return buf.array()
    }

    fun decodeRoster(bytes: ByteArray): List<RosterMember> {
        if (bytes.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buf.get().toInt() and 0xFF
        if (count > MAX_MEMBERS) throw PacketException.InvalidPacket("stored roster exceeds max members ($count)")
        val roster = ArrayList<RosterMember>(count)
        for (i in 0 until count) {
            if (buf.remaining() < 34) throw PacketException.InvalidPacket("stored roster entry truncated")
            val edPk = ByteArray(32)
            buf.get(edPk)
            val nickLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nickLen + 1) throw PacketException.InvalidPacket("stored roster nick truncated")
            val nickBytes = ByteArray(nickLen)
            buf.get(nickBytes)
            val avatar = buf.get()
            roster.add(RosterMember(edPk, nickBytes.toString(Charsets.UTF_8), avatar))
        }
        return roster
    }

    // ---- Group message plaintext: reply-to + disappearing ----
    //
    // Legacy plaintext is raw UTF-8 text and stays byte-identical: a message
    // with neither a parent nor a TTL is encoded as plain text. When either is
    // present the plaintext uses a versioned form:
    //
    //   0x02 | flags(1) | [parentLen(1) + parentHex] | [ttlSeconds(4 BE)] | textLen(2 BE) + text
    //
    // flags: bit0 = hasParent, bit1 = hasTtl (other bits must be 0).
    // Detection on decode: leading byte 0x02 **and** the structure parses
    // cleanly; anything else is legacy raw text (so old messages keep working
    // and a stray 0x02 in text can never lose a message).

    /** The decrypted body of a group message, with optional reply-to / TTL. */
    data class GroupText(
        val text: String,
        val parentHex: String? = null,
        val ttlSeconds: Int? = null,
    )

    const val GROUP_TEXT_VERSION: Byte = 0x02
    const val MAX_GROUP_TEXT_BYTES: Int = 4_000
    const val MAX_GROUP_TTL_SECONDS: Int = 7 * 24 * 60 * 60
    private const val GT_FLAG_HAS_PARENT = 0x01
    private const val GT_FLAG_HAS_TTL = 0x02

    fun encodeGroupText(gt: GroupText): ByteArray {
        val textBytes = gt.text.toByteArray(Charsets.UTF_8)
        if (textBytes.isEmpty() || textBytes.size > MAX_GROUP_TEXT_BYTES) {
            throw PacketException.InvalidPacket("group text must be 1..$MAX_GROUP_TEXT_BYTES bytes")
        }
        if (gt.parentHex == null && gt.ttlSeconds == null) return textBytes
        val parentBytes = gt.parentHex?.toByteArray(Charsets.UTF_8)
        if (gt.parentHex != null && (parentBytes == null || parentBytes.isEmpty() || parentBytes.size > 64)) {
            throw PacketException.InvalidPacket("group parentHex must be 1..64 bytes")
        }
        if (gt.ttlSeconds != null && gt.ttlSeconds !in 1..MAX_GROUP_TTL_SECONDS) {
            throw PacketException.InvalidPacket("group ttlSeconds must be 1..$MAX_GROUP_TTL_SECONDS")
        }
        val flags = ((if (gt.parentHex != null) GT_FLAG_HAS_PARENT else 0) or
            (if (gt.ttlSeconds != null) GT_FLAG_HAS_TTL else 0))
        val total = 2 +
            (if (parentBytes != null) 1 + parentBytes.size else 0) +
            (if (gt.ttlSeconds != null) 4 else 0) +
            2 + textBytes.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(GROUP_TEXT_VERSION)
        buf.put(flags.toByte())
        if (parentBytes != null) {
            buf.put(parentBytes.size.toByte())
            buf.put(parentBytes)
        }
        gt.ttlSeconds?.let { buf.putInt(it) }
        buf.putShort(textBytes.size.toShort())
        buf.put(textBytes)
        return buf.array()
    }

    fun decodeGroupText(payload: ByteArray): GroupText {
        if (payload.isNotEmpty() && payload[0] == GROUP_TEXT_VERSION) {
            parseStructuredGroupText(payload)?.let { return it }
        }
        // Legacy raw text (also the fallback for a malformed versioned form).
        return GroupText(payload.toString(Charsets.UTF_8))
    }

    private fun parseStructuredGroupText(payload: ByteArray): GroupText? {
        if (payload.size < 4) return null
        val flags = payload[1].toInt() and 0xFF
        if (flags and (GT_FLAG_HAS_PARENT or GT_FLAG_HAS_TTL).inv() != 0) return null
        var pos = 2
        var parentHex: String? = null
        if (flags and GT_FLAG_HAS_PARENT != 0) {
            if (pos >= payload.size) return null
            val len = payload[pos].toInt() and 0xFF
            if (len !in 1..64 || pos + 1 + len > payload.size) return null
            parentHex = payload.copyOfRange(pos + 1, pos + 1 + len).toString(Charsets.UTF_8)
            pos += 1 + len
        }
        var ttlSeconds: Int? = null
        if (flags and GT_FLAG_HAS_TTL != 0) {
            if (pos + 4 > payload.size) return null
            val ttl = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt(pos)
            if (ttl !in 1..MAX_GROUP_TTL_SECONDS) return null
            ttlSeconds = ttl
            pos += 4
        }
        if (pos + 2 > payload.size) return null
        val textLen = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getShort(pos).toInt() and 0xFFFF
        pos += 2
        if (textLen < 1 || textLen > MAX_GROUP_TEXT_BYTES || pos + textLen != payload.size) return null
        val text = payload.copyOfRange(pos, pos + textLen).toString(Charsets.UTF_8)
        return GroupText(text, parentHex, ttlSeconds)
    }
}
