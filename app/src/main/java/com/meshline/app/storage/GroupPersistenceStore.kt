package com.meshline.app.storage

import com.meshline.app.channels.ChannelMessageUi
import com.meshline.app.channels.ChannelPersistence
import com.meshline.app.groups.GroupItem
import com.meshline.app.groups.GroupMessageUi
import com.meshline.app.groups.GroupPersistence
import com.meshline.app.protocol.GroupPayloads

/**
 * Room-backed [GroupPersistence] (DB v10).
 *
 * The roster is stored with [GroupPayloads.encodeRoster] so the membership
 * list — and with it the authority to send and the admin identity — survives a
 * restart. [GroupItem.isAdmin] is deliberately reported as `false` here: the
 * adapter has no local identity, and `GroupManager` recomputes it while
 * hydrating.
 *
 * The caller (`GroupManager`) serialises writes, so this class makes no
 * threading assumptions of its own.
 */
class RoomGroupPersistence(
    private val groups: GroupsDao,
    private val messages: GroupMessagesDao,
) : GroupPersistence {

    override suspend fun loadGroups(): List<GroupItem> = groups.allOnce().mapNotNull { row ->
        val roster = runCatching { GroupPayloads.decodeRoster(row.roster) }.getOrNull()
            ?: return@mapNotNull null
        GroupItem(
            groupIdHex = row.groupIdHex,
            name = row.name,
            avatarIndex = row.avatarIndex.toByte(),
            epoch = row.epoch,
            epochKey = row.epochKey,
            adminEd25519Pk = row.adminEd25519Pk,
            groupTagHex = row.groupTagHex,
            roster = roster,
            isAdmin = false,
            unreadCount = row.unreadCount,
            joinedAtMs = row.joinedAtMs,
            lastActivityMs = row.lastActivityMs,
        )
    }

    override suspend fun upsertGroup(group: GroupItem) {
        groups.upsert(
            GroupRecordEntity(
                groupIdHex = group.groupIdHex,
                name = group.name,
                avatarIndex = group.avatarIndex.toInt() and 0xFF,
                epoch = group.epoch,
                epochKey = group.epochKey,
                adminEd25519Pk = group.adminEd25519Pk,
                groupTagHex = group.groupTagHex,
                roster = GroupPayloads.encodeRoster(group.roster),
                unreadCount = group.unreadCount,
                joinedAtMs = group.joinedAtMs,
                lastActivityMs = group.lastActivityMs,
            )
        )
    }

    override suspend fun deleteGroup(groupIdHex: String) {
        groups.delete(groupIdHex)
    }

    override suspend fun deleteAll() {
        groups.deleteAll()
        messages.deleteAll()
    }

    override suspend fun loadMessages(): List<GroupMessageUi> = messages.allOnce().map { row ->
        GroupMessageUi(
            messageIdHex = row.messageIdHex,
            groupIdHex = row.groupIdHex,
            senderIdHex = row.senderIdHex,
            senderNickname = row.senderNickname,
            senderAvatarIndex = row.senderAvatarIndex,
            text = row.text,
            epoch = row.epoch,
            timestampMs = row.timestampMs,
            isSelf = row.isSelf,
            parentIdHex = row.parentIdHex,
            expiresAtMs = row.expiresAtMs,
            deleted = row.deleted,
            editedAtMs = row.editedAtMs,
        )
    }

    override suspend fun insertMessage(message: GroupMessageUi) {
        messages.insert(
            GroupMessageRecordEntity(
                messageIdHex = message.messageIdHex,
                groupIdHex = message.groupIdHex,
                senderIdHex = message.senderIdHex,
                senderNickname = message.senderNickname,
                senderAvatarIndex = message.senderAvatarIndex,
                text = message.text,
                epoch = message.epoch,
                timestampMs = message.timestampMs,
                isSelf = message.isSelf,
                parentIdHex = message.parentIdHex,
                expiresAtMs = message.expiresAtMs,
                deleted = message.deleted,
                editedAtMs = message.editedAtMs,
            )
        )
    }

    override suspend fun deleteMessages(groupIdHex: String) {
        messages.deleteForGroup(groupIdHex)
    }

    override suspend fun markMessageDeleted(messageIdHex: String) {
        messages.markDeleted(messageIdHex)
    }

    override suspend fun editMessage(messageIdHex: String, newText: String, editedAtMs: Long) {
        messages.editText(messageIdHex, newText, editedAtMs)
    }

    override suspend fun deleteMessagesOlderThan(thresholdMs: Long) {
        messages.deleteOlderThan(thresholdMs)
    }

    override suspend fun deleteExpiredMessages(nowMs: Long) {
        messages.deleteExpired(nowMs)
    }
}

/**
 * Room-backed [ChannelPersistence] (DB v10).
 *
 * Channel history used to live only in memory, so every restart wiped it.
 * The caller (`ChannelManager`) serialises writes.
 */
class RoomChannelPersistence(
    private val messages: ChannelMessagesDao,
) : ChannelPersistence {

    override suspend fun loadMessages(): List<ChannelMessageUi> = messages.allOnce().map { row ->
        ChannelMessageUi(
            messageIdHex = row.messageIdHex,
            channel = row.channel,
            senderIdHex = row.senderIdHex,
            senderNickname = row.senderNickname,
            senderAvatarIndex = row.senderAvatarIndex,
            text = row.text,
            timestampMs = row.timestampMs,
            isSelf = row.isSelf,
            parentIdHex = row.parentIdHex,
            deleted = row.deleted,
            editedAtMs = row.editedAtMs,
        )
    }

    override suspend fun insertMessage(message: ChannelMessageUi) {
        messages.insert(
            ChannelMessageRecordEntity(
                messageIdHex = message.messageIdHex,
                channel = message.channel,
                senderIdHex = message.senderIdHex,
                senderNickname = message.senderNickname,
                senderAvatarIndex = message.senderAvatarIndex,
                text = message.text,
                timestampMs = message.timestampMs,
                isSelf = message.isSelf,
                parentIdHex = message.parentIdHex,
                deleted = message.deleted,
                editedAtMs = message.editedAtMs,
            )
        )
    }

    override suspend fun markMessageDeleted(messageIdHex: String) {
        messages.markDeleted(messageIdHex)
    }

    override suspend fun editMessage(messageIdHex: String, newText: String, editedAtMs: Long) {
        messages.editText(messageIdHex, newText, editedAtMs)
    }

    override suspend fun deleteChannel(channel: String) {
        messages.deleteForChannel(channel)
    }

    override suspend fun deleteAll() {
        messages.deleteAll()
    }

    override suspend fun deleteMessagesOlderThan(thresholdMs: Long) {
        messages.deleteOlderThan(thresholdMs)
    }
}
