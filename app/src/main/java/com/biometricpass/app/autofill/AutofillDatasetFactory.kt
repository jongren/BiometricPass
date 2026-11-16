package com.biometricpass.app.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.IntentCompat
import com.biometricpass.app.R
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.data.model.ExtraField
import com.biometricpass.app.data.repository.CredentialMatchRules
import com.biometricpass.app.service.ActiveAppContextTracker
import com.biometricpass.app.ui.main.MainActivity
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.PendingIntentBalHelper
import java.util.regex.Pattern

/**
 * Typed representation of an Autofill or `ACTION_PROCESS_TEXT` target request passed to
 * `AuthAndPickActivity` or `GenerateAndSaveActivity`.
 */
data class AutofillTargetRequest(
    val credentialId: Long = 0L,
    val targetPackage: String = "",
    val targetWebDomain: String = "",
    val liveUsernameText: String = "",
    val usernameAutofillId: AutofillId? = null,
    val secondaryUsernameAutofillId: AutofillId? = null,
    val passwordAutofillId: AutofillId? = null,
    val focusedAutofillId: AutofillId? = null,
    val allEditableAutofillIds: List<AutofillId> = emptyList(),
    val isProcessText: Boolean = false,
    val isProcessTextReadOnly: Boolean = false,
    val slotSessionToken: Int = 0,
    val slotCategory: String = ""
) {
    val hasAutofillIds: Boolean
        get() = usernameAutofillId != null ||
            secondaryUsernameAutofillId != null ||
            passwordAutofillId != null ||
            focusedAutofillId != null ||
            allEditableAutofillIds.isNotEmpty()

    val hasDirectAutofillFields: Boolean
        get() = usernameAutofillId != null ||
            secondaryUsernameAutofillId != null ||
            passwordAutofillId != null ||
            focusedAutofillId != null

    val canReplaceProcessText: Boolean
        get() = isProcessText && !isProcessTextReadOnly

    val canDirectFill: Boolean
        get() = hasAutofillIds || canReplaceProcessText

    fun writeToIntent(intent: Intent) {
        intent.apply {
            putExtra(EXTRA_CREDENTIAL_ID, credentialId)
            putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            putExtra(EXTRA_TARGET_WEB_DOMAIN, targetWebDomain)
            putExtra(EXTRA_USERNAME_AUTOFILL_ID, usernameAutofillId)
            putExtra(EXTRA_SECONDARY_USERNAME_AUTOFILL_ID, secondaryUsernameAutofillId)
            putExtra(EXTRA_PASSWORD_AUTOFILL_ID, passwordAutofillId)
            putExtra(EXTRA_FOCUSED_AUTOFILL_ID, focusedAutofillId)
            if (allEditableAutofillIds.isNotEmpty()) {
                putParcelableArrayListExtra(
                    EXTRA_ALL_EDITABLE_AUTOFILL_IDS,
                    ArrayList(allEditableAutofillIds)
                )
            }
            putExtra(EXTRA_IS_PROCESS_TEXT, isProcessText)
            putExtra(EXTRA_PROCESS_TEXT_READONLY, isProcessTextReadOnly)
            putExtra(EXTRA_SLOT_SESSION_TOKEN, slotSessionToken)
            putExtra(EXTRA_SLOT_CATEGORY, slotCategory)
        }
    }

    companion object {
        const val EXTRA_CREDENTIAL_ID = "extra_credential_id"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_TARGET_WEB_DOMAIN = "extra_target_web_domain"
        const val EXTRA_USERNAME_AUTOFILL_ID = "extra_username_autofill_id"
        const val EXTRA_SECONDARY_USERNAME_AUTOFILL_ID = "extra_secondary_username_autofill_id"
        const val EXTRA_PASSWORD_AUTOFILL_ID = "extra_password_autofill_id"
        const val EXTRA_FOCUSED_AUTOFILL_ID = "extra_focused_autofill_id"
        const val EXTRA_ALL_EDITABLE_AUTOFILL_IDS = "extra_all_editable_autofill_ids"
        const val EXTRA_IS_PROCESS_TEXT = "extra_is_process_text"
        const val EXTRA_PROCESS_TEXT_READONLY = "extra_process_text_readonly"
        const val EXTRA_SLOT_SESSION_TOKEN = "extra_slot_session_token"
        const val EXTRA_SLOT_CATEGORY = "extra_slot_category"

        /**
         * Extracts the live `AssistStructure` (when present) and Intent extras into a single
         * [AutofillTargetRequest]. Never falls back to [ActiveAppContextTracker] for `PROCESS_TEXT`
         * or when [allowActiveAppFallback] is `false` (e.g. editing an existing vault item).
         */
        fun fromIntent(
            intent: Intent,
            ownPackageName: String,
            allowActiveAppFallback: Boolean = true
        ): AutofillTargetRequest {
            val credentialId = intent.getLongExtra(EXTRA_CREDENTIAL_ID, 0L)
            val isProcessText = intent.getBooleanExtra(EXTRA_IS_PROCESS_TEXT, false)
            val useFallback = allowActiveAppFallback && !isProcessText && credentialId == 0L

            val liveStructure = IntentCompat.getParcelableExtra(
                intent,
                AutofillManager.EXTRA_ASSIST_STRUCTURE,
                AssistStructure::class.java
            )
            val liveParsed = liveStructure?.let { AssistStructureParser.parse(it, ownPackageName) }

            val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
                .ifBlank { liveParsed?.packageName.orEmpty() }
                .ifBlank { if (useFallback) ActiveAppContextTracker.lastPackageName else "" }
            val targetWebDomain = intent.getStringExtra(EXTRA_TARGET_WEB_DOMAIN).orEmpty()
                .ifBlank { liveParsed?.webDomain.orEmpty() }
                .ifBlank { if (useFallback) ActiveAppContextTracker.lastWebDomain else "" }

            val usernameId = liveParsed?.usernameId ?: IntentCompat.getParcelableExtra(
                intent,
                EXTRA_USERNAME_AUTOFILL_ID,
                AutofillId::class.java
            )
            val secondaryUsernameId = liveParsed?.secondaryUsernameId ?: IntentCompat.getParcelableExtra(
                intent,
                EXTRA_SECONDARY_USERNAME_AUTOFILL_ID,
                AutofillId::class.java
            )
            val passwordId = liveParsed?.passwordId ?: IntentCompat.getParcelableExtra(
                intent,
                EXTRA_PASSWORD_AUTOFILL_ID,
                AutofillId::class.java
            )
            // CRITICAL: Android's EXTRA_ASSIST_STRUCTURE carries the initial snapshot from when
            // onFillRequest first ran, so `liveParsed.focusedInputId` is STALE if the user moved
            // focus to another box before tapping a per-field Picker/Generator chip! Always honor
            // EXTRA_FOCUSED_AUTOFILL_ID (set per-field on each Dataset's PendingIntent) first!
            val focusedId = IntentCompat.getParcelableExtra(
                intent,
                EXTRA_FOCUSED_AUTOFILL_ID,
                AutofillId::class.java
            ) ?: liveParsed?.focusedInputId
            val editableIds = liveParsed?.allEditableIds?.takeIf { it.isNotEmpty() }
                ?: IntentCompat.getParcelableArrayListExtra(
                    intent,
                    EXTRA_ALL_EDITABLE_AUTOFILL_IDS,
                    AutofillId::class.java
                ).orEmpty()

            return AutofillTargetRequest(
                credentialId = credentialId,
                targetPackage = targetPackage,
                targetWebDomain = targetWebDomain,
                liveUsernameText = liveParsed?.usernameText.orEmpty(),
                usernameAutofillId = usernameId,
                secondaryUsernameAutofillId = secondaryUsernameId,
                passwordAutofillId = passwordId,
                focusedAutofillId = focusedId,
                allEditableAutofillIds = editableIds,
                isProcessText = isProcessText,
                isProcessTextReadOnly = intent.getBooleanExtra(EXTRA_PROCESS_TEXT_READONLY, false),
                slotSessionToken = intent.getIntExtra(EXTRA_SLOT_SESSION_TOKEN, 0),
                slotCategory = intent.getStringExtra(EXTRA_SLOT_CATEGORY).orEmpty()
            )
        }
    }
}

