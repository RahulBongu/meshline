package com.meshline.app.storage

import android.content.Context
import androidx.room.Room
import com.meshline.app.security.DbPassphrase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Encrypted DB builder (Room + SQLCipher).
 *
 * The passphrase comes from [DbPassphrase]: 32 bytes generated once with
 * `SecureRandom`, wrapped by a Keystore AES-256-GCM key (`meshline-db-wrap`).
 * It is never derived from a static value and never logged. Panic wipe calls
 * [DbPassphrase.wipe], which clears the wrap key and deletes the DB files.
 */
object DatabaseFactory {
    /** Production entry point: Keystore-backed random passphrase. */
    fun create(context: Context): MeshlineDatabase = create(context, DbPassphrase.getOrCreate(context))

    /** Test entry point (explicit passphrase). */
    fun create(context: Context, passphrase: ByteArray): MeshlineDatabase {
        // net.zetetic:sqlcipher-android ships libsqlcipher.so; load it explicitly.
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, MeshlineDatabase::class.java, "meshline.db")
            .openHelperFactory(factory)
            .addMigrations(
                MeshlineDatabase.MIGRATION_1_2,
                MeshlineDatabase.MIGRATION_2_3,
                MeshlineDatabase.MIGRATION_3_4,
                MeshlineDatabase.MIGRATION_4_5,
                MeshlineDatabase.MIGRATION_5_6,
            )
            .fallbackToDestructiveMigration()
            .build()
    }
}
