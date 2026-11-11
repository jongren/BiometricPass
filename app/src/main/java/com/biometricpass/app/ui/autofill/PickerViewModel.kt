package com.biometricpass.app.ui.autofill

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.autofill.AssistStructureParser
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.util.AppSignatureVerifier
import com.biometricpass.app.util.AppSignatures
import com.biometricpass.app.util.InstalledAppHelper
import com.biometricpass.app.util.PublicSuffixList
import com.biometricpass.app.util.WebDomainMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.crypto.SecretKey

data class PickerTargetConfig(
    val targetPackage: String = "",
    val targetWebDomain: String = ""
)

data class PickerUiState(
    val searchQuery: String = "",
    val targetPackage: String = "",
    val targetWebDomain: String = "",
    val targetAppLabel: String = "",
    val isBrowser: Boolean = false,
    val isNonBrowserWebView: Boolean = false,
    val signatureMismatchItemIds: Set<Long> = emptySet(),
    val suggestedItems: List<CredentialEntity> = emptyList(),
    val otherItems: List<CredentialEntity> = emptyList()
) {
    val isEmpty: Boolean
        get() = suggestedItems.isEmpty() && otherItems.isEmpty()

    val hasSignatureMismatch: Boolean
        get() = signatureMismatchItemIds.isNotEmpty()
}

/**
 * ViewModel backing [AuthAndPickActivity]'s picker bottom sheet.
 *
 * Performs search filtering (including cached App labels) and target-match grouping off the main
 * thread on [Dispatchers.Default], keeping typing responsive even on large vaults.
 */
class PickerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BiometricPassApp = application as BiometricPassApp

    private val _unlockedDekFlow = MutableStateFlow<SecretKey?>(null)
    val unlockedDekFlow: StateFlow<SecretKey?> = _unlockedDekFlow.asStateFlow()

    val unlockedDek: SecretKey?
        get() = _unlockedDekFlow.value

    private val searchQueryFlow = MutableStateFlow("")
    private val targetConfigFlow = MutableStateFlow(PickerTargetConfig())

    val uiState: StateFlow<PickerUiState> = combine(
        app.repository.observeAllCredentials(),
        searchQueryFlow,
        targetConfigFlow
    ) { allItems, query, target ->
        val isBrowser = AssistStructureParser.isBrowserPackage(target.targetPackage)
        val isNonBrowserWebView =
            target.targetPackage.isNotBlank() && !isBrowser && target.targetWebDomain.isNotBlank()
        val targetAppLabel = if (target.targetPackage.isBlank()) {
            ""
        } else {
            InstalledAppHelper.getAppLabel(app, target.targetPackage) ?: target.targetPackage
        }

        val q = query.trim().lowercase()
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

        val liveTargetSignatures = if (!isBrowser && target.targetPackage.isNotBlank()) {
            InstalledAppHelper.getAppSignatures(app, target.targetPackage)
        } else {
            null
        }
        val mismatchIds = if (liveTargetSignatures != null) {
            allItems.asSequence()
                .filter {
                    it.packageName == target.targetPackage &&
                        AppSignatureVerifier.hasSignatureMismatch(it.appSignatureSha256, liveTargetSignatures)
                }
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }

        val psl = app.publicSuffixList
        val (suggested, others) = filtered.partition { item ->
            credentialMatchesTarget(
                item = item,
                targetPackage = target.targetPackage,
                targetWebDomain = target.targetWebDomain,
                isBrowser = isBrowser,
                psl = psl,
                liveTargetSignatures = liveTargetSignatures
            )
        }

        PickerUiState(
            searchQuery = query,
            targetPackage = target.targetPackage,
            targetWebDomain = target.targetWebDomain,
            targetAppLabel = targetAppLabel,
            isBrowser = isBrowser,
            isNonBrowserWebView = isNonBrowserWebView,
            signatureMismatchItemIds = mismatchIds,
            suggestedItems = suggested,
            otherItems = others
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PickerUiState()
    )

    fun setTargetContext(targetPackage: String, targetWebDomain: String) {
        targetConfigFlow.value = PickerTargetConfig(
            targetPackage = targetPackage,
            targetWebDomain = targetWebDomain
        )
    }

    fun onUnlocked(dek: SecretKey) {
        _unlockedDekFlow.value = dek
    }

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    suspend fun getCredentialById(id: Long): CredentialEntity? =
        app.repository.getCredentialById(id)

    fun decryptPassword(entity: CredentialEntity, dek: SecretKey): String =
        app.repository.decryptPassword(entity, dek)

    fun isSignatureTrustedForTarget(entity: CredentialEntity, targetPackage: String): Boolean {
        val cleanPkg = targetPackage.trim()
        if (cleanPkg.isEmpty() || AssistStructureParser.isBrowserPackage(cleanPkg)) return true
        val liveSig = InstalledAppHelper.getAppSignatures(app, cleanPkg)
        return AppSignatureVerifier.matchesPinnedSignature(entity.appSignatureSha256, liveSig)
    }

    override fun onCleared() {
        _unlockedDekFlow.value = null
        super.onCleared()
    }

    companion object {
        /**
         * True when [item] was saved for the requesting target: the same App with a trusted developer
         * signature (outside browsers), or the same website / a parent or sub-domain within the same
         * registrable domain (in browsers).
         */
        internal fun credentialMatchesTarget(
            item: CredentialEntity,
            targetPackage: String,
            targetWebDomain: String,
            isBrowser: Boolean,
            psl: PublicSuffixList,
            liveTargetSignatures: AppSignatures? = null
        ): Boolean {
            if (isBrowser) return WebDomainMatcher.matches(item.webDomain, targetWebDomain, psl)
            return targetPackage.isNotBlank() &&
                item.packageName == targetPackage &&
                AppSignatureVerifier.matchesPinnedSignature(item.appSignatureSha256, liveTargetSignatures)
        }
    }
}