/**
 * Centralized factory for building Autofill [Dataset]s and authentication result [Intent]s.
 *
 * Encapsulates critical Android 10 (API 29) vs Android 11+ (API 30+) invariants in one place:
 * - Returned authentication result datasets MUST NEVER call `setAuthentication(...)` (otherwise
 *   `Session.autoFill()` loops authentication infinitely).
 * - On Android 10 (API 29), `Session.setAuthenticationResultLocked` permanently overwrites the
 *   triggered dataset slot in `FillResponse`; we return `RemoteViews(packageName, 0)` on the consumed
 *   slot (skipped by `FillUi.<init>`) and call [AutofillSlotProvider.advanceSlot] to expose the next
 *   pre-allocated authenticated slot.
 * - On Android 11+ (API 30+), `EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET = true` keeps the
 *   authenticated dataset reusable without slot rotation.
 */
object AutofillDatasetFactory {

    val MATCH_ALL_PATTERN: Pattern = Pattern.compile(".*", Pattern.DOTALL)
    val NON_BLANK_PATTERN: Pattern = Pattern.compile("\\S+")

    @SuppressLint("RestrictedApi")
    fun buildAuthenticatedTriggerDataset(
        context: Context,
        title: String,
        subtitle: String,
        targetIds: List<AutofillId>,
        filterPattern: Pattern?,
        pendingIntent: PendingIntent,
        inlineSpec: InlinePresentationSpec?,
        reqCode: Int,
        slotUri: Uri? = null
    ): Dataset {
        val remoteViews = RemoteViews(context.packageName, R.layout.item_autofill_dataset).apply {
            setTextViewText(R.id.autofill_item_title, title)
            setTextViewText(R.id.autofill_item_subtitle, subtitle)
            if (slotUri != null) {
                setImageViewUri(R.id.autofill_item_slot_gate, slotUri)
            }
        }

        @Suppress("DEPRECATION")
        val builder = Dataset.Builder(remoteViews)
        builder.setAuthentication(pendingIntent.intentSender)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineSpec != null) {
            try {
                val attributionIntent = PendingIntentBalHelper.getActivityAllowingBal(
                    context = context,
                    requestCode = reqCode + 50_000,
                    intent = Intent(context, MainActivity::class.java),
                    flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val contentBuilder = InlineSuggestionUi.newContentBuilder(attributionIntent)
                    .setTitle(title)
                if (subtitle.isNotBlank()) {
                    contentBuilder.setSubtitle(subtitle)
                }
                val slice = contentBuilder.build().slice
                val inlinePresentation = InlinePresentation(slice, inlineSpec, false)
                @Suppress("DEPRECATION")
                builder.setInlinePresentation(inlinePresentation)
            } catch (e: Exception) {
                AppLog.w("AutofillDataset", "Failed to build InlinePresentation slice", e)
            }
        }

        for (id in targetIds) {
            @Suppress("DEPRECATION")
            builder.setValue(id, null, filterPattern, remoteViews)
        }

        return builder.build()
    }

