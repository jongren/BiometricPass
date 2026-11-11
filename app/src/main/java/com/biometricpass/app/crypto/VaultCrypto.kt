package com.biometricpass.app.crypto

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure (Android-framework-free) cryptographic primitives used by [CryptoManager].
 *
 * Everything here works on plain JVM so it can be covered by local unit tests:
 * AES-256-GCM with random 96-bit IVs, GZIP, Base64, PBKDF2 key derivation, and the
 * backup-file / migration-QR / recovery-QR (V1 & passphrase-protected V2) container formats.
 */
object VaultCrypto {

    const val AES_KEY_BIT_LEN = 256
    const val AES_KEY_BYTE_LEN = 32
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTE_LEN = 12
    private const val GCM_TAG_BIT_LEN = 128

    private const val BACKUP_MAGIC = "BIOMETRICPASS_BACKUP_V1"

    /**
     * Backup container version. Version 2 binds the header (magic + version) as AES-GCM associated
     * data so a tampered / downgraded header fails authentication. Version 1 files remain readable.
     */
    private const val BACKUP_VERSION = 2

    const val RECOVERY_V1_QR_PREFIX = "BIOMETRICPASS_RECOVERY_V1:"
    const val RECOVERY_V2_QR_PREFIX = "BIOMETRICPASS_RECOVERY_V2:"

    const val MIGRATION_QR_PREFIX = "BIOMETRICPASS_MIGRATION_V2:"
    private const val PBKDF2_SALT_BYTE_LEN = 16

    /**
     * PBKDF2-HMAC-SHA256 work factor for NEW payloads (OWASP: >= 310,000). The value is stored in each
     * payload (`"i"`) so it can be raised later without breaking existing QR codes / transfer files.
     */
    const val PBKDF2_ITERATIONS = 310_000

    /** Work factor of payloads created before the iteration count was stored (no `"i"` field). */
    private const val PBKDF2_LEGACY_ITERATIONS = 150_000

    /** Upper bound accepted from a payload, so a crafted QR code cannot freeze the device. */
    private const val PBKDF2_MAX_ITERATIONS = 2_000_000

    private val secureRandom = SecureRandom()

    data class EncryptedPayload(
        val ciphertextBase64: String,
        val ivBase64: String
    )

    data class DecodedRecoveryPayload(
        val kek2Bytes: ByteArray,
        val dekBytes: ByteArray
    )

    class WrongTransferCodeException : Exception("Invalid transfer code")

    class WrongRecoveryPassphraseException : Exception("Incorrect backup passphrase")

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { secureRandom.nextBytes(it) }

    fun newAesKey(): SecretKey = aesKey(randomBytes(AES_KEY_BYTE_LEN))

    fun aesKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")

