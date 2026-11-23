package com.meshline.app.groups

import com.meshline.app.protocol.GroupPayloads
import com.meshline.app.protocol.MeshPacket
import com.meshline.app.protocol.MessageIds
import com.meshline.app.protocol.PacketCodec
import com.meshline.app.protocol.PacketType
import com.meshline.app.security.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * UI-ready representation of an active group.
 */
data class GroupItem(
    val groupIdHex: String,
    val name: String,
    val avatarIndex: Byte,
    val epoch: Int,
    val epochKey: ByteArray,
    val adminEd25519Pk: ByteArray,
    val groupTagHex: String,
    val roster: List<GroupPayloads.RosterMember>,
    val isAdmin: Boolean,
    val unreadCount: Int = 0,
    /** When the local user joined (persisted; 0 for groups built before v10). */
    val joinedAtMs: Long = 0L,
    /** Timestamp of the newest message, used to order the chat list (persisted). */
    val lastActivityMs: Long = 0L,
) {
    val groupTagBytes: ByteArray get() = groupTagHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun isMember(senderId: ByteArray): Boolean {
        return roster.any { MessageIds.idFromPublicKey(it.ed25519PublicKey).contentEquals(senderId) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupItem) return false
        return groupIdHex == other.groupIdHex &&
            name == other.name &&
            avatarIndex == other.avatarIndex &&
            epoch == other.epoch &&
            epochKey.contentEquals(other.epochKey) &&
            adminEd25519Pk.contentEquals(other.adminEd25519Pk) &&
            groupTagHex == other.groupTagHex &&
            roster == other.roster &&
            isAdmin == other.isAdmin &&
            unreadCount == other.unreadCount &&
            joinedAtMs == other.joinedAtMs &&
            lastActivityMs == other.lastActivityMs
    }

    override fun hashCode(): Int {
        var h = groupIdHex.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + avatarIndex.toInt()
        h = 31 * h + epoch
        h = 31 * h + epochKey.contentHashCode()
        h = 31 * h + adminEd25519Pk.contentHashCode()
        h = 31 * h + groupTagHex.hashCode()
        h = 31 * h + roster.hashCode()
        h = 31 * h + isAdmin.hashCode()
        h = 31 * h + unreadCount
        h = 31 * h + joinedAtMs.hashCode()
        h = 31 * h + lastActivityMs.hashCode()
        return h
    }
}

data class GroupMessageUi(
    val messageIdHex: String,
    val groupIdHex: String,
    val senderIdHex: String,
    val senderNickname: String,
    val senderAvatarIndex: Int,
    val text: String,
    val epoch: Int,
    val timestampMs: Long,
    val isSelf: Boolean,
    /** Reply-to quoting: the quoted message's id (null when not a reply). */
    val parentIdHex: String? = null,
    /** Disappearing messages: epoch millis at which the message must be dropped. */
    val expiresAtMs: Long? = null,
    /** True once the author deleted it (shown as a tombstone). */
    val deleted: Boolean = false,
    /** When the author last edited it (null = never edited). */
    val editedAtMs: Long? = null,
)

data class PendingGroupInvite(
    val invite: GroupPayloads.GroupInvite,
    val receivedAtMs: Long,
)

/**
 * A broadcast admin roster change (Feature 6: member removal, admin
 * transfer). The admin's `GroupManager` state is already rotated; the
 * [control] tells the other members the new roster and [invites] hand the
 * new epoch key to the members that must be able to keep decrypting.
 */
data class RosterChange(
    val updatedGroup: GroupItem,
    val control: GroupPayloads.GroupControl,
    /** Peer hex ids that receive the broadcast control (new roster + the removed member, minus self). */
    val controlPeerIds: List<String>,
    /** Encoded [GroupPayloads.GroupInvite] per new-roster peer (minus self) announcing the new epoch. */
    val invites: Map<String, ByteArray>,
)

/**
 * Manages Group Chat lifecycle, epoch encryption, and roster tracking (GROUPS_DESIGN.md).
 */