    /**
     * Builds the `EXTRA_AUTHENTICATION_RESULT` [Intent] for filling an existing [entity] (including
     * 3-field Bank National ID + User ID + Password when [extraFields] are present) from
     * `AuthAndPickActivity`, or returns `null` if no target field could be populated.
     */
    fun buildCredentialFillResultIntent(
        context: Context,
        target: AutofillTargetRequest,
        entity: CredentialEntity,
        plainPassword: String,
        extraFields: List<ExtraField> = emptyList()
    ): Intent? {
        if (!target.hasAutofillIds) return null

        val fieldValues = mapCredentialFillValues(
            usernameId = target.usernameAutofillId,
            secondaryUsernameId = target.secondaryUsernameAutofillId,
            passwordId = target.passwordAutofillId,
            focusedId = target.focusedAutofillId,
            editableIds = target.allEditableAutofillIds,
            username = entity.username,
            password = plainPassword,
            extraFields = extraFields
        )
        if (fieldValues.values.none { it != null }) return null

        val isDirectCredentialSlot = target.credentialId > 0L
        val slotTitle = if (isDirectCredentialSlot) {
            if (entity.username.isNotBlank()) {
                context.getString(R.string.autofill_chip_fill_user, entity.username)
            } else {
                context.getString(R.string.autofill_chip_fill_pass, entity.title)
            }
        } else {
            context.getString(R.string.autofill_chip_pick_vault)
        }
        val slotSubtitle = if (isDirectCredentialSlot) {
            context.getString(R.string.autofill_slot_tap_to_fill, entity.title)
        } else {
            context.getString(R.string.autofill_slot_selected, entity.username.ifBlank { entity.title })
        }

        return createResultDatasetIntent(
            context = context,
            target = target,
            fallbackTitle = slotTitle,
            fallbackSubtitle = slotSubtitle,
            fieldValues = fieldValues,
            filterPattern = MATCH_ALL_PATTERN
        )
    }

