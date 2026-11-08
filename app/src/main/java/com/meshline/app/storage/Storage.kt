package com.meshline.app.storage

/**
 * Storage contract (PRD §14). Phase D.
 *
 * Tables: `peers`, `messages`, `channels` (P1), `outbox`, `settings`.
 * The database is Room + SQLCipher (encrypted at rest, auto-delete options
 * off / 1 day / 7 days are P1). Private keys are NEVER stored here — they live
 * only in the Android Keystore ([com.meshline.app.security.SecurityModule]).
 */
interface Storage {
    suspend fun clearAll() // panic wipe (Phase D)
}
