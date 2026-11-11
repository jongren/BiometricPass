package com.biometricpass.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.biometricpass.app.crypto.VaultCrypto.decodeBase64
import com.biometricpass.app.crypto.VaultCrypto.encodeBase64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Envelope Encryption Manager for BiometricPass.
 *
 * Key Hierarchy:
 * 1. DEK (Data Encryption Key): 256-bit AES-GCM key generated randomly on first initialization.
 *    Used to encrypt/decrypt credential passwords and SAF backup payloads.
 * 2. KEK 1 (`biometric_pass_kek_v1`): 256-bit AES-GCM key inside AndroidKeyStore wrapping the DEK.
 *    Configured with:
 *    - `setUserAuthenticationRequired(true)`
 *    - `setInvalidatedByBiometricEnrollment(false)` (Adding/changing fingerprints NEVER invalidates KEK 1)
 *    - `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`
 * 3. KEK 2 (Backup Unlock Key): 256-bit AES-GCM key wrapping a rescue copy of the DEK, encoded into
 *    the "備份解鎖金鑰" QR Code (optionally protected by a PBKDF2 custom passphrase in V2).
 *
 * All key-material writes use [SharedPreferences.Editor.commit] (synchronous).
 */
class CryptoManager(context: Context) : VaultCipher {

    /**
     * Thrown ONLY when the hardware-wrapped DEK is permanently unreadable (Keystore key invalidated,
     * deleted, or wrapped data corrupted). Transient Keystore / authentication errors are propagated
     * unchanged so the UI offers a simple retry instead of the destructive recovery flow.
     */
    class VaultKeyCorruptedException(cause: Throwable? = null) :
        Exception("Vault key material is permanently unreadable", cause)

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Keystore alias of a fresh KEK 1 prepared for locked-screen recovery (not yet active). */
    @Volatile
    private var pendingRecoveryKekAlias: String? = null

    fun isInitialized(): Boolean {
        return prefs.contains(KEY_WRAPPED_DEK_KEYSTORE) &&
            prefs.contains(KEY_WRAPPED_DEK_KEYSTORE_IV)
    }

    private fun activeKekAlias(): String =
        prefs.getString(KEY_ACTIVE_KEK_ALIAS, null) ?: KEYSTORE_KEK_ALIAS

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Must be invoked inside the success callback of BiometricPrompt (BIOMETRIC_STRONG or DEVICE_CREDENTIAL),
     * when AndroidKeyStore has authorized KEK 1 for cryptographic operations.
     */
    @Synchronized
    fun initializeOrUnlockDek(): SecretKey {
        return if (!isInitialized()) {
            setupNewDekAndKeys()
        } else {
            resolveInterruptedRotation(unwrapDekWithKeystore())
        }
    }

    /**
     * Checks whether a candidate DEK decrypts the stored vault (provided by the App, because the
     * vault database lives outside this class). Only used to finish a key rotation that was
     * interrupted (e.g. process killed) between re-encrypting the database and committing the keys.
     */
    @Volatile
    var vaultKeyVerifier: ((SecretKey) -> Boolean)? = null

    private fun setupNewDekAndKeys(): SecretKey {
        val dek = VaultCrypto.newAesKey()
        val alias = activeKekAlias()
        persistWrappedKeys(
            dekBytes = dek.encoded,
            kek2Bytes = VaultCrypto.randomBytes(VaultCrypto.AES_KEY_BYTE_LEN),
            kek1 = getOrCreateKeystoreKek(alias),
            kek1Alias = alias
        )
        return dek
    }

    /** All persisted wrapped forms of one DEK (+ its KEK 2). */
    private class WrappedKeySet(
        val wrappedDekByKek1: String,
        val wrappedDekByKek1Iv: String,
        val wrappedDekByKek2: String,
        val wrappedDekByKek2Iv: String,
        val encryptedKek2ByDek: String,
        val encryptedKek2ByDekIv: String
    )

