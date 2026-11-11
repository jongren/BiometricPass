package com.biometricpass.app.ui.main

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.R
import com.biometricpass.app.crypto.CryptoManager
import com.biometricpass.app.crypto.TransferCode
import com.biometricpass.app.crypto.VaultCrypto
import com.biometricpass.app.data.repository.CredentialRepository
import com.biometricpass.app.ui.main.components.QrModalInfo
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.AppSettings
import com.biometricpass.app.util.CsvPasswordParser
import com.biometricpass.app.util.QrCodeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

data class BackupMigrationUiState(
    val activeQrDialogData: QrModalInfo? = null,
    val showKeyProtectionDialog: Boolean = false,
    val showQrScanner: Boolean = false,
    val showNeedKeyForBackupDialog: Boolean = false,
    val pendingMigrationPayload: String? = null,
    val transferCodeError: String? = null,
    val isMigrationImporting: Boolean = false,
    val pendingRecoveryV2Payload: String? = null,
    val recoveryPassphraseError: String? = null,
    val isRecoveryWorking: Boolean = false,
    val csvDeleteReminderSummary: String? = null,
    val isBackupKeySaved: Boolean = false,
    val isRotatingKey: Boolean = false
)

sealed interface BackupUiEvent {
    data class Toast(val message: String, val longDuration: Boolean = true) : BackupUiEvent
    data class LaunchExportBackupSaf(val suggestedFileName: String) : BackupUiEvent
    data object LaunchImportBackupSaf : BackupUiEvent
    data object LaunchImportCsvSaf : BackupUiEvent
    data class ShareBackupFile(val fileUri: Uri) : BackupUiEvent
    data class VaultKeyRotated(val newDek: SecretKey) : BackupUiEvent
}

/**
 * ViewModel managing `.bpass` backup export/import, CSV import, Device-to-Device QR / Quick Share
 * migration, and V1/V2 Backup Unlock Key flows.
 */
class BackupMigrationViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BiometricPassApp = application as BiometricPassApp

    private val _uiState = MutableStateFlow(
        BackupMigrationUiState(isBackupKeySaved = AppSettings.isBackupKeySaved(application))
    )
    val uiState: StateFlow<BackupMigrationUiState> = _uiState.asStateFlow()

    private val _events = Channel<BackupUiEvent>(Channel.BUFFERED)
    val events: Flow<BackupUiEvent> = _events.receiveAsFlow()

    /** Key decoded from a scanned backup unlock key QR Code, kept in memory to decrypt `.bpass` files. */
    @Volatile
    private var scannedBackupKey: SecretKey? = null

    @Volatile
    private var pendingBackupContent: String? = null

    init {
        purgeOldSharedBackups()
    }

    fun clearSessionOnLock() {
        scannedBackupKey = null
        pendingBackupContent = null
        purgeSharedTransferFiles(olderThanMs = 0L)
        _uiState.update {
            it.copy(
                activeQrDialogData = null,
                showKeyProtectionDialog = false,
                showQrScanner = false,
                showNeedKeyForBackupDialog = false,
                pendingMigrationPayload = null,
                transferCodeError = null,
                pendingRecoveryV2Payload = null,
                recoveryPassphraseError = null
            )
        }
    }

    private fun purgeOldSharedBackups() {
        purgeSharedTransferFiles(olderThanMs = SHARED_FILE_MAX_AGE_MS)
    }

    /** Deletes Quick Share transfer files left in the cache (all of them when [olderThanMs] is 0). */
    private fun purgeSharedTransferFiles(olderThanMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - olderThanMs
            File(app.cacheDir, SHARED_DIR_NAME).listFiles()
                ?.filter { olderThanMs <= 0L || it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    /** Reads a user-selected text file, refusing anything larger than [MAX_IMPORT_FILE_BYTES]. */
    private suspend fun readTextFileWithLimit(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = app.contentResolver.openInputStream(uri) ?: return@withContext ""
        input.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_FILE_BYTES) throw FileTooLargeException()
                buffer.write(chunk, 0, read)
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }

    private class FileTooLargeException : Exception("Selected file is too large")

    // ---------------------------------------------------------------------------------------------
    // Backup Unlock Key (V1 plain & V2 passphrase-protected)
    // ---------------------------------------------------------------------------------------------

    fun requestShowBackupUnlockKey() {
        _uiState.update { it.copy(showKeyProtectionDialog = true) }
    }

    fun dismissKeyProtectionDialog() {
        _uiState.update { it.copy(showKeyProtectionDialog = false) }
    }

    fun generateAndDisplayBackupUnlockKey(dek: SecretKey, passphrase: String?) {
        _uiState.update { it.copy(showKeyProtectionDialog = false) }
        viewModelScope.launch {
            showBackupUnlockKey(dek, passphrase)
        }
    }

    private suspend fun showBackupUnlockKey(dek: SecretKey, passphrase: String?) {
        try {
            // PBKDF2 key derivation is CPU heavy: keep it off the main thread.
            val payload = withContext(Dispatchers.Default) {
                app.cryptoManager.generateRecoveryQrPayload(dek, passphrase)
            }
            val hasPassphrase = !passphrase.isNullOrBlank()
            val desc = if (hasPassphrase) {
                app.getString(R.string.backup_key_desc_with_passphrase)
            } else {
                app.getString(R.string.backup_key_desc_no_passphrase)
            }
            _uiState.update {
                it.copy(
                    activeQrDialogData = QrModalInfo(
                        title = app.getString(R.string.menu_backup_unlock_key),
                        description = desc,
                        payload = payload,
                        defaultFileName = app.getString(R.string.backup_key_default_filename),
                        allowSaveImage = true,
                        onImageSaved = { markBackupKeySaved() }
                    )
                )
            }
        } catch (_: Exception) {
            emitToast(app.getString(R.string.backup_key_generate_error), longDuration = false)
        }
    }

    fun markBackupKeySaved() {
        AppSettings.setBackupKeySaved(app, true)
        _uiState.update { it.copy(isBackupKeySaved = true) }
    }

    fun dismissQrDisplayDialog() {
        _uiState.update { it.copy(activeQrDialogData = null) }
    }

    // ---------------------------------------------------------------------------------------------
    // Device-to-Device Migration (QR + 8-char Transfer Code, or Quick Share)
    // ---------------------------------------------------------------------------------------------

    fun startSendToNewPhone(dek: SecretKey, credentialCount: Int) {
        if (credentialCount <= 0) {
            emitToast(app.getString(R.string.migration_empty_vault_toast), longDuration = false)
            return
        }
        if (credentialCount > QrCodeHelper.MAX_MIGRATION_QR_CREDENTIALS) {
            startQuickShareMigration(dek)
        } else {
            startQrMigration(dek, credentialCount)
        }
    }

    private fun startQrMigration(dek: SecretKey, credentialCount: Int) {
        viewModelScope.launch {
            try {
                val transferCode = TransferCode.generate()
                val payload = app.repository.exportMigrationQrPayload(dek, transferCode)
                if (QrCodeHelper.canFitInSingleQr(credentialCount, payload)) {
                    _uiState.update {
                        it.copy(
                            activeQrDialogData = QrModalInfo(
                                title = app.getString(R.string.menu_send_to_new_phone),
                                description = app.getString(R.string.migration_qr_dialog_desc),
                                payload = payload,
                                defaultFileName = "",
                                transferCode = transferCode,
                                allowSaveImage = false,
                                extraActionLabel = app.getString(R.string.migration_switch_to_quick_share),
                                onExtraAction = { startQuickShareMigration(dek) }
                            )
                        )
                    }
                } else {
                    startQuickShareMigration(dek)
                }
            } catch (_: Exception) {
                startQuickShareMigration(dek)
            }
        }
    }

    /**
     * Sends the vault to a new phone via Quick Share WITHOUT revealing the permanent Backup Key:
     * the transfer file is encrypted with a key derived from a one-time 8-character transfer code
     * (same container as the migration QR Code), shown only on this screen.
     */
    fun startQuickShareMigration(dek: SecretKey) {
        viewModelScope.launch {
            try {
                val transferCode = TransferCode.generate()
                val transferPayload = app.repository.exportMigrationQrPayload(dek, transferCode)
                val prefix = app.getString(R.string.backup_file_prefix)
                val fileUri = withContext(Dispatchers.IO) {
                    val shareDir = File(app.cacheDir, SHARED_DIR_NAME).apply { mkdirs() }
                    shareDir.listFiles()?.forEach { it.delete() }
                    val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    val transferFile = File(shareDir, "${prefix}_transfer_$dateStr.bpass")
                    transferFile.writeText(transferPayload, Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        app,
                        "${app.packageName}.fileprovider",
                        transferFile
                    )
                }

                _uiState.update {
                    it.copy(
                        activeQrDialogData = QrModalInfo(
                            title = app.getString(R.string.quick_share_dialog_title),
                            description = app.getString(R.string.quick_share_dialog_desc),
                            payload = "",
                            defaultFileName = "",
                            transferCode = transferCode,
                            allowSaveImage = false,
                            extraActionLabel = app.getString(R.string.quick_share_resend_btn),
                            onExtraAction = {
                                viewModelScope.launch {
                                    _events.send(BackupUiEvent.ShareBackupFile(fileUri))
                                }
                            }
                        )
                    )
                }
                _events.send(BackupUiEvent.ShareBackupFile(fileUri))
            } catch (_: Exception) {
                emitToast(app.getString(R.string.quick_share_failed_toast))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SAF .bpass Backup Export & Import + CSV Import
    // ---------------------------------------------------------------------------------------------

    fun requestExportBackup(credentialCount: Int) {
        if (credentialCount <= 0) {
            emitToast(app.getString(R.string.export_empty_vault_toast), longDuration = false)
            return
        }
        val prefix = app.getString(R.string.backup_file_prefix)
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        viewModelScope.launch {
            _events.send(BackupUiEvent.LaunchExportBackupSaf("${prefix}_$dateStr.bpass"))
        }
    }

    fun exportBackupToUri(uri: Uri, dek: SecretKey) {
        viewModelScope.launch {
            try {
                val encryptedBackup = app.repository.exportEncryptedBackup(dek)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(encryptedBackup.toByteArray(Charsets.UTF_8))
                    }
                }
                val saved = _uiState.value.isBackupKeySaved
                emitToast(
                    if (saved) {
                        app.getString(R.string.export_success_toast)
                    } else {
                        app.getString(R.string.export_success_remind_key_toast)
                    }
                )
            } catch (_: Exception) {
                emitToast(app.getString(R.string.export_failed_toast))
            }
        }
    }

    fun requestImportBackup() {
        scannedBackupKey = null
        viewModelScope.launch { _events.send(BackupUiEvent.LaunchImportBackupSaf) }
    }

    fun onImportBackupPickerCancelled() {
        scannedBackupKey = null
    }

    fun importBackupFromUri(uri: Uri, dek: SecretKey) {
        viewModelScope.launch {
            val content = try {
                readTextFileWithLimit(uri)
            } catch (_: FileTooLargeException) {
                scannedBackupKey = null
                emitToast(app.getString(R.string.import_file_too_large_error))
                return@launch
            } catch (_: Exception) {
                scannedBackupKey = null
                emitToast(app.getString(R.string.import_read_file_error))
                return@launch
            }

            val trimmed = content.trim()
            // Quick Share transfer file: protected by the one-time transfer code shown on the old phone
            if (trimmed.startsWith(CryptoManager.MIGRATION_QR_PREFIX)) {
                scannedBackupKey = null
                _uiState.update { it.copy(pendingMigrationPayload = trimmed, transferCodeError = null) }
                return@launch
            }
            if (!VaultCrypto.isBackupFileFormat(trimmed)) {
                scannedBackupKey = null
                emitToast(app.getString(R.string.import_not_bpass_error))
                return@launch
            }

            val outcome = tryRestoreBackupContent(trimmed, dek)
            handleBackupRestoreOutcome(outcome, onNeedsKey = {
                pendingBackupContent = trimmed
                _uiState.update { it.copy(showNeedKeyForBackupDialog = true) }
            })
        }
    }

    private sealed interface BackupRestoreOutcome {
        data object NeedsKey : BackupRestoreOutcome
        data class Restored(val result: CredentialRepository.ImportResult) : BackupRestoreOutcome
        data object WrongKey : BackupRestoreOutcome
        data object CorruptedFile : BackupRestoreOutcome
    }

    private suspend fun tryRestoreBackupContent(
        backupText: String,
        storeDek: SecretKey
    ): BackupRestoreOutcome {
        // Strictly single-use: every .bpass restore requires scanning the Backup Unlock Key
        // and immediately clears it from memory once consumed.
        val key = scannedBackupKey ?: return BackupRestoreOutcome.NeedsKey
        scannedBackupKey = null
        return try {
            val result = app.repository.importEncryptedBackup(
                backupText,
                decryptKey = key,
                storeDek = storeDek,
                defaultUntitledTitle = app.getString(R.string.default_untitled_item)
            )
            BackupRestoreOutcome.Restored(result)
        } catch (_: AEADBadTagException) {
            BackupRestoreOutcome.WrongKey
        } catch (e: Exception) {
            AppLog.w("BackupMigration", "Backup container damaged or unreadable after decryption", e)
            BackupRestoreOutcome.CorruptedFile
        }
    }

    private fun handleBackupRestoreOutcome(
        outcome: BackupRestoreOutcome,
        onNeedsKey: () -> Unit = {}
    ) {
        when (outcome) {
            BackupRestoreOutcome.NeedsKey -> onNeedsKey()
            is BackupRestoreOutcome.Restored -> {
                pendingBackupContent = null
                if (outcome.result.total == 0) {
                    emitToast(app.getString(R.string.import_empty_backup_toast))
                } else {
                    emitToast(app.getString(R.string.import_restore_complete_toast, formatImportResult(outcome.result)))
                }
            }
            BackupRestoreOutcome.WrongKey -> {
                emitToast(app.getString(R.string.import_wrong_key_toast))
            }
            BackupRestoreOutcome.CorruptedFile -> {
                pendingBackupContent = null
                emitToast(app.getString(R.string.import_corrupted_bpass_error))
            }
        }
    }

    fun dismissNeedKeyDialog(clearPendingBackup: Boolean = true) {
        if (clearPendingBackup) {
            pendingBackupContent = null
            scannedBackupKey = null
        }
        _uiState.update { it.copy(showNeedKeyForBackupDialog = false) }
    }

    fun scanKeyForPendingBackup() {
        _uiState.update { it.copy(showNeedKeyForBackupDialog = false, showQrScanner = true) }
    }

    fun requestImportCsv() {
        viewModelScope.launch { _events.send(BackupUiEvent.LaunchImportCsvSaf) }
    }

    fun importCsvFromUri(uri: Uri, dek: SecretKey) {
        viewModelScope.launch {
            val csvText = try {
                readTextFileWithLimit(uri)
            } catch (_: FileTooLargeException) {
                emitToast(app.getString(R.string.import_file_too_large_error))
                return@launch
            } catch (_: Exception) {
                emitToast(app.getString(R.string.csv_read_file_error))
                return@launch
            }

            try {
                val result = app.repository.importCsvCredentials(
                    csvText = csvText,
                    dek = dek,
                    defaultImportedTitle = app.getString(R.string.default_imported_title),
                    defaultUntitledTitle = app.getString(R.string.default_untitled_item)
                )
                _uiState.update {
                    it.copy(
                        csvDeleteReminderSummary = app.getString(
                            R.string.csv_import_result_prefix,
                            formatImportResult(result)
                        )
                    )
                }
            } catch (_: CsvPasswordParser.MissingPasswordColumnException) {
                emitToast(app.getString(R.string.csv_missing_password_col_error))
            } catch (_: IllegalArgumentException) {
                emitToast(app.getString(R.string.csv_invalid_format_error))
            } catch (_: Exception) {
                emitToast(app.getString(R.string.csv_import_failed_error))
            }
        }
    }

    fun dismissCsvDeleteReminder() {
        _uiState.update { it.copy(csvDeleteReminderSummary = null) }
    }

    // ---------------------------------------------------------------------------------------------
    // QR Scanner & Transfer Code / Recovery Passphrase Verification
    // ---------------------------------------------------------------------------------------------

    fun openQrScanner() {
        _uiState.update { it.copy(showQrScanner = true) }
    }

    fun dismissQrScanner() {
        _uiState.update { it.copy(showQrScanner = false) }
    }

    fun onQrCodeScanned(scannedText: String, dek: SecretKey) {
        _uiState.update { it.copy(showQrScanner = false) }
        val trimmed = scannedText.trim()
        when {
            trimmed.startsWith(CryptoManager.MIGRATION_QR_PREFIX) -> {
                _uiState.update {
                    it.copy(pendingMigrationPayload = trimmed, transferCodeError = null)
                }
            }

            VaultCrypto.isPassphraseProtectedRecoveryQr(trimmed) -> {
                _uiState.update {
                    it.copy(pendingRecoveryV2Payload = trimmed, recoveryPassphraseError = null)
                }
            }

            trimmed.startsWith(CryptoManager.RECOVERY_QR_PREFIX) -> {
                processScannedRecoveryQr(trimmed, passphrase = null, currentDek = dek)
            }

            else -> {
                emitToast(app.getString(R.string.qr_unrecognized_toast))
            }
        }
    }

    fun dismissMigrationCodeDialog() {
        if (!_uiState.value.isMigrationImporting) {
            _uiState.update { it.copy(pendingMigrationPayload = null, transferCodeError = null) }
        }
    }

    fun confirmMigrationTransferCode(code: String, dek: SecretKey) {
        val payload = _uiState.value.pendingMigrationPayload ?: return
        _uiState.update { it.copy(isMigrationImporting = true, transferCodeError = null) }
        viewModelScope.launch {
            try {
                val result = app.repository.importMigrationQrPayload(
                    qrPayload = payload,
                    transferCode = code,
                    dek = dek,
                    defaultUntitledTitle = app.getString(R.string.default_untitled_item)
                )
                _uiState.update { it.copy(pendingMigrationPayload = null, isMigrationImporting = false) }
                emitToast(app.getString(R.string.migration_complete_toast, formatImportResult(result)))
            } catch (_: VaultCrypto.WrongTransferCodeException) {
                _uiState.update {
                    it.copy(
                        isMigrationImporting = false,
                        transferCodeError = app.getString(R.string.migration_wrong_code_error)
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(pendingMigrationPayload = null, isMigrationImporting = false) }
                emitToast(app.getString(R.string.migration_qr_read_error))
            }
        }
    }

    fun dismissRecoveryPassphraseDialog() {
        if (!_uiState.value.isRecoveryWorking) {
            _uiState.update { it.copy(pendingRecoveryV2Payload = null, recoveryPassphraseError = null) }
        }
    }

    fun confirmRecoveryPassphrase(passphrase: String, currentDek: SecretKey) {
        val payload = _uiState.value.pendingRecoveryV2Payload ?: return
        _uiState.update { it.copy(isRecoveryWorking = true, recoveryPassphraseError = null) }
        processScannedRecoveryQr(payload, passphrase = passphrase, currentDek = currentDek)
    }

    /**
     * Decodes a scanned V1 or V2 backup unlock key in memory and either:
     * 1. Immediately decrypts & imports [pendingBackupContent] into Room under [currentDek] (if the
     *    user already selected a `.bpass` file first), OR
     * 2. Automatically launches the `.bpass` file picker so the user can select the backup file to restore.
     */
    private fun processScannedRecoveryQr(
        qrText: String,
        passphrase: String?,
        currentDek: SecretKey
    ) {
        viewModelScope.launch {
            val candidate = try {
                withContext(Dispatchers.Default) {
                    app.cryptoManager.decodeRecoveryDek(qrText, passphrase)
                }
            } catch (_: VaultCrypto.WrongRecoveryPassphraseException) {
                _uiState.update {
                    it.copy(
                        isRecoveryWorking = false,
                        recoveryPassphraseError = app.getString(R.string.passphrase_wrong_retry)
                    )
                }
                return@launch
            } catch (_: Exception) {
                _uiState.update { it.copy(pendingRecoveryV2Payload = null, isRecoveryWorking = false) }
                emitToast(app.getString(R.string.recovery_key_read_error))
                return@launch
            }

            _uiState.update { it.copy(pendingRecoveryV2Payload = null, isRecoveryWorking = false) }
            scannedBackupKey = candidate
            markBackupKeySaved()

            val backupToRestore = pendingBackupContent
            if (!backupToRestore.isNullOrBlank()) {
                val outcome = tryRestoreBackupContent(backupToRestore, currentDek)
                handleBackupRestoreOutcome(outcome)
            } else {
                emitToast(app.getString(R.string.recovery_key_verified_pick_file_toast))
                _events.send(BackupUiEvent.LaunchImportBackupSaf)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reset Backup Key (vault key rotation)
    // ---------------------------------------------------------------------------------------------

    /**
     * Rotates the vault key. MUST be called from a BiometricPrompt success callback (KEK 1 authorized).
     * 1. Stores the new DEK as a pending key set (old Backup Key still valid).
     * 2. Re-encrypts every password in one database transaction.
     * 3. Promotes the pending key set; the old Backup Key and old backups no longer match this vault.
     * Any failure rolls back to the old key with no data change.
     */
    fun rotateVaultKey(currentDek: SecretKey) {
        if (_uiState.value.isRotatingKey) return
        val newDek = VaultCrypto.newAesKey()
        try {
            app.cryptoManager.beginKeyRotation(newDek)
        } catch (_: Exception) {
            emitToast(app.getString(R.string.rotate_key_failed_toast))
            return
        }
        _uiState.update { it.copy(isRotatingKey = true) }
        viewModelScope.launch {
            try {
                app.repository.reencryptAllCredentials(oldDek = currentDek, newDek = newDek)
            } catch (_: Exception) {
                app.cryptoManager.discardPendingKeyRotation()
                _uiState.update { it.copy(isRotatingKey = false) }
                emitToast(app.getString(R.string.rotate_key_failed_toast))
                return@launch
            }
            // The database now uses the new DEK: committing is a plain preferences write.
            app.cryptoManager.commitKeyRotation()
            scannedBackupKey = null
            pendingBackupContent = null
            AppSettings.setBackupKeySaved(app, false)
            _uiState.update {
                it.copy(isRotatingKey = false, isBackupKeySaved = false, showKeyProtectionDialog = true)
            }
            _events.send(BackupUiEvent.VaultKeyRotated(newDek))
            emitToast(app.getString(R.string.rotate_key_success_toast))
        }
    }

    private fun formatImportResult(result: CredentialRepository.ImportResult): String = buildString {
        append(app.getString(R.string.import_result_added, result.added))
        if (result.updated > 0) append(app.getString(R.string.import_result_updated, result.updated))
        if (result.skipped > 0) append(app.getString(R.string.import_result_skipped, result.skipped))
    }

    private fun emitToast(message: String, longDuration: Boolean = true) {
        viewModelScope.launch {
            _events.send(BackupUiEvent.Toast(message, longDuration))
        }
    }

    private companion object {
        const val SHARED_DIR_NAME = "shared_backups"
        const val SHARED_FILE_MAX_AGE_MS = 10 * 60 * 1000L
        const val MAX_IMPORT_FILE_BYTES = 20L * 1024 * 1024
    }
}
