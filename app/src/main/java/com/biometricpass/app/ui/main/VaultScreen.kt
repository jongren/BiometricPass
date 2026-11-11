package com.biometricpass.app.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.R
import com.biometricpass.app.crypto.BiometricAuthHelper
import com.biometricpass.app.ui.components.QrDisplayDialog
import com.biometricpass.app.ui.components.QrScannerDialog
import com.biometricpass.app.ui.generate.GenerateAndSaveActivity
import com.biometricpass.app.ui.icons.Key
import com.biometricpass.app.ui.icons.Shield
import com.biometricpass.app.ui.icons.SwapVert
import com.biometricpass.app.ui.main.components.AutofillStatusBanner
import com.biometricpass.app.ui.main.components.BackupKeyReminderBanner
import com.biometricpass.app.ui.main.components.CredentialCardItem
import com.biometricpass.app.ui.main.components.PasswordHealthDialog
import com.biometricpass.app.ui.main.components.VaultOverflowMenu
import com.biometricpass.app.util.AppSettings
import com.biometricpass.app.util.AutoLockGuard
import com.biometricpass.app.util.VaultSortOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.crypto.SecretKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnlockedVaultScreen(
    dek: SecretKey,
    vaultViewModel: VaultViewModel,
    backupViewModel: BackupMigrationViewModel,
    incomingBackupUri: Uri?,
    onIncomingBackupConsumed: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val vaultState by vaultViewModel.uiState.collectAsState()
    val backupState by backupViewModel.uiState.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showRotateKeyConfirm by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(!AppSettings.isOnboardingDone(context)) }
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vaultViewModel.refreshAutofillStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) backupViewModel.exportBackupToUri(uri, dek)
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            backupViewModel.importBackupFromUri(uri, dek)
        } else {
            backupViewModel.onImportBackupPickerCancelled()
        }
    }

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) backupViewModel.importCsvFromUri(uri, dek)
    }

    LaunchedEffect(vaultViewModel) {
        vaultViewModel.events.collect { event ->
            when (event) {
                is VaultUiEvent.Toast -> {
                    Toast.makeText(
                        context,
                        event.message,
                        if (event.longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                }
                is VaultUiEvent.ShowUndoDelete -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val autoDismissJob = launch {
                        delay(3000L)
                        snackbarHostState.currentSnackbarData?.dismiss()
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.deleted_snackbar_msg, event.deleted.title),
                        actionLabel = context.getString(R.string.undo_action),
                        withDismissAction = true,
                        duration = SnackbarDuration.Short
                    )
                    autoDismissJob.cancel()
                    if (result == SnackbarResult.ActionPerformed) {
                        vaultViewModel.restoreDeletedCredential(event.deleted)
                    }
                }
            }
        }
    }

    LaunchedEffect(backupViewModel, dek) {
        backupViewModel.events.collect { event ->
            when (event) {
                is BackupUiEvent.Toast -> {
                    Toast.makeText(
                        context,
                        event.message,
                        if (event.longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                }
                is BackupUiEvent.LaunchExportBackupSaf -> {
                    AutoLockGuard.expectExternalActivity()
                    exportBackupLauncher.launch(event.suggestedFileName)
                }
                BackupUiEvent.LaunchImportBackupSaf -> {
                    AutoLockGuard.expectExternalActivity()
                    importBackupLauncher.launch(arrayOf("*/*"))
                }
                BackupUiEvent.LaunchImportCsvSaf -> {
                    AutoLockGuard.expectExternalActivity()
                    importCsvLauncher.launch(arrayOf("text/*", "application/csv", "*/*"))
                }
                is BackupUiEvent.ShareBackupFile -> {
                    AutoLockGuard.expectExternalActivity()
                    shareBackupFileUri(context, event.fileUri)
                }
                is BackupUiEvent.VaultKeyRotated -> {
                    vaultViewModel.onVaultUnlocked(event.newDek)
                }
            }
        }
    }

    LaunchedEffect(incomingBackupUri) {
        if (incomingBackupUri != null) {
            val targetUri = incomingBackupUri
            onIncomingBackupConsumed()
            backupViewModel.importBackupFromUri(targetUri, dek)
        }
    }

    if (backupState.showKeyProtectionDialog) {
        BackupKeyProtectionDialog(
            onGenerateWithPassphrase = { passphrase ->
                backupViewModel.generateAndDisplayBackupUnlockKey(dek, passphrase)
            },
            onGenerateWithoutPassphrase = {
                backupViewModel.generateAndDisplayBackupUnlockKey(dek, passphrase = null)
            },
            onDismiss = { backupViewModel.dismissKeyProtectionDialog() }
        )
    }

    if (backupState.showNeedKeyForBackupDialog) {
        AlertDialog(
            onDismissRequest = { backupViewModel.dismissNeedKeyDialog(clearPendingBackup = false) },
            title = { Text(stringResource(R.string.need_key_dialog_title)) },
            text = { Text(stringResource(R.string.need_key_dialog_body)) },
            confirmButton = {
                Button(onClick = { backupViewModel.scanKeyForPendingBackup() }) {
                    Text(stringResource(R.string.need_key_dialog_scan_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { backupViewModel.dismissNeedKeyDialog(clearPendingBackup = true) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    backupState.activeQrDialogData?.let { info ->
        QrDisplayDialog(
            title = info.title,
            description = info.description,
            qrPayload = info.payload,
            defaultFileName = info.defaultFileName,
            transferCode = info.transferCode,
            allowSaveImage = info.allowSaveImage,
            extraActionLabel = info.extraActionLabel,
            onExtraAction = info.onExtraAction,
            onImageSaved = info.onImageSaved,
            onDismiss = { backupViewModel.dismissQrDisplayDialog() }
        )
    }

    if (backupState.pendingMigrationPayload != null) {
        TransferCodeInputDialog(
            errorMessage = backupState.transferCodeError,
            isWorking = backupState.isMigrationImporting,
            onDismiss = { backupViewModel.dismissMigrationCodeDialog() },
            onConfirm = { code -> backupViewModel.confirmMigrationTransferCode(code, dek) }
        )
    }

    if (backupState.pendingRecoveryV2Payload != null) {
        RecoveryPassphraseInputDialog(
            errorMessage = backupState.recoveryPassphraseError,
            isWorking = backupState.isRecoveryWorking,
            onDismiss = { backupViewModel.dismissRecoveryPassphraseDialog() },
            onConfirm = { passphrase -> backupViewModel.confirmRecoveryPassphrase(passphrase, dek) }
        )
    }

    backupState.csvDeleteReminderSummary?.let { summary ->
        CsvDeleteReminderDialog(
            summaryMessage = summary,
            onDismiss = { backupViewModel.dismissCsvDeleteReminder() }
        )
    }

    if (backupState.showQrScanner) {
        QrScannerDialog(
            onDismiss = { backupViewModel.dismissQrScanner() },
            onQrCodeScanned = { scannedText ->
                backupViewModel.onQrCodeScanned(scannedText, dek)
            }
        )
    }

    vaultState.healthReport?.let { report ->
        PasswordHealthDialog(
            report = report,
            onEditIssueItem = { issue ->
                vaultViewModel.dismissHealthReport()
                val editIntent = GenerateAndSaveActivity.createIntent(
                    context = context,
                    credentialId = issue.credentialId,
                    initialTitle = issue.title,
                    targetPackage = issue.packageName,
                    targetWebDomain = issue.webDomain,
                    initialUsername = issue.username
                )
                context.startActivity(editIntent)
            },
            onDismiss = { vaultViewModel.dismissHealthReport() }
        )
    }

    if (showRotateKeyConfirm) {
        RotateBackupKeyConfirmDialog(
            onConfirm = {
                showRotateKeyConfirm = false
                val activity = context as? FragmentActivity ?: return@RotateBackupKeyConfirmDialog
                val app = activity.application as BiometricPassApp
                AutoLockGuard.expectExternalActivity()
                BiometricAuthHelper.authenticateAndUnlockDek(
                    activity = activity,
                    cryptoManager = app.cryptoManager,
                    title = context.getString(R.string.rotate_key_biometric_title),
                    subtitle = context.getString(R.string.rotate_key_biometric_subtitle),
                    onSuccess = { verifiedDek ->
                        AutoLockGuard.clearGrace()
                        backupViewModel.rotateVaultKey(verifiedDek)
                    },
                    onError = { err ->
                        AutoLockGuard.clearGrace()
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onDismiss = { showRotateKeyConfirm = false }
        )
    }

    if (backupState.isRotatingKey) {
        RotatingKeyProgressDialog()
    }

    if (showSettings) {
        SettingsDialog(
            isAutofillEnabled = vaultState.isAutofillEnabled,
            onOpenAutofillSettings = { openAutofillSettings(context, vaultState.isAutofillEnabled) },
            onShowGuide = {
                showSettings = false
                showOnboarding = true
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showOnboarding) {
        OnboardingDialog(
            isAutofillEnabled = vaultState.isAutofillEnabled,
            onOpenAutofillSettings = { openAutofillSettings(context, vaultState.isAutofillEnabled) },
            onFinish = {
                AppSettings.setOnboardingDone(context)
                showOnboarding = false
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.vault_subtitle_count, vaultState.totalCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    VaultOverflowMenu(
                        totalCount = vaultState.totalCount,
                        onSendToNewPhone = { backupViewModel.startSendToNewPhone(dek, vaultState.totalCount) },
                        onOpenQrScanner = { backupViewModel.openQrScanner() },
                        onShowBackupKey = { backupViewModel.requestShowBackupUnlockKey() },
                        onRequestRotateKey = { showRotateKeyConfirm = true },
                        onExportBackup = { backupViewModel.requestExportBackup(vaultState.totalCount) },
                        onImportBackup = { backupViewModel.requestImportBackup() },
                        onImportCsv = { backupViewModel.requestImportCsv() },
                        onRunPasswordHealth = { vaultViewModel.runPasswordHealthCheck() },
                        onOpenSettings = { showSettings = true }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val intent = GenerateAndSaveActivity.createIntent(context)
                    context.startActivity(intent)
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.fab_add_password)) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (!vaultState.isAutofillEnabled) {
                AutofillStatusBanner(
                    onOpenAutofillSettings = { openAutofillSettings(context, isAutofillEnabled = false) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else if (vaultState.totalCount > 0 && !backupState.isBackupKeySaved && vaultState.searchQuery.isBlank()) {
                BackupKeyReminderBanner(onSaveNow = { backupViewModel.requestShowBackupUnlockKey() })
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = vaultState.searchQuery,
                    onValueChange = { vaultViewModel.onSearchQueryChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (vaultState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { vaultViewModel.onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.clear_search_desc)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )

                OutlinedButton(
                    onClick = { vaultViewModel.toggleSortOption() },
                    modifier = Modifier.fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (vaultState.sortOption == VaultSortOption.RECENT) {
                            stringResource(R.string.sort_recent)
                        } else {
                            stringResource(R.string.sort_title)
                        },
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (vaultState.credentials.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (vaultState.searchQuery.isBlank()) {
                                stringResource(R.string.empty_vault_message)
                            } else {
                                stringResource(R.string.empty_search_message, vaultState.searchQuery)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vaultState.credentials, key = { it.id }) { item ->
                        CredentialCardItem(
                            item = item,
                            revealedPassword = vaultState.revealedPasswords[item.id],
                            onToggleRevealPassword = { vaultViewModel.toggleRevealPassword(item) },
                            onCopyUsername = { vaultViewModel.copyUsername(item) },
                            onCopyPassword = { vaultViewModel.copyPassword(item) },
                            onEdit = {
                                val editIntent = GenerateAndSaveActivity.createIntent(
                                    context = context,
                                    credentialId = item.id,
                                    initialTitle = item.title,
                                    targetPackage = item.packageName,
                                    targetWebDomain = item.webDomain,
                                    initialUsername = item.username
                                )
                                context.startActivity(editIntent)
                            },
                            onDelete = { vaultViewModel.deleteCredential(item) }
                        )
                    }
                }
            }
        }
    }
}

private fun shareBackupFileUri(context: Context, fileUri: Uri) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, fileUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(sendIntent, context.getString(R.string.share_backup_chooser_title))
    )
}

private fun openAutofillSettings(context: Context, isAutofillEnabled: Boolean) {
    AutoLockGuard.expectExternalActivity()
    if (isAutofillEnabled) {
        val changeCandidates = listOf(
            Intent("com.samsung.settings.DEFAULT_AUTOFILL_PICKER"),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$DefaultAutofillPickerActivity"
            ),
            Intent(
                Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                Uri.parse("package:none")
            )
        )
        for (candidate in changeCandidates) {
            try {
                context.startActivity(candidate)
                return
            } catch (_: Exception) {
                // Try next candidate
            }
        }
    }
    try {
        val intent = Intent(
            Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.autofill_settings_fallback_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