    fun encryptBytes(plainBytes: ByteArray, key: SecretKey, aad: ByteArray? = null): EncryptedPayload {
        val iv = randomBytes(GCM_IV_BYTE_LEN)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BIT_LEN, iv))
        if (aad != null) cipher.updateAAD(aad)
        return EncryptedPayload(
            ciphertextBase64 = encodeBase64(cipher.doFinal(plainBytes)),
            ivBase64 = encodeBase64(iv)
        )
    }

    fun decryptBytes(
        ciphertextBase64: String,
        ivBase64: String,
        key: SecretKey,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BIT_LEN, decodeBase64(ivBase64)))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(decodeBase64(ciphertextBase64))
    }

    fun encryptString(plaintext: String, key: SecretKey): EncryptedPayload =
        encryptBytes(plaintext.toByteArray(StandardCharsets.UTF_8), key)

    fun decryptString(ciphertextBase64: String, ivBase64: String, key: SecretKey): String =
        String(decryptBytes(ciphertextBase64, ivBase64, key), StandardCharsets.UTF_8)

    // ---------------------------------------------------------------------------------------------
    // Backup file (.bpass)
    // ---------------------------------------------------------------------------------------------

    private fun backupAad(version: Int): ByteArray? =
        if (version >= 2) "$BACKUP_MAGIC|$version".toByteArray(StandardCharsets.UTF_8) else null

    fun encryptBackupFileContent(plainBackupJson: String, dek: SecretKey): String {
        val compressed = gzipCompress(plainBackupJson.toByteArray(StandardCharsets.UTF_8))
        val encrypted = encryptBytes(compressed, dek, backupAad(BACKUP_VERSION))
        return JSONObject().apply {
            put("magic", BACKUP_MAGIC)
            put("version", BACKUP_VERSION)
            put("iv", encrypted.ivBase64)
            put("ciphertext", encrypted.ciphertextBase64)
            put("exportedAt", System.currentTimeMillis())
        }.toString(2)
    }

    fun decryptBackupFileContent(backupFileText: String, dek: SecretKey): String {
        val json = JSONObject(backupFileText.trim())
        require(json.optString("magic", "") == BACKUP_MAGIC) { "Unsupported backup file format" }
        val version = json.optInt("version", 1)
        require(version in 1..BACKUP_VERSION) { "Unsupported backup file version" }
        val compressedBytes = decryptBytes(json.getString("ciphertext"), json.getString("iv"), dek, backupAad(version))
        return String(gzipDecompress(compressedBytes), StandardCharsets.UTF_8)
    }

    fun isBackupFileFormat(text: String): Boolean =
        try {
            JSONObject(text.trim()).optString("magic", "") == BACKUP_MAGIC
        } catch (_: Exception) {
            false
        }

    // ---------------------------------------------------------------------------------------------
    // Backup Unlock Key QR Code (V1 plain KEK2 & V2 passphrase-protected KEK2)
    // ---------------------------------------------------------------------------------------------

    fun isRecoveryQrPayload(text: String): Boolean {
        val t = text.trim()
        return t.startsWith(RECOVERY_V1_QR_PREFIX) || t.startsWith(RECOVERY_V2_QR_PREFIX)
    }

    fun isPassphraseProtectedRecoveryQr(text: String): Boolean =
        text.trim().startsWith(RECOVERY_V2_QR_PREFIX)

    /**
     * Encodes the backup unlock key QR payload.
     * - When [passphrase] is null or blank: produces `BIOMETRICPASS_RECOVERY_V1:` (compatible with V1).
     * - When [passphrase] is provided: wraps [kek2Bytes] with a PBKDF2-derived AES-256-GCM key and
     *   produces `BIOMETRICPASS_RECOVERY_V2:` so the QR image alone cannot unlock backups without the passphrase.
     */
    fun createRecoveryQrPayload(
        kek2Bytes: ByteArray,
        wrappedDekCiphertextBase64: String,
        wrappedDekIvBase64: String,
        passphrase: String? = null
    ): String {
        val cleanPassphrase = passphrase?.trim().orEmpty()
        if (cleanPassphrase.isEmpty()) {
            val json = JSONObject().apply {
                put("v", 1)
                put("type", "recovery")
                put("kek2", encodeBase64(kek2Bytes))
                put("wdek", wrappedDekCiphertextBase64)
                put("iv", wrappedDekIvBase64)
            }
            val encodedJson = encodeBase64(json.toString().toByteArray(StandardCharsets.UTF_8))
            return "$RECOVERY_V1_QR_PREFIX$encodedJson"
        }

        val salt = randomBytes(PBKDF2_SALT_BYTE_LEN)
        val passphraseKey = derivePbkdf2Key(cleanPassphrase, salt, PBKDF2_ITERATIONS)
        val encryptedKek2 = encryptBytes(kek2Bytes, passphraseKey)
        val json = JSONObject().apply {
            put("v", 2)
            put("type", "recovery_v2")
            put("s", encodeBase64(salt))
            put("i", PBKDF2_ITERATIONS)
            put("ekek2", encryptedKek2.ciphertextBase64)
            put("k2iv", encryptedKek2.ivBase64)
            put("wdek", wrappedDekCiphertextBase64)
            put("iv", wrappedDekIvBase64)
        }
        val encodedJson = encodeBase64(json.toString().toByteArray(StandardCharsets.UTF_8))
        return "$RECOVERY_V2_QR_PREFIX$encodedJson"
    }

    /**
     * Decodes either a V1 or V2 backup unlock key QR payload.
     * Throws [WrongRecoveryPassphraseException] when a V2 payload is given a missing or wrong passphrase.
     */
    fun decodeRecoveryQrPayload(
        qrPayload: String,
        passphrase: String? = null
    ): DecodedRecoveryPayload {
        val trimmed = qrPayload.trim()
        return when {
            trimmed.startsWith(RECOVERY_V1_QR_PREFIX) -> {
                val json = JSONObject(
                    String(decodeBase64(trimmed.removePrefix(RECOVERY_V1_QR_PREFIX)), StandardCharsets.UTF_8)
                )
                val kek2Bytes = decodeBase64(json.getString("kek2"))
                val qrWrappedDek = json.optString("wdek", "")
                val qrIv = json.optString("iv", "")
                require(qrWrappedDek.isNotBlank() && qrIv.isNotBlank()) { "Incomplete recovery QR payload" }
                val dekBytes = decryptBytes(qrWrappedDek, qrIv, aesKey(kek2Bytes))
                DecodedRecoveryPayload(kek2Bytes = kek2Bytes, dekBytes = dekBytes)
            }

            trimmed.startsWith(RECOVERY_V2_QR_PREFIX) -> {
                val cleanPassphrase = passphrase?.trim().orEmpty()
                if (cleanPassphrase.isEmpty()) {
                    throw WrongRecoveryPassphraseException()
                }
                val json = JSONObject(
                    String(decodeBase64(trimmed.removePrefix(RECOVERY_V2_QR_PREFIX)), StandardCharsets.UTF_8)
                )
                val salt = decodeBase64(json.getString("s"))
                val ekek2 = json.getString("ekek2")
                val k2iv = json.getString("k2iv")
                val qrWrappedDek = json.optString("wdek", "")
                val qrIv = json.optString("iv", "")
                require(qrWrappedDek.isNotBlank() && qrIv.isNotBlank()) { "Incomplete recovery QR payload" }

                val passphraseKey = derivePbkdf2Key(cleanPassphrase, salt, payloadIterations(json))
                val kek2Bytes = try {
                    decryptBytes(ekek2, k2iv, passphraseKey)
                } catch (_: AEADBadTagException) {
                    throw WrongRecoveryPassphraseException()
                }
                val dekBytes = decryptBytes(qrWrappedDek, qrIv, aesKey(kek2Bytes))
                DecodedRecoveryPayload(kek2Bytes = kek2Bytes, dekBytes = dekBytes)
            }

            else -> throw IllegalArgumentException("Not a valid recovery QR code")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Device-to-Device migration QR Code
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates a GZIP-compressed & AES-256-GCM encrypted QR payload for Device-to-Device migration.
     *
     * Security: the key is NOT embedded in the QR Code. It is derived with PBKDF2-HMAC-SHA256 from a
     * one-time [transferCode] that the old phone shows separately as text and the user types on the
     * new phone. Photographing the QR Code alone reveals nothing.
     */
    fun createMigrationQrPayload(plainMigrationJson: String, transferCode: String): String {
        val salt = randomBytes(PBKDF2_SALT_BYTE_LEN)
        val transferKey = derivePbkdf2Key(TransferCode.normalize(transferCode), salt, PBKDF2_ITERATIONS)
        val compressed = gzipCompress(plainMigrationJson.toByteArray(StandardCharsets.UTF_8))
        val encrypted = encryptBytes(compressed, transferKey)
        val wrapperJson = JSONObject().apply {
            put("v", 2)
            put("s", encodeBase64(salt))
            put("i", PBKDF2_ITERATIONS)
            put("iv", encrypted.ivBase64)
            put("d", encrypted.ciphertextBase64)
        }
        val encoded = encodeBase64(wrapperJson.toString().toByteArray(StandardCharsets.UTF_8))
        return "$MIGRATION_QR_PREFIX$encoded"
    }

    /**
     * Decrypts a migration QR Code payload. Throws [WrongTransferCodeException] on a wrong code.
     */
    fun decryptMigrationQrPayload(qrPayload: String, transferCode: String): String {
        val trimmed = qrPayload.trim()
        require(trimmed.startsWith(MIGRATION_QR_PREFIX)) {
            "Unsupported migration QR version"
        }
        val wrapperJson = JSONObject(
            String(decodeBase64(trimmed.removePrefix(MIGRATION_QR_PREFIX)), StandardCharsets.UTF_8)
        )
        val salt = decodeBase64(wrapperJson.getString("s"))
        val transferKey = derivePbkdf2Key(TransferCode.normalize(transferCode), salt, payloadIterations(wrapperJson))
        val compressed = try {
            decryptBytes(wrapperJson.getString("d"), wrapperJson.getString("iv"), transferKey)
        } catch (_: AEADBadTagException) {
            throw WrongTransferCodeException()
        }
        return String(gzipDecompress(compressed), StandardCharsets.UTF_8)
    }

    private fun payloadIterations(json: JSONObject): Int {
        val iterations = json.optInt("i", PBKDF2_LEGACY_ITERATIONS)
        require(iterations in 1..PBKDF2_MAX_ITERATIONS) { "Unsupported key derivation parameters" }
        return iterations
    }

    private fun derivePbkdf2Key(secret: String, salt: ByteArray, iterations: Int): SecretKey {
        val chars = secret.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, AES_KEY_BIT_LEN)
        try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return aesKey(keyBytes)
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun gzipDecompress(compressed: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }

    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decodeBase64(str: String): ByteArray = Base64.getMimeDecoder().decode(str.trim())
}

/**
 * One-time transfer code displayed on the old phone during QR migration.
 * 8 characters from a 31-symbol alphabet without look-alike characters (0/O, 1/I/L) ≈ 40 bits.
 */
object TransferCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    private val secureRandom = SecureRandom()

    fun generate(): String =
        (1..LENGTH).map { ALPHABET[secureRandom.nextInt(ALPHABET.length)] }.joinToString("")

    /** Formats as `ABCD-EFGH` for display. */
    fun format(code: String): String {
        val n = normalize(code)
        return if (n.length == LENGTH) "${n.substring(0, 4)}-${n.substring(4)}" else n
    }

    /** Uppercases and strips separators / spaces. */
    fun normalize(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }

    fun isComplete(input: String): Boolean = normalize(input).length == LENGTH
}
