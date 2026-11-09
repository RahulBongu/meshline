package com.meshline.app.profile

import android.content.Context
import com.meshline.app.protocol.AvatarTable
import com.meshline.app.protocol.Payloads
import kotlin.random.Random

/**
 * Profile persistence (D1): nickname + fruit avatar, changeable in Settings.
 * Avatar display string is "fruit nickname - 4-hex" (see Identity).
 */
class ProfileStore(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("meshline_profile", Context.MODE_PRIVATE)

    fun load(): Profile {
        val nick = prefs.getString("nickname", null)
            ?: return randomProfile().also { save(it) }
        val avatar = prefs.getInt("avatar", 0).toByte()
        return Profile(nick, avatar)
    }

    fun save(profile: Profile) {
        val clean = Payloads.sanitizeNickname(profile.nickname)
        prefs.edit().putString("nickname", clean).putInt("avatar", profile.avatarIndex.toInt()).apply()
    }

    fun wipe() {
        prefs.edit().clear().apply()
    }

    companion object {
        fun randomProfile(): Profile = Profile("anon${Random.nextInt(1000, 9999)}", Random.nextInt(0, 16).toByte())
        fun displayName(profile: Profile, fingerprintShort: String): String =
            "${AvatarTable.name(profile.avatarIndex.toInt())} ${profile.nickname} - $fingerprintShort"
    }
}
