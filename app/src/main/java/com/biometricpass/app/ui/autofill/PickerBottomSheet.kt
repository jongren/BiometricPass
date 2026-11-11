package com.biometricpass.app.ui.autofill

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biometricpass.app.R
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.ui.icons.AutoAwesome
import com.biometricpass.app.ui.icons.ContentCopy
import com.biometricpass.app.ui.icons.Fingerprint
import com.biometricpass.app.ui.icons.Key
import com.biometricpass.app.util.InstalledAppHelper
import com.biometricpass.app.util.SensitiveClipboardHelper
import javax.crypto.SecretKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerBottomSheetContent(
    uiState: PickerUiState,
    unlockedDek: SecretKey?,
    canDirectFill: Boolean,
    confirmUnlinkedCredential: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onRequestUnlock: () -> Unit,
    onOpenGenerator: () -> Unit,
    onAutoFillCredential: (CredentialEntity, SecretKey) -> Unit,
    onCopyPassword: (CredentialEntity, SecretKey) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingSignatureMismatchItem by remember { mutableStateOf<CredentialEntity?>(null) }
    var pendingWebViewConfirmItem by remember { mutableStateOf<CredentialEntity?>(null) }
    var pendingUnlinkedConfirmItem by remember { mutableStateOf<CredentialEntity?>(null) }

    pendingSignatureMismatchItem?.let { item ->
        if (unlockedDek != null) {
            val receiverLabel = uiState.targetAppLabel.ifBlank { uiState.targetPackage }
            PickerConfirmFillDialog(
                title = stringResource(R.string.signature_mismatch_dialog_title),
                body = stringResource(
                    R.string.signature_mismatch_dialog_body,
                    receiverLabel,
                    item.title
                ),
                onConfirm = {
                    pendingSignatureMismatchItem = null
                    onAutoFillCredential(item, unlockedDek)
                },
                onDismiss = { pendingSignatureMismatchItem = null }
            )
        }
    }

    pendingWebViewConfirmItem?.let { item ->
        if (unlockedDek != null) {
            PickerConfirmFillDialog(
                title = stringResource(R.string.webview_warning_dialog_title),
                body = stringResource(
                    R.string.webview_warning_dialog_body,
                    uiState.targetAppLabel,
                    item.title
                ),
                onConfirm = {
                    pendingWebViewConfirmItem = null
                    onAutoFillCredential(item, unlockedDek)
                },
                onDismiss = { pendingWebViewConfirmItem = null }
            )
        }
    }

    // Text selection (PROCESS_TEXT) can be triggered by ANY App: confirm before handing it a password
    // that was not saved for it, showing which App will receive it.
    pendingUnlinkedConfirmItem?.let { item ->
        if (unlockedDek != null) {
            val receiverLabel = uiState.targetAppLabel.ifBlank {
                stringResource(R.string.process_text_unknown_app)
            }
            PickerConfirmFillDialog(
                title = stringResource(R.string.process_text_confirm_title),
                body = stringResource(R.string.process_text_confirm_body, receiverLabel, item.title),
                onConfirm = {
                    pendingUnlinkedConfirmItem = null
                    onAutoFillCredential(item, unlockedDek)
                },
                onDismiss = { pendingUnlinkedConfirmItem = null }
            )
        }
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
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.picker_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    val appPrefix = if (uiState.targetAppLabel.isNotBlank()) {
                        stringResource(R.string.picker_current_app_prefix, uiState.targetAppLabel)
                    } else {
                        ""
                    }
                    val targetHint = buildString {
                        if (appPrefix.isNotBlank()) append(appPrefix)
                        if (uiState.targetWebDomain.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(uiState.targetWebDomain)
                        }
                    }
                    if (targetHint.isNotBlank()) {
                        Text(
                            text = targetHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            if (uiState.hasSignatureMismatch) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
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
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.picker_signature_mismatch_banner,
                                uiState.targetAppLabel.ifBlank { uiState.targetPackage }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (uiState.isNonBrowserWebView) {
                Spacer(modifier = Modifier.height(8.dp))
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
                            text = stringResource(R.string.picker_webview_banner_warning, uiState.targetWebDomain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onOpenGenerator,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.picker_btn_open_generator))
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (unlockedDek == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.picker_locked_prompt),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onRequestUnlock) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.picker_btn_unlock))
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.picker_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.picker_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val hasSuggestedSection = uiState.suggestedItems.isNotEmpty()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasSuggestedSection) {
                            item(key = "header_suggested") {
                                PickerSectionHeader(stringResource(R.string.picker_section_suggested))
                            }
                            items(uiState.suggestedItems, key = { it.id }) { item ->
                                PickerCredentialCard(
                                    item = item,
                                    isMatchedTarget = true,
                                    canDirectFill = canDirectFill,
                                    onCardClick = {
                                        if (uiState.isNonBrowserWebView && item.packageName != uiState.targetPackage) {
                                            pendingWebViewConfirmItem = item
                                        } else {
                                            onAutoFillCredential(item, unlockedDek)
                                        }
                                    },
                                    onCopyPassword = { onCopyPassword(item, unlockedDek) }
                                )
                            }
                        }

                        if (uiState.otherItems.isNotEmpty()) {
                            if (hasSuggestedSection) {
                                item(key = "header_others") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    PickerSectionHeader(stringResource(R.string.picker_section_others))
                                }
                            }
                            items(uiState.otherItems, key = { it.id }) { item ->
                                PickerCredentialCard(
                                    item = item,
                                    isMatchedTarget = false,
                                    canDirectFill = canDirectFill,
                                    onCardClick = {
                                        if (item.id in uiState.signatureMismatchItemIds) {
                                            pendingSignatureMismatchItem = item
                                        } else if (uiState.isNonBrowserWebView && item.packageName != uiState.targetPackage) {
                                            pendingWebViewConfirmItem = item
                                        } else if (confirmUnlinkedCredential) {
                                            pendingUnlinkedConfirmItem = item
                                        } else {
                                            onAutoFillCredential(item, unlockedDek)
                                        }
                                    },
                                    onCopyPassword = { onCopyPassword(item, unlockedDek) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun PickerConfirmFillDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.webview_warning_confirm_fill))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun PickerCredentialCard(
    item: CredentialEntity,
    isMatchedTarget: Boolean,
    canDirectFill: Boolean,
    onCardClick: () -> Unit,
    onCopyPassword: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(item.packageName) {
        InstalledAppHelper.getAppIcon(context, item.packageName)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMatchedTarget) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = item.title,
                    modifier = Modifier.size(38.dp)
                )
            } else {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.username.ifBlank { item.packageName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (canDirectFill) {
                        stringResource(R.string.picker_card_hint_direct_fill)
                    } else {
                        stringResource(R.string.picker_card_hint_copy)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.username.isNotBlank()) {
                TextButton(
                    onClick = {
                        SensitiveClipboardHelper.copySensitive(
                            context,
                            context.getString(R.string.field_username),
                            item.username
                        )
                    }
                ) {
                    Text(stringResource(R.string.action_copy_username))
                }
            }

            FilledTonalButton(onClick = onCopyPassword) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_copy_password))
            }
        }
    }
}