    /**
     * Builds the `EXTRA_AUTHENTICATION_RESULT` [Intent] for filling a single selected value
     * (e.g. an encrypted extra field like `User ID` or `PIN 碼`) strictly into the currently
     * focused input field, or returns `null` if no target field exists.
     */
    fun buildSingleFieldFillResultIntent(
        context: Context,
        target: AutofillTargetRequest,
        value: String,
        fieldLabel: String
    ): Intent? {
        if (!target.hasAutofillIds || value.isBlank()) return null
        val fieldValues = mapSingleFieldFillValues(
            focusedId = target.focusedAutofillId,
            passwordId = target.passwordAutofillId,
            usernameId = target.usernameAutofillId,
            editableIds = target.allEditableAutofillIds,
            value = value
        )
        if (fieldValues.isEmpty()) return null

        return createResultDatasetIntent(
            context = context,
            target = target,
            fallbackTitle = context.getString(R.string.autofill_chip_pick_vault),
            fallbackSubtitle = context.getString(R.string.autofill_slot_selected, fieldLabel),
            fieldValues = fieldValues,
            filterPattern = MATCH_ALL_PATTERN
        )
    }

    internal fun <Id : Any> mapSingleFieldFillValues(
        focusedId: Id?,
        passwordId: Id?,
        usernameId: Id?,
        editableIds: List<Id>,
        value: String
    ): Map<Id, String?> {
        if (value.isBlank()) return emptyMap()
        val chosenId = focusedId ?: passwordId ?: usernameId ?: editableIds.firstOrNull() ?: return emptyMap()
        return mapOf(chosenId to value)
    }

    /**
     * Builds the `EXTRA_AUTHENTICATION_RESULT` [Intent] for filling a newly generated/edited
     * credential from `GenerateAndSaveActivity`, or returns `null` if no field was populated.
     */
    fun buildGeneratedFillResultIntent(
        context: Context,
        target: AutofillTargetRequest,
        username: String,
        plainPassword: String
    ): Intent? {
        if (!target.hasDirectAutofillFields) return null

        val fieldValues = mapGeneratedFillValues(
            usernameId = target.usernameAutofillId,
            passwordId = target.passwordAutofillId,
            focusedId = target.focusedAutofillId,
            username = username,
            password = plainPassword
        )
        if (fieldValues.isEmpty()) return null

        return createResultDatasetIntent(
            context = context,
            target = target,
            fallbackTitle = context.getString(R.string.autofill_chip_generate_new),
            fallbackSubtitle = context.getString(R.string.autofill_chip_generate_subtitle),
            fieldValues = fieldValues,
            filterPattern = null
        )
    }

    /**
     * Pure field-assignment rules for filling a saved credential into detected form fields,
     * including 3-field Taiwanese Bank forms (`身分證字號` + `使用者代號` + `使用者密碼`).
     * Kept generic over [Id] so it can be verified directly in plain JVM unit tests.
     */
    internal fun <Id : Any> mapCredentialFillValues(
        usernameId: Id?,
        secondaryUsernameId: Id? = null,
        passwordId: Id?,
        focusedId: Id?,
        editableIds: List<Id>,
        username: String,
        password: String,
        extraFields: List<ExtraField> = emptyList()
    ): Map<Id, String?> {
        val fieldValues = LinkedHashMap<Id, String?>()

        // Resolve (primaryAccount, secondaryAccount) when a 3-field login form has secondaryUsernameId:
        // Supports BOTH storage styles:
        // - Style A: `username` = National ID (`A123456789`), `extraFields` has `User ID` (`jongren88`)
        // - Style B: `username` = User ID (`jongren88`), `extraFields` has `身分證字號` (`A123456789`)
        val (primaryAccount, secondaryAccount) = resolveAccountPair(
            username = username,
            extraFields = extraFields,
            hasSecondarySlot = secondaryUsernameId != null && secondaryUsernameId != usernameId
        )

        // 1) Primary Username / National ID field
        if (usernameId != null) {
            fieldValues[usernameId] = primaryAccount.takeIf { it.isNotBlank() }
        }

        // 1b) Secondary Login ID / Bank User ID field (when present on a 3-field Bank form)
        if (secondaryUsernameId != null &&
            secondaryUsernameId != usernameId &&
            secondaryUsernameId != passwordId &&
            secondaryAccount.isNotBlank()
        ) {
            fieldValues[secondaryUsernameId] = secondaryAccount
        }

        // 2) Primary Password field
        if (passwordId != null && password.isNotBlank()) {
            fieldValues[passwordId] = password
        }

        // 3) Currently focused field (never inject a password into a known username/ID slot!)
        if (focusedId != null &&
            focusedId != usernameId &&
            focusedId != secondaryUsernameId &&
            !fieldValues.containsKey(focusedId)
        ) {
            if (passwordId != null && focusedId != passwordId && primaryAccount.isNotBlank()) {
                fieldValues[focusedId] = primaryAccount
            } else if (password.isNotBlank()) {
                fieldValues[focusedId] = password
            }
        }

        // 4) Companion fallback when usernameId was not explicitly identified alongside passwordId
        if (usernameId == null && passwordId != null && primaryAccount.isNotBlank()) {
            val pwdIndex = editableIds.indexOf(passwordId)
            val fallbackUserId = if (pwdIndex > 0) {
                editableIds[pwdIndex - 1]
            } else {
                editableIds.firstOrNull { it != passwordId }
            }
            if (fallbackUserId != null && !fieldValues.containsKey(fallbackUserId)) {
                fieldValues[fallbackUserId] = primaryAccount
            }
        }

        return fieldValues
    }

