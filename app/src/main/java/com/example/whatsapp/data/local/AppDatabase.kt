package com.example.whatsapp.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.whatsapp.security.DatabaseKeyProvider
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, SyncStateEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2: media_sha256
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        private const val DB_NAME = "chat_secure.db"
        private const val LEGACY_PLAINTEXT_DB = "chat.db"

        /**
         * Opens the SQLCipher-encrypted database (AES-256, per-page HMAC). If the Keystore key that
         * protects the passphrase is gone (e.g. data restored onto a new device, OEM Keystore reset),
         * the DB is unreadable by design: it is wiped and the app re-syncs from the server rather
         * than crash-looping.
         */
        fun create(context: Context, keys: DatabaseKeyProvider = DatabaseKeyProvider(context)): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = try {
                keys.getOrCreatePassphrase()
            } catch (e: DatabaseKeyProvider.KeyUnavailableException) {
                keys.destroy()
                context.deleteDatabase(DB_NAME)
                keys.getOrCreatePassphrase()
            }
            migrateLegacyPlaintext(context, passphrase)
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // WAL lets the UI read while the sync engine writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        /**
         * One-time upgrade for installs that predate encryption: copies the plaintext DB into a new
         * encrypted file with sqlcipher_export() and deletes the plaintext copy. Schema version is
         * carried over so Room's normal migrations (1 -> 2) still run afterwards.
         */
        private fun migrateLegacyPlaintext(context: Context, passphrase: ByteArray) {
            val legacy = context.getDatabasePath(LEGACY_PLAINTEXT_DB)
            if (!legacy.exists()) return
            val target = context.getDatabasePath(DB_NAME)
            if (!target.exists() || target.length() == 0L) {
                // Export to a temp file and rename, so a crash mid-export never leaves a partial
                // "encrypted" DB that would cause the plaintext original to be discarded.
                val temp = context.getDatabasePath("$DB_NAME.migrating").apply { delete() }
                // CREATE_IF_NECESSARY matters: ATTACH inherits these flags and must create the target.
                val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
                val plain = SQLiteDatabase.openDatabase(legacy.path, "", null, flags, null, null)
                try {
                    val version = plain.version
                    // Bound parameters, never string-concatenated: SQLite's error log prints the failing
                    // statement text, which would otherwise leak the passphrase into logcat.
                    plain.execSQL(
                        "ATTACH DATABASE ? AS encrypted KEY ?",
                        arrayOf(temp.path, String(passphrase, Charsets.US_ASCII)),
                    )
                    val attached = plain.rawQuery("PRAGMA database_list", null).use { c ->
                        generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == "encrypted" }
                    }
                    check(attached) { "ATTACH of encrypted target failed" }
                    plain.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
                    plain.execSQL("PRAGMA encrypted.user_version = $version")
                    plain.execSQL("DETACH DATABASE encrypted")
                } finally {
                    plain.close()
                }
                if (!temp.renameTo(target)) return // keep plaintext; retry next launch
            }
            context.deleteDatabase(LEGACY_PLAINTEXT_DB) // also removes -wal / -shm
        }
    }
}
