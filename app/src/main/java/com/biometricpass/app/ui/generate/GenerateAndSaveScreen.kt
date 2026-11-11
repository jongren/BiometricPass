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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerateAndSaveSheetContent(
    uiState: GenerateAndSaveUiState,
    onTitleChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPackageNameChange: (String) -> Unit,
    onWebDomainChange: (String) -> Unit,
    onAppPicked: (InstalledAppInfo) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectMode: (Int) -> Unit,
    onLengthChange: (Float) -> Unit,
    onRegenerate: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmSave: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        InstalledAppPickerDialog(
            onDismiss = { showAppPicker = false },
            onAppSelected = { appInfo ->
                onAppPicked(appInfo)
                showAppPicker = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = when {
                    uiState.isCompactFillMode -> stringResource(R.string.gen_sheet_title_compact)
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

            Spacer(modifier = Modifier.height(14.dp))

            if (uiState.isFromAutofillSave) {
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gen_title_label)) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!uiState.isCompactFillMode) {
                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gen_username_label)) },
                    placeholder = { Text(stringResource(R.string.gen_username_placeholder)) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
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
                        onConfirmSave()
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
private fun resolveSheetSubtitle(uiState: GenerateAndSaveUiState): String {
    if (uiState.isFromAutofillSave) {
        val saveTarget = uiState.webDomain.ifBlank { uiState.title.ifBlank { uiState.packageName } }
        if (saveTarget.isNotBlank()) {
            return stringResource(
                if (uiState.isUpdateAction) {
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
