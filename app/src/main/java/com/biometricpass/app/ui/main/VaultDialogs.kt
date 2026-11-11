package com.biometricpass.app.ui.main

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biometricpass.app.R
import com.biometricpass.app.crypto.TransferCode
import com.biometricpass.app.ui.icons.Fingerprint
import com.biometricpass.app.ui.icons.Key
import com.biometricpass.app.ui.icons.Shield
import com.biometricpass.app.ui.icons.Visibility
import com.biometricpass.app.ui.icons.VisibilityOff
import com.biometricpass.app.util.AppSettings

/**
 * Asks for the one-time transfer code shown on the old phone after scanning its migration QR Code.
 */
@Composable
internal fun TransferCodeInputDialog(
    errorMessage: String?,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val isComplete = TransferCode.isComplete(code)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_code_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.transfer_code_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = TransferCode.normalize(input).take(TransferCode.LENGTH)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isWorking,
                    placeholder = { Text("ABCD-EFGH") },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 3.sp
                    ),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (isComplete) onConfirm(code) })
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isWorking,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.36f)
                        .heightIn(min = 46.dp)
                ) {
                    Text(stringResource(R.string.action_cancel), maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = { onConfirm(code) },
                    enabled = isComplete && !isWorking,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.64f)
                        .heightIn(min = 46.dp)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isWorking) {
                            stringResource(R.string.transfer_code_importing)
                        } else {
                            stringResource(R.string.transfer_code_confirm)
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    )
}

/**
 * Lets the user optionally protect the Backup Unlock Key QR Code with a custom passphrase (V2),
 * or generate a plain V1 QR Code with no passphrase to remember.
 */
@Composable
internal fun BackupKeyProtectionDialog(
    onGenerateWithPassphrase: (String) -> Unit,
    onGenerateWithoutPassphrase: () -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    val trimmed = passphrase.trim()
    val isTooShort = trimmed.isNotEmpty() && trimmed.length < MIN_BACKUP_PASSPHRASE_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_key_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_key_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_key_passphrase_label)) },
                    isError = isTooShort,
                    supportingText = if (isTooShort) {
                        { Text(stringResource(R.string.backup_key_passphrase_too_short)) }
                    } else {
                        null
                    },
                    visualTransformation = if (showPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.toggle_passphrase_visibility_desc)
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.36f)
                        .heightIn(min = 46.dp)
                ) {
                    Text(stringResource(R.string.action_cancel), maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        if (trimmed.isEmpty()) {
                            onGenerateWithoutPassphrase()
                        } else {
                            onGenerateWithPassphrase(trimmed)
                        }
                    },
                    enabled = !isTooShort,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.64f)
                        .heightIn(min = 46.dp)
                ) {
                    Text(
                        text = if (trimmed.isEmpty()) {
                            stringResource(R.string.backup_key_btn_no_passphrase)
                        } else {
                            stringResource(R.string.backup_key_btn_with_passphrase)
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    )
}

/** Minimum length of an optional Backup Key passphrase (offline brute-force resistance). */
private const val MIN_BACKUP_PASSPHRASE_LENGTH = 8

/**
 * Explains what "Reset Backup Key" does before the user verifies and the vault key is rotated.
 */
@Composable
internal fun RotateBackupKeyConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.rotate_key_dialog_title)) },
        text = {
            Text(
                text = stringResource(R.string.rotate_key_dialog_body),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.36f)
                        .heightIn(min = 46.dp)
                ) {
                    Text(stringResource(R.string.action_cancel), maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onConfirm,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.64f)
                        .heightIn(min = 46.dp)
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.rotate_key_confirm), maxLines = 1, softWrap = false)
                }
            }
        }
    )
}

/** Non-dismissable progress while every password is re-encrypted with the new vault key. */
@Composable
internal fun RotatingKeyProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.rotate_key_working_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.rotate_key_working_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {}
    )
}

/**
 * Prompts for the custom passphrase when scanning a `BIOMETRICPASS_RECOVERY_V2:` QR Code.
 */
@Composable
internal fun RecoveryPassphraseInputDialog(
    errorMessage: String?,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    val valid = passphrase.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.recovery_passphrase_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.recovery_passphrase_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isWorking,
                    label = { Text(stringResource(R.string.recovery_passphrase_label)) },
                    visualTransformation = if (showPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.toggle_passphrase_visibility_desc)
                            )
                        }
                    },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(passphrase.trim()) })
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isWorking,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.36f)
                        .heightIn(min = 46.dp)
                ) {
                    Text(stringResource(R.string.action_cancel), maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = { onConfirm(passphrase.trim()) },
                    enabled = valid && !isWorking,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(0.64f)
                        .heightIn(min = 46.dp)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isWorking) {
                            stringResource(R.string.recovery_passphrase_verifying)
                        } else {
                            stringResource(R.string.recovery_passphrase_confirm)
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    )
}