    private fun buildWrappedKeySet(dekBytes: ByteArray, kek2Bytes: ByteArray, kek1: SecretKey): WrappedKeySet {
        val dek = VaultCrypto.aesKey(dekBytes)
        val keystoreCipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        keystoreCipher.init(Cipher.ENCRYPT_MODE, kek1)
        val wrappedDekByKek1 = keystoreCipher.doFinal(dekBytes)
        val kek1Iv = keystoreCipher.iv

        val wrappedByKek2 = VaultCrypto.encryptBytes(dekBytes, VaultCrypto.aesKey(kek2Bytes))
        val encryptedKek2ByDek = VaultCrypto.encryptBytes(kek2Bytes, dek)
        return WrappedKeySet(
            wrappedDekByKek1 = encodeBase64(wrappedDekByKek1),
            wrappedDekByKek1Iv = encodeBase64(kek1Iv),
            wrappedDekByKek2 = wrappedByKek2.ciphertextBase64,
            wrappedDekByKek2Iv = wrappedByKek2.ivBase64,
            encryptedKek2ByDek = encryptedKek2ByDek.ciphertextBase64,
            encryptedKek2ByDekIv = encryptedKek2ByDek.ivBase64
        )
    }

    private fun SharedPreferences.Editor.putKeySet(set: WrappedKeySet, prefix: String = ""): SharedPreferences.Editor =
        putString(prefix + KEY_WRAPPED_DEK_KEYSTORE, set.wrappedDekByKek1)
            .putString(prefix + KEY_WRAPPED_DEK_KEYSTORE_IV, set.wrappedDekByKek1Iv)
            .putString(prefix + KEY_WRAPPED_DEK_RECOVERY, set.wrappedDekByKek2)
            .putString(prefix + KEY_WRAPPED_DEK_RECOVERY_IV, set.wrappedDekByKek2Iv)
            .putString(prefix + KEY_ENCRYPTED_KEK2_BY_DEK, set.encryptedKek2ByDek)
            .putString(prefix + KEY_ENCRYPTED_KEK2_IV, set.encryptedKek2ByDekIv)

    private fun readKeySet(prefix: String): WrappedKeySet? {
        return WrappedKeySet(
            wrappedDekByKek1 = prefs.getString(prefix + KEY_WRAPPED_DEK_KEYSTORE, null) ?: return null,
            wrappedDekByKek1Iv = prefs.getString(prefix + KEY_WRAPPED_DEK_KEYSTORE_IV, null) ?: return null,
            wrappedDekByKek2 = prefs.getString(prefix + KEY_WRAPPED_DEK_RECOVERY, null) ?: return null,
            wrappedDekByKek2Iv = prefs.getString(prefix + KEY_WRAPPED_DEK_RECOVERY_IV, null) ?: return null,
            encryptedKek2ByDek = prefs.getString(prefix + KEY_ENCRYPTED_KEK2_BY_DEK, null) ?: return null,
            encryptedKek2ByDekIv = prefs.getString(prefix + KEY_ENCRYPTED_KEK2_IV, null) ?: return null
        )
    }

    private fun SharedPreferences.Editor.removePendingKeySet(): SharedPreferences.Editor {
        for (key in KEY_SET_NAMES) remove(PENDING_PREFIX + key)
        return this
    }

    private fun persistWrappedKeys(
        dekBytes: ByteArray,
        kek2Bytes: ByteArray,
        kek1: SecretKey,
        kek1Alias: String
    ) {
        val set = buildWrappedKeySet(dekBytes, kek2Bytes, kek1)
        val ok = prefs.edit()
            .putString(KEY_ACTIVE_KEK_ALIAS, kek1Alias)
            .putKeySet(set)
            .removePendingKeySet()
            .commit()
        check(ok) { "Failed to persist wrapped key material" }
    }

    // ---------------------------------------------------------------------------------------------
    // Vault key rotation ("Reset Backup Key")
    // ---------------------------------------------------------------------------------------------

