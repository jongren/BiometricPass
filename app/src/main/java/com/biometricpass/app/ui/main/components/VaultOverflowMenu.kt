package com.biometricpass.app.ui.main.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biometricpass.app.R
import com.biometricpass.app.service.QuickActionNotificationHelper
import com.biometricpass.app.ui.icons.FileDownload
import com.biometricpass.app.ui.icons.FileUpload
import com.biometricpass.app.ui.icons.Key
import com.biometricpass.app.ui.icons.PhonelinkSetup
import com.biometricpass.app.ui.icons.QrCodeScanner
import com.biometricpass.app.ui.icons.Shield

@Composable
internal fun VaultOverflowMenu(
    totalCount: Int,
    onSendToNewPhone: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onShowBackupKey: () -> Unit,
    onRequestRotateKey: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onImportCsv: () -> Unit,
    onRunPasswordHealth: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    helpDialog?.let { (helpTitle, helpDescription) ->
        AlertDialog(
            onDismissRequest = { helpDialog = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = helpTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = helpDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(onClick = { helpDialog = null }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options_desc)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            MenuSectionHeader(title = stringResource(R.string.menu_section_migration))

            val sendTitle = stringResource(R.string.menu_send_to_new_phone)
            val sendHelp = stringResource(R.string.menu_send_to_new_phone_help, totalCount)
            CompactMenuItemWithInfo(
                title = sendTitle,
                leadingIcon = Icons.Default.PhonelinkSetup,
                onClick = {
                    expanded = false
                    onSendToNewPhone()
                },
                onInfoClick = { helpDialog = sendTitle to sendHelp }
            )

            val scanTitle = stringResource(R.string.menu_scan_qr)
            val scanHelp = stringResource(R.string.menu_scan_qr_help)
            CompactMenuItemWithInfo(
                title = scanTitle,
                leadingIcon = Icons.Default.QrCodeScanner,
                onClick = {
                    expanded = false
                    onOpenQrScanner()
                },
                onInfoClick = { helpDialog = scanTitle to scanHelp }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            MenuSectionHeader(title = stringResource(R.string.menu_section_backup))

            val keyTitle = stringResource(R.string.menu_backup_unlock_key)
            val keyHelp = stringResource(R.string.menu_backup_unlock_key_help)
            CompactMenuItemWithInfo(
                title = keyTitle,
                leadingIcon = Icons.Default.Key,
                onClick = {
                    expanded = false
                    onShowBackupKey()
                },
                onInfoClick = { helpDialog = keyTitle to keyHelp }
            )

            val rotateTitle = stringResource(R.string.menu_rotate_key)
            val rotateHelp = stringResource(R.string.menu_rotate_key_help)
            CompactMenuItemWithInfo(
                title = rotateTitle,
                leadingIcon = Icons.Default.Refresh,
                onClick = {
                    expanded = false
                    onRequestRotateKey()
                },
                onInfoClick = { helpDialog = rotateTitle to rotateHelp }
            )

            val exportTitle = stringResource(R.string.menu_export_backup)
            val exportHelp = stringResource(R.string.menu_export_backup_help)
            CompactMenuItemWithInfo(
                title = exportTitle,
                leadingIcon = Icons.Default.FileUpload,
                onClick = {
                    expanded = false
                    onExportBackup()
                },
                onInfoClick = { helpDialog = exportTitle to exportHelp }
            )

            val importTitle = stringResource(R.string.menu_import_backup)
            val importHelp = stringResource(R.string.menu_import_backup_help)
            CompactMenuItemWithInfo(
                title = importTitle,
                leadingIcon = Icons.Default.FileDownload,
                onClick = {
                    expanded = false
                    onImportBackup()
                },
                onInfoClick = { helpDialog = importTitle to importHelp }
            )

            val csvTitle = stringResource(R.string.menu_import_csv)
            val csvHelp = stringResource(R.string.menu_import_csv_help)
            CompactMenuItemWithInfo(
                title = csvTitle,
                leadingIcon = Icons.Default.FileDownload,
                onClick = {
                    expanded = false
                    onImportCsv()
                },
                onInfoClick = { helpDialog = csvTitle to csvHelp }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            MenuSectionHeader(title = stringResource(R.string.menu_section_tools))

            val healthTitle = stringResource(R.string.menu_password_health)
            val healthHelp = stringResource(R.string.menu_password_health_help)
            CompactMenuItemWithInfo(
                title = healthTitle,
                leadingIcon = Icons.Default.CheckCircle,
                onClick = {
                    expanded = false
                    onRunPasswordHealth()
                },
                onInfoClick = { helpDialog = healthTitle to healthHelp }
            )

            if (QuickActionNotificationHelper.isSupported()) {
                val helperTitle = stringResource(R.string.menu_notification_helper)
                val helperHelp = stringResource(R.string.menu_notification_helper_help)
                CompactMenuItemWithInfo(
                    title = helperTitle,
                    leadingIcon = Icons.Default.Shield,
                    onClick = {
                        expanded = false
                        QuickActionNotificationHelper.showQuickAccessNotification(
                            context,
                            forceEnable = true
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.notification_helper_added_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onInfoClick = { helpDialog = helperTitle to helperHelp }
                )
            }

            val settingsTitle = stringResource(R.string.menu_settings)
            val settingsHelp = stringResource(R.string.menu_settings_help)
            CompactMenuItemWithInfo(
                title = settingsTitle,
                leadingIcon = Icons.Default.Settings,
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
                onInfoClick = { helpDialog = settingsTitle to settingsHelp }
            )
        }
    }
}