/**
 * Security reminder shown right after importing a plaintext CSV file.
 */
@Composable
internal fun CsvDeleteReminderDialog(
    summaryMessage: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.csv_reminder_dialog_title)) },
        text = {
            Text(stringResource(R.string.csv_reminder_dialog_body, summaryMessage))
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.csv_reminder_dialog_confirm)) }
        }
    )
}

/**
 * App settings: auto-lock delay, dynamic color (Android 12+), autofill status,
 * privacy policy, and the usage guide.
 */
@Composable
internal fun SettingsDialog(
    isAutofillEnabled: Boolean,
    onOpenAutofillSettings: () -> Unit,
    onShowGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var autoLockSeconds by remember { mutableIntStateOf(AppSettings.getAutoLockSeconds(context)) }
    var useDynamicColor by remember { mutableStateOf(AppSettings.isDynamicColorEnabled(context)) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_settings)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_auto_lock_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.settings_auto_lock_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppSettings.AUTO_LOCK_OPTIONS.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = autoLockSeconds == seconds,
                                onClick = {
                                    autoLockSeconds = seconds
                                    AppSettings.setAutoLockSeconds(context, seconds)
                                }
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = autoLockSeconds == seconds, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = AppSettings.autoLockLabel(context, seconds),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_dynamic_color_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_dynamic_color_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useDynamicColor,
                            onCheckedChange = { enabled ->
                                useDynamicColor = enabled
                                AppSettings.setDynamicColorEnabled(context, enabled)
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                StatusRow(
                    title = stringResource(R.string.settings_autofill_title),
                    enabled = isAutofillEnabled,
                    actionLabel = if (isAutofillEnabled) {
                        stringResource(R.string.settings_action_change)
                    } else {
                        stringResource(R.string.settings_action_enable)
                    },
                    onAction = onOpenAutofillSettings
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_chrome_autofill_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                OutlinedButton(onClick = onShowGuide, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_view_guide))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { showPrivacyPolicy = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_privacy_security))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}

@Composable
internal fun PrivacyPolicyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Shield, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_privacy_security)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.privacy_policy_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) }
        }
    )
}

@Composable
private fun StatusRow(
    title: String,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = if (enabled) {
                    stringResource(R.string.settings_status_enabled)
                } else {
                    stringResource(R.string.settings_status_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

private data class GuidePage(
    val icon: ImageVector,
    val title: String,
    val body: String
)

/**
 * First-run guide (3 short pages).
 */
@Composable
internal fun OnboardingDialog(
    isAutofillEnabled: Boolean,
    onOpenAutofillSettings: () -> Unit,
    onFinish: () -> Unit
) {
    val hasInlineSuggestions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val p1Title = stringResource(R.string.onboarding_p1_title)
    val p1Body = stringResource(R.string.onboarding_p1_body)
    val p2Title = stringResource(R.string.onboarding_p2_title)
    val p2Body = if (hasInlineSuggestions) {
        stringResource(R.string.onboarding_p2_body_inline)
    } else {
        stringResource(R.string.onboarding_p2_body_legacy)
    }
    val p3Title = stringResource(R.string.onboarding_p3_title)
    val p3Body = stringResource(R.string.onboarding_p3_body)

    val pages = remember(p1Title, p1Body, p2Title, p2Body, p3Title, p3Body) {
        listOf(
            GuidePage(
                icon = Icons.Default.Fingerprint,
                title = p1Title,
                body = p1Body
            ),
            GuidePage(
                icon = Icons.Default.Shield,
                title = p2Title,
                body = p2Body
            ),
            GuidePage(
                icon = Icons.Default.Key,
                title = p3Title,
                body = p3Body
            )
        )
    }
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    val isLast = pageIndex == pages.lastIndex

    AlertDialog(
        onDismissRequest = { /* Require explicit completion */ },
        icon = { Icon(page.icon, contentDescription = null, modifier = Modifier.size(36.dp)) },
        title = { Text(page.title, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(page.body, style = MaterialTheme.typography.bodyMedium)
                if (pageIndex == 1 && !isAutofillEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenAutofillSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.autofill_banner_action))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.indices.forEach { i ->
                        Text(
                            text = if (i == pageIndex) "●" else "○",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (isLast) onFinish() else pageIndex++ }) {
                Text(
                    if (isLast) {
                        stringResource(R.string.onboarding_btn_start)
                    } else {
                        stringResource(R.string.onboarding_btn_next)
                    }
                )
            }
        },
        dismissButton = {
            if (!isLast) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_btn_skip)) }
            }
        }
    )
}
