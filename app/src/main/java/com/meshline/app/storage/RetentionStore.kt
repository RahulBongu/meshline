package com.meshline.app.storage

import android.content.Context

/**
 * The message retention setting (FR-15).
 *
 * | value | meaning |
 * |---|---|
 * | off (default) | keep everything |
 * | 1 day | messages older than a day are purged |
 * | 7 days | messages older than a week are purged |
 *
 * The purge runs with the service's periodic tick. "Clear chat" always works,
 * whatever the retention is.
 */
enum class MessageRetention(val days: Int) {
    /** Keep everything (the default). */
    OFF(0),

    /** Purge messages older than one day. */
    ONE_DAY(1),

    /** Purge messages older than seven days. */
    SEVEN_DAYS(7);

    companion object {
        fun of(days: Int): MessageRetention = entries.firstOrNull { it.days == days } ?: OFF
    }
}

/** The retention setting, persisted in plain preferences (no secrets). */
class RetentionStore(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("meshline_settings", Context.MODE_PRIVATE)

    fun load(): MessageRetention = MessageRetention.of(prefs.getInt(KEY_DAYS, 0))

    fun save(retention: MessageRetention) {
        prefs.edit().putInt(KEY_DAYS, retention.days).apply()
    }

    /** Panic wipe: the setting goes back to the default. */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_DAYS = "message_retention_days"
    }
}
