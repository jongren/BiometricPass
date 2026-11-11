package com.biometricpass.app.data.repository

import com.biometricpass.app.crypto.VaultCipher
import com.biometricpass.app.crypto.VaultCrypto
import com.biometricpass.app.data.local.CredentialDao
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.util.AppSignatureVerifier
import com.biometricpass.app.util.AppSignatures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey

class CredentialRepositoryTest {

    private class FakeCipher : VaultCipher {
        override fun encryptWithDek(plaintext: String, dek: SecretKey) = VaultCrypto.encryptString(plaintext, dek)
        override fun decryptWithDek(ciphertextBase64: String, ivBase64: String, dek: SecretKey) =
            VaultCrypto.decryptString(ciphertextBase64, ivBase64, dek)
        override fun encryptBackupFileContent(plainBackupJson: String, dek: SecretKey) =
            VaultCrypto.encryptBackupFileContent(plainBackupJson, dek)
        override fun decryptBackupFileContent(backupFileText: String, dek: SecretKey) =
            VaultCrypto.decryptBackupFileContent(backupFileText, dek)
        override fun createMigrationQrPayload(plainMigrationJson: String, transferCode: String) =
            VaultCrypto.createMigrationQrPayload(plainMigrationJson, transferCode)
        override fun decryptMigrationQrPayload(qrPayload: String, transferCode: String) =
            VaultCrypto.decryptMigrationQrPayload(qrPayload, transferCode)
    }

    private class InMemoryDao : CredentialDao {
        val rows = linkedMapOf<Long, CredentialEntity>()
        private var nextId = 1L

        private fun sorted() = rows.values.sortedByDescending { it.updatedAt }
        override fun observeAll(): Flow<List<CredentialEntity>> = flowOf(sorted())
        override suspend fun getAll() = sorted()
        override suspend fun countAll() = rows.size
        override suspend fun findByPackage(packageName: String) =
            sorted().filter { it.packageName == packageName }
        override suspend fun findWithWebDomain() = sorted().filter { it.webDomain.isNotEmpty() }
        override suspend fun getById(id: Long) = rows[id]
        override suspend fun insert(entity: CredentialEntity): Long {
            val id = if (entity.id > 0) entity.id else nextId++
            if (id >= nextId) nextId = id + 1
            rows[id] = entity.copy(id = id)
            return id
        }
        override suspend fun update(entity: CredentialEntity) {
            rows[entity.id] = entity
        }
        override suspend fun deleteById(id: Long) {
            rows.remove(id)
        }
    }

    private val line = "jp.naver.line.android"

