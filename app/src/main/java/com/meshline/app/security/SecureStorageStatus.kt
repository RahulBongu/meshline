package com.meshline.app.security

import android.content.Context

/**
 * One-shot notice that secure storage could not be unlocked and was reset.
 *
 * Reset means: the Keystore wrapping key was invalidated (OS upgrade / OEM
 * key loss) or the SQLCipher database could not be decrypted. The app then
 * creates a **new identity** and an **empty contacts DB** instead of crashing.
 * Peers see a new senderId/fingerprint (and in D2 a key-change warning).
 *
 * The reason is persisted in plain prefs (no secrets) and consumed by the UI
 * exactly once, so a reset that happens in the service is still shown when the
 * user next opens the app.
 */
object SecureStorageStatus {

    private const val PREFS = "meshline_secure_storage"
    private const val KEY_REASON = "reset_reason"
    private const val KEY_AT = "reset_at"

    enum class Reason {
        /** The Keystore wrap key disappeared or could not decrypt the identity. */
        KEYSTORE_INVALIDATED,

        /** The SQLCipher DB (or its wrapped passphrase) could not be read. */
        DB_UNREADABLE,

        /** The user wiped the device on purpose (panic wipe). */
        PANIC_WIPE,
    }

    data class Event(val reason: Reason, val atMs: Long)

    fun record(context: Context, reason: Reason) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REASON, reason.name)
            .putLong(KEY_AT, System.currentTimeMillis())
            .commit()
    }

    /** Returns the pending reset notice and clears it (show once). */
    fun consume(context: Context): Event? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REASON, null) ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        prefs.edit().clear().apply()
        val reason = runCatching { Reason.valueOf(raw) }.getOrDefault(Reason.KEYSTORE_INVALIDATED)
        return Event(reason, at)
    }
}
