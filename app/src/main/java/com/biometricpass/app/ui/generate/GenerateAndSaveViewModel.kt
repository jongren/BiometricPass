package com.biometricpass.app.ui.generate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.crypto.InMemorySecretRelay
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.data.repository.CredentialRepository
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.InstalledAppInfo
import com.biometricpass.app.util.PasswordGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.SecretKey
import kotlin.math.roundToInt

data class GenerateAndSaveUiState(
    val credentialId: Long = 0L,
    val title: String = "",
    val initialPackageName: String = "",
    val packageName: String = "",
    val webDomain: String = "",
    val username: String = "",
    val password: String = "",
    /** `0` = Random Password Generator, `1` = Custom Password Input. */
    val selectedMode: Int = 0,
    val lengthSlider: Float = 16f,
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigits: Boolean = true,
    val useSymbols: Boolean = true,
    val showPassword: Boolean = true,
    val isCompactFillMode: Boolean = false,
    val isFromAutofillSave: Boolean = false,
    val matchedExisting: CredentialEntity? = null
) {
    val isUpdateAction: Boolean
        get() = matchedExisting != null || credentialId > 0L
}

data class SavedCredentialOutcome(
    val isUpdate: Boolean,
    val finalTitle: String,
    val username: String,
    val password: String
)

/**
 * ViewModel holding state and repository operations for [GenerateAndSaveActivity].
 *
 * Security rule: [GenerateAndSaveUiState.password] is kept strictly in process memory (never placed
 * in `SavedStateHandle` or `Bundle`) and cleared in [onCleared].
 */
class GenerateAndSaveViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BiometricPassApp = application as BiometricPassApp

    private val _uiState = MutableStateFlow(GenerateAndSaveUiState())
    val uiState: StateFlow<GenerateAndSaveUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun initializeIfNeeded(
        credentialId: Long,
        initialTitle: String,
        initialPackageName: String,
        initialWebDomain: String,
        initialUsername: String,
        saveSecretToken: String?,
        sessionDekAtLaunch: SecretKey?,
        isCompactFillMode: Boolean,
        isFromAutofillSave: Boolean
    ) {
        if (initialized) return
        initialized = true

        val relayedPassword = InMemorySecretRelay.consumePendingSavePassword(saveSecretToken)
        val startCustomMode = relayedPassword.isNotBlank() || isFromAutofillSave || credentialId > 0L
        val startPassword = when {
            relayedPassword.isNotBlank() -> relayedPassword
            credentialId > 0L -> ""
            else -> PasswordGenerator.generate(
                length = 16,
                useUppercase = true,
                useLowercase = true,
                useDigits = true,
                useSymbols = true
            )
        }

        _uiState.value = GenerateAndSaveUiState(
            credentialId = credentialId,
            title = initialTitle,
            initialPackageName = initialPackageName,
            packageName = initialPackageName,
            webDomain = initialWebDomain,
            username = initialUsername,
            password = startPassword,
            selectedMode = if (startCustomMode) 1 else 0,
            isCompactFillMode = isCompactFillMode,
            isFromAutofillSave = isFromAutofillSave
        )

        viewModelScope.launch {
            if (credentialId > 0L) {
                val existing = app.repository.getCredentialById(credentialId)
                val decrypted = if (existing != null && sessionDekAtLaunch != null && relayedPassword.isEmpty()) {
                    try {
                        app.repository.decryptPassword(existing, sessionDekAtLaunch)
                    } catch (e: Exception) {
                        AppLog.w("GenerateViewModel", "Failed to decrypt credential for editing", e)
                        ""
                    }
                } else {
                    ""
                }
                _uiState.update { state ->
                    state.copy(
                        matchedExisting = existing,
                        title = state.title.ifBlank { existing?.title.orEmpty() },
                        username = state.username.ifBlank { existing?.username.orEmpty() },
                        password = if (state.password.isEmpty() && decrypted.isNotBlank()) decrypted else state.password,
                        selectedMode = if (decrypted.isNotBlank()) 1 else state.selectedMode
                    )
                }
            } else {
                refreshExistingMatch(initialPackageName, initialWebDomain, initialUsername, syncBlankFields = true)
            }
        }
    }

    private suspend fun refreshExistingMatch(
        packageName: String,
        webDomain: String,
        username: String,
        syncBlankFields: Boolean = false
    ) {
        val existing = app.repository.findExistingCredential(
            packageName = packageName,
            webDomain = webDomain,
            username = username
        )
        _uiState.update { state ->
            state.copy(
                matchedExisting = existing,
                title = if (syncBlankFields && state.title.isBlank()) existing?.title.orEmpty() else state.title,
                username = if (syncBlankFields && state.username.isBlank()) existing?.username.orEmpty() else state.username
            )
        }
    }

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(s.packageName, s.webDomain, value) }
        }
    }

    fun onPackageNameChange(value: String) {
        _uiState.update { it.copy(packageName = value) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(value, s.webDomain, s.username) }
        }
    }

    fun onWebDomainChange(value: String) {
        _uiState.update { it.copy(webDomain = value) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(s.packageName, value, s.username) }
        }
    }

    fun onAppPicked(appInfo: InstalledAppInfo) {
        _uiState.update { state ->
            val updatedTitle = if (state.title.isBlank() || state.title == state.initialPackageName) {
                appInfo.appName
            } else {
                state.title
            }
            state.copy(packageName = appInfo.packageName, title = updatedTitle)
        }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(appInfo.packageName, s.webDomain, s.username) }
        }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(showPassword = !it.showPassword) }
    }

    fun selectMode(mode: Int) {
        _uiState.update { state ->
            val pwd = if (mode == 0 && state.password.isBlank()) generateFromState(state) else state.password
            state.copy(selectedMode = mode, password = pwd)
        }
    }

    fun regeneratePassword() {
        _uiState.update { state -> state.copy(password = generateFromState(state)) }
    }

    fun onLengthChange(length: Float) {
        _uiState.update { state ->
            val next = state.copy(lengthSlider = length)
            next.copy(password = generateFromState(next))
        }
    }

    fun toggleUpper() {
        _uiState.update { state ->
            val upper = !state.useUpper
            val lower = if (!upper && !state.useLower && !state.useDigits && !state.useSymbols) true else state.useLower
            val next = state.copy(useUpper = upper, useLower = lower)
            next.copy(password = generateFromState(next))
        }
    }

    fun toggleLower() {
        _uiState.update { state ->
            val lower = !state.useLower
            val digits = if (!state.useUpper && !lower && !state.useDigits && !state.useSymbols) true else state.useDigits
            val next = state.copy(useLower = lower, useDigits = digits)
            next.copy(password = generateFromState(next))
        }
    }

    fun toggleDigits() {
        _uiState.update { state ->
            val digits = !state.useDigits
            val lower = if (!state.useUpper && !state.useLower && !digits && !state.useSymbols) true else state.useLower
            val next = state.copy(useDigits = digits, useLower = lower)
            next.copy(password = generateFromState(next))
        }
    }

    fun toggleSymbols() {
        _uiState.update { state ->
            val symbols = !state.useSymbols
            val lower = if (!state.useUpper && !state.useLower && !state.useDigits && !symbols) true else state.useLower
            val next = state.copy(useSymbols = symbols, useLower = lower)
            next.copy(password = generateFromState(next))
        }
    }

    suspend fun saveCredential(
        dek: SecretKey,
        defaultUntitledTitle: String,
        defaultCustomTitle: String
    ): SavedCredentialOutcome {
        val s = _uiState.value
        val resolvedTitle = s.title.ifBlank {
            s.webDomain.ifBlank { s.packageName.ifBlank { defaultCustomTitle } }
        }
        val saveResult: CredentialRepository.SaveOrUpdateResult = app.repository.saveOrUpdateCredential(
            id = s.credentialId,
            title = resolvedTitle,
            packageName = s.packageName,
            webDomain = s.webDomain,
            username = s.username,
            plainPassword = s.password,
            dek = dek,
            defaultUntitledTitle = defaultUntitledTitle
        )
        return SavedCredentialOutcome(
            isUpdate = saveResult.isUpdate,
            finalTitle = resolvedTitle,
            username = s.username,
            password = s.password
        )
    }

    override fun onCleared() {
        _uiState.update { it.copy(password = "") }
        super.onCleared()
    }

    private fun generateFromState(state: GenerateAndSaveUiState): String =
        PasswordGenerator.generate(
            length = state.lengthSlider.roundToInt(),
            useUppercase = state.useUpper,
            useLowercase = state.useLower,
            useDigits = state.useDigits,
            useSymbols = state.useSymbols
        )
}