    /**
     * Step 1 of a key rotation. Must run right after a successful BiometricPrompt (KEK 1 authorized).
     * Wraps [newDek] with KEK 1 and a brand-new KEK 2 and stores the result as a PENDING key set, while
     * the active key set keeps working. No Keystore operation is needed afterwards, so the rotation
     * can complete even if re-encrypting the database outlasts the KEK 1 authorization window.
     */
    @Synchronized
    fun beginKeyRotation(newDek: SecretKey) {
        rotationInProgress = true
        val kek1 = (loadKeyStore().getEntry(activeKekAlias(), null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Active KEK is missing")
        val newKek2 = VaultCrypto.randomBytes(VaultCrypto.AES_KEY_BYTE_LEN)
        try {
            val pending = buildWrappedKeySet(newDek.encoded, newKek2, kek1)
            val ok = prefs.edit().putKeySet(pending, PENDING_PREFIX).commit()
            check(ok) { "Failed to persist pending key material" }
        } catch (e: Exception) {
            rotationInProgress = false
            throw e
        } finally {
            newKek2.fill(0)
        }
    }

    /**
     * Step 3 of a key rotation, after the database has been re-encrypted with the new DEK:
     * atomically promotes the pending key set to active. The old Backup Key stops working.
     */
    @Synchronized
    fun commitKeyRotation() {
        val pending = readKeySet(PENDING_PREFIX) ?: throw IllegalStateException("No pending key rotation")
        val ok = prefs.edit().putKeySet(pending).removePendingKeySet().commit()
        check(ok) { "Failed to commit rotated key material" }
        rotationInProgress = false
    }

    /** Abandons a rotation whose database re-encryption failed (the database was rolled back). */
    @Synchronized
    fun discardPendingKeyRotation() {
        prefs.edit().removePendingKeySet().commit()
        rotationInProgress = false
    }

    /** True while THIS process is re-encrypting the database; the pending key set must not be touched. */
    @Volatile
    private var rotationInProgress = false

    /**
     * Finishes or discards a rotation interrupted by a crash, choosing whichever DEK actually
     * decrypts the database, so an interrupted rotation can never lock the user out.
     */
    private fun resolveInterruptedRotation(activeDek: SecretKey): SecretKey {
        if (rotationInProgress) return activeDek
        val pending = readKeySet(PENDING_PREFIX) ?: return activeDek
        val pendingDek = try {
            unwrapWithActiveKek(pending.wrappedDekByKek1, pending.wrappedDekByKek1Iv)
        } catch (_: Exception) {
            null
        }
        val verifier = vaultKeyVerifier
        if (pendingDek == null) {
            discardPendingKeyRotation()
            return activeDek
        }
        if (verifier == null) return activeDek
        return if (!verifier(activeDek) && verifier(pendingDek)) {
            commitKeyRotation()
            pendingDek
        } else {
            discardPendingKeyRotation()
            activeDek
        }
    }

    /**
     * Unwraps the DEK with the active KEK 1. Throws [VaultKeyCorruptedException] only for permanent
     * failures; any other exception (e.g. `UserNotAuthenticatedException`) is transient and retryable.
     */
    private fun unwrapDekWithKeystore(): SecretKey {
        val wrappedBase64 = prefs.getString(KEY_WRAPPED_DEK_KEYSTORE, null)
            ?: throw VaultKeyCorruptedException()
        val ivBase64 = prefs.getString(KEY_WRAPPED_DEK_KEYSTORE_IV, null)
            ?: throw VaultKeyCorruptedException()
        return unwrapWithActiveKek(wrappedBase64, ivBase64)
    }

    private fun unwrapWithActiveKek(wrappedBase64: String, ivBase64: String): SecretKey {

        val kek1 = (
            try {
                (loadKeyStore().getEntry(activeKekAlias(), null) as? KeyStore.SecretKeyEntry)?.secretKey
            } catch (e: UnrecoverableKeyException) {
                throw VaultKeyCorruptedException(e)
            }
            ) ?: throw VaultKeyCorruptedException()

        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek1, GCMParameterSpec(GCM_TAG_BIT_LEN, decodeBase64(ivBase64)))
            VaultCrypto.aesKey(cipher.doFinal(decodeBase64(wrappedBase64)))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultKeyCorruptedException(e)
        } catch (e: AEADBadTagException) {
            throw VaultKeyCorruptedException(e)
        } catch (e: IllegalArgumentException) {
            // Malformed Base64 in the stored wrapped key material
            throw VaultKeyCorruptedException(e)
        }
    }

