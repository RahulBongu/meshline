package com.meshline.app.channels

/**
 * Durable storage for topic-channel history.
 *
 * [ChannelManager] keeps the live lists in memory; this port is what makes
 * them survive a restart. The manager serialises every call through a single
 * consumer coroutine, in the same order the state changed, so implementations
 * do not need to be thread-safe.
 */
interface ChannelPersistence {

    /** Every stored channel message, oldest first. */
    suspend fun loadMessages(): List<ChannelMessageUi>

    suspend fun insertMessage(message: ChannelMessageUi)

    /** MESSAGE_CONTROL: tombstone one message (author-checked by the caller). */
    suspend fun markMessageDeleted(messageIdHex: String)

    /** MESSAGE_CONTROL: replace one message's body and stamp the edit time. */
    suspend fun editMessage(messageIdHex: String, newText: String, editedAtMs: Long)

    /** Clears one channel's history ("clear channel"). */
    suspend fun deleteChannel(channel: String)

    /** Drops everything (panic wipe). */
    suspend fun deleteAll()

    /** Retention sweep: drops history older than [thresholdMs] (epoch millis). */
    suspend fun deleteMessagesOlderThan(thresholdMs: Long)
}
