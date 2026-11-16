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
    val appSignatureSha256: String = "",
    /** AES-256-GCM encrypted JSON array of `ExtraField` items (e.g. User ID, PIN, encrypted notes). */
    @ColumnInfo(defaultValue = "")
    val encryptedExtraFields: String = "",
    /** Dedicated 12-byte random GCM IV paired exclusively with [encryptedExtraFields]. */
    @ColumnInfo(defaultValue = "")
    val extraFieldsIv: String = ""
) {
    val hasExtraFields: Boolean
        get() = encryptedExtraFields.isNotBlank() && extraFieldsIv.isNotBlank()
}
