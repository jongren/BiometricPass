package com.biometricpass.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local vault database.
 *
 * DATA SAFETY RULE: this database must NEVER use `fallbackToDestructiveMigration()`.
 * Every schema change must bump [version] and add an explicit [Migration] to [ALL_MIGRATIONS]
 * (the exported schema JSON lives in `app/schemas/` and is tracked by Git). If a migration is
 * missing, Room throws on open instead of silently wiping the user's passwords.
 */
@Database(
    entities = [CredentialEntity::class],
    version = 2,
    exportSchema = true
)
abstract class CredentialDatabase : RoomDatabase() {

    abstract fun credentialDao(): CredentialDao

    companion object {
        private const val DATABASE_NAME = "biometric_pass_vault.db"

        /** v1 -> v2: adds `appSignatureSha256` for target App signing-certificate pinning (S7). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `credentials` ADD COLUMN `appSignatureSha256` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        @Volatile
        private var INSTANCE: CredentialDatabase? = null

        fun getInstance(context: Context): CredentialDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CredentialDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
