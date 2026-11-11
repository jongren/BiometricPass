package com.biometricpass.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room Entity storing credential metadata in cleartext (for instant AutofillService matching
 * without triggering BiometricPrompt) and the AES-256-GCM encrypted password + IV.
 */
@Entity(
    tableName = "credentials",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["webDomain"]),
        Index(value = ["title"])
    ]
)
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val packageName: String,
    val webDomain: String = "",
    val username: String,
    val encryptedPassword: String,
    val iv: String,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Lowercase hex SHA-256 of the target App's signing certificate when saved (empty for browsers / legacy). */
    @ColumnInfo(defaultValue = "")
    val appSignatureSha256: String = ""
)
