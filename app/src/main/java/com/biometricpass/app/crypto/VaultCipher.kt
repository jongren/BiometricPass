package com.biometricpass.app.crypto

import javax.crypto.SecretKey

/**
 * Encryption operations the repository needs. Implemented by [CryptoManager] in the App and by a
 * pure [VaultCrypto]-backed fake in JVM unit tests.
 */
interface VaultCipher {
    fun encryptWithDek(plaintext: String, dek: SecretKey): VaultCrypto.EncryptedPayload
    fun decryptWithDek(ciphertextBase64: String, ivBase64: String, dek: SecretKey): String
    fun encryptBackupFileContent(plainBackupJson: String, dek: SecretKey): String
    fun decryptBackupFileContent(backupFileText: String, dek: SecretKey): String
    fun createMigrationQrPayload(plainMigrationJson: String, transferCode: String): String
    fun decryptMigrationQrPayload(qrPayload: String, transferCode: String): String
}
