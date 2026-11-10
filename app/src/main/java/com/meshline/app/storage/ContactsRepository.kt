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
 * notice, and continues with an empty list — never crashes. The UI shows the
 * "secure storage was reset" dialog; peers see a new identity (key change).
 */
class ContactsRepository(private val context: Context) {

    @Volatile
    private var db: MeshlineDatabase? = null

    @Synchronized
    private fun open(): MeshlineDatabase =
        db ?: DatabaseFactory.create(context).also { db = it }

    fun observeAll(): Flow<List<ContactEntity>> = flow {
        emitAll(open().contactsDao().observeAll())
    }.catch { t ->
        if (t is CancellationException) throw t
        resetStorage()
        emit(emptyList())
    }

    suspend fun all(): List<ContactEntity> = runCatching {
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
