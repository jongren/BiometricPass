package com.biometricpass.app.ui.generate

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biometricpass.app.R
import com.biometricpass.app.autofill.CapturedFormField
import com.biometricpass.app.data.model.ExtraField
import com.biometricpass.app.ui.components.AnchoredBottomSheetContainer
import com.biometricpass.app.ui.components.InstalledAppPickerDialog
import com.biometricpass.app.ui.icons.Apps
import com.biometricpass.app.ui.icons.AutoAwesome
import com.biometricpass.app.ui.icons.ContentCopy
import com.biometricpass.app.ui.icons.Fingerprint
import com.biometricpass.app.ui.icons.Visibility
import com.biometricpass.app.ui.icons.VisibilityOff
import com.biometricpass.app.util.InstalledAppInfo
import com.biometricpass.app.util.SensitiveClipboardHelper
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GenerateAndSaveSheetContent(
    uiState: GenerateAndSaveUiState,
    onTitleChange: (String) -> Unit,
    onApplyQuickTitleSuffix: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPackageNameChange: (String) -> Unit,
    onWebDomainChange: (String) -> Unit,
    onAppPicked: (InstalledAppInfo) -> Unit,
    onSetAppendMode: (Boolean) -> Unit,
    onSelectAppendTarget: (Long) -> Unit,
    onAppendFieldLabelChange: (String) -> Unit,
    onAddExtraField: (String) -> Unit,
    onUpdateExtraField: (Int, String, String) -> Unit,
    onRemoveExtraField: (Int) -> Unit,
    onAssignCapturedToUsername: (CapturedFormField) -> Unit,
    onAssignCapturedToExtraField: (CapturedFormField) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectMode: (Int) -> Unit,
    onLengthChange: (Float) -> Unit,
    onRegenerate: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmSave: (forceCreateNew: Boolean) -> Unit
) {
    val context = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }
    var showOverwriteConfirmDialog by remember { mutableStateOf(false) }

    if (showAppPicker) {
        InstalledAppPickerDialog(
            onDismiss = { showAppPicker = false },
            onAppSelected = { appInfo ->
                onAppPicked(appInfo)
                showAppPicker = false
            }
        )
    }

    if (showOverwriteConfirmDialog && uiState.matchedExisting != null) {
        val existing = uiState.matchedExisting
        val targetDisplay = if (existing.username.isNotBlank()) {
            "${existing.title} (${existing.username})"
        } else {
            existing.title
        }
        OverwriteProtectionDialog(
            targetDisplay = targetDisplay,
            onSaveAsNew = {
                showOverwriteConfirmDialog = false
                onConfirmSave(true)
            },
            onConfirmOverwrite = {
                showOverwriteConfirmDialog = false
                onConfirmSave(false)
            },
            onDismiss = { showOverwriteConfirmDialog = false }
        )
    }

    AnchoredBottomSheetContainer(
        onDismissRequest = onDismiss,
        dismissOnOutsideClick = false,
        confirmBeforeBackDismiss = uiState.hasUnsavedWorkToProtect,
        unsavedDismissTitle = stringResource(R.string.unsaved_exit_dialog_title),
        unsavedDismissBody = stringResource(
            if (uiState.isFromAutofillSave) {
                R.string.unsaved_exit_dialog_body_autofill
            } else {
                R.string.unsaved_exit_dialog_body_edit
            }
        ),
        unsavedKeepEditingLabel = stringResource(R.string.unsaved_exit_keep_editing),
        unsavedDiscardLabel = stringResource(R.string.unsaved_exit_discard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = when {
                    uiState.isCompactFillMode -> stringResource(R.string.gen_sheet_title_compact)
                    uiState.isAppendToExistingMode -> stringResource(R.string.save_mode_append_to_existing)
                    uiState.isFromAutofillSave && uiState.isUpdateAction ->
                        stringResource(R.string.gen_sheet_title_autofill_update)
                    uiState.isFromAutofillSave ->
                        stringResource(R.string.gen_sheet_title_autofill_save)
                    uiState.credentialId > 0L -> stringResource(R.string.gen_sheet_title_edit)
                    else -> stringResource(R.string.gen_sheet_title_new)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = resolveSheetSubtitle(uiState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            // When saving from Autofill onto an App that already has saved login(s), let the user
            // seamlessly choose between saving a separate item vs appending to an existing item's
            // encrypted extra fields (e.g. Samsung Wallet PIN / Bank Transaction Code).
            if (uiState.canAppendToExisting) {
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !uiState.isAppendToExistingMode,
                        onClick = { onSetAppendMode(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(
                            text = stringResource(R.string.save_mode_separate_item),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SegmentedButton(
                        selected = uiState.isAppendToExistingMode,
                        onClick = { onSetAppendMode(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(
                            text = stringResource(R.string.save_mode_append_to_existing),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val targetEntity = uiState.appendTargetEntity
            if (uiState.isAppendToExistingMode && targetEntity != null) {
                val targetName = if (targetEntity.username.isNotBlank()) {
                    "${targetEntity.title} (${targetEntity.username})"
                } else {
                    targetEntity.title
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.save_mode_append_target_label, targetName),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (uiState.siteExistingCredentials.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                uiState.siteExistingCredentials.forEach { candidate ->
                                    val chipLabel = if (candidate.username.isNotBlank()) {
                                        "${candidate.title} (${candidate.username})"
                                    } else {
                                        candidate.title
                                    }
                                    FilterChip(
                                        selected = candidate.id == targetEntity.id,
                                        onClick = { onSelectAppendTarget(candidate.id) },
                                        label = { Text(chipLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = uiState.appendFieldLabel,
                    onValueChange = onAppendFieldLabelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.save_mode_append_field_label)) },
                    singleLine = true
                )

                val quickAppendTags = listOf(
                    stringResource(R.string.tag_pin_code),
                    stringResource(R.string.tag_tx_password),
                    stringResource(R.string.tag_user_id),
                    stringResource(R.string.tag_secret_note)
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickAppendTags.forEach { tag ->
                        FilterChip(
                            selected = uiState.appendFieldLabel.trim() == tag,
                            onClick = { onAppendFieldLabelChange(tag) },
                            label = { Text(tag) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                PasswordInputField(
                    password = uiState.password,
                    selectedMode = 1,
                    showPassword = uiState.showPassword,
                    onPasswordChange = onPasswordChange,
                    onTogglePasswordVisibility = onTogglePasswordVisibility
                )
            } else {
                if (uiState.isFromAutofillSave) {
                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.gen_title_label)) },
                        singleLine = true
                    )
                    if (uiState.username.isBlank()) {
                        val pinTag = stringResource(R.string.tag_pin_code)
                        val txTag = stringResource(R.string.tag_tx_password)
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = { onApplyQuickTitleSuffix(pinTag) },
                                label = { Text("+ $pinTag") }
                            )
                            AssistChip(
                                onClick = { onApplyQuickTitleSuffix(txTag) },
                                label = { Text("+ $txTag") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (!uiState.isCompactFillMode) {
                    if (uiState.hasMaskedUsernameWarning) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.masked_username_warning_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = onUsernameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.gen_username_label)) },
                        placeholder = { Text(stringResource(R.string.gen_username_placeholder)) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Multi-field / unlabeled form helper: when the screen had additional non-password
                    // inputs (e.g. Bank ID + User ID), offer 1-tap assignment without re-typing!
                    val unusedCaptured = uiState.capturedFields.filter { cf ->
                        !cf.isPasswordMask &&
                            !cf.value.equals(uiState.username.trim(), ignoreCase = true) &&
                            uiState.extraFields.none { it.value.equals(cf.value, ignoreCase = true) }
                    }
                    if (unusedCaptured.isNotEmpty()) {
                        CapturedScreenInputsCard(
                            candidates = unusedCaptured,
                            onSetUsername = onAssignCapturedToUsername,
                            onAddExtra = onAssignCapturedToExtraField
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                PasswordInputField(
                    password = uiState.password,
                    selectedMode = uiState.selectedMode,
                    showPassword = uiState.showPassword,
                    onPasswordChange = onPasswordChange,
                    onTogglePasswordVisibility = onTogglePasswordVisibility
                )

                if (!uiState.isFromAutofillSave) {
                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = uiState.selectedMode == 0,
                            onClick = { onSelectMode(0) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) }
                        ) {
                            Text(stringResource(R.string.gen_mode_random))
                        }
                        SegmentedButton(
                            selected = uiState.selectedMode == 1,
                            onClick = { onSelectMode(1) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        ) {
                            Text(stringResource(R.string.gen_mode_custom))
                        }
                    }

                    if (uiState.selectedMode == 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        GeneratorOptionsCard(
                            lengthSlider = uiState.lengthSlider,
                            useUpper = uiState.useUpper,
                            useLower = uiState.useLower,
                            useDigits = uiState.useDigits,
                            useSymbols = uiState.useSymbols,
                            onLengthChange = onLengthChange,
                            onRegenerate = onRegenerate,
                            onToggleUpper = onToggleUpper,
                            onToggleLower = onToggleLower,
                            onToggleDigits = onToggleDigits,
                            onToggleSymbols = onToggleSymbols
                        )
                    }
                }

                if (!uiState.isCompactFillMode && uiState.extraFieldsLoaded) {
                    ExtraFieldsEditorSection(
                        extraFields = uiState.extraFields,
                        onAddExtraField = onAddExtraField,
                        onUpdateExtraField = onUpdateExtraField,
                        onRemoveExtraField = onRemoveExtraField
                    )
                }

                if (!uiState.isCompactFillMode && !uiState.isFromAutofillSave) {
                    AppDomainMetadataSection(
                        title = uiState.title,
                        packageName = uiState.packageName,
                        webDomain = uiState.webDomain,
                        onTitleChange = onTitleChange,
                        onPackageNameChange = onPackageNameChange,
                        onWebDomainChange = onWebDomainChange,
                        onOpenAppPicker = { showAppPicker = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .fillMaxHeight()
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = {
                        if (uiState.password.isBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.gen_empty_password_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        if (uiState.requiresOverwriteConfirmation) {
                            showOverwriteConfirmDialog = true
                        } else {
                            onConfirmSave(false)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .fillMaxHeight()
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            uiState.isAppendToExistingMode ->
                                stringResource(R.string.gen_btn_append_extra_field)
                            uiState.isCompactFillMode && uiState.isUpdateAction ->
                                stringResource(R.string.gen_btn_fill_and_update)
                            uiState.isCompactFillMode ->
                                stringResource(R.string.gen_btn_fill_and_save)
                            uiState.isUpdateAction ->
                                stringResource(R.string.gen_btn_confirm_update)
                            else ->
                                stringResource(R.string.gen_btn_confirm_save)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OverwriteProtectionDialog(
    targetDisplay: String,
    onSaveAsNew: () -> Unit,
    onConfirmOverwrite: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.overwrite_dialog_title)) },
        text = { Text(stringResource(R.string.overwrite_dialog_body, targetDisplay)) },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSaveAsNew,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.overwrite_dialog_save_as_new))
                }
                OutlinedButton(
                    onClick = onConfirmOverwrite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.overwrite_dialog_confirm_overwrite),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
private fun CapturedScreenInputsCard(
    candidates: List<CapturedFormField>,
    onSetUsername: (CapturedFormField) -> Unit,
    onAddExtra: (CapturedFormField) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.captured_inputs_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.captured_inputs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            candidates.forEach { field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (field.labelHint.isNotBlank()) {
                            Text(
                                text = field.labelHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            text = field.value,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onSetUsername(field) }) {
                        Text(stringResource(R.string.captured_action_set_username))
                    }
                    TextButton(onClick = { onAddExtra(field) }) {
                        Text(stringResource(R.string.captured_action_add_extra))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraFieldsEditorSection(
    extraFields: List<ExtraField>,
    onAddExtraField: (String) -> Unit,
    onUpdateExtraField: (Int, String, String) -> Unit,
    onRemoveExtraField: (Int) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(10.dp))

    Text(
        text = stringResource(R.string.extra_fields_section_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    val presetTags = listOf(
        stringResource(R.string.tag_user_id),
        stringResource(R.string.tag_pin_code),
        stringResource(R.string.tag_tx_password),
        stringResource(R.string.tag_id_number),
        stringResource(R.string.tag_secret_note)
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presetTags.forEach { tag ->
            AssistChip(
                onClick = { onAddExtraField(tag) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(tag) }
            )
        }
    }

    extraFields.forEachIndexed { index, field ->
        var showSecret by remember(index) { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = field.label,
                        onValueChange = { newLabel -> onUpdateExtraField(index, newLabel, field.value) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.extra_field_label_hint)) },
                        singleLine = true
                    )
                    IconButton(onClick = { onRemoveExtraField(index) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.extra_field_remove_desc),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { newValue -> onUpdateExtraField(index, field.label, newValue) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.extra_field_value_hint)) },
                    visualTransformation = if (showSecret) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.toggle_password_visibility_desc)
                            )
                        }
                    },
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun resolveSheetSubtitle(uiState: GenerateAndSaveUiState): String {
    if (uiState.isFromAutofillSave) {
        val saveTarget = uiState.webDomain.ifBlank { uiState.title.ifBlank { uiState.packageName } }
        if (saveTarget.isNotBlank()) {
            return stringResource(
                if (uiState.isUpdateAction || uiState.isAppendToExistingMode) {
                    R.string.gen_sheet_subtitle_autofill_update_target
                } else {
                    R.string.gen_sheet_subtitle_autofill_save_target
                },
                saveTarget
            )
        }
    }
    if (!uiState.isCompactFillMode) {
        return stringResource(
            if (uiState.isUpdateAction) {
                R.string.gen_sheet_subtitle_full_update
            } else {
                R.string.gen_sheet_subtitle_full_save
            }
        )
    }
    val targetLabel = uiState.matchedExisting?.let {
        "${it.title}${if (it.username.isNotBlank()) " (${it.username})" else ""}"
    } ?: uiState.title.ifBlank { uiState.packageName }
    return when {
        uiState.isUpdateAction && targetLabel.isNotBlank() ->
            stringResource(R.string.gen_sheet_subtitle_compact_update_target, targetLabel)
        uiState.isUpdateAction ->
            stringResource(R.string.gen_sheet_subtitle_compact_update)
        targetLabel.isNotBlank() ->
            stringResource(R.string.gen_sheet_subtitle_compact_save_target, targetLabel)
        else ->
            stringResource(R.string.gen_sheet_subtitle_compact_save)
    }
}

@Composable
private fun PasswordInputField(
    password: String,
    selectedMode: Int,
    showPassword: Boolean,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit
) {
    val context = LocalContext.current
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                stringResource(
                    if (selectedMode == 0) {
                        R.string.gen_password_label_random
                    } else {
                        R.string.gen_password_label_custom
                    }
                )
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.toggle_password_visibility_desc)
                    )
                }
                IconButton(
                    onClick = {
                        if (password.isNotBlank()) {
                            SensitiveClipboardHelper.copySensitive(
                                context,
                                context.getString(R.string.field_password),
                                password
                            )
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_password)
                    )
                }
            }
        },
        singleLine = true
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratorOptionsCard(
    lengthSlider: Float,
    useUpper: Boolean,
    useLower: Boolean,
    useDigits: Boolean,
    useSymbols: Boolean,
    onLengthChange: (Float) -> Unit,
    onRegenerate: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.gen_length_label, lengthSlider.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onRegenerate) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.gen_btn_regenerate))
                }
            }

            Slider(
                value = lengthSlider,
                onValueChange = onLengthChange,
                valueRange = 8f..32f,
                steps = 23
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = useUpper,
                    onClick = onToggleUpper,
                    label = { Text(stringResource(R.string.gen_chip_upper)) }
                )
                FilterChip(
                    selected = useLower,
                    onClick = onToggleLower,
                    label = { Text(stringResource(R.string.gen_chip_lower)) }
                )
                FilterChip(
                    selected = useDigits,
                    onClick = onToggleDigits,
                    label = { Text(stringResource(R.string.gen_chip_digits)) }
                )
                FilterChip(
                    selected = useSymbols,
                    onClick = onToggleSymbols,
                    label = { Text(stringResource(R.string.gen_chip_symbols)) }
                )
            }
        }
    }
}

@Composable
private fun AppDomainMetadataSection(
    title: String,
    packageName: String,
    webDomain: String,
    onTitleChange: (String) -> Unit,
    onPackageNameChange: (String) -> Unit,
    onWebDomainChange: (String) -> Unit,
    onOpenAppPicker: () -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.gen_app_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        OutlinedButton(onClick = onOpenAppPicker) {
            Icon(Icons.Default.Apps, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.gen_btn_pick_app))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.gen_title_label)) },
        singleLine = true
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = packageName,
        onValueChange = onPackageNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.gen_package_label)) },
        placeholder = { Text(stringResource(R.string.gen_optional_placeholder)) },
        singleLine = true
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = webDomain,
        onValueChange = onWebDomainChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.gen_domain_label)) },
        placeholder = { Text(stringResource(R.string.gen_domain_placeholder)) },
        singleLine = true
    )
}
