package com.biometricpass.app.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.biometricpass.app.ui.icons.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.biometricpass.app.R
import com.biometricpass.app.crypto.TransferCode
import com.biometricpass.app.util.AutoLockGuard
import com.biometricpass.app.util.QrCodeHelper

/**
 * Displays a sensitive QR Code (backup unlock key / device migration).
 *
 * - The dialog window is FLAG_SECURE (no screenshots, hidden from Recents).
 * - [transferCode]: shown as large text under a migration QR Code; it is NOT part of the QR Code,
 *   so a photo of the QR Code alone cannot be decrypted.
 * - [allowSaveImage]: saving is only offered for the backup unlock key, and only after a
 *   warning about cloud-synced photo galleries.
 */
@Composable
fun QrDisplayDialog(
    title: String,
    description: String,
    qrPayload: String,
    defaultFileName: String = "biometricpass_qr.png",
    transferCode: String? = null,
    allowSaveImage: Boolean = true,
    extraActionLabel: String? = null,
    onExtraAction: (() -> Unit)? = null,
    onImageSaved: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // A blank payload means "transfer code only" (e.g. Quick Share): no QR Code is rendered.
    val showQr = qrPayload.isNotBlank()
    val qrBitmap: Bitmap? = remember(qrPayload) {
        if (!showQr) return@remember null
        try {
            QrCodeHelper.generateQrBitmap(qrPayload, 768)
        } catch (_: Exception) {
            null
        }
    }
    var showSaveWarning by remember { mutableStateOf(false) }

    val savePngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null && qrBitmap != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                onImageSaved?.invoke()
                Toast.makeText(context, context.getString(R.string.qr_image_saved_toast), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.qr_image_save_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showSaveWarning) {
        AlertDialog(
            onDismissRequest = { showSaveWarning = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.qr_save_warning_title)) },
            text = {
                Text(stringResource(R.string.qr_save_warning_body))
            },
            confirmButton = {
                Button(onClick = {
                    showSaveWarning = false
                    AutoLockGuard.expectExternalActivity()
                    savePngLauncher.launch(defaultFileName)
                }) {
                    Text(stringResource(R.string.qr_save_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = title,
                            modifier = Modifier.size(236.dp)
                        )
                    }
                } else if (showQr) {
                    Text(
                        text = stringResource(R.string.qr_payload_too_large_error),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                if (!transferCode.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.qr_transfer_code_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = TransferCode.format(transferCode),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            letterSpacing = 3.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.qr_transfer_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!extraActionLabel.isNullOrBlank() && onExtraAction != null) {
                    OutlinedButton(
                        onClick = onExtraAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(extraActionLabel)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_close))
                    }
                    if (qrBitmap != null && allowSaveImage) {
                        Button(
                            onClick = { showSaveWarning = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.qr_save_image_btn))
                        }
                    }
                }
            }
        }
    }
}
