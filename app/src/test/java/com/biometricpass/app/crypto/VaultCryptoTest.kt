package com.biometricpass.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class VaultCryptoTest {

    @Test
    fun `aes-gcm round trip with unicode`() {
        val key = VaultCrypto.newAesKey()
        val payload = VaultCrypto.encryptString("密碼 P@ss🔐", key)
        assertEquals("密碼 P@ss🔐", VaultCrypto.decryptString(payload.ciphertextBase64, payload.ivBase64, key))
    }

    @Test
    fun `every encryption uses a fresh iv`() {
        val key = VaultCrypto.newAesKey()
        val a = VaultCrypto.encryptString("same", key)
        val b = VaultCrypto.encryptString("same", key)
        assertNotEquals(a.ivBase64, b.ivBase64)
        assertNotEquals(a.ciphertextBase64, b.ciphertextBase64)
    }

    @Test(expected = AEADBadTagException::class)
    fun `decrypting with the wrong key fails`() {
        val payload = VaultCrypto.encryptString("secret", VaultCrypto.newAesKey())
        VaultCrypto.decryptString(payload.ciphertextBase64, payload.ivBase64, VaultCrypto.newAesKey())
    }

    @Test
    fun `backup file round trip`() {
        val key = VaultCrypto.newAesKey()
        val json = """{"version":1,"items":[{"username":"alice","password":"p1"}]}"""
        val file = VaultCrypto.encryptBackupFileContent(json, key)
        assertTrue(VaultCrypto.isBackupFileFormat(file))
        assertFalse(file.contains("alice"))
        assertEquals(json, VaultCrypto.decryptBackupFileContent(file, key))
    }

    @Test
    fun `non backup text is rejected`() {
        assertFalse(VaultCrypto.isBackupFileFormat("hello"))
        assertFalse(VaultCrypto.isBackupFileFormat("""{"ciphertext":"x"}"""))
    }

    @Test
    fun `recovery qr v1 backward compatible round trip`() {
        val dek = VaultCrypto.newAesKey()
        val kek2Bytes = VaultCrypto.randomBytes(VaultCrypto.AES_KEY_BYTE_LEN)
        val wrapped = VaultCrypto.encryptBytes(dek.encoded, VaultCrypto.aesKey(kek2Bytes))

        val qr = VaultCrypto.createRecoveryQrPayload(
            kek2Bytes = kek2Bytes,
            wrappedDekCiphertextBase64 = wrapped.ciphertextBase64,
            wrappedDekIvBase64 = wrapped.ivBase64,
            passphrase = null
        )
        assertTrue(qr.startsWith(VaultCrypto.RECOVERY_V1_QR_PREFIX))
        assertTrue(VaultCrypto.isRecoveryQrPayload(qr))
        assertFalse(VaultCrypto.isPassphraseProtectedRecoveryQr(qr))

        val decoded = VaultCrypto.decodeRecoveryQrPayload(qr, passphrase = null)
        assertArrayEquals(dek.encoded, decoded.dekBytes)
    }

    @Test
    fun `recovery qr v2 passphrase protection round trip and wrong passphrase rejection`() {
        val dek = VaultCrypto.newAesKey()
        val kek2Bytes = VaultCrypto.randomBytes(VaultCrypto.AES_KEY_BYTE_LEN)
        val wrapped = VaultCrypto.encryptBytes(dek.encoded, VaultCrypto.aesKey(kek2Bytes))

        val qr = VaultCrypto.createRecoveryQrPayload(
            kek2Bytes = kek2Bytes,
            wrappedDekCiphertextBase64 = wrapped.ciphertextBase64,
            wrappedDekIvBase64 = wrapped.ivBase64,
            passphrase = "我的備份密語2026"
        )
        assertTrue(qr.startsWith(VaultCrypto.RECOVERY_V2_QR_PREFIX))
        assertTrue(VaultCrypto.isRecoveryQrPayload(qr))
        assertTrue(VaultCrypto.isPassphraseProtectedRecoveryQr(qr))

        val rawJson = String(VaultCrypto.decodeBase64(qr.removePrefix(VaultCrypto.RECOVERY_V2_QR_PREFIX)))
        assertFalse("V2 QR must not embed plain kek2", rawJson.contains("\"kek2\""))

        // Missing passphrase must fail
        try {
            VaultCrypto.decodeRecoveryQrPayload(qr, passphrase = null)
            fail("Expected WrongRecoveryPassphraseException for missing passphrase")
        } catch (_: VaultCrypto.WrongRecoveryPassphraseException) {
            // expected
        }

        // Wrong passphrase must fail
        try {
            VaultCrypto.decodeRecoveryQrPayload(qr, passphrase = "錯誤的密語")
            fail("Expected WrongRecoveryPassphraseException for wrong passphrase")
        } catch (_: VaultCrypto.WrongRecoveryPassphraseException) {
            // expected
        }

        // Correct passphrase unlocks DEK
        val decoded = VaultCrypto.decodeRecoveryQrPayload(qr, passphrase = "  我的備份密語2026  ")
        assertArrayEquals(dek.encoded, decoded.dekBytes)
    }

    @Test
    fun `migration qr does not contain the key and needs the transfer code`() {
        val code = TransferCode.generate()
        val json = """{"items":[{"username":"alice","password":"TopSecret123"}]}"""
        val qr = VaultCrypto.createMigrationQrPayload(json, code)

        assertTrue(qr.startsWith(VaultCrypto.MIGRATION_QR_PREFIX))
        val decodedWrapper = String(VaultCrypto.decodeBase64(qr.removePrefix(VaultCrypto.MIGRATION_QR_PREFIX)))
        assertFalse("QR must not embed a key", decodedWrapper.contains("\"k\""))

        assertEquals(json, VaultCrypto.decryptMigrationQrPayload(qr, TransferCode.format(code).lowercase()))
    }

    @Test
    fun `wrong transfer code is reported clearly`() {
        val qr = VaultCrypto.createMigrationQrPayload("{}", "ABCDEFGH")
        try {
            VaultCrypto.decryptMigrationQrPayload(qr, "ABCDEFGJ")
            fail("Expected WrongTransferCodeException")
        } catch (_: VaultCrypto.WrongTransferCodeException) {
            // expected
        }
    }

    @Test
    fun `transfer code format and normalization`() {
        val code = TransferCode.generate()
        assertEquals(TransferCode.LENGTH, code.length)
        assertTrue(code.none { it in "01OIL" })
        assertEquals("ABCD-EFGH", TransferCode.format("abcdefgh"))
        assertEquals("ABCDEFGH", TransferCode.normalize(" abcd-efgh "))
        assertTrue(TransferCode.isComplete("abcd-efgh"))
        assertFalse(TransferCode.isComplete("abcd"))
    }

    @Test
    fun `legacy version 1 backup files without header binding remain readable`() {
        val key = VaultCrypto.newAesKey()
        val json = """{"version":1,"items":[]}"""
        val encrypted = VaultCrypto.encryptBytes(VaultCrypto.gzipCompress(json.toByteArray()), key)
        val legacyFile = org.json.JSONObject().apply {
            put("magic", "BIOMETRICPASS_BACKUP_V1")
            put("version", 1)
            put("iv", encrypted.ivBase64)
            put("ciphertext", encrypted.ciphertextBase64)
        }.toString()
        assertEquals(json, VaultCrypto.decryptBackupFileContent(legacyFile, key))
    }

    @Test
    fun `downgrading the backup header version breaks authentication`() {
        val key = VaultCrypto.newAesKey()
        val file = VaultCrypto.encryptBackupFileContent("""{"items":[]}""", key)
        val tampered = org.json.JSONObject(file).apply { put("version", 1) }.toString()
        try {
            VaultCrypto.decryptBackupFileContent(tampered, key)
            fail("Expected header tampering to be detected")
        } catch (_: AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `new payloads store the pbkdf2 work factor and reject absurd values`() {
        val qr = VaultCrypto.createMigrationQrPayload("{}", "ABCDEFGH")
        val wrapper = org.json.JSONObject(
            String(VaultCrypto.decodeBase64(qr.removePrefix(VaultCrypto.MIGRATION_QR_PREFIX)))
        )
        assertEquals(VaultCrypto.PBKDF2_ITERATIONS, wrapper.getInt("i"))

        wrapper.put("i", 50_000_000)
        val crafted = VaultCrypto.MIGRATION_QR_PREFIX +
            VaultCrypto.encodeBase64(wrapper.toString().toByteArray())
        try {
            VaultCrypto.decryptMigrationQrPayload(crafted, "ABCDEFGH")
            fail("Expected an absurd iteration count to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
