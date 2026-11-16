package com.biometricpass.app.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.RegexValidator
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.inline.InlinePresentationSpec
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.R
import com.biometricpass.app.crypto.InMemorySecretRelay
import com.biometricpass.app.data.repository.CredentialMatchRules
import com.biometricpass.app.service.ActiveAppContextTracker
import com.biometricpass.app.service.QuickActionNotificationHelper
import com.biometricpass.app.ui.autofill.AuthAndPickActivity
import com.biometricpass.app.ui.generate.GenerateAndSaveActivity
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.MaskedIdentifierHelper
import com.biometricpass.app.util.PendingIntentBalHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Native AutofillService following official Android `AutofillService` best practices:
 * - Only monitors real login / password forms (`passwordId != null`) or explicit two-step login
 *   username screens that already have saved credentials (plus explicit `FLAG_MANUAL_REQUEST`).
 * - Strictly attaches [SaveInfo] ONLY when an explicit password field (`passwordId != null`) exists
 *   and validates that the password field is non-blank, preventing false save prompts in chat apps
 *   (e.g. LINE) or news/content webpages.
 */
class PassAutofillService : AutofillService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestCodeGenerator = AtomicInteger(1000)

    override fun onConnected() {
        super.onConnected()
        QuickActionNotificationHelper.showIfEnabled(this)
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val fillContexts = request.fillContexts
        val latestStructure = fillContexts.lastOrNull()?.structure
        if (latestStructure == null) {
            callback.onSuccess(null)
            return
        }

        val isManualRequest = (request.flags and FillRequest.FLAG_MANUAL_REQUEST) != 0
        val parsed = AssistStructureParser.parse(
            structure = latestStructure,
            ownPackageName = packageName,
            isManualRequest = isManualRequest
        )

        if (parsed.packageName.isNotBlank()) {
            ActiveAppContextTracker.updateActiveApp(
                packageName = parsed.packageName,
                webDomain = parsed.webDomain,
                ownPackageName = packageName
            )
        }

        // Cache any unmasked account / User ID text visible before form submission so if the target
        // App masks the field with '*' inside its Login button click handler, onSaveRequest can recover it.
        val unmaskedVisibleInputs = buildList {
            if (parsed.usernameText.isNotBlank()) add(parsed.usernameText)
            if (parsed.secondaryUsernameText.isNotBlank()) add(parsed.secondaryUsernameText)
            for (cf in parsed.capturedFields) {
                if (!cf.isPasswordMask && cf.value.isNotBlank()) add(cf.value)
            }
        }
        if (unmaskedVisibleInputs.isNotEmpty()) {
            InMemorySecretRelay.recordRecentUnmaskedInputs(
                packageName = parsed.packageName,
                webDomain = parsed.webDomain,
                values = unmaskedVisibleInputs
            )
        }

        // Ignore screens that have neither a password field nor an explicit login username field
        // (unless the user explicitly invoked manual autofill via long-press -> Autofill).
        if (!parsed.hasCredentialFields && !(isManualRequest && parsed.focusedInputId != null)) {
            callback.onSuccess(null)
            return
        }

        val app = application as BiometricPassApp
        val inlineSpecs: List<InlinePresentationSpec> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty()
        } else {
            emptyList()
        }
        val maxInlineSuggestions: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0
        } else {
            0
        }

        // Bind Autofill datasets ONLY to actual credential fields, never to search bars / chat inputs.
        // On confirmed password forms (e.g. 3-field Bank logins: ID + User ID + Password), also attach
        // companion editable inputs (up to 6) so focusing the User ID field shows the Vault Picker chip.
        val targetIds = buildList {
            parsed.usernameId?.let { add(it) }
            parsed.secondaryUsernameId?.let { if (!contains(it)) add(it) }
            parsed.passwordId?.let { if (!contains(it)) add(it) }
            for (pwdId in parsed.allPasswordIds) {
                if (!contains(pwdId)) add(pwdId)
            }
            if (parsed.hasPasswordField || parsed.secondaryUsernameId != null) {
                for (editId in parsed.allEditableIds.take(6)) {
                    if (!contains(editId)) add(editId)
                }
            }
            if (isManualRequest) {
                parsed.focusedInputId?.let { if (!contains(it)) add(it) }
            }
        }

        if (targetIds.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        serviceScope.launch {
            try {
                val rawMatched = app.repository.findMatchingForAutofill(
                    packageName = parsed.packageName,
                    webDomain = parsed.webDomain
                )
                val preferPinRecord = parsed.isNumericPasswordFocus && parsed.usernameId == null
                val matchedCredentials = if (preferPinRecord) {
                    rawMatched.sortedByDescending {
                        CredentialMatchRules.isSecondarySecretTitle(it.title) || it.username.isBlank()
                    }
                } else {
                    rawMatched.sortedByDescending { it.username.isNotBlank() }
                }
                val hasSignatureBlocked = matchedCredentials.isEmpty() &&
                    app.repository.hasSignatureBlockedCredentials(parsed.packageName)
                val totalVaultCount = if (matchedCredentials.isEmpty()) {
                    app.repository.countCredentials()
                } else {
                    matchedCredentials.size
                }

                // On a screen with NO password field (e.g. standalone email box): do not show chips
                // unless we already have saved credentials for this app/domain (2-step login step 1).
                // On Android 12+ (API 31+), attach FLAG_DELAY_SAVE so 2-step login step 1 preserves
                // the typed username into step 2 without ever showing a premature save prompt.
                if (!parsed.hasPasswordField &&
                    parsed.secondaryUsernameId == null &&
                    !isManualRequest &&
                    matchedCredentials.isEmpty() &&
                    !hasSignatureBlocked
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && parsed.usernameId != null) {
                        val delaySave = SaveInfo.Builder(
                            SaveInfo.SAVE_DATA_TYPE_USERNAME,
                            arrayOf(parsed.usernameId)
                        )
                            .setFlags(SaveInfo.FLAG_DELAY_SAVE)
                            .build()
                        callback.onSuccess(FillResponse.Builder().setSaveInfo(delaySave).build())
                    } else {
                        callback.onSuccess(null)
                    }
                    return@launch
                }

                val responseBuilder = FillResponse.Builder()
                var specIndex = 0
                var addedAnyDataset = false

                fun nextInlineSpec(): InlinePresentationSpec? {
                    if (inlineSpecs.isEmpty() || specIndex >= maxInlineSuggestions) return null
                    val spec = if (specIndex < inlineSpecs.size) inlineSpecs[specIndex] else inlineSpecs.last()
                    specIndex++
                    return spec
                }

                val useApi29Slots = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                val sessionToken = if (useApi29Slots) AutofillSlotProvider.newSessionToken() else 0
                val slotCount = if (useApi29Slots) AutofillSlotProvider.MAX_SLOTS_API29 else 1

                /**
                 * Adds one authenticated Dataset per target [AutofillId] (sharing the category's
                 * single [InlinePresentationSpec]).
                 *
                 * WHY PER-FIELD DATASETS ARE ESSENTIAL:
                 * Android's `AutofillService` only runs `onFillRequest` ONCE for a multi-field form.
                 * When the user later moves cursor focus between Field 1 (`身分證字號`), Field 2
                 * (`使用者代號`), and Field 3 (`使用者密碼`), Android does NOT update `AssistStructure`
                 * focus; instead it displays whichever `Dataset` in `FillResponse` matches the
                 * currently focused `AutofillId`. Creating a dedicated `Dataset` per `fieldId`
                 * guarantees the launched `PendingIntent` always knows the exact `fieldId` the
                 * user's cursor is currently inside!
                 */
                fun addPerFieldSlotDatasets(
                    category: String,
                    title: String,
                    subtitle: String,
                    fieldIds: List<AutofillId>,
                    filterPattern: Pattern?,
                    intentBuilder: (AutofillId) -> Intent
                ) {
                    val inlineSpec = nextInlineSpec()
                    for (fieldId in fieldIds) {
                        for (slotIndex in 0 until slotCount) {
                            val reqCode = requestCodeGenerator.incrementAndGet()
                            val pendingIntent = PendingIntentBalHelper.getActivityAllowingBal(
                                context = this@PassAutofillService,
                                requestCode = reqCode,
                                intent = intentBuilder(fieldId),
                                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                            )
                            val slotUri = if (useApi29Slots) {
                                AutofillSlotProvider.buildSlotUri(
                                    packageName = packageName,
                                    sessionToken = sessionToken,
                                    category = category,
                                    slotIndex = slotIndex
                                )
                            } else {
                                null
                            }
                            val dataset = AutofillDatasetFactory.buildAuthenticatedTriggerDataset(
                                context = this@PassAutofillService,
                                title = title,
                                subtitle = subtitle,
                                targetIds = listOf(fieldId),
                                filterPattern = filterPattern,
                                pendingIntent = pendingIntent,
                                inlineSpec = if (slotIndex == 0) inlineSpec else null,
                                reqCode = reqCode,
                                slotUri = slotUri
                            )
                            responseBuilder.addDataset(dataset)
                            addedAnyDataset = true
                        }
                    }
                }

                // 1. Most recently updated matched credential (full-form fill across all matched fields)
                for (cred in matchedCredentials.take(1)) {
                    val chipTitle = if (cred.username.isNotBlank()) {
                        getString(R.string.autofill_chip_fill_user, cred.username)
                    } else {
                        getString(R.string.autofill_chip_fill_pass, cred.title)
                    }
                    addPerFieldSlotDatasets(
                        category = AutofillSlotProvider.CATEGORY_CREDENTIAL,
                        title = chipTitle,
                        subtitle = getString(R.string.autofill_chip_fill_subtitle, cred.title),
                        fieldIds = targetIds,
                        filterPattern = AutofillDatasetFactory.MATCH_ALL_PATTERN
                    ) { activeFieldId ->
                        AuthAndPickActivity.createIntent(
                            context = this@PassAutofillService,
                            credentialId = cred.id,
                            targetPackage = parsed.packageName,
                            targetWebDomain = parsed.webDomain,
                            usernameAutofillId = parsed.usernameId,
                            secondaryUsernameAutofillId = parsed.secondaryUsernameId,
                            passwordAutofillId = parsed.passwordId,
                            focusedAutofillId = activeFieldId,
                            allEditableAutofillIds = ArrayList(targetIds),
                            slotSessionToken = sessionToken,
                            slotCategory = AutofillSlotProvider.CATEGORY_CREDENTIAL
                        )
                    }
                }

                // 2. Vault picker chip when multiple accounts match, when a matched account carries
                //    encrypted extra fields (e.g. Bank User ID / Wallet PIN for single-field fill),
                //    when no direct match exists yet in a non-empty vault, or on signature mismatch.
                val hasExtraFieldsMatch = matchedCredentials.any { it.hasExtraFields }
                val showVaultPicker = matchedCredentials.size > 1 ||
                    hasExtraFieldsMatch ||
                    hasSignatureBlocked ||
                    (matchedCredentials.isEmpty() && totalVaultCount > 0)
                if (showVaultPicker) {
                    val pickerSubtitle = when {
                        matchedCredentials.size > 1 ->
                            getString(R.string.autofill_chip_pick_subtitle, matchedCredentials.size)
                        hasExtraFieldsMatch ->
                            getString(R.string.autofill_chip_pick_extra_fields_subtitle)
                        matchedCredentials.isEmpty() && totalVaultCount > 0 && !hasSignatureBlocked ->
                            getString(R.string.autofill_chip_pick_subtitle, totalVaultCount)
                        else -> getString(R.string.picker_sheet_title)
                    }
                    addPerFieldSlotDatasets(
                        category = AutofillSlotProvider.CATEGORY_PICKER,
                        title = getString(R.string.autofill_chip_pick_vault),
                        subtitle = pickerSubtitle,
                        fieldIds = targetIds,
                        filterPattern = AutofillDatasetFactory.MATCH_ALL_PATTERN
                    ) { activeFieldId ->
                        AuthAndPickActivity.createIntent(
                            context = this@PassAutofillService,
                            credentialId = 0L,
                            targetPackage = parsed.packageName,
                            targetWebDomain = parsed.webDomain,
                            usernameAutofillId = parsed.usernameId,
                            secondaryUsernameAutofillId = parsed.secondaryUsernameId,
                            passwordAutofillId = parsed.passwordId,
                            focusedAutofillId = activeFieldId,
                            allEditableAutofillIds = ArrayList(targetIds),
                            slotSessionToken = sessionToken,
                            slotCategory = AutofillSlotProvider.CATEGORY_PICKER
                        )
                    }
                }

                // 3. Password generator chip when a password field exists (or manual request)
                if (parsed.hasPasswordField || isManualRequest) {
                    val genTargetIds = if (matchedCredentials.isEmpty()) {
                        targetIds
                    } else {
                        parsed.allPasswordIds.ifEmpty {
                            targetIds.filter { it != parsed.usernameId && it != parsed.secondaryUsernameId }
                                .ifEmpty { targetIds }
                        }
                    }
                    val genFilterPattern = if (matchedCredentials.isEmpty()) {
                        AutofillDatasetFactory.MATCH_ALL_PATTERN
                    } else {
                        null
                    }
                    addPerFieldSlotDatasets(
                        category = AutofillSlotProvider.CATEGORY_GENERATOR,
                        title = getString(R.string.autofill_chip_generate_new),
                        subtitle = getString(R.string.autofill_chip_generate_subtitle),
                        fieldIds = genTargetIds,
                        filterPattern = genFilterPattern
                    ) { activeFieldId ->
                        GenerateAndSaveActivity.createIntent(
                            context = this@PassAutofillService,
                            targetPackage = parsed.packageName,
                            targetWebDomain = parsed.webDomain,
                            initialUsername = parsed.usernameText,
                            usernameAutofillId = parsed.usernameId,
                            passwordAutofillId = parsed.passwordId ?: activeFieldId,
                            focusedAutofillId = activeFieldId,
                            slotSessionToken = sessionToken,
                            slotCategory = AutofillSlotProvider.CATEGORY_GENERATOR
                        )
                    }
                }

                // 4. Register SaveInfo ONLY when an explicit password field exists
                val pwdId = parsed.passwordId
                if (pwdId != null) {
                    val previousUsernameId = if (parsed.usernameId == null && fillContexts.size > 1) {
                        fillContexts.dropLast(1).asReversed().firstNotNullOfOrNull { ctx ->
                            ctx.structure?.let { AssistStructureParser.parse(it, packageName).usernameId }
                        }
                    } else {
                        null
                    }
                    val effectiveUsernameId = parsed.usernameId ?: previousUsernameId

                    val saveType = if (effectiveUsernameId != null) {
                        SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD
                    } else {
                        SaveInfo.SAVE_DATA_TYPE_PASSWORD
                    }

                    val saveBuilder = SaveInfo.Builder(saveType, arrayOf(pwdId))
                        .setValidator(RegexValidator(pwdId, AutofillDatasetFactory.NON_BLANK_PATTERN))
                        .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)

                    val optionalIds = buildList {
                        if (effectiveUsernameId != null && effectiveUsernameId != pwdId) {
                            add(effectiveUsernameId)
                        }
                        for (editId in parsed.allEditableIds.take(6)) {
                            if (editId != pwdId && !contains(editId)) {
                                add(editId)
                            }
                        }
                    }
                    if (optionalIds.isNotEmpty()) {
                        saveBuilder.setOptionalIds(optionalIds.toTypedArray())
                    }

                    responseBuilder.setSaveInfo(saveBuilder.build())
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && parsed.usernameId != null) {
                    val delaySave = SaveInfo.Builder(
                        SaveInfo.SAVE_DATA_TYPE_USERNAME,
                        arrayOf(parsed.usernameId)
                    )
                        .setFlags(SaveInfo.FLAG_DELAY_SAVE)
                        .build()
                    responseBuilder.setSaveInfo(delaySave)
                } else if (!addedAnyDataset) {
                    callback.onSuccess(null)
                    return@launch
                }

                callback.onSuccess(responseBuilder.build())
            } catch (e: Exception) {
                AppLog.w("PassAutofillService", "onFillRequest failed unexpectedly", e)
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val contexts = request.fillContexts
        if (contexts.isEmpty()) {
            callback.onSuccess()
            return
        }

        // Parse from latest context backwards so 2-step sign-in flows preserve step 1's username,
        // while preferring unmasked account values if the target App masked the field with '*' on submit.
        var resolvedPackage = ""
        var resolvedDomain = ""
        var resolvedPassword = ""
        var resolvedSecondaryLabel = ""
        var anyNumericPasswordField = false
        val usernameCandidatesNewestFirst = ArrayList<String>()
        val secondaryUsernameCandidatesNewestFirst = ArrayList<String>()
        val rawCapturedFields = ArrayList<CapturedFormField>()

        for (ctx in contexts.asReversed()) {
            val structure = ctx.structure ?: continue
            val parsed = AssistStructureParser.parse(structure, packageName)
            if (resolvedPackage.isBlank() && parsed.packageName.isNotBlank()) {
                resolvedPackage = parsed.packageName
            }
            if (resolvedDomain.isBlank() && parsed.webDomain.isNotBlank()) {
                resolvedDomain = parsed.webDomain
            }
            if (resolvedPassword.isBlank() && parsed.passwordText.isNotBlank()) {
                resolvedPassword = parsed.passwordText
                anyNumericPasswordField = parsed.isNumericPasswordFocus ||
                    parsed.capturedFields.any { it.isPasswordMask && it.isNumericPassword }
            }
            if (parsed.usernameText.isNotBlank()) {
                usernameCandidatesNewestFirst.add(parsed.usernameText)
            }
            if (parsed.secondaryUsernameText.isNotBlank()) {
                secondaryUsernameCandidatesNewestFirst.add(parsed.secondaryUsernameText)
                if (resolvedSecondaryLabel.isBlank() && parsed.secondaryUsernameLabel.isNotBlank()) {
                    resolvedSecondaryLabel = parsed.secondaryUsernameLabel
                }
            }
            for (field in parsed.capturedFields) {
                rawCapturedFields.add(field)
            }
        }

        if (resolvedPassword.isBlank()) {
            callback.onSuccess()
            return
        }

        val cachedUnmaskedInputs = InMemorySecretRelay.getRecentUnmaskedInputs(
            packageName = resolvedPackage,
            webDomain = resolvedDomain
        )
        val resolvedUsername = MaskedIdentifierHelper.resolveBestUsername(
            candidatesNewestFirst = usernameCandidatesNewestFirst,
            cachedUnmasked = cachedUnmaskedInputs
        )
        val resolvedSecondaryUsername = MaskedIdentifierHelper.resolveBestUsername(
            candidatesNewestFirst = secondaryUsernameCandidatesNewestFirst,
            cachedUnmasked = cachedUnmaskedInputs.filter { !it.equals(resolvedUsername, ignoreCase = true) }
        )

        // Deduplicate captured non-password fields, upgrading any masked field value when an unmasked
        // match exists in an earlier FillContext or in the recent RAM cache.
        val mergedCapturedFields = ArrayList<CapturedFormField>()
        val unmaskedPool = (rawCapturedFields.filter { !it.isPasswordMask }.map { it.value } + cachedUnmaskedInputs)
            .filter { !MaskedIdentifierHelper.isMaskedIdentifier(it) }
            .distinct()

        for (field in rawCapturedFields) {
            if (field.isPasswordMask) continue
            val upgradedValue = if (MaskedIdentifierHelper.isMaskedIdentifier(field.value)) {
                unmaskedPool.firstOrNull { MaskedIdentifierHelper.matchesUnmasked(field.value, it) }
                    ?: field.value
            } else {
                field.value
            }
            if (mergedCapturedFields.none { it.value.equals(upgradedValue, ignoreCase = true) }) {
                mergedCapturedFields.add(field.copy(value = upgradedValue))
            }
        }

        val isPinLike = resolvedUsername.isBlank() &&
            resolvedSecondaryUsername.isBlank() &&
            MaskedIdentifierHelper.isPinLikeSecret(resolvedPassword, anyNumericPasswordField)

        // Relay captured password and extra form fields strictly in process memory (never in a PendingIntent extra).
        val saveToken = InMemorySecretRelay.putPendingSavePayload(
            InMemorySecretRelay.PendingSavePayload(
                password = resolvedPassword,
                secondaryUsername = resolvedSecondaryUsername,
                secondaryLabel = resolvedSecondaryLabel,
                isPinLike = isPinLike,
                capturedFields = mergedCapturedFields
            )
        )
        val saveIntent = GenerateAndSaveActivity.createIntent(
            context = this,
            targetPackage = resolvedPackage,
            targetWebDomain = resolvedDomain,
            initialUsername = resolvedUsername,
            saveSecretToken = saveToken,
            isAutofillSave = true
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntentBalHelper.getActivityAllowingBal(
            context = this,
            requestCode = requestCodeGenerator.incrementAndGet(),
            intent = saveIntent,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        callback.onSuccess(pendingIntent.intentSender)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
