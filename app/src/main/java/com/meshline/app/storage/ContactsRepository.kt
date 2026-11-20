package com.meshline.app.storage

import android.content.Context
import com.meshline.app.protocol.MessageIds
import com.meshline.app.security.DbPassphrase
import com.meshline.app.security.QrPayload
import com.meshline.app.security.SecureStorageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Contacts repository (D1): owns the Room+SQLCipher handle.
 *
 * If the DB cannot be opened/read (Keystore key invalidated, file corrupt),
 * the repository wipes the passphrase + DB, records a [SecureStorageStatus]
 * notice, and continues with an empty list - never crashes. The UI shows the
 * "secure storage was reset" dialog; peers see a new identity (key change).
 *
 * It also implements [ContactDirectory] - the view of the contacts the D2
 * secure channel needs (the trusted X25519 key, the QR-verified flag, the
 * block list).
 */
class ContactsRepository(private val context: Context) : ContactDirectory {

    @Volatile
    private var isClosed = false

    @Volatile
    private var isResetting = false

    @Volatile
    private var db: MeshlineDatabase? = null

    @Synchronized
    private fun open(): MeshlineDatabase? {
        if (isClosed) return null
        return db ?: DatabaseFactory.create(context).also { db = it }
    }

    /**
     * The D2 stores (sessions, outbox, seen ids, messages) - the **same** Room
     * handle, so the app opens the encrypted database once.
     */
    val secure: SecureStores by lazy {
        SecureStores(checkNotNull(open()) { "ContactsRepository is closed" })
    }

    fun observeAll(): Flow<List<ContactEntity>> = flow {
        if (isClosed) {
            emit(emptyList())
            return@flow
        }
        val database = open() ?: run {
            emit(emptyList())
            return@flow
        }
        emitAll(database.contactsDao().observeAll())
    }.catch { t ->
        if (t is CancellationException) throw t
        if (isClosed) {
            emit(emptyList())
            return@catch
        }
        if (t is IllegalStateException && t.message?.contains("closed", ignoreCase = true) == true) {
            emit(emptyList())
            return@catch
        }
        val currentDb = db
        if (!isClosed && currentDb != null && currentDb.isOpen && isUnrecoverableDbError(t)) {
            resetStorage()
        }
        emit(emptyList())
    }

    override suspend fun all(): List<ContactEntity> {
        if (isClosed) return emptyList()
        return runCatching {
            open()?.contactsDao()?.allOnce() ?: emptyList()
        }.getOrElse { t ->
            if (isClosed) return emptyList()
            val currentDb = db
            if (!isClosed && currentDb != null && currentDb.isOpen && isUnrecoverableDbError(t)) {
                resetStorage()
            }
            emptyList()
        }
    }

    /** Adds a QR-verified contact. Returns false if storage had to be reset. */
    suspend fun addVerified(card: QrPayload.ContactCard): Boolean {
        if (isClosed) return false
        return runCatching {
            open()?.contactsDao()?.upsert(card.toEntity())
            true
        }.getOrElse { t ->
            if (isClosed) return false
            val currentDb = db
            if (!isClosed && currentDb != null && currentDb.isOpen && isUnrecoverableDbError(t)) {
                resetStorage()
            }
            false
        }
    }

    /** The X25519 key we know for this peer (from a QR-verified contact). */
    override suspend fun x25519For(peerIdHex: String): ByteArray? =
        if (isClosed) null
        else runCatching { open()?.contactsDao()?.find(peerIdHex)?.x25519PublicKey }.getOrNull()

    /** Is this peer a QR-verified contact? */
    override suspend fun isVerified(peerIdHex: String): Boolean =
        if (isClosed) false
        else runCatching { open()?.contactsDao()?.find(peerIdHex)?.verified == true }.getOrDefault(false)

    /** Is this peer blocked? */
    override suspend fun isBlocked(peerIdHex: String): Boolean =
        if (isClosed) false
        else runCatching { open()?.contactsDao()?.find(peerIdHex)?.blocked == true }.getOrDefault(false)

    /**
     * A contact's keys changed and the user accepted the new identity: the
     * contact loses the QR-verified badge (the scanned fingerprint no longer
     * matches) and becomes TOFU.
     */
    override suspend fun acceptNewKeys(
        peerIdHex: String,
        ed25519PublicKey: ByteArray,
        x25519PublicKey: ByteArray,
    ) {
        if (isClosed) return
        runCatching {
            val dao = open()?.contactsDao() ?: return
            val existing = dao.find(peerIdHex) ?: return
            dao.upsert(
                existing.copy(
                    ed25519PublicKey = ed25519PublicKey,
                    x25519PublicKey = x25519PublicKey,
                    fingerprint = MessageIds.fingerprint(ed25519PublicKey, x25519PublicKey),
                    verified = false,
                    lastSeenMs = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Blocks a peer (the user rejected a key change). */
    override suspend fun block(peerIdHex: String) {
        if (isClosed) return
        runCatching { open()?.contactsDao()?.setBlocked(peerIdHex, true) }
    }

    /** Unblocks a peer. */
    override suspend fun unblock(peerIdHex: String) {
        if (isClosed) return
        runCatching { open()?.contactsDao()?.setBlocked(peerIdHex, false) }
    }

    /** Panic wipe: every contact goes with the identity. */
    override suspend fun deleteAll() {
        if (isClosed) return
        runCatching { open()?.contactsDao()?.deleteAll() }
    }

    fun close() {
        isClosed = true
        val target = db
        db = null
        runCatching { target?.close() }
    }

    @Synchronized
    private fun resetStorage() {
        if (isClosed || isResetting) return
        isResetting = true
        try {
            val currentDb = db
            if (currentDb == null || !currentDb.isOpen) {
                db = null
                return
            }
            DatabaseFactory.closeAll()
            db = null
            DbPassphrase.wipe(context)
            SecureStorageStatus.record(context, SecureStorageStatus.Reason.DB_UNREADABLE)
        } finally {
            isResetting = false
        }
    }

    private fun isUnrecoverableDbError(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val name = current.javaClass.name
            if (name.contains("SQLiteNotADatabaseException") ||
                name.contains("SQLiteDatabaseCorruptException") ||
                name.contains("AEADBadTagException")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun QrPayload.ContactCard.toEntity() = ContactEntity(
        senderIdHex = MessageIds.idFromPublicKey(ed25519PublicKey).toHex(),
        nickname = nickname,
        avatarIndex = avatarIndex.toInt(),
        ed25519PublicKey = ed25519PublicKey,
        x25519PublicKey = x25519PublicKey,
        fingerprint = MessageIds.fingerprint(ed25519PublicKey, x25519PublicKey),
        verified = true,
        lastSeenMs = System.currentTimeMillis(),
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
