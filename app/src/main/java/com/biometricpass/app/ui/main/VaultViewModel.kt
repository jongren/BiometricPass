package com.biometricpass.app.ui.main

import android.app.Application
import android.view.autofill.AutofillManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.R
import com.biometricpass.app.crypto.InMemorySecretRelay
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.AppSettings
import com.biometricpass.app.util.InstalledAppHelper
import com.biometricpass.app.util.PasswordHealthAnalyzer
import com.biometricpass.app.util.SensitiveClipboardHelper
import com.biometricpass.app.util.VaultSortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.SecretKey

data class VaultUiState(
    val isUnlocked: Boolean = false,
    val searchQuery: String = "",
    val sortOption: VaultSortOption = VaultSortOption.RECENT,
    val totalCount: Int = 0,
    val credentials: List<CredentialEntity> = emptyList(),
    val revealedPasswords: Map<Long, String> = emptyMap(),
    val isAutofillEnabled: Boolean = false,
    val healthReport: PasswordHealthAnalyzer.PasswordHealthReport? = null
)

sealed interface VaultUiEvent {
    data class Toast(val message: String, val longDuration: Boolean = false) : VaultUiEvent
    data class ShowUndoDelete(val deleted: CredentialEntity) : VaultUiEvent
}

/**
 * ViewModel managing the vault credential list, search/sort (including installed App name matching),
 * password reveal/copy, undoable deletion, and offline password health check.
 *
 * Security rule: [unlockedDek] lives strictly in process memory (never in `SavedStateHandle`) and is
 * wiped immediately together with [VaultUiState.revealedPasswords] and [VaultUiState.healthReport] on [lockVault].
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BiometricPassApp = application as BiometricPassApp

    private val _unlockedDekFlow = MutableStateFlow<SecretKey?>(null)
    val unlockedDekFlow: StateFlow<SecretKey?> = _unlockedDekFlow.asStateFlow()

    val unlockedDek: SecretKey?
        get() = _unlockedDekFlow.value

    private val isUnlockedFlow = MutableStateFlow(false)
    private val searchQueryFlow = MutableStateFlow("")
    private val sortOptionFlow = MutableStateFlow(AppSettings.getSortOption(application))
    private val revealedPasswordsFlow = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val isAutofillEnabledFlow = MutableStateFlow(checkAutofillEnabled())
    private val healthReportFlow = MutableStateFlow<PasswordHealthAnalyzer.PasswordHealthReport?>(null)

    private val _events = Channel<VaultUiEvent>(Channel.BUFFERED)
    val events: Flow<VaultUiEvent> = _events.receiveAsFlow()

    private data class FilterMeta(
        val isUnlocked: Boolean,
        val query: String,
        val sort: VaultSortOption,
        val autofill: Boolean
    )

    val uiState: StateFlow<VaultUiState> = combine(
        app.repository.observeAllCredentials(),
        combine(isUnlockedFlow, searchQueryFlow, sortOptionFlow, isAutofillEnabledFlow, ::FilterMeta),
        revealedPasswordsFlow,
        healthReportFlow
    ) { allItems, meta, revealed, health ->
        val q = meta.query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allItems
        } else {
            allItems.filter { item ->
                item.title.lowercase().contains(q) ||
                    item.username.lowercase().contains(q) ||
                    item.webDomain.lowercase().contains(q) ||
                    item.packageName.lowercase().contains(q) ||
                    InstalledAppHelper.getAppLabel(app, item.packageName).orEmpty().lowercase().contains(q)
            }
        }
        val sorted = when (meta.sort) {
            VaultSortOption.RECENT -> filtered.sortedByDescending { it.updatedAt }
            VaultSortOption.TITLE -> filtered.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        }
        VaultUiState(
            isUnlocked = meta.isUnlocked,
            searchQuery = meta.query,
            sortOption = meta.sort,
            totalCount = allItems.size,
            credentials = sorted,
            revealedPasswords = revealed,
            isAutofillEnabled = meta.autofill,
            healthReport = health
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultUiState(
            sortOption = sortOptionFlow.value,
            isAutofillEnabled = isAutofillEnabledFlow.value
        )
    )

    fun onVaultUnlocked(dek: SecretKey) {
        _unlockedDekFlow.value = dek
        InMemorySecretRelay.setVaultDek(dek)
        isUnlockedFlow.value = true
        refreshAutofillStatus()
    }

    fun lockVault(): Boolean {
        val wasUnlocked = _unlockedDekFlow.value != null
        _unlockedDekFlow.value = null
        InMemorySecretRelay.clearVaultDek()
        isUnlockedFlow.value = false
        revealedPasswordsFlow.value = emptyMap()
        healthReportFlow.value = null
        return wasUnlocked
    }

    override fun onCleared() {
        InMemorySecretRelay.clearVaultDek()
        _unlockedDekFlow.value = null
        super.onCleared()
    }

    fun refreshAutofillStatus() {
        isAutofillEnabledFlow.value = checkAutofillEnabled()
    }

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun toggleSortOption() {
        val next = if (sortOptionFlow.value == VaultSortOption.RECENT) {
            VaultSortOption.TITLE
        } else {
            VaultSortOption.RECENT
        }
        sortOptionFlow.value = next
        AppSettings.setSortOption(app, next)
    }

    fun toggleRevealPassword(item: CredentialEntity) {
        val dek = unlockedDek ?: return
        val current = revealedPasswordsFlow.value
        if (current.containsKey(item.id)) {
            revealedPasswordsFlow.update { it - item.id }
        } else {
            try {
                val plain = app.repository.decryptPassword(item, dek)
                revealedPasswordsFlow.update { it + (item.id to plain) }
            } catch (e: Exception) {
                AppLog.w("VaultViewModel", "Failed to decrypt credential on reveal", e)
                emitToast(app.getString(R.string.decrypt_failed_msg), longDuration = true)
            }
        }
    }

    fun copyUsername(item: CredentialEntity) {
        if (item.username.isNotBlank()) {
            SensitiveClipboardHelper.copySensitive(app, app.getString(R.string.field_username), item.username)
        }
    }

    fun copyPassword(item: CredentialEntity) {
        val dek = unlockedDek ?: return
        try {
            val plain = revealedPasswordsFlow.value[item.id]
                ?: app.repository.decryptPassword(item, dek)
            SensitiveClipboardHelper.copySensitive(app, app.getString(R.string.field_password), plain)
        } catch (e: Exception) {
            AppLog.w("VaultViewModel", "Failed to decrypt credential on copy", e)
            emitToast(app.getString(R.string.decrypt_failed_msg), longDuration = true)
        }
    }

    fun deleteCredential(item: CredentialEntity) {
        viewModelScope.launch {
            app.repository.deleteCredential(item.id)
            revealedPasswordsFlow.update { it - item.id }
            _events.send(VaultUiEvent.ShowUndoDelete(item))
        }
    }

    fun restoreDeletedCredential(item: CredentialEntity) {
        viewModelScope.launch {
            app.repository.restoreDeletedCredential(item)
        }
    }

    fun runPasswordHealthCheck() {
        val dek = unlockedDek ?: return
        viewModelScope.launch {
            val report = app.repository.analyzeVaultHealth(dek)
            healthReportFlow.value = report
        }
    }

    fun dismissHealthReport() {
        healthReportFlow.value = null
    }

    private fun checkAutofillEnabled(): Boolean {
        val afm = app.getSystemService(AutofillManager::class.java) ?: return false
        return afm.hasEnabledAutofillServices()
    }

    private fun emitToast(message: String, longDuration: Boolean = false) {
        viewModelScope.launch {
            _events.send(VaultUiEvent.Toast(message, longDuration))
        }
    }
}
