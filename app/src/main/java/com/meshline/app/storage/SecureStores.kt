package com.meshline.app.storage

import com.meshline.app.channels.ChannelPersistence
import com.meshline.app.groups.GroupPersistence

/**
 * The D2 stores, all on the same SQLCipher-encrypted Room handle
 * (the database key is wrapped by the Android Keystore).
 */
class SecureStores(private val db: MeshlineDatabase) {
    /** The Noise sessions (metadata; the transport key is never written). */
    val sessions = SessionStore(db.noiseSessionDao())

    /** The sender's outbox: the queued plaintexts and their states. */
    val outbox = OutboxRepository(db.outboxDao())

    /** The receiver's record of the application-level ids it already showed. */
    val seen = SeenMessageStore(db.seenMessageDao())

    /** The chat history (both directions). */
    val messages: MessageStore = RoomMessageStore(db.messagesDao())

    /** Groups and group messages (Phase E). */
    val groups = db.groupsDao()
    val groupMessages = db.groupMessagesDao()
    val channelMessages = db.channelMessagesDao()

    /** Groups + history, restored on service start (DB v10). */
    val groupPersistence: GroupPersistence = RoomGroupPersistence(groups, groupMessages)

    /** Topic-channel history, restored on service start (DB v10). */
    val channelPersistence: ChannelPersistence = RoomChannelPersistence(channelMessages)
}