    private fun resolveAccountPair(
        username: String,
        extraFields: List<ExtraField>,
        hasSecondarySlot: Boolean
    ): Pair<String, String> {
        val cleanUser = username.trim()
        if (!hasSecondarySlot || extraFields.isEmpty()) {
            return cleanUser to ""
        }

        val nonEmptyExtras = extraFields.filter { it.value.isNotBlank() }
        if (nonEmptyExtras.isEmpty()) return cleanUser to ""

        // Check if the user stored National ID (`身分證字號`) in extraFields and User ID in `username`
        val explicitNatIdExtra = nonEmptyExtras.firstOrNull { ef ->
            val lower = ef.label.trim().lowercase()
            LoginFormAnalyzer.NATIONAL_ID_KEYWORDS.any { lower.contains(it) } ||
                lower == "id number"
        }
        val explicitUserCodeExtra = nonEmptyExtras.firstOrNull { ef ->
            val lower = ef.label.trim().lowercase()
            LoginFormAnalyzer.USER_ID_CODE_KEYWORDS.any { lower.contains(it) } ||
                lower == "user id"
        }

        return when {
            explicitNatIdExtra != null && explicitUserCodeExtra != null && cleanUser.isBlank() ->
                explicitNatIdExtra.value.trim() to explicitUserCodeExtra.value.trim()
            explicitNatIdExtra != null && cleanUser.isNotBlank() ->
                explicitNatIdExtra.value.trim() to cleanUser
            explicitUserCodeExtra != null ->
                cleanUser to explicitUserCodeExtra.value.trim()
            else -> {
                // Fallback: use the first non-PIN / non-note extra field as the secondary login ID
                val candidate = nonEmptyExtras.firstOrNull { ef ->
                    val lower = ef.label.trim().lowercase()
                    !CredentialMatchRules.isSecondarySecretTitle(lower) &&
                        !lower.contains("備註") &&
                        !lower.contains("note")
                }
                cleanUser to candidate?.value?.trim().orEmpty()
            }
        }
    }

    /**
     * Pure field-assignment rules for filling a generated/custom password (and optional username).
     */
    internal fun <Id : Any> mapGeneratedFillValues(
        usernameId: Id?,
        passwordId: Id?,
        focusedId: Id?,
        username: String,
        password: String
    ): Map<Id, String?> {
        val fieldValues = LinkedHashMap<Id, String?>()
        if (usernameId != null && username.isNotBlank()) {
            fieldValues[usernameId] = username
        }
        if (passwordId != null && password.isNotBlank()) {
            fieldValues[passwordId] = password
        }
        if (focusedId != null &&
            focusedId != passwordId &&
            focusedId != usernameId &&
            password.isNotBlank()
        ) {
            fieldValues[focusedId] = password
        }
        return fieldValues
    }

    private fun createResultDatasetIntent(
        context: Context,
        target: AutofillTargetRequest,
        fallbackTitle: String,
        fallbackSubtitle: String,
        fieldValues: Map<AutofillId, String?>,
        filterPattern: Pattern?
    ): Intent {
        val useApi29SlotRotation =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                target.slotSessionToken > 0 &&
                target.slotCategory.isNotBlank()

        val presentation = if (useApi29SlotRotation) {
            RemoteViews(context.packageName, 0)
        } else {
            RemoteViews(context.packageName, R.layout.item_autofill_dataset).apply {
                setTextViewText(R.id.autofill_item_title, fallbackTitle)
                setTextViewText(R.id.autofill_item_subtitle, fallbackSubtitle)
            }
        }

        @Suppress("DEPRECATION")
        val builder = Dataset.Builder(presentation)
        for ((id, textValue) in fieldValues) {
            val autofillValue = textValue?.let { AutofillValue.forText(it) }
            @Suppress("DEPRECATION")
            builder.setValue(id, autofillValue, filterPattern, presentation)
        }

        if (useApi29SlotRotation) {
            AutofillSlotProvider.advanceSlot(target.slotSessionToken, target.slotCategory)
        }

        return Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET, true)
            }
        }
    }
}