    @Test
    fun `second account for the same app does not overwrite the first`() = runTest {
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        val dek = VaultCrypto.newAesKey()

        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = dek)
        val second = repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "bob", plainPassword = "b1", dek = dek)

        assertFalse(second.isUpdate)
        assertEquals(2, repo.countCredentials())
        assertEquals(setOf("alice", "bob"), dao.rows.values.map { it.username }.toSet())
        val alice = dao.rows.values.first { it.username == "alice" }
        assertEquals("a1", repo.decryptPassword(alice, dek))
    }

    @Test
    fun `same account is updated, not duplicated`() = runTest {
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        val dek = VaultCrypto.newAesKey()

        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "old", dek = dek)
        val result = repo.saveOrUpdateCredential(title = "", packageName = line, username = "ALICE", plainPassword = "new", dek = dek)

        assertTrue(result.isUpdate)
        assertEquals(1, dao.rows.size)
        assertEquals("new", repo.decryptPassword(dao.rows.values.single(), dek))
    }

    @Test
    fun `save from a spoofing webview app never overwrites a browser website credential`() = runTest {
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        val dek = VaultCrypto.newAesKey()

        repo.saveOrUpdateCredential(
            title = "Google",
            packageName = "com.android.chrome",
            webDomain = "accounts.google.com",
            username = "alice@gmail.com",
            plainPassword = "RealPassword!1",
            dek = dek
        )

        // An untrusted App embeds a WebView claiming accounts.google.com and the user saves there
        val spoofed = repo.saveOrUpdateCredential(
            title = "Google",
            packageName = "com.untrusted.game",
            webDomain = "accounts.google.com",
            username = "alice@gmail.com",
            plainPassword = "TypedInFakeApp",
            dek = dek
        )
        assertFalse("Must create a separate record, not update the browser one", spoofed.isUpdate)
        assertEquals(2, dao.rows.size)
        val chromeItem = dao.rows.values.first { it.packageName == "com.android.chrome" }
        assertEquals("RealPassword!1", repo.decryptPassword(chromeItem, dek))
        val existingForSpoof =
            repo.findExistingCredential("com.untrusted.game", "accounts.google.com", "alice@gmail.com")
        assertTrue(existingForSpoof?.packageName != "com.android.chrome")

        // A real browser saving the same site + account still updates the browser record
        val browserUpdate = repo.saveOrUpdateCredential(
            title = "",
            packageName = "com.sec.android.app.sbrowser",
            webDomain = "accounts.google.com",
            username = "alice@gmail.com",
            plainPassword = "NewRealPassword!2",
            dek = dek
        )
        assertTrue(browserUpdate.isUpdate)
        assertEquals("NewRealPassword!2", repo.decryptPassword(dao.rows[chromeItem.id]!!, dek))
        // ...and never deletes or rebinds the App-owned record as a "duplicate"
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.values.any { it.packageName == "com.untrusted.game" })
    }

    @Test
    fun `backup round trip keeps every account of the same app`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val sourceDao = InMemoryDao()
        val source = CredentialRepository(sourceDao, FakeCipher())
        source.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = dek)
        source.saveOrUpdateCredential(title = "LINE", packageName = line, username = "bob", plainPassword = "b1", dek = dek)
        source.saveOrUpdateCredential(title = "Bank", packageName = "com.bank", username = "", plainPassword = "pin", dek = dek)
        val backup = source.exportEncryptedBackup(dek)

        val newDek = VaultCrypto.newAesKey()
        val targetDao = InMemoryDao()
        val target = CredentialRepository(targetDao, FakeCipher())
        val result = target.importEncryptedBackup(backup, decryptKey = dek, storeDek = newDek)

        assertEquals(3, result.added)
        assertEquals(3, targetDao.rows.size)
        val bob = targetDao.rows.values.first { it.username == "bob" }
        assertEquals("b1", target.decryptPassword(bob, newDek))
    }

    @Test
    fun `importing the same backup twice adds nothing and keeps newer local data`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = dek)
        val backup = repo.exportEncryptedBackup(dek)

        Thread.sleep(5)
        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a2-newer", dek = dek)
        val result = repo.importEncryptedBackup(backup, decryptKey = dek)

        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertEquals("a2-newer", repo.decryptPassword(dao.rows.values.single(), dek))
    }

    @Test
    fun `migration qr round trip with transfer code`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val source = CredentialRepository(InMemoryDao(), FakeCipher())
        source.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = dek)
        source.saveOrUpdateCredential(title = "LINE", packageName = line, username = "bob", plainPassword = "b1", dek = dek)
        val code = "ABCDEFGH"
        val qr = source.exportMigrationQrPayload(dek, code)

        val targetDao = InMemoryDao()
        val target = CredentialRepository(targetDao, FakeCipher())
        val newDek = VaultCrypto.newAesKey()
        val result = target.importMigrationQrPayload(qr, "abcd-efgh", newDek)

        assertEquals(2, result.added)
        assertEquals(setOf("alice", "bob"), targetDao.rows.values.map { it.username }.toSet())
    }

    @Test
    fun `webview domain spoofing protection blocks non-browser apps from receiving website credentials`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())

        // Credential saved in Chrome for accounts.google.com
        repo.saveOrUpdateCredential(
            title = "Google",
            packageName = "com.android.chrome",
            webDomain = "accounts.google.com",
            username = "alice@gmail.com",
            plainPassword = "StrongPassword!123",
            dek = dek
        )
        // Credential saved specifically for a native banking app
        repo.saveOrUpdateCredential(
            title = "MyBank",
            packageName = "com.mybank.app",
            webDomain = "",
            username = "bankuser",
            plainPassword = "BankPassword!456",
            dek = dek
        )

        // 1. Untrusted non-browser app embedding a WebView claiming accounts.google.com -> MUST NOT match
        val spoofMatches = repo.findMatchingForAutofill(
            packageName = "com.untrusted.game",
            webDomain = "accounts.google.com"
        )
        assertTrue("Non-browser WebView must not auto-match website credentials", spoofMatches.isEmpty())

        // 2. Legitimate browser (Chrome) visiting accounts.google.com -> MUST match
        val chromeMatches = repo.findMatchingForAutofill(
            packageName = "com.android.chrome",
            webDomain = "accounts.google.com"
        )
        assertEquals(1, chromeMatches.size)
        assertEquals("alice@gmail.com", chromeMatches.single().username)

        // 3. Banking app using its own in-app login WebView -> matches its own packageName credential
        val bankMatches = repo.findMatchingForAutofill(
            packageName = "com.mybank.app",
            webDomain = "sso.mybank.com"
        )
        assertEquals(1, bankMatches.size)
        assertEquals("bankuser", bankMatches.single().username)
    }

    @Test
    fun `csv import merges credentials without overwriting other accounts`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())

        val csv = """
            name,url,username,password
            GitHub,https://github.com/login,alice,"Pass,123!"
            GitHub,https://github.com/login,bob,"Pass""456!"
            LINE,android://hash@jp.naver.line.android/,line_user,LineSecret99!
        """.trimIndent()

        val result = repo.importCsvCredentials(csv, dek)
        assertEquals(3, result.added)
        assertEquals(3, dao.rows.size)
        val lineItem = dao.rows.values.first { it.packageName == line }
        assertEquals("LineSecret99!", repo.decryptPassword(lineItem, dek))
    }

    @Test
    fun `key compatibility check`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        assertTrue("empty vault accepts any key", repo.isKeyCompatibleWithVault(VaultCrypto.newAesKey()))
        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = dek)
        assertTrue(repo.isKeyCompatibleWithVault(dek))
        assertFalse(repo.isKeyCompatibleWithVault(VaultCrypto.newAesKey()))
    }

    @Test
    fun `re-encrypting the vault moves every password to the new key`() = runTest {
        val oldDek = VaultCrypto.newAesKey()
        val newDek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = oldDek)
        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "bob", plainPassword = "b1", dek = oldDek)

        assertEquals(2, repo.reencryptAllCredentials(oldDek, newDek))

        assertTrue(repo.isKeyCompatibleWithVault(newDek))
        assertFalse(repo.isKeyCompatibleWithVault(oldDek))
        assertEquals(
            setOf("a1", "b1"),
            dao.rows.values.map { repo.decryptPassword(it, newDek) }.toSet()
        )
    }

    @Test
    fun `re-encrypting with the wrong current key fails without changing data`() = runTest {
        val realDek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val repo = CredentialRepository(dao, FakeCipher())
        repo.saveOrUpdateCredential(title = "LINE", packageName = line, username = "alice", plainPassword = "a1", dek = realDek)
        val before = dao.rows.values.single()

        try {
            repo.reencryptAllCredentials(VaultCrypto.newAesKey(), VaultCrypto.newAesKey())
            org.junit.Assert.fail("Expected re-encryption with a wrong key to fail")
        } catch (_: Exception) {
            // expected
        }
        assertEquals(before, dao.rows.values.single())
        assertEquals("a1", repo.decryptPassword(dao.rows.values.single(), realDek))
    }

    @Test
    fun `browser autofill matches sub-domains but never crosses public suffixes`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val repo = CredentialRepository(
            InMemoryDao(),
            FakeCipher(),
            publicSuffixList = { com.biometricpass.app.util.PublicSuffixListTest.bundled }
        )
        val chrome = "com.android.chrome"
        repo.saveOrUpdateCredential(title = "Google", packageName = chrome, webDomain = "google.com", username = "alice", plainPassword = "g1", dek = dek)
        repo.saveOrUpdateCredential(title = "Pages", packageName = chrome, webDomain = "alice.github.io", username = "alice", plainPassword = "p1", dek = dek)

        assertEquals(
            listOf("google.com"),
            repo.findMatchingForAutofill(chrome, "accounts.google.com").map { it.webDomain }
        )
        assertTrue(repo.findMatchingForAutofill(chrome, "attacker.github.io").isEmpty())
        assertTrue(repo.findMatchingForAutofill(chrome, "github.io").isEmpty())
        assertEquals(1, repo.findMatchingForAutofill(chrome, "ALICE.github.io").size)
    }

    @Test
    fun `app signature pinning blocks sideloaded counterfeit apk and prevents password overwrite`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val dao = InMemoryDao()
        val realBankCert = AppSignatureVerifier.sha256Hex("official-bank-release-key".toByteArray())
        val rotatedBankCert = AppSignatureVerifier.sha256Hex("official-bank-v2-rotated-key".toByteArray())
        val fakeApkCert = AppSignatureVerifier.sha256Hex("attacker-sideloaded-key".toByteArray())

        var installedBankSignatures: AppSignatures? = AppSignatures.ofSingle(realBankCert)
        val repo = CredentialRepository(
            credentialDao = dao,
            cryptoManager = FakeCipher(),
            appSignatureProvider = { pkg ->
                if (pkg == "com.bank.mobile") installedBankSignatures else null
            }
        )

        // 1. User saves credential while the official Bank app is installed
        repo.saveOrUpdateCredential(
            title = "MyBank",
            packageName = "com.bank.mobile",
            username = "alice",
            plainPassword = "OfficialSecret!1",
            dek = dek
        )
        val saved = dao.rows.values.single()
        assertEquals(realBankCert, saved.appSignatureSha256)
        assertEquals(1, repo.findMatchingForAutofill("com.bank.mobile", "").size)
        assertFalse(repo.hasSignatureBlockedCredentials("com.bank.mobile"))

        // 2. Official developer rotates signing key via APK Signature Scheme v3 (old cert in history) -> still trusted!
        installedBankSignatures = AppSignatures(
            primarySha256 = rotatedBankCert,
            trustedSha256Set = setOf(rotatedBankCert, realBankCert)
        )
        assertEquals(1, repo.findMatchingForAutofill("com.bank.mobile", "").size)
        assertFalse(repo.hasSignatureBlockedCredentials("com.bank.mobile"))

        // 3. User uninstalls official app and sideloads a counterfeit APK with the SAME packageName!
        installedBankSignatures = AppSignatures.ofSingle(fakeApkCert)
        assertTrue(
            "Counterfeit APK must NOT receive automatic Autofill suggestions",
            repo.findMatchingForAutofill("com.bank.mobile", "").isEmpty()
        )
        assertTrue(
            "Service should detect blocked credentials so it can offer manual picker with warning",
            repo.hasSignatureBlockedCredentials("com.bank.mobile")
        )

        // 4. Saving inside the counterfeit APK must NOT overwrite the real bank password
        val fakeSave = repo.saveOrUpdateCredential(
            title = "MyBank",
            packageName = "com.bank.mobile",
            username = "alice",
            plainPassword = "TypedInFakeBankApp",
            dek = dek
        )
        assertFalse("Counterfeit APK save must not overwrite authentic record", fakeSave.isUpdate)
        assertEquals("OfficialSecret!1", repo.decryptPassword(dao.rows[saved.id]!!, dek))
    }

    @Test
    fun `backup export and import preserves pinned app signature across devices`() = runTest {
        val dek = VaultCrypto.newAesKey()
        val realCert = AppSignatureVerifier.sha256Hex("line-official-cert".toByteArray())
        val fakeCert = AppSignatureVerifier.sha256Hex("fake-repackaged-line".toByteArray())

        val sourceRepo = CredentialRepository(
            credentialDao = InMemoryDao(),
            cryptoManager = FakeCipher(),
            appSignatureProvider = { AppSignatures.ofSingle(realCert) }
        )
        sourceRepo.saveOrUpdateCredential(
            title = "LINE",
            packageName = line,
            username = "alice",
            plainPassword = "LinePassword!9",
            dek = dek
        )
        val backup = sourceRepo.exportEncryptedBackup(dek)

        // Restore onto a new phone that currently has a repackaged fake LINE APK installed
        val targetDao = InMemoryDao()
        val targetRepo = CredentialRepository(
            credentialDao = targetDao,
            cryptoManager = FakeCipher(),
            appSignatureProvider = { AppSignatures.ofSingle(fakeCert) }
        )
        val imported = targetRepo.importEncryptedBackup(backup, decryptKey = dek)
        assertEquals(1, imported.added)
        assertEquals(realCert, targetDao.rows.values.single().appSignatureSha256)
        assertTrue(targetRepo.findMatchingForAutofill(line, "").isEmpty())
        assertTrue(targetRepo.hasSignatureBlockedCredentials(line))
    }
}
