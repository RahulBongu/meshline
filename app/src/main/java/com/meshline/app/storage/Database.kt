package com.meshline.app.storage

import android.content.Context
import androidx.room.Room
import net.sqlcipher.database.SQLiteDatabase

/**
 * Encrypted DB builder (Room + SQLCipher). The passphrase is a **random
 * 32-byte key generated once with [java.security.SecureRandom]**, wrapped
 * with a Keystore AES-GCM key (alias `meshline-db-wrap`) and stored as
 * ciphertext in SharedPreferences; it is **never derived from any static
 * input** and never logged. Panic wipe deletes the DB file and the wrap key.
 */
object DatabaseFactory {
    fun create(context: Context, passphrase: ByteArray): MeshlineDatabase {
        SQLiteDatabase.loadLibs(context)
        val factory = net.sqlcipher.database.SupportFactory(passphrase)
        return Room.databaseBuilder(context, MeshlineDatabase::class.java, "meshline.db")
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }
}
