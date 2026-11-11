package com.biometricpass.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test verifying the exported Room schema (`app/schemas/.../1.json`) and
 * ensuring [CredentialDatabase] opens cleanly with [CredentialDatabase.ALL_MIGRATIONS]
 * without `fallbackToDestructiveMigration()`.
 */
@RunWith(AndroidJUnit4::class)
class CredentialDatabaseMigrationTest {

    private val testDbName = "migration_test_vault.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CredentialDatabase::class.java
    )

    @Test
    fun migrate1To2PreservesCredentialsAndDefaultsSignatureToEmpty() = runTest {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                """
                INSERT INTO credentials (id, title, packageName, webDomain, username, encryptedPassword, iv, updatedAt)
                VALUES (1, 'Google', 'com.android.chrome', 'accounts.google.com', 'alice', 'cipher', 'iv', 1000)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(testDbName, 2, true, CredentialDatabase.MIGRATION_1_2)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, CredentialDatabase::class.java, testDbName)
            .addMigrations(*CredentialDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val all = db.credentialDao().getAll()
            assertEquals(1, all.size)
            val single = all.single()
            assertEquals("alice", single.username)
            assertEquals("", single.appSignatureSha256)
        } finally {
            db.close()
            context.deleteDatabase(testDbName)
        }
    }
}
