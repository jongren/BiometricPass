package com.biometricpass.app.ui.generate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.R
import com.biometricpass.app.autofill.CapturedFormField
import com.biometricpass.app.crypto.InMemorySecretRelay
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.data.model.ExtraField
import com.biometricpass.app.data.model.ExtraFieldsCodec
import com.biometricpass.app.data.repository.CredentialRepository
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.InstalledAppInfo
import com.biometricpass.app.util.MaskedIdentifierHelper
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
    val isPinLikeCapture: Boolean = false,
    val matchedExisting: CredentialEntity? = null,
    val siteExistingCredentials: List<CredentialEntity> = emptyList(),
    val isAppendToExistingMode: Boolean = false,
    val appendTargetCredentialId: Long = 0L,
    val appendFieldLabel: String = "",
    val extraFields: List<ExtraField> = emptyList(),
    val extraFieldsLoaded: Boolean = false,
    val capturedFields: List<CapturedFormField> = emptyList(),
    val hasUserModifiedFields: Boolean = false
) {
    val isUpdateAction: Boolean
        get() = !isAppendToExistingMode && (matchedExisting != null || credentialId > 0L)

    val requiresOverwriteConfirmation: Boolean
        get() = !isAppendToExistingMode && credentialId == 0L && matchedExisting != null

    val hasUnsavedWorkToProtect: Boolean
        get() = isFromAutofillSave || credentialId > 0L || hasUserModifiedFields

    val hasMaskedUsernameWarning: Boolean
        get() = !isAppendToExistingMode && MaskedIdentifierHelper.isMaskedIdentifier(username)

    val canAppendToExisting: Boolean
        get() = credentialId == 0L && siteExistingCredentials.isNotEmpty() && isFromAutofillSave

    val appendTargetEntity: CredentialEntity?
        get() = siteExistingCredentials.firstOrNull { it.id == appendTargetCredentialId }
            ?: siteExistingCredentials.firstOrNull()
}

data class SavedCredentialOutcome(
    val isUpdate: Boolean,
    val isAppendedExtraField: Boolean = false,
    val finalTitle: String,
    val username: String,
    val password: String
)

