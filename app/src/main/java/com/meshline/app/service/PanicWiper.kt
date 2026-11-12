package com.meshline.app.service

import android.content.Context
import com.meshline.app.profile.ProfileStore
import com.meshline.app.security.AndroidKeystoreIdentityStore
import com.meshline.app.security.DbPassphrase
import com.meshline.app.security.SecureStorageStatus
import com.meshline.app.storage.ContactsRepository
import com.meshline.app.storage.RetentionStore

/**
 * The **panic wipe** (PROTOCOL §7, NOISE_DESIGN §7).
 *
 * One call erases everything this device knows:
 *
 * | what | how |
 * |---|---|
 * | the Noise sessions, the outbox, the seen ids, the chat history, the contacts | [SecureChannel.wipe] (the stores) |
 * | the identity (and its Keystore alias) | [AndroidKeystoreIdentityStore.wipe] |
 * | the profile and the retention setting | [ProfileStore.wipe], [RetentionStore.wipe] |
 * | the SQLCipher passphrase, its Keystore alias and the database file | [DbPassphrase.wipe] |
 *
 * After the wipe the device has no identity: the next launch creates a brand
 * new one, and every peer sees a new key (and a key-change warning).
 */
class PanicWiper(
    private val context: Context,
    private val identityStore: AndroidKeystoreIdentityStore,
    private val contacts: ContactsRepository,
    private val profileStore: ProfileStore,
    private val retentionStore: RetentionStore,
) {
    suspend fun wipe(channel: SecureChannel?) {
        // The chat, the sessions, the delivery queue, the seen ids.
        channel?.wipe()
        // The identity (prefs + the Keystore wrap key).
        runCatching { identityStore.wipe() }
        // The profile and the settings.
        runCatching { profileStore.wipe() }
        runCatching { retentionStore.wipe() }
        // The database: the passphrase, its Keystore alias and the files.
        runCatching { contacts.close() }
        runCatching { DbPassphrase.wipe(context) }
        // The UI shows why the next launch starts over.
        SecureStorageStatus.record(context, SecureStorageStatus.Reason.PANIC_WIPE)
    }
}
