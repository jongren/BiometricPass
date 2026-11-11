package com.biometricpass.app.ui.generate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.BuildConfig
import com.biometricpass.app.R
import com.biometricpass.app.autofill.AutofillDatasetFactory
import com.biometricpass.app.autofill.AutofillTargetRequest
import com.biometricpass.app.crypto.BiometricAuthHelper
import com.biometricpass.app.crypto.InMemorySecretRelay
import com.biometricpass.app.ui.theme.BiometricPassTheme
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.InstalledAppHelper
import com.biometricpass.app.util.SensitiveClipboardHelper
import kotlinx.coroutines.launch

/**
 * Transparent ModalBottomSheet coordinator Activity for password generation, Autofill saving, and
 * vault credential creation/editing.
 *
 * State and persistence live in [GenerateAndSaveViewModel]; Compose UI lives in
 * [GenerateAndSaveSheetContent]; Autofill result Datasets are built by [AutofillDatasetFactory].
 */
class GenerateAndSaveActivity : AppCompatActivity() {

    private val viewModel: GenerateAndSaveViewModel by viewModels()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SensitiveClipboardHelper.clearDeferredIfNeeded(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        val app = application as BiometricPassApp
        val target = AutofillTargetRequest.fromIntent(intent, packageName)
        val initialUsername = target.liveUsernameText.ifBlank {
            intent.getStringExtra(EXTRA_INITIAL_USERNAME).orEmpty()
        }
        val initialTitle = intent.getStringExtra(EXTRA_INITIAL_TITLE).orEmpty().ifBlank {
            InstalledAppHelper.getAppLabel(this, target.targetPackage)
                ?: target.targetWebDomain.ifBlank { target.targetPackage }
        }
        val isFromAutofillSave = intent.getBooleanExtra(EXTRA_IS_AUTOFILL_SAVE, false)
        val isCompactFillMode = target.hasDirectAutofillFields || target.isProcessText

        // Capture the vault session DEK synchronously at launch: MainActivity may auto-lock behind
        // this transparent sheet before a coroutine runs.
        val sessionDekAtLaunch = if (target.credentialId > 0L) {
            InMemorySecretRelay.activeVaultDek
        } else {
            null
        }

        viewModel.initializeIfNeeded(
            credentialId = target.credentialId,
            initialTitle = initialTitle,
            initialPackageName = target.targetPackage,
            initialWebDomain = target.targetWebDomain,
            initialUsername = initialUsername,
            saveSecretToken = intent.getStringExtra(EXTRA_SAVE_SECRET_TOKEN),
            sessionDekAtLaunch = sessionDekAtLaunch,
            isCompactFillMode = isCompactFillMode,
            isFromAutofillSave = isFromAutofillSave
        )

        setContent {
            BiometricPassTheme {
                val uiState by viewModel.uiState.collectAsState()

                GenerateAndSaveSheetContent(
                    uiState = uiState,
                    onTitleChange = viewModel::onTitleChange,
                    onUsernameChange = viewModel::onUsernameChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onPackageNameChange = viewModel::onPackageNameChange,
                    onWebDomainChange = viewModel::onWebDomainChange,
                    onAppPicked = viewModel::onAppPicked,
                    onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                    onSelectMode = viewModel::selectMode,
                    onLengthChange = viewModel::onLengthChange,
                    onRegenerate = viewModel::regeneratePassword,
                    onToggleUpper = viewModel::toggleUpper,
                    onToggleLower = viewModel::toggleLower,
                    onToggleDigits = viewModel::toggleDigits,
                    onToggleSymbols = viewModel::toggleSymbols,
                    onDismiss = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onConfirmSave = {
                        promptBiometricAndSave(
                            app = app,
                            target = target,
                            isCompactFillMode = uiState.isCompactFillMode,
                            isFromAutofillSave = uiState.isFromAutofillSave,
                            isUpdateAction = uiState.isUpdateAction
                        )
                    }
                )
            }
        }
    }

