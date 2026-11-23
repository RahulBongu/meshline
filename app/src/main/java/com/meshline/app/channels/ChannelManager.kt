package com.meshline.app.channels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages topic-based channels (e.g. #general, #emergency, #geo-local).
 *
 * Channel messages use PacketType.CHANNEL_MESSAGE (0x04), signed and broadcast
 * across the BLE mesh (and optionally relayed over Nostr if internet transport is enabled).
 */
data class ChannelItem(
    val name: String,
    val description: String,
    val isDefault: Boolean = false,
    val isGeographic: Boolean = false,
    val unreadCount: Int = 0,
)

data class ChannelMessageUi(
    val messageIdHex: String,
    val channel: String,
    val senderIdHex: String,
    val senderNickname: String,
    val senderAvatarIndex: Int,
    val text: String,
    val timestampMs: Long,
    val isSelf: Boolean,
    /** Reply-to quoting: the quoted message's id (null when not a reply). */
    val parentIdHex: String? = null,
    /** True once the author deleted it (shown as a tombstone). */
    val deleted: Boolean = false,
    /** When the author last edited it (null = never edited). */
    val editedAtMs: Long? = null,
)

class ChannelManager(
    val ownIdHex: String,
    private val persistence: ChannelPersistence? = null,
    scope: CoroutineScope? = null,
) {
    companion object {
        val DEFAULT_CHANNELS = listOf(
            ChannelItem(name = "#general", description = "Public discussion for all nearby peers", isDefault = true),
            ChannelItem(name = "#mesh", description = "Network status, hop announcements and radio diagnostics", isDefault = true),
            ChannelItem(name = "#emergency", description = "Urgent alerts and community assistance", isDefault = true),
            ChannelItem(name = "#geo-local", description = "Geographic proximity channel (nearby area)", isGeographic = true),
        )
    }

    private val writeQueue = Channel<suspend (ChannelPersistence) -> Unit>(Channel.UNLIMITED)

    private val _channels = MutableStateFlow<List<ChannelItem>>(DEFAULT_CHANNELS)
    val channels: Flow<List<ChannelItem>> = _channels.asStateFlow()

    private val _activeChannel = MutableStateFlow("#general")
    val activeChannel: Flow<String> = _activeChannel.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChannelMessageUi>>>(emptyMap())
    val messages: Flow<Map<String, List<ChannelMessageUi>>> = _messages.asStateFlow()

    init {
        val store = persistence
        if (store != null && scope != null) {
            scope.launch {
                hydrate(store)
                for (op in writeQueue) runCatching { op(store) }
            }
        }
    }

    fun currentActiveChannel(): String = _activeChannel.value

    fun setActiveChannel(channel: String) {
        val normalized = normalizeChannel(channel)
        joinChannel(normalized)
        _activeChannel.value = normalized
        // Clear unread count for this channel
        _channels.value = _channels.value.map {
            if (it.name == normalized) it.copy(unreadCount = 0) else it
        }
    }

    fun joinChannel(channel: String, description: String = "Custom topic channel") {
        val normalized = normalizeChannel(channel)
        if (_channels.value.none { it.name == normalized }) {
            _channels.value = _channels.value + ChannelItem(name = normalized, description = description)
        }
    }

    fun addMessage(msg: ChannelMessageUi) {
        val normChannel = normalizeChannel(msg.channel)
        joinChannel(normChannel)
        val currentList = _messages.value[normChannel] ?: emptyList()
        // Deduplicate by messageIdHex
        if (currentList.any { it.messageIdHex == msg.messageIdHex }) return

        val updatedList = (currentList + msg).takeLast(200)
        _messages.value = _messages.value + (normChannel to updatedList)
        persist { it.insertMessage(msg.copy(channel = normChannel)) }

        // Increment unread if not currently viewing this channel and not self
        if (_activeChannel.value != normChannel && !msg.isSelf) {
            _channels.value = _channels.value.map {
                if (it.name == normChannel) it.copy(unreadCount = it.unreadCount + 1) else it
            }
        }
    }

    fun clearChannelMessages(channel: String) {
        val norm = normalizeChannel(channel)
        _messages.value = _messages.value + (norm to emptyList())
        persist { it.deleteChannel(norm) }
    }

    /**
     * Applies a MESSAGE_CONTROL (delete / edit) to a channel message.
     * Only the original author may mutate their own message. Returns true
     * when applied.
     */
    fun applyMessageControl(targetIdHex: String, senderIdHex: String, delete: Boolean, newText: String?): Boolean {
        for ((channel, list) in _messages.value) {
            val msg = list.firstOrNull { it.messageIdHex == targetIdHex } ?: continue
            if (msg.senderIdHex != senderIdHex) return false
            val updatedList = list.map {
                if (it.messageIdHex != targetIdHex) it
                else if (delete) it.copy(deleted = true, text = "")
                else it.copy(text = newText ?: it.text, editedAtMs = System.currentTimeMillis())
            }
            _messages.value = _messages.value + (channel to updatedList)
            if (delete) persist { it.markMessageDeleted(targetIdHex) }
            else persist { it.editMessage(targetIdHex, newText ?: "", System.currentTimeMillis()) }
            return true
        }
        return false
    }

    private suspend fun hydrate(store: ChannelPersistence) {
        val stored = runCatching { store.loadMessages() }.getOrDefault(emptyList())
        if (stored.isEmpty()) return
        val byChannel = _messages.value.toMutableMap()
        for (msg in stored) {
            val norm = normalizeChannel(msg.channel)
            val current = byChannel[norm] ?: emptyList()
            if (current.any { it.messageIdHex == msg.messageIdHex }) continue
            byChannel[norm] = (current + msg.copy(channel = norm)).takeLast(200)
            joinChannel(norm)
        }
        _messages.value = byChannel
    }

    private fun persist(op: suspend (ChannelPersistence) -> Unit) {
        if (persistence == null) return
        writeQueue.trySend(op)
    }

    private fun normalizeChannel(raw: String): String {
        val trimmed = raw.trim().lowercase()
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }
}
