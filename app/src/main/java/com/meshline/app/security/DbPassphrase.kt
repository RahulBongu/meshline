package com.meshline.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.meshline.app.storage.DatabaseFactory
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SQLCipher passphrase provider (D1 review item 1).
 *
 * The passphrase is **32 bytes from [SecureRandom]**, generated once on first
 * launch. It is never derived from a PIN, nickname, device id, `Build.*`, or
 * any other static input. It is wrapped with a non-exportable Keystore
 * AES-256-GCM key (alias below), and only the ciphertext + IV are stored, in a
 * dedicated prefs file that is excluded from cloud backup and device transfer
 * (`data_extraction_rules.xml`).
 *
 * Nothing here is ever logged.
 */
object DbPassphrase {
    private const val PREFS = "meshline_db_key"
    private const val ALIAS = "meshline-db-wrap"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val DB_NAME = "meshline.db"
    private const val TAG = "DbPassphrase"

    /** Returns the existing passphrase, or generates and persists a new one. */
    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        val prefs = prefs(context)
        val wrapped = prefs.getString("wrapped", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val iv = prefs.getString("iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

        if (wrapped != null && iv != null) {
            val unwrapped = runCatching {
                decrypt(wrapped, iv, keystoreKey(createIfMissing = false) ?: error("missing key"))
            }.getOrNull()
            if (unwrapped != null && unwrapped.size == KEY_BYTES) {
                Log.i(TAG, "getOrCreate: reusing stored passphrase")
                return unwrapped
            }
            // The Keystore key was invalidated (OEM OS upgrade) or the blob is
            // corrupt. The DB is unreadable either way, so discard it and start
            // clean rather than crash or silently keep an undecryptable file.
            SecureStorageStatus.record(context, SecureStorageStatus.Reason.DB_UNREADABLE)
            deleteDatabaseFiles(context)
        }

        // Fresh install path.
        Log.i(TAG, "getOrCreate: generating a fresh passphrase")
        // No usable wrapped passphrase remains. Clean up any orphaned database
        // files (e.g. from a prior install restored by backup or test wipe)
        // so SQLCipher doesn't fail trying to decrypt old data with a new key.
        // Fail loudly if the deletion didn't stick: opening a stale,
        // still-encrypted meshline.db with a fresh passphrase only surfaces
        // as code 26 (SQLiteNotADatabaseException) at first query — we want
        // the true cause now, not that deep in the stack.
        val deleted = deleteDatabaseFiles(context)
        check(deleted) { "meshline.db files could not be deleted before creating a new passphrase" }
        if (context.getDatabasePath(DB_NAME).exists()) {
            throw IllegalStateException("meshline.db exists after deletion")
        }
        deleteWrapKey()
        val passphrase = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = keystoreKey(createIfMissing = true) ?: error("Failed to generate Keystore wrap key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(passphrase)
        prefs.edit()
            .clear()
            .putString("wrapped", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        return passphrase
    }

    /** Panic wipe: forget the passphrase and delete the encrypted DB.
     *  Returns true iff every database file (main + sidecars) is gone. */
    @Synchronized
    fun wipe(context: Context): Boolean {
        prefs(context).edit().clear().commit()
        deleteWrapKey()
        return deleteDatabaseFiles(context)
    }

    internal fun deleteWrapKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    /**
     * Delete `meshline.db` and every sidecar file deterministically: close all
     * Room/SQLCipher handles first, then delete the main file and the
     * `-journal`, `-wal` and `-shm` sidecars, verify each is absent and retry
     * a few times. Returns true iff every expected file is gone.
     *
     * No `runCatching` around the deletion itself — the answer is real, so
     * callers cannot continue with stale files while believing they are erased.
     */
    internal fun deleteDatabaseFiles(context: Context): Boolean {
        DatabaseFactory.closeAll()
        runCatching { context.deleteDatabase(DB_NAME) }
        var allDeleted = true
        val parent = context.getDatabasePath(DB_NAME).parentFile
        if (parent != null && parent.exists()) {
            parent.listFiles()?.filter { it.name.startsWith(DB_NAME) }?.forEach { f ->
                var attempts = 0
                while (attempts < 5 && f.exists()) {
                    attempts++
                    f.delete()
                    if (f.exists()) Thread.sleep(50)
                }
                if (f.exists()) {
                    allDeleted = false
                    Log.w(TAG, "could not delete: ${f.name}")
                }
            }
        }
        for (name in listOf(DB_NAME, "$DB_NAME-journal", "$DB_NAME-wal", "$DB_NAME-shm")) {
            val file = context.getDatabasePath(name)
            val before = file.exists()
            var attempts = 0
            while (attempts < 5 && file.exists()) {
                attempts++
                file.delete()
                if (file.exists()) Thread.sleep(50)
            }
            val after = file.exists()
            if (before != after) {
                Log.i(TAG, "deleteDatabaseFiles: $name before=$before after=$after")
            }
            if (file.exists()) {
                allDeleted = false
                // File name only — never keys, passphrases or contents.
                Log.w(TAG, "could not delete: " + name)
            }
        }
        runCatching { context.getDatabasePath(DB_NAME).parentFile?.mkdirs() }
        return allDeleted
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keystoreKey(createIfMissing: Boolean): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = runCatching {
                if (ks.containsAlias(ALIAS)) {
                    (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                } else null
            }.getOrNull()
            if (existing != null) return@runCatching existing
            if (!createIfMissing) return@runCatching null
            runCatching { ks.deleteEntry(ALIAS) }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }.getOrNull()
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