    private fun promptBiometricAndSave(
        app: BiometricPassApp,
        target: AutofillTargetRequest,
        isCompactFillMode: Boolean,
        isFromAutofillSave: Boolean,
        isUpdateAction: Boolean
    ) {
        BiometricAuthHelper.authenticateAndUnlockDek(
            activity = this,
            cryptoManager = app.cryptoManager,
            title = getString(
                if (isUpdateAction) R.string.gen_biometric_title_update else R.string.gen_biometric_title_save
            ),
            subtitle = when {
                isCompactFillMode && isUpdateAction -> getString(R.string.gen_biometric_subtitle_compact_update)
                isCompactFillMode -> getString(R.string.gen_biometric_subtitle_compact_save)
                isUpdateAction -> getString(R.string.gen_biometric_subtitle_full_update)
                else -> getString(R.string.gen_biometric_subtitle_full_save)
            },
            onSuccess = { dek ->
                lifecycleScope.launch {
                    try {
                        val saved = viewModel.saveCredential(
                            dek = dek,
                            defaultUntitledTitle = getString(R.string.default_untitled_item),
                            defaultCustomTitle = getString(R.string.default_custom_password_title)
                        )
                        deliverSavedResultAndFinish(saved, target, isFromAutofillSave)
                    } catch (e: Exception) {
                        AppLog.w("GenerateActivity", "Failed to encrypt and save credential", e)
                        Toast.makeText(
                            this@GenerateAndSaveActivity,
                            getString(R.string.gen_error_with_reason, e.localizedMessage.orEmpty()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onError = { errMsg ->
                Toast.makeText(this, errMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun deliverSavedResultAndFinish(
        saved: SavedCredentialOutcome,
        target: AutofillTargetRequest,
        isFromAutofillSave: Boolean
    ) {
        // 1. Direct Autofill dataset delivery
        val autofillResult = AutofillDatasetFactory.buildGeneratedFillResultIntent(
            context = this,
            target = target,
            username = saved.username,
            plainPassword = saved.password
        )
        if (autofillResult != null) {
            Toast.makeText(
                this,
                getString(
                    if (saved.isUpdate) R.string.gen_toast_autofilled_updated else R.string.gen_toast_autofilled_saved
                ),
                Toast.LENGTH_SHORT
            ).show()
            setResult(Activity.RESULT_OK, autofillResult)
            finish()
            return
        }

        // 2. ACTION_PROCESS_TEXT replacement
        if (target.isProcessText) {
            Toast.makeText(
                this,
                getString(
                    if (saved.isUpdate) R.string.gen_toast_filled_updated else R.string.gen_toast_filled_saved
                ),
                Toast.LENGTH_SHORT
            ).show()
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, saved.password)
            )
            finish()
            return
        }

        // 3. Autofill Save or Standalone Save/Update (Notification shortcut / Vault FAB / Edit)
        if (!isFromAutofillSave && target.credentialId == 0L) {
            SensitiveClipboardHelper.copySensitive(
                context = this,
                label = getString(R.string.clipboard_label_item_password, saved.finalTitle),
                secretText = saved.password,
                showToast = false
            )
            Toast.makeText(
                this,
                getString(
                    if (saved.isUpdate) R.string.gen_toast_copied_updated else R.string.gen_toast_copied_saved
                ),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                getString(if (saved.isUpdate) R.string.gen_toast_updated else R.string.gen_toast_saved),
                Toast.LENGTH_SHORT
            ).show()
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_INITIAL_TITLE = "extra_initial_title"
        const val EXTRA_INITIAL_USERNAME = "extra_initial_username"
        const val EXTRA_SAVE_SECRET_TOKEN = "extra_save_secret_token"
        const val EXTRA_IS_AUTOFILL_SAVE = "extra_is_autofill_save"

        fun createIntent(
            context: Context,
            credentialId: Long = 0L,
            initialTitle: String = "",
            targetPackage: String = "",
            targetWebDomain: String = "",
            initialUsername: String = "",
            saveSecretToken: String = "",
            usernameAutofillId: AutofillId? = null,
            passwordAutofillId: AutofillId? = null,
            focusedAutofillId: AutofillId? = null,
            isAutofillSave: Boolean = false,
            isProcessText: Boolean = false,
            slotSessionToken: Int = 0,
            slotCategory: String = ""
        ): Intent {
            return Intent(context, GenerateAndSaveActivity::class.java).also { intent ->
                AutofillTargetRequest(
                    credentialId = credentialId,
                    targetPackage = targetPackage,
                    targetWebDomain = targetWebDomain,
                    usernameAutofillId = usernameAutofillId,
                    passwordAutofillId = passwordAutofillId,
                    focusedAutofillId = focusedAutofillId,
                    isProcessText = isProcessText,
                    slotSessionToken = slotSessionToken,
                    slotCategory = slotCategory
                ).writeToIntent(intent)
                intent.putExtra(EXTRA_INITIAL_TITLE, initialTitle)
                intent.putExtra(EXTRA_INITIAL_USERNAME, initialUsername)
                if (saveSecretToken.isNotBlank()) {
                    intent.putExtra(EXTRA_SAVE_SECRET_TOKEN, saveSecretToken)
                }
                intent.putExtra(EXTRA_IS_AUTOFILL_SAVE, isAutofillSave)
            }
        }
    }
}
