package com.meshline.app.channels

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
)

class ChannelManager(
    val ownIdHex: String,
) {
    companion object {
        val DEFAULT_CHANNELS = listOf(
            ChannelItem(name = "#general", description = "Public discussion for all nearby peers", isDefault = true),
            ChannelItem(name = "#mesh", description = "Network status, hop announcements and radio diagnostics", isDefault = true),
            ChannelItem(name = "#emergency", description = "Urgent alerts and community assistance", isDefault = true),
            ChannelItem(name = "#geo-local", description = "Geographic proximity channel (nearby area)", isGeographic = true),
        )
    }

    private val _channels = MutableStateFlow<List<ChannelItem>>(DEFAULT_CHANNELS)
    val channels: Flow<List<ChannelItem>> = _channels.asStateFlow()

    private val _activeChannel = MutableStateFlow("#general")
    val activeChannel: Flow<String> = _activeChannel.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChannelMessageUi>>>(emptyMap())
    val messages: Flow<Map<String, List<ChannelMessageUi>>> = _messages.asStateFlow()

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
    }

    private fun normalizeChannel(raw: String): String {
        val trimmed = raw.trim().lowercase()
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }
}
