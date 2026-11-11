package com.biometricpass.app.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biometricpass.app.R
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.ui.icons.ContentCopy
import com.biometricpass.app.ui.icons.Key
import com.biometricpass.app.ui.icons.Shield
import com.biometricpass.app.ui.icons.Visibility
import com.biometricpass.app.ui.icons.VisibilityOff
import com.biometricpass.app.util.InstalledAppHelper
import com.biometricpass.app.util.PasswordHealthAnalyzer

data class QrModalInfo(
    val title: String,
    val description: String,
    val payload: String,
    val defaultFileName: String,
    val transferCode: String? = null,
    val allowSaveImage: Boolean = false,
    val extraActionLabel: String? = null,
    val onExtraAction: (() -> Unit)? = null,
    val onImageSaved: (() -> Unit)? = null
)

@Composable
fun MenuSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
fun CompactMenuItemWithInfo(
    title: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.menu_item_info_desc, title),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick
    )
}

@Composable
fun AutofillStatusBanner(
    onOpenAutofillSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.autofill_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.autofill_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onOpenAutofillSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.autofill_banner_action))
            }
        }
    }
}

@Composable
fun BackupKeyReminderBanner(
    onSaveNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_key_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.backup_key_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            TextButton(onClick = onSaveNow) {
                Text(
                    text = stringResource(R.string.backup_key_banner_action),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun CredentialCardItem(
    item: CredentialEntity,
    revealedPassword: String?,
    onToggleRevealPassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(item.packageName) {
        InstalledAppHelper.getAppIcon(context, item.packageName)
    }
    var isExpanded by rememberSaveable(item.id) { mutableStateOf(false) }

    fun toggleExpanded() {
        if (isExpanded && revealedPassword != null) {
            onToggleRevealPassword()
        }
        isExpanded = !isExpanded
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { toggleExpanded() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = item.title,
                        modifier = Modifier.size(42.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Key,
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val appLabel = remember(item.packageName) {
                        if (item.packageName.isBlank()) null
                        else InstalledAppHelper.getAppLabel(context, item.packageName)
                    }
                    val subtitle = buildString {
                        if (item.packageName.isNotBlank()) append(appLabel ?: item.packageName)
                        if (item.webDomain.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(item.webDomain)
                        }
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = { toggleExpanded() }) {
                    Icon(
                        imageVector = if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (isExpanded) {
                            stringResource(R.string.card_collapse_desc)
                        } else {
                            stringResource(R.string.card_expand_desc)
                        }
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Username row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.field_username),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = item.username.ifBlank { stringResource(R.string.field_username_empty) },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (item.username.isNotBlank()) {
                            TextButton(onClick = onCopyUsername) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_copy_username))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Password row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.field_password),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = revealedPassword ?: "••••••••••••••••",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = if (revealedPassword != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onToggleRevealPassword) {
                                Icon(
                                    imageVector = if (revealedPassword != null) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = stringResource(R.string.toggle_password_visibility_desc)
                                )
                            }
                            TextButton(onClick = onCopyPassword) {
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
            }
        }
    }
}

@Composable
fun PasswordHealthDialog(
    report: PasswordHealthAnalyzer.PasswordHealthReport,
    onEditIssueItem: (PasswordHealthAnalyzer.HealthIssueItem) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (report.hasIssues) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (report.hasIssues) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
            )
        },
        title = { Text(stringResource(R.string.menu_password_health)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (report.hasIssues) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val color = if (report.hasIssues) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                        Text(
                            text = if (report.totalCount == 0) {
                                stringResource(R.string.health_empty)
                            } else if (!report.hasIssues) {
                                stringResource(R.string.health_all_good, report.totalCount)
                            } else {
                                stringResource(R.string.health_summary_counts, report.totalCount, report.healthyCount)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.health_local_privacy_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }

                if (report.reusedItems.isNotEmpty()) {
                    HealthIssueSection(
                        header = stringResource(R.string.health_section_reused, report.reusedItems.size),
                        items = report.reusedItems,
                        onEditItem = onEditIssueItem
                    )
                }
                if (report.weakItems.isNotEmpty()) {
                    HealthIssueSection(
                        header = stringResource(R.string.health_section_weak, report.weakItems.size),
                        items = report.weakItems,
                        onEditItem = onEditIssueItem
                    )
                }
                if (report.outdatedItems.isNotEmpty()) {
                    HealthIssueSection(
                        header = stringResource(R.string.health_section_outdated, report.outdatedItems.size),
                        items = report.outdatedItems,
                        onEditItem = onEditIssueItem
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun HealthIssueSection(
    header: String,
    items: List<PasswordHealthAnalyzer.HealthIssueItem>,
    onEditItem: (PasswordHealthAnalyzer.HealthIssueItem) -> Unit
) {
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = header,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(6.dp))
    items.forEach { issue ->
        val detailText = when (val reason = issue.reason) {
            is PasswordHealthAnalyzer.IssueReason.TooShort ->
                stringResource(R.string.health_reason_too_short, reason.length, reason.minLength)
            PasswordHealthAnalyzer.IssueReason.SingleCharClass ->
                stringResource(R.string.health_reason_single_class)
            is PasswordHealthAnalyzer.IssueReason.OutdatedMonths ->
                stringResource(R.string.health_reason_outdated_months, reason.months)
            is PasswordHealthAnalyzer.IssueReason.ReusedWithSingle ->
                stringResource(R.string.health_reason_reused_single, reason.otherTitle)
            is PasswordHealthAnalyzer.IssueReason.ReusedWithMultiple ->
                stringResource(R.string.health_reason_reused_multiple, reason.firstTitle, reason.otherCount)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (issue.username.isNotBlank()) {
                        stringResource(R.string.health_item_title_with_user, issue.title, issue.username)
                    } else {
                        issue.title
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onEditItem(issue) }) {
                Text(stringResource(R.string.action_update))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    }
}