class GroupManager(
    val localIdentity: Identity,
    val localNickname: () -> String,
    val localAvatarIndex: () -> Byte,
    /** Invoked once per received group message from another member (Feature 9: notifications). */
    private val onNewMessage: ((GroupMessageUi) -> Unit)? = null,
    /** Durable storage. Null keeps the manager purely in-memory (unit tests). */
    private val persistence: GroupPersistence? = null,
    /**
     * Hosts the ordered write queue. Required when [persistence] is set: a
     * single consumer applies the queued writes, so the database sees the
     * mutations in exactly the order they were applied in memory (create,
     * epoch rotation, removal, leave).
     */
    scope: CoroutineScope? = null,
) {
    companion object {
        /** Newest messages kept per group, in memory and on restore. */
        const val MAX_HISTORY = 300
    }

    private val secureRandom = SecureRandom()

    /** Unbounded so a burst of mutations is never dropped; consumed in order. */
    private val writeQueue = Channel<suspend (GroupPersistence) -> Unit>(Channel.UNLIMITED)

    private val _groups = MutableStateFlow<Map<String, GroupItem>>(emptyMap())
    val groups: Flow<Map<String, GroupItem>> = _groups.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<GroupMessageUi>>>(emptyMap())
    val messages: Flow<Map<String, List<GroupMessageUi>>> = _messages.asStateFlow()

    private val _pendingInvites = MutableStateFlow<List<PendingGroupInvite>>(emptyList())
    val pendingInvites: Flow<List<PendingGroupInvite>> = _pendingInvites.asStateFlow()

    init {
        val store = persistence
        if (store != null && scope != null) {
            scope.launch {
                hydrate(store)
                for (op in writeQueue) runCatching { op(store) }
            }
        }
    }

    fun allGroups(): List<GroupItem> = _groups.value.values.toList()

    fun getGroup(groupIdHex: String): GroupItem? = _groups.value[groupIdHex]

    fun findGroupByTag(tagHex: String): GroupItem? =
        _groups.value.values.firstOrNull { it.groupTagHex.equals(tagHex, ignoreCase = true) }

    private suspend fun hydrate(store: GroupPersistence) {
        val storedGroups = runCatching { store.loadGroups() }.getOrDefault(emptyList())
        val storedMessages = runCatching { store.loadMessages() }.getOrDefault(emptyList())

        if (storedGroups.isNotEmpty()) {
            val merged = storedGroups.associateBy { it.groupIdHex }.toMutableMap()
            // Anything created while the load was in flight wins over the stored copy.
            merged.putAll(_groups.value)
            _groups.value = merged.mapValues { (_, group) ->
                group.copy(isAdmin = group.adminEd25519Pk.contentEquals(localIdentity.ed25519PublicKey))
            }
        }

        if (storedMessages.isNotEmpty()) {
            val byGroup = _messages.value.toMutableMap()
            for (msg in storedMessages) {
                val current = byGroup[msg.groupIdHex] ?: emptyList()
                if (current.any { it.messageIdHex == msg.messageIdHex }) continue
                byGroup[msg.groupIdHex] = (current + msg).sortedBy { it.timestampMs }.takeLast(MAX_HISTORY)
            }
            _messages.value = byGroup
        }
    }

    private fun persist(op: suspend (GroupPersistence) -> Unit) {
        if (persistence == null) return
        writeQueue.trySend(op)
    }

    /**
     * Creates a new group with the local user as admin.
     * Returns the created [GroupItem] and encoded [GroupInvite] byte arrays to send to members.
     */
    fun createGroup(
        name: String,
        avatarIndex: Byte,
        initialMembers: List<GroupPayloads.RosterMember>,
    ): Pair<GroupItem, Map<String, ByteArray>> {
        val groupId = ByteArray(GroupPayloads.GROUP_ID_BYTES).also { secureRandom.nextBytes(it) }
        val groupIdHex = hex(groupId)
        val epochKey = ByteArray(GroupPayloads.EPOCH_KEY_BYTES).also { secureRandom.nextBytes(it) }
        val epoch = 1
        val groupTag = GroupPayloads.deriveGroupTag(epochKey)
        val groupTagHex = hex(groupTag)

        val selfMember = GroupPayloads.RosterMember(
            ed25519PublicKey = localIdentity.ed25519PublicKey,
            nickname = localNickname(),
            avatarIndex = localAvatarIndex(),
        )

        // Ensure self is in roster
        val fullRoster = listOf(selfMember) + initialMembers.filterNot {
            it.ed25519PublicKey.contentEquals(localIdentity.ed25519PublicKey)
        }.take(GroupPayloads.MAX_MEMBERS - 1)

        val now = System.currentTimeMillis()

        val group = GroupItem(
            groupIdHex = groupIdHex,
            name = name.take(GroupPayloads.MAX_GROUP_NAME_CHARS),
            avatarIndex = avatarIndex,
            epoch = epoch,
            epochKey = epochKey,
            adminEd25519Pk = localIdentity.ed25519PublicKey,
            groupTagHex = groupTagHex,
            roster = fullRoster,
            isAdmin = true,
            joinedAtMs = now,
            lastActivityMs = now,
        )

        _groups.value = _groups.value + (groupIdHex to group)
        persist { it.upsertGroup(group) }
        val invitePayload = GroupPayloads.GroupInvite(
            groupId = groupId,
            name = group.name,
            icon = avatarIndex,
            epoch = epoch,
            epochKey = epochKey,
            adminPubkey = localIdentity.ed25519PublicKey,
            roster = fullRoster,
        )
        val encodedInvite = GroupPayloads.encodeGroupInvite(invitePayload)

        val invitesByPeerId = mutableMapOf<String, ByteArray>()
        for (m in initialMembers) {
            val peerId = MessageIds.idFromPublicKey(m.ed25519PublicKey)
            if (!peerId.contentEquals(localIdentity.senderId)) {
                invitesByPeerId[hex(peerId)] = encodedInvite
            }
        }

        return group to invitesByPeerId
    }

    /**
     * Receives an incoming group invite from a Noise direct message.
     *
     * For an existing group this is the epoch-update channel (GROUPS_DESIGN.md
     * §3): when the invite is newer, the new epoch key and roster are applied.
     * A group we are no longer a member of (removed, or transferred away
     * from) is left locally.
     */
    fun offerInvite(inviteBytes: ByteArray) {
        if (!GroupPayloads.isGroupInvite(inviteBytes)) return
        val invite = try {
            GroupPayloads.decodeGroupInvite(inviteBytes)
        } catch (_: Exception) {
            return
        }
        val groupIdHex = hex(invite.groupId)
        val existing = _groups.value[groupIdHex]
        if (existing != null) {
            // Epoch update for an existing group (admin rotation, removal, transfer).
            if (invite.epoch <= existing.epoch) return
            val stillMember = invite.roster.any {
                MessageIds.idFromPublicKey(it.ed25519PublicKey).contentEquals(localIdentity.senderId)
            }
            if (!stillMember) {
                leaveGroup(groupIdHex)
                return
            }
            val updated = existing.copy(
                epoch = invite.epoch,
                epochKey = invite.epochKey,
                groupTagHex = hex(GroupPayloads.deriveGroupTag(invite.epochKey)),
                roster = invite.roster,
                adminEd25519Pk = invite.adminPubkey,
                isAdmin = invite.adminPubkey.contentEquals(localIdentity.ed25519PublicKey),
                lastActivityMs = System.currentTimeMillis(),
            )
            _groups.value = _groups.value + (groupIdHex to updated)
            persist { it.upsertGroup(updated) }
            return
        }
        // New-group path: the local identity must be in the roster to be accepted.
        if (!invite.roster.any { MessageIds.idFromPublicKey(it.ed25519PublicKey).contentEquals(localIdentity.senderId) }) return
        if (_pendingInvites.value.none { hex(it.invite.groupId) == groupIdHex }) {
            _pendingInvites.value = _pendingInvites.value + PendingGroupInvite(invite, System.currentTimeMillis())
        }
    }

    /**
     * Accepts a pending group invite.
     */
    fun acceptInvite(groupIdHex: String): GroupItem? {
        val pending = _pendingInvites.value.firstOrNull { hex(it.invite.groupId) == groupIdHex } ?: return null
        val invite = pending.invite
        val tag = GroupPayloads.deriveGroupTag(invite.epochKey)

        val now = System.currentTimeMillis()

        val group = GroupItem(
            groupIdHex = groupIdHex,
            name = invite.name,
            avatarIndex = invite.icon,
            epoch = invite.epoch,
            epochKey = invite.epochKey,
            adminEd25519Pk = invite.adminPubkey,
            groupTagHex = hex(tag),
            roster = invite.roster,
            isAdmin = invite.adminPubkey.contentEquals(localIdentity.ed25519PublicKey),
            joinedAtMs = now,
            lastActivityMs = now,
        )

        _groups.value = _groups.value + (groupIdHex to group)
        persist { it.upsertGroup(group) }
        _pendingInvites.value = _pendingInvites.value.filterNot { hex(it.invite.groupId) == groupIdHex }
        return group
    }

    /**
     * Declines a pending group invite.
     */
    fun declineInvite(groupIdHex: String) {
        _pendingInvites.value = _pendingInvites.value.filterNot { hex(it.invite.groupId) == groupIdHex }
    }

    /**
     * Admin-only: starts a new epoch with [newRoster] and returns the
     * [RosterChange] to broadcast. Returns null when this caller is not the
     * admin or the group is not found.
     */
    private fun rotateEpoch(
        groupIdHex: String,
        kind: GroupPayloads.ControlKind,
        newRoster: List<GroupPayloads.RosterMember>,
        newAdminPk: ByteArray? = null,
        extraControlPeers: Set<String> = emptySet(),
    ): RosterChange? {
        val group = _groups.value[groupIdHex] ?: return null
        if (!group.isAdmin) return null

        val newEpoch = group.epoch + 1
        val newEpochKey = ByteArray(GroupPayloads.EPOCH_KEY_BYTES).also { secureRandom.nextBytes(it) }
        val newGroupTag = GroupPayloads.deriveGroupTag(newEpochKey)
        val newAdmin = newAdminPk ?: group.adminEd25519Pk

        val updatedGroup = group.copy(
            epoch = newEpoch,
            epochKey = newEpochKey,
            groupTagHex = hex(newGroupTag),
            roster = newRoster,
            adminEd25519Pk = newAdmin,
            isAdmin = newAdmin.contentEquals(localIdentity.ed25519PublicKey),
            lastActivityMs = System.currentTimeMillis(),
        )
        _groups.value = _groups.value + (groupIdHex to updatedGroup)
        persist { it.upsertGroup(updatedGroup) }

        val control = GroupPayloads.GroupControl(
            epoch = newEpoch,
            kind = kind,
            groupId = hexToBytes(group.groupIdHex),
            name = group.name,
            roster = newRoster,
            newAdmin = if (kind == GroupPayloads.ControlKind.TRANSFER_ADMIN) newAdmin else null,
        )

        val selfHex = hex(localIdentity.senderId)
        val controlPeerIds = (newRoster.map { hex(MessageIds.idFromPublicKey(it.ed25519PublicKey)) } +
            extraControlPeers.toList())
            .filterNot { it == selfHex }
            .distinct()

        // The per-peer invite carries the new epoch key so members can keep
        // decrypting (GROUPS_DESIGN.md §3).
        val invitePayload = GroupPayloads.GroupInvite(
            groupId = hexToBytes(group.groupIdHex),
            name = updatedGroup.name,
            icon = updatedGroup.avatarIndex,
            epoch = newEpoch,
            epochKey = newEpochKey,
            adminPubkey = newAdmin,
            roster = newRoster,
        )
        val encodedInvite = GroupPayloads.encodeGroupInvite(invitePayload)
        val invites = newRoster.mapNotNull { m ->
            val peerId = hex(MessageIds.idFromPublicKey(m.ed25519PublicKey))
            if (peerId != selfHex) peerId to encodedInvite else null
        }.toMap()

        return RosterChange(updatedGroup, control, controlPeerIds, invites)
    }

    /**
     * Feature 6: admin removes [memberIdHex] (the 8-byte senderId hex) from
     * the roster. Broadcasts a `REMOVE_MEMBER` control plus the new-epoch
     * invites; returns the updated state, or null when the caller is not the
     * admin or the member is not in the roster.
     */
    fun removeMember(groupIdHex: String, memberIdHex: String): RosterChange? {
        val group = _groups.value[groupIdHex] ?: return null
        if (!group.isAdmin) return null
        // The admin cannot remove themselves; they use Leave instead.
        if (memberIdHex == hex(MessageIds.idFromPublicKey(group.adminEd25519Pk))) return null
        val removed = group.roster.firstOrNull {
            MessageIds.idFromPublicKey(it.ed25519PublicKey).contentEquals(hexToBytes(memberIdHex))
        } ?: return null
        val newRoster = group.roster.filterNot { it.ed25519PublicKey.contentEquals(removed.ed25519PublicKey) }
        if (newRoster.size == group.roster.size) return null
        // The removed member learns the new roster through the broadcast control.
        return rotateEpoch(
            groupIdHex,
            GroupPayloads.ControlKind.REMOVE_MEMBER,
            newRoster,
            extraControlPeers = setOf(memberIdHex),
        )
    }

    /**
     * Feature 6: the admin transfers admin rights to another roster member
     * [newAdminIdHex]. The roster is unchanged; the epoch still rotates so
     * the old admin can no longer read new traffic.
     */
    fun transferAdmin(groupIdHex: String, newAdminIdHex: String): RosterChange? {
        val group = _groups.value[groupIdHex] ?: return null
        if (!group.isAdmin) return null
        val newAdmin = group.roster.firstOrNull {
            MessageIds.idFromPublicKey(it.ed25519PublicKey).contentEquals(hexToBytes(newAdminIdHex))
        } ?: return null
        return rotateEpoch(
            groupIdHex,
            GroupPayloads.ControlKind.TRANSFER_ADMIN,
            group.roster,
            newAdminPk = newAdmin.ed25519PublicKey,
        )
    }

    /**
     * Applies an incoming admin `GROUP_CONTROL` (Feature 6: removal, admin
     * transfer). The router has verified the packet signature; the admin
     * check is re-done here because the roster is the authority on who is admin.
     */
    fun onIncomingGroupControl(packet: MeshPacket) {
        val ctrl = try {
            GroupPayloads.decodeGroupControl(packet.payload)
        } catch (_: Exception) {
            return
        }
        val group = _groups.value[hex(ctrl.groupId)] ?: return
        if (!packet.senderId.contentEquals(MessageIds.idFromPublicKey(group.adminEd25519Pk))) return

        when (ctrl.kind) {
            GroupPayloads.ControlKind.REMOVE_MEMBER -> {
                val updated = group.copy(epoch = ctrl.epoch, roster = ctrl.roster, lastActivityMs = System.currentTimeMillis())
                _groups.value = _groups.value + (group.groupIdHex to updated)
                persist { it.upsertGroup(updated) }
                // If this member was removed, drop the group locally.
                if (!updated.isMember(localIdentity.senderId)) {
                    leaveGroup(group.groupIdHex)
                }
            }
            GroupPayloads.ControlKind.TRANSFER_ADMIN -> {
                val newAdmin = ctrl.newAdmin ?: return
                if (!ctrl.roster.any { it.ed25519PublicKey.contentEquals(newAdmin) }) return
                val updated = group.copy(
                    epoch = ctrl.epoch,
                    roster = ctrl.roster,
                    adminEd25519Pk = newAdmin,
                    isAdmin = newAdmin.contentEquals(localIdentity.ed25519PublicKey),
                    lastActivityMs = System.currentTimeMillis(),
                )
                _groups.value = _groups.value + (group.groupIdHex to updated)
                persist { it.upsertGroup(updated) }
            }
            else -> Unit // LEAVE / RENAME / ROSTER_UPDATE: nothing to do on receive.
        }
    }

    /**
     * Leaves a group locally.
     */
    fun leaveGroup(groupIdHex: String) {
        _groups.value = _groups.value - groupIdHex
        _messages.value = _messages.value - groupIdHex
        persist { it.deleteGroup(groupIdHex); it.deleteMessages(groupIdHex) }
    }

    /**
     * Encrypts and encodes an outgoing group message.
     *
     * [parentMessageIdHex] quotes another message (group reply-to);
     * [ttlSeconds] makes it a disappearing message. Both ride the versioned
     * group plaintext ([GroupPayloads.encodeGroupText]); plain messages stay
     * byte-identical to the legacy raw-text form.
     */
    fun createOutgoingGroupPacket(
        groupIdHex: String,
        text: String,
        parentMessageIdHex: String? = null,
        ttlSeconds: Int? = null,
    ): MeshPacket? {
        val group = _groups.value[groupIdHex] ?: return null
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return null
        val plaintext = try {
            GroupPayloads.encodeGroupText(
                GroupPayloads.GroupText(cleanText, parentMessageIdHex, ttlSeconds)
            )
        } catch (_: Exception) {
            return null
        }

        val nonce = GroupCipher.generateNonce()
        val ad = GroupPayloads.computeAssociatedData(
            groupTag = group.groupTagBytes,
            epoch = group.epoch,
            senderId = localIdentity.senderId,
        )

        val ciphertext = GroupCipher.encrypt(
            plaintext = plaintext,
            epochKey = group.epochKey,
            nonce = nonce,
            ad = ad,
        )

        val payload = GroupPayloads.encodeGroupMessage(
            GroupPayloads.GroupMessage(
                epoch = group.epoch,
                nonce = nonce,
                ciphertext = ciphertext,
            )
        )

        val unsignedFlags = (if (!group.groupTagBytes.contentEquals(PacketCodec.BROADCAST_ID)) 0x01 else 0).toByte()
        val unsigned = MeshPacket(
            version = PacketCodec.PROTOCOL_VERSION,
            type = PacketType.GROUP_MESSAGE,
            ttl = PacketCodec.MAX_TTL,
            flags = unsignedFlags,
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            senderId = localIdentity.senderId,
            recipientId = group.groupTagBytes,
            signature = null,
        )

        val signature = localIdentity.signEd25519(PacketCodec.signingInput(unsigned))
        val signedPacket = unsigned.copy(
            flags = (unsignedFlags.toInt() or 0x02).toByte(), // HAS_RECIPIENT | HAS_SIGNATURE
            signature = signature,
        )

        // Store message locally in history
        val msgUi = GroupMessageUi(
            messageIdHex = hex(signedPacket.messageId),
            groupIdHex = groupIdHex,
            senderIdHex = hex(localIdentity.senderId),
            senderNickname = localNickname(),
            senderAvatarIndex = localAvatarIndex().toInt() and 0xFF,
            text = cleanText,
            epoch = group.epoch,
            timestampMs = signedPacket.timestampMs,
            isSelf = true,
            parentIdHex = parentMessageIdHex,
            expiresAtMs = ttlSeconds?.let { signedPacket.timestampMs + it * 1_000L },
        )
        recordMessage(msgUi)

        return signedPacket
    }

    /**
     * Decrypts an incoming GROUP_MESSAGE packet.
     * Returns true if successfully decrypted and stored.
     */
    fun onIncomingGroupPacket(packet: MeshPacket): Boolean {
        if (packet.type != PacketType.GROUP_MESSAGE) return false
        val tagHex = hex(packet.recipientId)
        val group = findGroupByTag(tagHex) ?: return false

        // Invariant: Sender must be an authorized member in the group roster
        val member = group.roster.firstOrNull {
            MessageIds.idFromPublicKey(it.ed25519PublicKey).contentEquals(packet.senderId)
        } ?: return false

        val groupMsg = try {
            GroupPayloads.decodeGroupMessage(packet.payload)
        } catch (_: Exception) {
            return false
        }

        // Epoch check: must match current group epoch
        if (groupMsg.epoch != group.epoch) return false

        val ad = GroupPayloads.computeAssociatedData(
            groupTag = group.groupTagBytes,
            epoch = groupMsg.epoch,
            senderId = packet.senderId,
        )

        val plaintext = try {
            GroupCipher.decrypt(
                ciphertext = groupMsg.ciphertext,
                epochKey = group.epochKey,
                nonce = groupMsg.nonce,
                ad = ad,
            )
        } catch (_: Exception) {
            return false
        }

        val body = GroupPayloads.decodeGroupText(plaintext)
        val msgUi = GroupMessageUi(
            messageIdHex = hex(packet.messageId),
            groupIdHex = group.groupIdHex,
            senderIdHex = hex(packet.senderId),
            senderNickname = member.nickname,
            senderAvatarIndex = member.avatarIndex.toInt() and 0xFF,
            text = body.text,
            epoch = groupMsg.epoch,
            timestampMs = packet.timestampMs,
            isSelf = packet.senderId.contentEquals(localIdentity.senderId),
            parentIdHex = body.parentHex,
            expiresAtMs = body.ttlSeconds?.let { packet.timestampMs + it * 1_000L },
        )
        recordMessage(msgUi)
        return true
    }

    private fun recordMessage(msg: GroupMessageUi) {
        val current = _messages.value[msg.groupIdHex] ?: emptyList()
        if (current.any { it.messageIdHex == msg.messageIdHex }) return
        val updated = (current + msg).takeLast(MAX_HISTORY)
        _messages.value = _messages.value + (msg.groupIdHex to updated)
        persist { it.insertMessage(msg) }

        if (!msg.isSelf) onNewMessage?.invoke(msg)
        val group = _groups.value[msg.groupIdHex]
        if (group != null) {
            val bumped = group.copy(
                unreadCount = if (msg.isSelf) group.unreadCount else group.unreadCount + 1,
                lastActivityMs = msg.timestampMs,
            )
            _groups.value = _groups.value + (msg.groupIdHex to bumped)
            persist { it.upsertGroup(bumped) }
        }
    }

    fun clearUnread(groupIdHex: String) {
        val group = _groups.value[groupIdHex] ?: return
        val updated = group.copy(unreadCount = 0)
        _groups.value = _groups.value + (groupIdHex to updated)
        persist { it.upsertGroup(updated) }
    }

    /**
     * Applies a MESSAGE_CONTROL (delete / edit) to a group message.
     * Only the **original author** may mutate their own message; the router
     * has already verified the packet signature. Returns true when applied.
     */
    fun applyMessageControl(targetIdHex: String, senderIdHex: String, delete: Boolean, newText: String?): Boolean {
        for ((groupIdHex, list) in _messages.value) {
            val msg = list.firstOrNull { it.messageIdHex == targetIdHex } ?: continue
            if (msg.senderIdHex != senderIdHex) return false
            val updatedList = list.map {
                if (it.messageIdHex != targetIdHex) it
                else if (delete) it.copy(deleted = true, text = "")
                else it.copy(text = newText ?: it.text, editedAtMs = System.currentTimeMillis())
            }
            _messages.value = _messages.value + (groupIdHex to updatedList)
            if (delete) persist { it.markMessageDeleted(targetIdHex) }
            else persist { it.editMessage(targetIdHex, newText ?: "", System.currentTimeMillis()) }
            return true
        }
        return false
    }

    /** Disappearing messages: drop expired rows from memory and storage. */
    fun sweepExpired(nowMs: Long = System.currentTimeMillis()) {
        var changed = false
        val next = _messages.value.mapValues { (_, list) ->
            val kept = list.filter { it.expiresAtMs == null || it.expiresAtMs > nowMs }
            if (kept.size != list.size) changed = true
            kept
        }
        if (changed) _messages.value = next
        persist { it.deleteExpiredMessages(nowMs) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