    private fun getOrCreateKeystoreKek(alias: String): SecretKey {
        val existingEntry = loadKeyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }
        return createNewKeystoreKek(alias)
    }

    /**
     * Creates a fresh KEK 1 under a NEW alias BEFORE showing BiometricPrompt during locked-screen key
     * recovery, so the user's authentication timestamp is newer than the key creation time.
     *
     * The currently active KEK 1 is never deleted here: it is only removed after the recovered DEK has
     * been successfully re-wrapped and persisted (see [restoreDekFromRecoveryQrAndRewrap]). Cancelling
     * the prompt therefore can never make the existing vault less recoverable.
     */
    @Synchronized
    fun prepareKeystoreKekForRecovery() {
        val keyStore = loadKeyStore()
        deleteInactiveKekAliases(keyStore, keepAlias = activeKekAlias())
        val alias = "${KEYSTORE_KEK_ALIAS}_r${System.currentTimeMillis()}"
        createNewKeystoreKek(alias)
        pendingRecoveryKekAlias = alias
    }

    /** Removes KEK 1 keys left behind by cancelled recoveries (never the active alias). */
    private fun deleteInactiveKekAliases(keyStore: KeyStore, keepAlias: String) {
        val stale = keyStore.aliases().toList().filter {
            it.startsWith(KEYSTORE_KEK_ALIAS) && it != keepAlias
        }
        for (alias in stale) {
            try {
                keyStore.deleteEntry(alias)
            } catch (_: Exception) {
            }
        }
    }

    private fun createNewKeystoreKek(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(VaultCrypto.AES_KEY_BIT_LEN)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    override fun encryptWithDek(plaintext: String, dek: SecretKey): VaultCrypto.EncryptedPayload =
        VaultCrypto.encryptString(plaintext, dek)

    override fun decryptWithDek(ciphertextBase64: String, ivBase64: String, dek: SecretKey): String =
        VaultCrypto.decryptString(ciphertextBase64, ivBase64, dek)

    /**
     * Generates the backup unlock key QR payload (V1 without passphrase, or V2 protected by [passphrase]).
     */
    fun generateRecoveryQrPayload(dek: SecretKey, passphrase: String? = null): String {
        var encryptedKek2 = prefs.getString(KEY_ENCRYPTED_KEK2_BY_DEK, null)
        var encryptedKek2Iv = prefs.getString(KEY_ENCRYPTED_KEK2_IV, null)
        var wrappedDekRecovery = prefs.getString(KEY_WRAPPED_DEK_RECOVERY, null)
        var wrappedDekRecoveryIv = prefs.getString(KEY_WRAPPED_DEK_RECOVERY_IV, null)

        val kek2Bytes: ByteArray
        if (encryptedKek2 != null && encryptedKek2Iv != null &&
            wrappedDekRecovery != null && wrappedDekRecoveryIv != null
        ) {
            kek2Bytes = VaultCrypto.decryptBytes(encryptedKek2, encryptedKek2Iv, dek)
        } else {
            kek2Bytes = VaultCrypto.randomBytes(VaultCrypto.AES_KEY_BYTE_LEN)
            val wrapped = VaultCrypto.encryptBytes(dek.encoded, VaultCrypto.aesKey(kek2Bytes))
            val encKek2 = VaultCrypto.encryptBytes(kek2Bytes, dek)
            wrappedDekRecovery = wrapped.ciphertextBase64
            wrappedDekRecoveryIv = wrapped.ivBase64
            encryptedKek2 = encKek2.ciphertextBase64
            encryptedKek2Iv = encKek2.ivBase64
            val ok = prefs.edit()
                .putString(KEY_WRAPPED_DEK_RECOVERY, wrappedDekRecovery)
                .putString(KEY_WRAPPED_DEK_RECOVERY_IV, wrappedDekRecoveryIv)
                .putString(KEY_ENCRYPTED_KEK2_BY_DEK, encryptedKek2)
                .putString(KEY_ENCRYPTED_KEK2_IV, encryptedKek2Iv)
                .commit()
            check(ok) { "Failed to persist recovery key material" }
        }

        return try {
            VaultCrypto.createRecoveryQrPayload(
                kek2Bytes = kek2Bytes,
                wrappedDekCiphertextBase64 = wrappedDekRecovery,
                wrappedDekIvBase64 = wrappedDekRecoveryIv,
                passphrase = passphrase
            )
        } finally {
            kek2Bytes.fill(0)
        }
    }

    /**
     * Decodes the DEK contained in a V1 or V2 backup unlock key QR Code WITHOUT persisting anything.
     */
    fun decodeRecoveryDek(qrPayload: String, passphrase: String? = null): SecretKey {
        val decoded = VaultCrypto.decodeRecoveryQrPayload(qrPayload, passphrase)
        decoded.kek2Bytes.fill(0)
        return VaultCrypto.aesKey(decoded.dekBytes)
    }

    /**
     * Re-wraps a recovered DEK using the KEK 1 prepared by [prepareKeystoreKekForRecovery] after passing
     * BiometricPrompt on the locked recovery screen. The previous KEK 1 is deleted only AFTER the new
     * wrapped key material has been committed.
     */
    @Synchronized
    fun restoreDekFromRecoveryQrAndRewrap(qrPayload: String, passphrase: String? = null): SecretKey {
        val decoded = VaultCrypto.decodeRecoveryQrPayload(qrPayload, passphrase)
        return rewrapDecodedRecoveryPayload(decoded)
    }

    /**
     * Persists a pre-decoded recovery payload under the prepared recovery KEK 1 immediately after
     * user authentication, without running PBKDF2 inside the short Keystore authorization window.
     */
    @Synchronized
    fun rewrapDecodedRecoveryPayload(decoded: VaultCrypto.DecodedRecoveryPayload): SecretKey {
        val newAlias = pendingRecoveryKekAlias
            ?: throw IllegalStateException("Recovery KEK was not prepared")
        val keyStore = loadKeyStore()
        val newKek = (keyStore.getEntry(newAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Prepared recovery KEK is missing")
        val previousAlias = activeKekAlias()
        try {
            persistWrappedKeys(
                dekBytes = decoded.dekBytes.copyOf(),
                kek2Bytes = decoded.kek2Bytes,
                kek1 = newKek,
                kek1Alias = newAlias
            )
        } finally {
            decoded.kek2Bytes.fill(0)
        }
        pendingRecoveryKekAlias = null
        if (previousAlias != newAlias) {
            try {
                keyStore.deleteEntry(previousAlias)
            } catch (_: Exception) {
            }
        }
        return VaultCrypto.aesKey(decoded.dekBytes.copyOf()).also {
            decoded.dekBytes.fill(0)
        }
    }

    override fun encryptBackupFileContent(plainBackupJson: String, dek: SecretKey): String =
        VaultCrypto.encryptBackupFileContent(plainBackupJson, dek)

    override fun decryptBackupFileContent(backupFileText: String, dek: SecretKey): String =
        VaultCrypto.decryptBackupFileContent(backupFileText, dek)

    override fun createMigrationQrPayload(plainMigrationJson: String, transferCode: String): String =
        VaultCrypto.createMigrationQrPayload(plainMigrationJson, transferCode)

    override fun decryptMigrationQrPayload(qrPayload: String, transferCode: String): String =
        VaultCrypto.decryptMigrationQrPayload(qrPayload, transferCode)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_KEK_ALIAS = "biometric_pass_kek_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BIT_LEN = 128

        /**
         * Tight TEE / StrongBox HardwareAuthToken validity window (5 seconds).
         *
         * Design rationale: Android's BiometricPrompt forbids combining `CryptoObject` (`timeout = 0`)
         * with `DEVICE_CREDENTIAL` (`IllegalArgumentException: Device credential not supported with crypto`).
         * Using `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL` with a 5-second hardware Keystore window
         * guarantees that phones without fingerprint hardware—or with a broken/locked-out fingerprint
         * sensor—can always fall back to Pattern / PIN / Password via Gatekeeper TEE tokens.
         */
        private const val AUTH_VALIDITY_SECONDS = 5

        private const val PREFS_NAME = "biometric_pass_crypto_prefs"
        private const val KEY_ACTIVE_KEK_ALIAS = "active_kek_alias"
        private const val KEY_WRAPPED_DEK_KEYSTORE = "wrapped_dek_keystore"
        private const val KEY_WRAPPED_DEK_KEYSTORE_IV = "wrapped_dek_keystore_iv"
        private const val KEY_WRAPPED_DEK_RECOVERY = "wrapped_dek_recovery"
        private const val KEY_WRAPPED_DEK_RECOVERY_IV = "wrapped_dek_recovery_iv"
        private const val KEY_ENCRYPTED_KEK2_BY_DEK = "encrypted_kek2_by_dek"
        private const val KEY_ENCRYPTED_KEK2_IV = "encrypted_kek2_iv"
        private const val PENDING_PREFIX = "pending_"
        private val KEY_SET_NAMES = listOf(
            KEY_WRAPPED_DEK_KEYSTORE,
            KEY_WRAPPED_DEK_KEYSTORE_IV,
            KEY_WRAPPED_DEK_RECOVERY,
            KEY_WRAPPED_DEK_RECOVERY_IV,
            KEY_ENCRYPTED_KEK2_BY_DEK,
            KEY_ENCRYPTED_KEK2_IV
        )

        const val RECOVERY_QR_PREFIX = VaultCrypto.RECOVERY_V1_QR_PREFIX
        const val RECOVERY_V2_QR_PREFIX = VaultCrypto.RECOVERY_V2_QR_PREFIX
        const val MIGRATION_QR_PREFIX = VaultCrypto.MIGRATION_QR_PREFIX
    }
}
