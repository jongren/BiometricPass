package com.biometricpass.app.ui.components

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Activity-hosted bottom sheet container designed specifically to replace Material 3's popup-based
 * `ModalBottomSheet` inside transparent overlay Activities (`GenerateAndSaveActivity`, `AuthAndPickActivity`).
 *
 * Why this exists:
 * - Material 3's `ModalBottomSheet` creates a separate nested `ComponentDialog` window whose
 *   `OnBackInvokedCallback` / `KEYCODE_BACK` listener intercepts the soft keyboard's "Hide Keyboard"
 *   button (`▼`) on Android 13–16 / Samsung One UI and prematurely calls `onDismissRequest()`,
 *   closing the editor or Autofill Save sheet without saving.
 * - By rendering directly inside the Activity's own `DecorView` window, IME hide events stay bound
 *   to the Activity window, outside taps while typing only hide the keyboard, and optional
 *   [confirmBeforeBackDismiss] prevents accidental unsaved exits.
 */
@Composable
fun AnchoredBottomSheetContainer(
    onDismissRequest: () -> Unit,
    dismissOnOutsideClick: Boolean = false,
    confirmBeforeBackDismiss: Boolean = false,
    unsavedDismissTitle: String = "",
    unsavedDismissBody: String = "",
    unsavedKeepEditingLabel: String = "",
    unsavedDiscardLabel: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val localView = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val rootImeVisible = ViewCompat.getRootWindowInsets(localView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    val isImeVisible = imeBottomPx > 0 || rootImeVisible

    var wasImeVisible by remember { mutableStateOf(false) }
    var lastImeHiddenAtMs by remember { mutableLongStateOf(0L) }
    val openedAtMs = remember { SystemClock.elapsedRealtime() }
    var showUnsavedExitConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            wasImeVisible = true
        } else if (wasImeVisible) {
            wasImeVisible = false
            lastImeHiddenAtMs = SystemClock.elapsedRealtime()
        }
    }

    if (showUnsavedExitConfirm) {
        AlertDialog(
            onDismissRequest = { showUnsavedExitConfirm = false },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            ),
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            },
            title = { Text(unsavedDismissTitle) },
            text = { Text(unsavedDismissBody) },
            confirmButton = {
                Button(onClick = { showUnsavedExitConfirm = false }) {
                    Text(unsavedKeepEditingLabel)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showUnsavedExitConfirm = false
                        onDismissRequest()
                    }
                ) {
                    Text(unsavedDiscardLabel)
                }
            }
        )
    }

    BackHandler(enabled = !showUnsavedExitConfirm) {
        val now = SystemClock.elapsedRealtime()
        val justClosedIme = (now - lastImeHiddenAtMs) < 400L
        val justOpenedOverlay = (now - openedAtMs) < 450L

        when {
            // 1. If the soft keyboard is open (or just closed milliseconds ago from pressing the
            //    keyboard's "Hide Keyboard ▼" button, or the overlay just opened over another App's
            //    closing keyboard), ONLY hide the keyboard and NEVER close the screen.
            isImeVisible || justClosedIme || justOpenedOverlay -> {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            // 2. Protect unsaved edits / Autofill Save captures from accidental Back gestures.
            confirmBeforeBackDismiss -> {
                showUnsavedExitConfirm = true
            }
            // 3. Clean exit when nothing unsaved is at risk.
            else -> {
                onDismissRequest()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isImeVisible) {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                } else if (dismissOnOutsideClick) {
                    onDismissRequest()
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 24.dp)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Consume clicks inside the sheet surface so they never propagate to the backdrop.
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally)
                )
                content()
            }
        }
    }
}
