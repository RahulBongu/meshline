package com.meshline.app.storage

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

    /** The private messages we received. */
    val inbox = db.inboxDao()
}