/**
 * ViewModel holding state and repository operations for [GenerateAndSaveActivity].
 *
 * Security rule: [GenerateAndSaveUiState.password], [GenerateAndSaveUiState.extraFields], and
 * [GenerateAndSaveUiState.capturedFields] are kept strictly in process memory (never placed in
 * `SavedStateHandle` or `Bundle`) and cleared in [onCleared].
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

        val savePayload = InMemorySecretRelay.consumePendingSavePayload(saveSecretToken)
        val relayedPassword = savePayload?.password.orEmpty()
        val relayedSecondaryUser = savePayload?.secondaryUsername.orEmpty().trim()
        val relayedSecondaryLabel = savePayload?.secondaryLabel.orEmpty().trim()
        val isPinLike = savePayload?.isPinLike == true
        val capturedFields = savePayload?.capturedFields.orEmpty()

        val initialAutoExtras = if (
            relayedSecondaryUser.isNotEmpty() &&
            !relayedSecondaryUser.equals(initialUsername.trim(), ignoreCase = true)
        ) {
            listOf(
                ExtraField(
                    label = relayedSecondaryLabel.ifBlank { app.getString(R.string.tag_user_id) },
                    value = relayedSecondaryUser,
                    isSecret = true
                )
            )
        } else {
            emptyList()
        }

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

        val resolvedInitialTitle = if (
            isFromAutofillSave &&
            isPinLike &&
            initialUsername.isBlank() &&
            initialTitle.isNotBlank() &&
            !initialTitle.contains("PIN", ignoreCase = true)
        ) {
            app.getString(R.string.pin_title_suffix, initialTitle)
        } else {
            initialTitle
        }

        val defaultAppendLabel = if (isPinLike || MaskedIdentifierHelper.isPinLikeSecret(relayedPassword)) {
            app.getString(R.string.tag_pin_code)
        } else {
            app.getString(R.string.tag_tx_password)
        }

        _uiState.value = GenerateAndSaveUiState(
            credentialId = credentialId,
            title = resolvedInitialTitle,
            initialPackageName = initialPackageName,
            packageName = initialPackageName,
            webDomain = initialWebDomain,
            username = initialUsername,
            password = startPassword,
            selectedMode = if (startCustomMode) 1 else 0,
            isCompactFillMode = isCompactFillMode,
            isFromAutofillSave = isFromAutofillSave,
            isPinLikeCapture = isPinLike,
            appendFieldLabel = defaultAppendLabel,
            extraFields = initialAutoExtras,
            capturedFields = capturedFields,
            extraFieldsLoaded = credentialId == 0L
        )

        viewModelScope.launch {
            if (credentialId > 0L) {
                val existing = app.repository.getCredentialById(credentialId)
                var decryptedPwd = ""
                var decryptedExtras: List<ExtraField> = emptyList()
                var loadedExtras = false
                if (existing != null && sessionDekAtLaunch != null) {
                    if (relayedPassword.isEmpty()) {
                        decryptedPwd = try {
                            app.repository.decryptPassword(existing, sessionDekAtLaunch)
                        } catch (e: Exception) {
                            AppLog.w("GenerateViewModel", "Failed to decrypt credential for editing", e)
                            ""
                        }
                    }
                    decryptedExtras = try {
                        loadedExtras = true
                        app.repository.decryptExtraFields(existing, sessionDekAtLaunch)
                    } catch (e: Exception) {
                        AppLog.w("GenerateViewModel", "Failed to decrypt extra fields for editing", e)
                        loadedExtras = false
                        emptyList()
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        matchedExisting = existing,
                        title = state.title.ifBlank { existing?.title.orEmpty() },
                        username = state.username.ifBlank { existing?.username.orEmpty() },
                        password = if (state.password.isEmpty() && decryptedPwd.isNotBlank()) decryptedPwd else state.password,
                        selectedMode = if (decryptedPwd.isNotBlank()) 1 else state.selectedMode,
                        extraFields = decryptedExtras,
                        extraFieldsLoaded = loadedExtras
                    )
                }
            } else {
                val siteExisting = app.repository.findSiteCredentialsForSave(
                    packageName = initialPackageName,
                    webDomain = initialWebDomain
                )
                // If the target App masked the username on submit (e.g. "A123***789"), recover the full
                // unmasked username from the user's existing vault records when uniquely unambiguous.
                val unmaskedResolvedUser = if (MaskedIdentifierHelper.isMaskedIdentifier(initialUsername)) {
                    MaskedIdentifierHelper.resolveBestUsername(
                        candidatesNewestFirst = listOf(initialUsername),
                        vaultUnmasked = siteExisting.map { it.username }
                    )
                } else {
                    initialUsername
                }
                val currentTitle = _uiState.value.title
                val existing = app.repository.findExistingCredential(
                    packageName = initialPackageName,
                    webDomain = initialWebDomain,
                    username = unmaskedResolvedUser,
                    proposedTitle = currentTitle
                )
                _uiState.update { state ->
                    // IMPORTANT: Never copy existing.username into a blank-username capture (prevents
                    // PIN-only screens from silently binding to and overwriting a login account).
                    state.copy(
                        username = unmaskedResolvedUser,
                        siteExistingCredentials = siteExisting,
                        appendTargetCredentialId = siteExisting.firstOrNull()?.id ?: 0L,
                        matchedExisting = existing,
                        title = if (state.title.isBlank()) existing?.title.orEmpty() else state.title
                    )
                }
            }
        }
    }

    private suspend fun refreshExistingMatch(
        packageName: String,
        webDomain: String,
        username: String,
        title: String
    ) {
        val siteExisting = app.repository.findSiteCredentialsForSave(packageName, webDomain)
        val existing = app.repository.findExistingCredential(
            packageName = packageName,
            webDomain = webDomain,
            username = username,
            proposedTitle = title
        )
        _uiState.update { state ->
            val validTargetId = if (siteExisting.any { it.id == state.appendTargetCredentialId }) {
                state.appendTargetCredentialId
            } else {
                siteExisting.firstOrNull()?.id ?: 0L
            }
            state.copy(
                siteExistingCredentials = siteExisting,
                appendTargetCredentialId = validTargetId,
                isAppendToExistingMode = state.isAppendToExistingMode && siteExisting.isNotEmpty(),
                matchedExisting = existing
            )
        }
    }

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value, hasUserModifiedFields = true) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(s.packageName, s.webDomain, s.username, value) }
        }
    }

    fun onApplyQuickTitleSuffix(tag: String) {
        val s = _uiState.value
        val base = s.title.trim().substringBefore(" - ").ifBlank {
            s.webDomain.ifBlank { s.packageName }
        }
        val nextTitle = if (base.isNotBlank()) "$base - $tag" else tag
        onTitleChange(nextTitle)
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, hasUserModifiedFields = true) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(s.packageName, s.webDomain, value, s.title) }
        }
    }

    fun onPackageNameChange(value: String) {
        _uiState.update { it.copy(packageName = value, hasUserModifiedFields = true) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(value, s.webDomain, s.username, s.title) }
        }
    }

    fun onWebDomainChange(value: String) {
        _uiState.update { it.copy(webDomain = value, hasUserModifiedFields = true) }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(s.packageName, value, s.username, s.title) }
        }
    }

    fun onAppPicked(appInfo: InstalledAppInfo) {
        _uiState.update { state ->
            val updatedTitle = if (state.title.isBlank() || state.title == state.initialPackageName) {
                appInfo.appName
            } else {
                state.title
            }
            state.copy(packageName = appInfo.packageName, title = updatedTitle, hasUserModifiedFields = true)
        }
        val s = _uiState.value
        if (s.credentialId == 0L) {
            viewModelScope.launch { refreshExistingMatch(appInfo.packageName, s.webDomain, s.username, s.title) }
        }
    }

    fun onSetAppendMode(appendToExisting: Boolean) {
        _uiState.update { it.copy(isAppendToExistingMode = appendToExisting && it.siteExistingCredentials.isNotEmpty()) }
    }

    fun onSelectAppendTarget(credentialId: Long) {
        _uiState.update { it.copy(appendTargetCredentialId = credentialId) }
    }

    fun onAppendFieldLabelChange(label: String) {
        _uiState.update { it.copy(appendFieldLabel = label, hasUserModifiedFields = true) }
    }

    fun addExtraField(label: String = "", value: String = "", isSecret: Boolean = true) {
        _uiState.update { state ->
            state.copy(
                extraFields = state.extraFields + ExtraField(
                    label = label,
                    value = value,
                    isSecret = isSecret
                ),
                hasUserModifiedFields = true
            )
        }
    }

    fun updateExtraField(index: Int, label: String, value: String) {
        _uiState.update { state ->
            if (index !in state.extraFields.indices) return@update state
            val updated = state.extraFields.toMutableList()
            updated[index] = updated[index].copy(label = label, value = value)
            state.copy(extraFields = updated, hasUserModifiedFields = true)
        }
    }

    fun removeExtraField(index: Int) {
        _uiState.update { state ->
            if (index !in state.extraFields.indices) return@update state
            val updated = state.extraFields.toMutableList().apply { removeAt(index) }
            state.copy(extraFields = updated, hasUserModifiedFields = true)
        }
    }

    fun assignCapturedToUsername(captured: CapturedFormField) {
        val previousUsername = _uiState.value.username.trim()
        onUsernameChange(captured.value)
        // If there was a different non-blank username previously (e.g. Bank ID vs User ID), offer it
        // seamlessly in extraFields if not already present!
        if (previousUsername.isNotEmpty() &&
            !previousUsername.equals(captured.value, ignoreCase = true) &&
            _uiState.value.extraFields.none { it.value.equals(previousUsername, ignoreCase = true) }
        ) {
            addExtraField(
                label = app.getString(R.string.tag_user_id),
                value = previousUsername,
                isSecret = true
            )
        }
    }

    fun assignCapturedToExtraField(captured: CapturedFormField) {
        val suggestedLabel = captured.labelHint.ifBlank { app.getString(R.string.tag_user_id) }
        if (_uiState.value.extraFields.any { it.value == captured.value }) return
        addExtraField(label = suggestedLabel, value = captured.value, isSecret = true)
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, hasUserModifiedFields = true) }
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
        defaultCustomTitle: String,
        forceCreateNew: Boolean = false
    ): SavedCredentialOutcome {
        val s = _uiState.value

        // Mode A: Append secret (e.g. Wallet PIN / Transaction Password) into an existing credential's
        // encrypted extra fields without modifying its login username or password!
        val appendTarget = s.appendTargetEntity
        if (s.isAppendToExistingMode && appendTarget != null) {
            val fieldLabel = s.appendFieldLabel.trim().ifBlank { app.getString(R.string.tag_pin_code) }
            app.repository.appendExtraFieldToCredential(
                credentialId = appendTarget.id,
                extraField = ExtraField(label = fieldLabel, value = s.password, isSecret = true),
                dek = dek
            )
            return SavedCredentialOutcome(
                isUpdate = true,
                isAppendedExtraField = true,
                finalTitle = appendTarget.title,
                username = appendTarget.username,
                password = s.password
            )
        }

        var resolvedTitle = s.title.trim().ifBlank {
            s.webDomain.ifBlank { s.packageName.ifBlank { defaultCustomTitle } }
        }
        // When user explicitly chose "Save as New Item" from the Overwrite Guard dialog and left the
        // exact same title on a blank-username / PIN entry, disambiguate the title automatically.
        if (forceCreateNew &&
            s.matchedExisting != null &&
            resolvedTitle.equals(s.matchedExisting.title.trim(), ignoreCase = true)
        ) {
            resolvedTitle = if (MaskedIdentifierHelper.isPinLikeSecret(s.password, s.isPinLikeCapture)) {
                app.getString(R.string.pin_title_suffix, resolvedTitle)
            } else {
                "$resolvedTitle (2)"
            }
        }

        // Resolve encrypted extra fields safely so updating a login password never wipes existing extra fields.
        val cleanedEditorExtras = s.extraFields.filter { it.value.isNotBlank() }
        val extraFieldsToPersist: List<ExtraField>? = when {
            s.credentialId > 0L -> if (s.extraFieldsLoaded) cleanedEditorExtras else null
            forceCreateNew -> cleanedEditorExtras.takeIf { it.isNotEmpty() }
            s.matchedExisting != null && cleanedEditorExtras.isNotEmpty() -> {
                val priorExtras = try {
                    app.repository.decryptExtraFields(s.matchedExisting, dek)
                } catch (_: Exception) {
                    emptyList()
                }
                ExtraFieldsCodec.mergeFields(priorExtras, cleanedEditorExtras)
            }
            cleanedEditorExtras.isNotEmpty() -> cleanedEditorExtras
            else -> null
        }

        if (s.username.isNotBlank() && !MaskedIdentifierHelper.isMaskedIdentifier(s.username)) {
            InMemorySecretRelay.recordRecentUnmaskedInputs(
                packageName = s.packageName,
                webDomain = s.webDomain,
                values = listOf(s.username)
            )
        }

        val saveResult: CredentialRepository.SaveOrUpdateResult = app.repository.saveOrUpdateCredential(
            id = if (forceCreateNew) 0L else s.credentialId,
            title = resolvedTitle,
            packageName = s.packageName,
            webDomain = s.webDomain,
            username = s.username,
            plainPassword = s.password,
            dek = dek,
            defaultUntitledTitle = defaultUntitledTitle,
            forceCreateNew = forceCreateNew,
            extraFields = extraFieldsToPersist
        )
        return SavedCredentialOutcome(
            isUpdate = saveResult.isUpdate,
            isAppendedExtraField = false,
            finalTitle = resolvedTitle,
            username = s.username,
            password = s.password
        )
    }

    override fun onCleared() {
        _uiState.update {
            it.copy(
                password = "",
                extraFields = emptyList(),
                capturedFields = emptyList()
            )
        }
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
