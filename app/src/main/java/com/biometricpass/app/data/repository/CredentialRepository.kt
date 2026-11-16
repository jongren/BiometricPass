package com.biometricpass.app.data.repository

import com.biometricpass.app.autofill.AssistStructureParser
import com.biometricpass.app.crypto.VaultCipher
import com.biometricpass.app.data.local.CredentialDao
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.data.model.ExtraField
import com.biometricpass.app.data.model.ExtraFieldsCodec
import com.biometricpass.app.util.AppSignatureVerifier
import com.biometricpass.app.util.AppSignatures
import com.biometricpass.app.util.CsvPasswordParser
import com.biometricpass.app.util.MaskedIdentifierHelper
import com.biometricpass.app.util.PasswordHealthAnalyzer
import com.biometricpass.app.util.PublicSuffixList
import com.biometricpass.app.util.WebDomainMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey

/**
 * Repository coordinating Room database operations and DEK envelope encryption/decryption.
 */
class CredentialRepository(
    private val credentialDao: CredentialDao,
    private val cryptoManager: VaultCipher,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    private val publicSuffixList: () -> PublicSuffixList = { PublicSuffixList.DEFAULT_RULE_ONLY },
    private val appSignatureProvider: (String) -> AppSignatures? = { null }
) {

    fun observeAllCredentials(): Flow<List<CredentialEntity>> =
        credentialDao.observeAll()

    suspend fun countCredentials(): Int =
        withContext(Dispatchers.IO) { credentialDao.countAll() }

    /**
     * Candidates bound to [packageName] and/or matching [webDomain] (equal, or a parent / sub-domain
     * within the same registrable domain per the Public Suffix List), most recently updated first.
     */
    private suspend fun findCandidates(packageName: String, webDomain: String): List<CredentialEntity> {
        val byPackage = if (packageName.isNotBlank()) credentialDao.findByPackage(packageName) else emptyList()
        val byDomain = if (webDomain.isNotBlank()) {
            val psl = publicSuffixList()
            credentialDao.findWithWebDomain().filter { WebDomainMatcher.matches(it.webDomain, webDomain, psl) }
        } else {
            emptyList()
        }
        return (byPackage + byDomain).distinctBy { it.id }.sortedByDescending { it.updatedAt }
    }

    suspend fun findMatchingForAutofill(
        packageName: String,
        webDomain: String
    ): List<CredentialEntity> = withContext(Dispatchers.IO) {
        val cleanPkg = packageName.trim()
        val cleanDomain = webDomain.trim()
        val isBrowser = AssistStructureParser.isBrowserPackage(cleanPkg)

        // Official Android Autofill Browser & WebView Spoofing Isolation (P2-7 / M4):
        // 1. Inside a recognized browser: match strictly by webDomain (if no webDomain, e.g. New Tab, return empty).
        // 2. Inside a non-browser App (including an embedded WebView claiming an arbitrary webDomain):
        //    match strictly by the App's own packageName so an untrusted App embedding a WebView
        //    can NEVER automatically receive credentials saved for websites in Chrome!
        val queryPkg = if (isBrowser) "" else cleanPkg
        val queryDomain = if (isBrowser) cleanDomain else ""

        if (queryPkg.isBlank() && queryDomain.isBlank()) {
            emptyList()
        } else {
            val liveSignatures = if (queryPkg.isNotBlank()) appSignatureProvider(queryPkg) else null
            val rawList = findCandidates(queryPkg, queryDomain).filter { cred ->
                queryPkg.isBlank() || AppSignatureVerifier.matchesPinnedSignature(cred.appSignatureSha256, liveSignatures)
            }
            // Keep distinct accounts AND distinct blank-username secondary secrets (e.g. "三星錢包 - PIN 碼").
            // Only drop an untitled/duplicate blank-username leftover when an account with the exact same
            // normalized title and a non-blank username already exists.
            val titledAccounts = rawList
                .filter { it.username.isNotBlank() }
                .map { it.title.trim().lowercase() }
                .toSet()
            rawList
                .filter { cred ->
                    cred.username.isNotBlank() ||
                        CredentialMatchRules.isSecondarySecretTitle(cred.title) ||
                        cred.hasExtraFields ||
                        cred.title.trim().lowercase() !in titledAccounts
                }
                .distinctBy {
                    CredentialMatchRules.importKey(it.packageName, it.webDomain, it.username, it.title)
                }
        }
    }

    /**
     * Returns `true` when [packageName] is a non-browser App that has saved credentials in the vault,
     * at least one of which was blocked from automatic Autofill because the installed App's signing
     * certificate does not match the pinned `appSignatureSha256`.
     */
    suspend fun hasSignatureBlockedCredentials(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            val cleanPkg = packageName.trim()
            if (cleanPkg.isEmpty() || AssistStructureParser.isBrowserPackage(cleanPkg)) {
                return@withContext false
            }
            val liveSignatures = appSignatureProvider(cleanPkg) ?: return@withContext false
            credentialDao.findByPackage(cleanPkg).any { cred ->
                AppSignatureVerifier.hasSignatureMismatch(cred.appSignatureSha256, liveSignatures)
            }
        }

    suspend fun getCredentialById(id: Long): CredentialEntity? =
        withContext(Dispatchers.IO) {
            credentialDao.getById(id)
        }

    /**
     * Returns all credentials bound to the same App (or the same website when saving from a browser).
     *
     * Applies the same WebView spoofing isolation as [findMatchingForAutofill]: a non-browser App that
     * embeds a WebView claiming an arbitrary webDomain is matched ONLY by its own packageName, so a
     * save from that App can never update (overwrite) a website credential saved from a real browser.
     * Website-only records (blank packageName, e.g. typed manually in the vault) match by webDomain.
     */
    private suspend fun findSiteMatches(
        packageName: String,
        webDomain: String
    ): List<CredentialEntity> {
        val cleanPkg = packageName.trim()
        val cleanDomain = webDomain.trim()
        val matchByDomain = cleanPkg.isBlank() || AssistStructureParser.isBrowserPackage(cleanPkg)
        val queryPkg = if (matchByDomain) "" else cleanPkg
        val queryDomain = if (matchByDomain) cleanDomain else ""
        if (queryPkg.isBlank() && queryDomain.isBlank()) return emptyList()
        val matches = findCandidates(queryPkg, queryDomain)
        // In website mode, never touch records owned by a native App (which may also carry a webDomain
        // captured from its in-app WebView): those belong to that App and are matched by packageName only.
        return if (matchByDomain) {
            matches.filter { it.packageName.isBlank() || AssistStructureParser.isBrowserPackage(it.packageName) }
        } else {
            val liveSignatures = appSignatureProvider(queryPkg)
            matches.filter { cred ->
                AppSignatureVerifier.matchesPinnedSignature(cred.appSignatureSha256, liveSignatures)
            }
        }
    }

    /**
     * Public IO accessor returning trusted existing credentials for the same App or website.
     * Used during Autofill Save to resolve masked usernames (e.g. `A123***789`) and to offer
     * appending a newly captured PIN / User ID into an existing credential's encrypted extra fields.
     */
    suspend fun findSiteCredentialsForSave(
        packageName: String,
        webDomain: String
    ): List<CredentialEntity> = withContext(Dispatchers.IO) {
        findSiteMatches(packageName, webDomain)
    }

    /**
     * Finds the credential that saving [username] for (packageName, webDomain) would update.
     * Returns `null` if a NEW record will be created.
     */
    suspend fun findExistingCredential(
        packageName: String,
        webDomain: String,
        username: String,
        proposedTitle: String = ""
    ): CredentialEntity? = withContext(Dispatchers.IO) {
        CredentialMatchRules.pickExistingForSave(
            siteMatches = findSiteMatches(packageName, webDomain),
            username = username,
            proposedTitle = proposedTitle
        )
    }

    data class SaveOrUpdateResult(
        val id: Long,
        val isUpdate: Boolean
    )

    /**
     * Encrypts [plainPassword] (and optional [extraFields]) with [dek] and inserts or updates the
     * credential in Room.
     * - [forceCreateNew] == true: always inserts a brand-new record without touching existing accounts.
     * - [id] > 0: explicitly edits that record (from the vault editor).
     * - [id] == 0: updates the same account if it unambiguously matches, otherwise inserts a new record.
     * - [extraFields] == null: preserves any existing encrypted extra fields on update.
     */
    suspend fun saveOrUpdateCredential(
        id: Long = 0L,
        title: String,
        packageName: String,
        webDomain: String = "",
        username: String,
        plainPassword: String,
        dek: SecretKey,
        defaultUntitledTitle: String = "Untitled",
        forceCreateNew: Boolean = false,
        extraFields: List<ExtraField>? = null
    ): SaveOrUpdateResult = withContext(Dispatchers.IO) {
        val encrypted = cryptoManager.encryptWithDek(plainPassword, dek)
        val cleanPkg = packageName.trim()
        val cleanDomain = webDomain.trim()
        val cleanUser = username.trim()
        val now = System.currentTimeMillis()

        val existing: CredentialEntity? = when {
            forceCreateNew -> null
            id > 0L -> credentialDao.getById(id)
            else -> CredentialMatchRules.pickExistingForSave(
                siteMatches = findSiteMatches(cleanPkg, cleanDomain),
                username = cleanUser,
                proposedTitle = title
            )
        }

        val finalTitle = title.trim().ifBlank {
            existing?.title?.ifBlank { null }
                ?: cleanDomain.ifBlank { cleanPkg.ifBlank { defaultUntitledTitle } }
        }

        // Never overwrite a clean unmasked username in the vault with a masked version (e.g. "A123***789").
        val finalUsername = when {
            cleanUser.isBlank() -> existing?.username.orEmpty()
            existing != null &&
                existing.username.isNotBlank() &&
                !MaskedIdentifierHelper.isMaskedIdentifier(existing.username) &&
                MaskedIdentifierHelper.isMaskedIdentifier(cleanUser) &&
                MaskedIdentifierHelper.matchesUnmasked(cleanUser, existing.username) -> existing.username
            else -> cleanUser
        }

        val finalPkg = cleanPkg.ifBlank { existing?.packageName.orEmpty() }
        val finalAppSig = if (finalPkg.isNotBlank() && !AssistStructureParser.isBrowserPackage(finalPkg)) {
            appSignatureProvider(finalPkg)?.primarySha256?.ifBlank { null }
                ?: existing?.takeIf { it.packageName == finalPkg }?.appSignatureSha256.orEmpty()
        } else {
            ""
        }

        val (encExtra, encExtraIv) = encryptExtraFieldsOrPreserve(
            extraFields = extraFields,
            existing = existing,
            dek = dek
        )

        val entity = CredentialEntity(
            id = existing?.id ?: 0L,
            title = finalTitle,
            packageName = finalPkg,
            webDomain = cleanDomain.ifBlank { existing?.webDomain.orEmpty() },
            username = finalUsername,
            encryptedPassword = encrypted.ciphertextBase64,
            iv = encrypted.ivBase64,
            updatedAt = now,
            appSignatureSha256 = finalAppSig,
            encryptedExtraFields = encExtra,
            extraFieldsIv = encExtraIv
        )

        var savedId = 0L
        runInTransaction {
            savedId = if (existing != null) {
                credentialDao.update(entity)
                entity.id
            } else {
                credentialDao.insert(entity)
            }
            if (!forceCreateNew) {
                // Only remove TRUE duplicates (same site + same non-blank unmasked username).
                val siteMatches = findSiteMatches(entity.packageName, entity.webDomain)
                CredentialMatchRules.duplicateIdsToRemove(siteMatches, entity.copy(id = savedId))
                    .forEach { credentialDao.deleteById(it) }
            }
        }

        SaveOrUpdateResult(id = savedId, isUpdate = existing != null)
    }

    /**
     * Appends or updates an encrypted [ExtraField] (such as a newly set Wallet PIN or Bank User ID)
     * inside an existing credential WITHOUT modifying its `username` or primary `encryptedPassword`.
     */
    suspend fun appendExtraFieldToCredential(
        credentialId: Long,
        extraField: ExtraField,
        dek: SecretKey
    ): CredentialEntity? = withContext(Dispatchers.IO) {
        var updatedEntity: CredentialEntity? = null
        runInTransaction {
            val existing = credentialDao.getById(credentialId) ?: return@runInTransaction
            val currentExtras = decryptExtraFields(existing, dek)
            val merged = ExtraFieldsCodec.mergeFields(currentExtras, listOf(extraField))
            val extraJson = ExtraFieldsCodec.encodeToJson(merged)
            val (encExtra, encIv) = if (extraJson.isNotEmpty()) {
                val enc = cryptoManager.encryptWithDek(extraJson, dek)
                enc.ciphertextBase64 to enc.ivBase64
            } else {
                "" to ""
            }
            val next = existing.copy(
                encryptedExtraFields = encExtra,
                extraFieldsIv = encIv,
                updatedAt = System.currentTimeMillis()
            )
            credentialDao.update(next)
            updatedEntity = next
        }
        updatedEntity
    }

    private fun encryptExtraFieldsOrPreserve(
        extraFields: List<ExtraField>?,
        existing: CredentialEntity?,
        dek: SecretKey
    ): Pair<String, String> {
        if (extraFields == null) {
            return (existing?.encryptedExtraFields.orEmpty()) to (existing?.extraFieldsIv.orEmpty())
        }
        val extraJson = ExtraFieldsCodec.encodeToJson(extraFields)
        if (extraJson.isEmpty()) {
            return "" to ""
        }
        val enc = cryptoManager.encryptWithDek(extraJson, dek)
        return enc.ciphertextBase64 to enc.ivBase64
    }

    /**
     * Decrypts a stored [CredentialEntity]'s password using the unlocked [dek].
     */
    fun decryptPassword(entity: CredentialEntity, dek: SecretKey): String {
        return cryptoManager.decryptWithDek(
            ciphertextBase64 = entity.encryptedPassword,
            ivBase64 = entity.iv,
            dek = dek
        )
    }

    /**
     * Decrypts a stored [CredentialEntity]'s optional [ExtraField] list using the unlocked [dek].
     */
    fun decryptExtraFields(entity: CredentialEntity, dek: SecretKey): List<ExtraField> {
        if (!entity.hasExtraFields) return emptyList()
        val json = cryptoManager.decryptWithDek(
            ciphertextBase64 = entity.encryptedExtraFields,
            ivBase64 = entity.extraFieldsIv,
            dek = dek
        )
        return ExtraFieldsCodec.decodeFromJson(json)
    }

    /**
     * Returns `true` when the vault is empty, or when at least one stored credential can be decrypted
     * with [candidateDek]. Used to make sure a scanned backup unlock key really belongs to this vault
     * before it replaces the local hardware-wrapped key.
     */
    suspend fun isKeyCompatibleWithVault(candidateDek: SecretKey): Boolean =
        withContext(Dispatchers.IO) {
            val all = credentialDao.getAll()
            if (all.isEmpty()) return@withContext true
            all.any { item ->
                try {
                    decryptPassword(item, candidateDek)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

    suspend fun deleteCredential(id: Long) = withContext(Dispatchers.IO) {
        credentialDao.deleteById(id)
    }

    /**
     * Re-encrypts every stored password AND encrypted extra-fields payload from [oldDek] to [newDek]
     * in ONE database transaction (all-or-nothing). Throws (and rolls back) if any record cannot be
     * decrypted with [oldDek], so a rotation can never leave the vault half old / half new.
     */
    suspend fun reencryptAllCredentials(oldDek: SecretKey, newDek: SecretKey): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            runInTransaction {
                for (item in credentialDao.getAll()) {
                    val plain = decryptPassword(item, oldDek)
                    val encrypted = cryptoManager.encryptWithDek(plain, newDek)
                    val (newExtraCipher, newExtraIv) = if (item.hasExtraFields) {
                        val plainExtraJson = cryptoManager.decryptWithDek(
                            ciphertextBase64 = item.encryptedExtraFields,
                            ivBase64 = item.extraFieldsIv,
                            dek = oldDek
                        )
                        val reencExtra = cryptoManager.encryptWithDek(plainExtraJson, newDek)
                        reencExtra.ciphertextBase64 to reencExtra.ivBase64
                    } else {
                        "" to ""
                    }
                    credentialDao.update(
                        item.copy(
                            encryptedPassword = encrypted.ciphertextBase64,
                            iv = encrypted.ivBase64,
                            encryptedExtraFields = newExtraCipher,
                            extraFieldsIv = newExtraIv
                        )
                    )
                    count++
                }
            }
            count
        }

    /**
     * Re-inserts a previously deleted credential (used by the "復原" Snackbar action).
     */
    suspend fun restoreDeletedCredential(entity: CredentialEntity) = withContext(Dispatchers.IO) {
        credentialDao.insert(entity)
    }

    /**
     * Serializes and encrypts all credentials into an AES-256-GCM encrypted backup file string
     * suitable for Storage Access Framework (SAF) export.
     */
    suspend fun exportEncryptedBackup(dek: SecretKey): String = withContext(Dispatchers.IO) {
        val plainJson = exportPlainCredentialsJson(dek)
        cryptoManager.encryptBackupFileContent(plainJson, dek)
    }

    data class ImportResult(
        val added: Int,
        val updated: Int,
        val skipped: Int
    ) {
        val total: Int get() = added + updated + skipped
    }

    /**
     * Decrypts a SAF backup file string with [decryptKey] (the key of the device that created it)
     * and merges all credentials into Room, re-encrypting each password with this device's [storeDek].
     */
    suspend fun importEncryptedBackup(
        encryptedBackupText: String,
        decryptKey: SecretKey,
        storeDek: SecretKey = decryptKey,
        defaultUntitledTitle: String = "Untitled"
    ): ImportResult = withContext(Dispatchers.IO) {
        val plainJson = cryptoManager.decryptBackupFileContent(encryptedBackupText, decryptKey)
        importPlainCredentialsJson(plainJson, storeDek, defaultUntitledTitle)
    }

    /**
     * Creates a self-contained encrypted QR Code payload for Device-to-Device migration,
     * protected by the one-time [transferCode] shown separately on the old phone.
     */
    suspend fun exportMigrationQrPayload(dek: SecretKey, transferCode: String): String =
        withContext(Dispatchers.IO) {
            val plainJson = exportPlainCredentialsJson(dek)
            cryptoManager.createMigrationQrPayload(plainJson, transferCode)
        }

    /**
     * Decrypts a scanned Device-to-Device Migration QR Code payload with the [transferCode]
     * and imports all credentials encrypted under this device's [dek].
     */
    suspend fun importMigrationQrPayload(
        qrPayload: String,
        transferCode: String,
        dek: SecretKey,
        defaultUntitledTitle: String = "Untitled"
    ): ImportResult = withContext(Dispatchers.IO) {
        val plainJson = cryptoManager.decryptMigrationQrPayload(qrPayload, transferCode)
        importPlainCredentialsJson(plainJson, dek, defaultUntitledTitle)
    }

    /**
     * Evaluates password health (reused, weak, outdated) strictly in memory and returns a
     * plaintext-free [PasswordHealthAnalyzer.PasswordHealthReport].
     */
    suspend fun analyzeVaultHealth(
        dek: SecretKey,
        nowMillis: Long = System.currentTimeMillis()
    ): PasswordHealthAnalyzer.PasswordHealthReport = withContext(Dispatchers.IO) {
        val all = credentialDao.getAll()
        val pairs = ArrayList<Pair<CredentialEntity, String>>(all.size)
        for (entity in all) {
            val plain = try {
                decryptPassword(entity, dek)
            } catch (_: Exception) {
                continue
            }
            pairs.add(entity to plain)
        }
        try {
            PasswordHealthAnalyzer.analyze(pairs, nowMillis)
        } finally {
            pairs.clear()
        }
    }

    /**
     * Parses a CSV file exported from Chrome / Edge / Firefox / Bitwarden and merges all
     * credentials non-destructively into Room under [dek].
     */
    suspend fun importCsvCredentials(
        csvText: String,
        dek: SecretKey,
        defaultImportedTitle: String = "Imported Item",
        defaultUntitledTitle: String = "Untitled"
    ): ImportResult = withContext(Dispatchers.IO) {
        val parsed = CsvPasswordParser.parse(csvText, defaultTitle = defaultImportedTitle)
        val array = JSONArray()
        val now = System.currentTimeMillis()
        for (item in parsed) {
            array.put(
                JSONObject().apply {
                    put("title", item.title)
                    put("packageName", item.packageName)
                    put("webDomain", item.webDomain)
                    put("username", item.username)
                    put("password", item.password)
                    put("updatedAt", now)
                }
            )
        }
        val rootJson = JSONObject().apply {
            put("version", 1)
            put("count", array.length())
            put("items", array)
        }.toString()
        importPlainCredentialsJson(rootJson, dek, defaultUntitledTitle)
    }

    private suspend fun exportPlainCredentialsJson(dek: SecretKey): String {
        val all = credentialDao.getAll()
        val array = JSONArray()
        for (item in all) {
            val plainPassword = decryptPassword(item, dek)
            val extraFields = decryptExtraFields(item, dek)
            val obj = JSONObject().apply {
                put("title", item.title)
                put("packageName", item.packageName)
                put("webDomain", item.webDomain)
                put("username", item.username)
                put("password", plainPassword)
                put("updatedAt", item.updatedAt)
                if (item.appSignatureSha256.isNotBlank()) {
                    put("appSignatureSha256", item.appSignatureSha256)
                }
                if (extraFields.isNotEmpty()) {
                    put("extraFields", ExtraFieldsCodec.toJsonArray(extraFields))
                }
            }
            array.put(obj)
        }
        return JSONObject().apply {
            put("version", 2)
            put("count", array.length())
            put("items", array)
        }.toString()
    }

    /**
     * Strict merge for backup import / device migration:
     * - Identity = (packageName, webDomain, username) exact match (disambiguated by title when username is blank).
     * - Same identity: keep whichever copy is newer (by updatedAt).
     * - Different identity: always inserted as a new record. Nothing is ever deleted.
     */
    private suspend fun importPlainCredentialsJson(
        plainJson: String,
        dek: SecretKey,
        defaultUntitledTitle: String = "Untitled"
    ): ImportResult {
        val root = JSONObject(plainJson)
        val items = root.getJSONArray("items")
        var added = 0
        var updated = 0
        var skipped = 0

        runInTransaction {
            val existingByKey = credentialDao.getAll()
                .associateBy {
                    CredentialMatchRules.importKey(it.packageName, it.webDomain, it.username, it.title)
                }
                .toMutableMap()

            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val packageName = obj.optString("packageName", "").trim()
                val webDomain = obj.optString("webDomain", "").trim()
                val username = obj.optString("username", "").trim()
                val password = obj.optString("password", "")
                if (password.isEmpty()) continue
                val title = obj.optString("title", "").trim()
                    .ifBlank { webDomain.ifBlank { packageName.ifBlank { defaultUntitledTitle } } }
                val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())

                val key = CredentialMatchRules.importKey(packageName, webDomain, username, title)
                val existing = existingByKey[key]
                if (existing != null && existing.updatedAt >= updatedAt) {
                    skipped++
                    continue
                }

                val importedSig = obj.optString("appSignatureSha256", "").trim().lowercase()
                val resolvedSig = if (packageName.isNotBlank() && !AssistStructureParser.isBrowserPackage(packageName)) {
                    importedSig.ifBlank {
                        existing?.appSignatureSha256?.ifBlank { null }
                            ?: appSignatureProvider(packageName)?.primarySha256.orEmpty()
                    }
                } else {
                    ""
                }

                val importedExtras = ExtraFieldsCodec.decodeFromJsonArray(obj.optJSONArray("extraFields"))
                val (encExtra, encExtraIv) = encryptExtraFieldsOrPreserve(
                    extraFields = if (obj.has("extraFields")) importedExtras else null,
                    existing = existing,
                    dek = dek
                )

                val encrypted = cryptoManager.encryptWithDek(password, dek)
                val entity = CredentialEntity(
                    id = existing?.id ?: 0L,
                    title = title,
                    packageName = packageName,
                    webDomain = webDomain,
                    username = username,
                    encryptedPassword = encrypted.ciphertextBase64,
                    iv = encrypted.ivBase64,
                    updatedAt = updatedAt,
                    appSignatureSha256 = resolvedSig,
                    encryptedExtraFields = encExtra,
                    extraFieldsIv = encExtraIv
                )
                if (existing != null) {
                    credentialDao.update(entity)
                    existingByKey[key] = entity
                    updated++
                } else {
                    val newId = credentialDao.insert(entity)
                    existingByKey[key] = entity.copy(id = newId)
                    added++
                }
            }
        }
        return ImportResult(added = added, updated = updated, skipped = skipped)
    }
}
