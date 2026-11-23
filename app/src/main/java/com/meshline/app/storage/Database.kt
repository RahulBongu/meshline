package com.meshline.app.storage

import android.content.Context
import androidx.room.Room
import android.util.Log
import androidx.room.RoomDatabase
import com.meshline.app.security.DbPassphrase
import java.util.concurrent.ConcurrentHashMap
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
    private const val DB_FILE_NAME = "meshline.db"

    /** Room instances created by this factory that are still registered. */
    private val open = ConcurrentHashMap.newKeySet<RoomDatabase>()

    /** Production entry point: Keystore-backed random passphrase. */
    fun create(context: Context): MeshlineDatabase = create(context, DbPassphrase.getOrCreate(context))

    /** Test entry point (explicit passphrase). */
    fun create(context: Context, passphrase: ByteArray): MeshlineDatabase {
        // net.zetetic:sqlcipher-android ships libsqlcipher.so; load it explicitly.
        System.loadLibrary("sqlcipher")
        // Room opens the database lazily on first access; the parent directory
        // must exist at that moment, or SQLCipher's open() can fail.
        val dbDir = context.getDatabasePath(DB_FILE_NAME).parentFile
        if (dbDir != null && !dbDir.isDirectory && !dbDir.mkdirs()) {
            Log.w(TAG, "database directory is not writable")
        }
        val factory = SupportOpenHelperFactory(passphrase)
        val db = Room.databaseBuilder(context, MeshlineDatabase::class.java, "meshline.db")
            .openHelperFactory(factory)
            .addMigrations(
                MeshlineDatabase.MIGRATION_1_2,
                MeshlineDatabase.MIGRATION_2_3,
                MeshlineDatabase.MIGRATION_3_4,
                MeshlineDatabase.MIGRATION_4_5,
                MeshlineDatabase.MIGRATION_5_6,
                MeshlineDatabase.MIGRATION_6_7,
                MeshlineDatabase.MIGRATION_7_8,
                MeshlineDatabase.MIGRATION_8_9,
                MeshlineDatabase.MIGRATION_9_10,
                MeshlineDatabase.MIGRATION_10_11,
            )
            .fallbackToDestructiveMigration()
            .build()
        register(db)
        return db
    }

    private fun register(db: RoomDatabase) {
        open.add(db)
        Log.i(TAG, "registering a new Room instance (open=${open.size})")
    }

    private const val TAG = "DatabaseFactory"

    /** Close every registered Room/SQLCipher handle (e.g. before file deletion). */
    @JvmStatic
    fun closeAll() {
        Log.i(TAG, "closeAll(${open.size} registered instances)")
        for (db in open.toList()) {
            open.remove(db)
            runCatching {
                db.close()
            }
        }
    }
}
