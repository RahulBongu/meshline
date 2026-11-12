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
    private var db: MeshlineDatabase? = null

    @Synchronized
    private fun open(): MeshlineDatabase =
        db ?: DatabaseFactory.create(context).also { db = it }

    /**
     * The D2 stores (sessions, outbox, seen ids, messages) - the **same** Room
     * handle, so the app opens the encrypted database once.
     */
    val secure: SecureStores by lazy { SecureStores(open()) }

    fun observeAll(): Flow<List<ContactEntity>> = flow {
        emitAll(open().contactsDao().observeAll())
    }.catch { t ->
        if (t is CancellationException) throw t
        resetStorage()
        emit(emptyList())
    }

    override suspend fun all(): List<ContactEntity> = runCatching {
        open().contactsDao().allOnce()
    }.getOrElse {
        resetStorage()
        emptyList()
    }

    /** Adds a QR-verified contact. Returns false if storage had to be reset. */
    suspend fun addVerified(card: QrPayload.ContactCard): Boolean = runCatching {
        open().contactsDao().upsert(card.toEntity())
        true
    }.getOrElse {
        resetStorage()
        false
    }

    /** The X25519 key we know for this peer (from a QR-verified contact). */
    override suspend fun x25519For(peerIdHex: String): ByteArray? =
        runCatching { open().contactsDao().find(peerIdHex)?.x25519PublicKey }.getOrNull()

    /** Is this peer a QR-verified contact? */
    override suspend fun isVerified(peerIdHex: String): Boolean =
        runCatching { open().contactsDao().find(peerIdHex)?.verified == true }.getOrDefault(false)

    /** Is this peer blocked? */
    override suspend fun isBlocked(peerIdHex: String): Boolean =
        runCatching { open().contactsDao().find(peerIdHex)?.blocked == true }.getOrDefault(false)

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
        runCatching {
            val dao = open().contactsDao()
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
        runCatching { open().contactsDao().setBlocked(peerIdHex, true) }
    }

    /** Panic wipe: every contact goes with the identity. */
    override suspend fun deleteAll() {
        runCatching { open().contactsDao().deleteAll() }
    }

    fun close() {
        runCatching { db?.close() }
        db = null
    }

    private fun resetStorage() {
        runCatching { db?.close() }
        db = null
        DbPassphrase.wipe(context)
        SecureStorageStatus.record(context, SecureStorageStatus.Reason.DB_UNREADABLE)
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
