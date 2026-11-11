package com.biometricpass.app.ui.autofill

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
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.ui.generate.GenerateAndSaveActivity
import com.biometricpass.app.ui.theme.BiometricPassTheme
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.SensitiveClipboardHelper
import kotlinx.coroutines.launch
import javax.crypto.SecretKey

/**
 * Transparent Biometric & Picker coordinator Activity:
 * 1. When `credentialId > 0` (tapped a matched Autofill chip): verifies biometrics, builds the
 *    Autofill result Dataset via [AutofillDatasetFactory], and finishes immediately.
 * 2. When `credentialId == 0` (Vault Picker chip, Quick Settings Tile, Notification shortcut, or
 *    `ACTION_PROCESS_TEXT`): verifies biometrics and renders [PickerBottomSheetContent] backed by
 *    [PickerViewModel].
 */
class AuthAndPickActivity : AppCompatActivity() {

    private val pickerViewModel: PickerViewModel by viewModels()

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
        pickerViewModel.setTargetContext(target.targetPackage, target.targetWebDomain)

        if (target.credentialId > 0L) {
            BiometricAuthHelper.authenticateAndUnlockDek(
                activity = this,
                cryptoManager = app.cryptoManager,
                title = getString(R.string.auth_autofill_title),
                subtitle = getString(R.string.auth_autofill_subtitle),
                onSuccess = { dek ->
                    lifecycleScope.launch {
                        val entity = pickerViewModel.getCredentialById(target.credentialId)
                        if (entity == null) {
                            Toast.makeText(
                                this@AuthAndPickActivity,
                                getString(R.string.auth_credential_not_found),
                                Toast.LENGTH_SHORT
                            ).show()
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                            return@launch
                        }
                        if (!pickerViewModel.isSignatureTrustedForTarget(entity, target.targetPackage)) {
                            // Downgrade to manual picker UI with signature warning instead of auto-filling
                            pickerViewModel.onUnlocked(dek)
                            showPickerContent(app, target)
                            return@launch
                        }
                        deliverCredentialAndFinish(entity, dek, target)
                    }
                },
                onError = { err ->
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            )
            return
        }

        if (pickerViewModel.unlockedDek == null) {
            promptPickerUnlock(app)
        }

        showPickerContent(app, target)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SensitiveClipboardHelper.clearDeferredIfNeeded(this)
        }
    }

    private fun showPickerContent(app: BiometricPassApp, target: AutofillTargetRequest) {
        setContent {
            BiometricPassTheme {
                val uiState by pickerViewModel.uiState.collectAsState()
                val unlockedDek by pickerViewModel.unlockedDekFlow.collectAsState()

                PickerBottomSheetContent(
                    uiState = uiState,
                    unlockedDek = unlockedDek,
                    canDirectFill = target.canDirectFill,
                    confirmUnlinkedCredential = target.canReplaceProcessText,
                    onSearchQueryChange = pickerViewModel::onSearchQueryChange,
                    onRequestUnlock = { promptPickerUnlock(app) },
                    onOpenGenerator = { openGeneratorAndFinish(target) },
                    onAutoFillCredential = { entity, dek ->
                        deliverCredentialAndFinish(entity, dek, target)
                    },
                    onCopyPassword = { entity, dek -> copyPasswordOnly(entity, dek) },
                    onDismiss = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    private fun promptPickerUnlock(app: BiometricPassApp) {
        BiometricAuthHelper.authenticateAndUnlockDek(
            activity = this,
            cryptoManager = app.cryptoManager,
            title = getString(R.string.auth_picker_title),
            subtitle = getString(R.string.auth_picker_subtitle),
            onSuccess = { dek -> pickerViewModel.onUnlocked(dek) },
            onError = { err ->
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun openGeneratorAndFinish(target: AutofillTargetRequest) {
        val genIntent = GenerateAndSaveActivity.createIntent(
            context = this,
            targetPackage = target.targetPackage,
            targetWebDomain = target.targetWebDomain,
            initialUsername = target.liveUsernameText,
            usernameAutofillId = target.usernameAutofillId,
            passwordAutofillId = target.passwordAutofillId ?: target.focusedAutofillId,
            focusedAutofillId = target.focusedAutofillId,
            isProcessText = target.canReplaceProcessText,
            slotSessionToken = target.slotSessionToken,
            slotCategory = target.slotCategory
        )
        if (target.hasAutofillIds || target.isProcessText) {
            genIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }
        startActivity(genIntent)
        finish()
    }

    private fun copyPasswordOnly(entity: CredentialEntity, dek: SecretKey) {
        try {
            val plainPassword = pickerViewModel.decryptPassword(entity, dek)
            SensitiveClipboardHelper.copySensitive(
                context = this,
                label = getString(R.string.clipboard_label_item_password, entity.title),
                secretText = plainPassword,
                showToast = true
            )
        } catch (e: Exception) {
            AppLog.w("AuthAndPickActivity", "Failed to decrypt credential for clipboard copy", e)
            Toast.makeText(
                this,
                getString(R.string.decrypt_error_with_reason, e.localizedMessage.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deliverCredentialAndFinish(
        entity: CredentialEntity,
        dek: SecretKey,
        target: AutofillTargetRequest
    ) {
        try {
            val plainPassword = pickerViewModel.decryptPassword(entity, dek)

            // 1. Autofill direct dataset delivery (no clipboard touch)
            val autofillResultIntent = AutofillDatasetFactory.buildCredentialFillResultIntent(
                context = this,
                target = target,
                entity = entity,
                plainPassword = plainPassword
            )
            if (autofillResultIntent != null) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_autofilled_item, entity.title),
                    Toast.LENGTH_SHORT
                ).show()
                setResult(Activity.RESULT_OK, autofillResultIntent)
                finish()
                return
            }

            // 2. Editable ACTION_PROCESS_TEXT selection replacement (no clipboard touch)
            if (target.canReplaceProcessText) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_filled_password_item, entity.title),
                    Toast.LENGTH_SHORT
                ).show()
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, plainPassword)
                )
                finish()
                return
            }

            // 3. Standalone picker mode (Notification shortcut / Quick Settings Tile): sensitive clipboard
            SensitiveClipboardHelper.copySensitive(
                context = this,
                label = getString(R.string.clipboard_label_item_password, entity.title),
                secretText = plainPassword,
                showToast = false
            )
            Toast.makeText(
                this,
                getString(R.string.toast_copied_password_paste, entity.title),
                Toast.LENGTH_SHORT
            ).show()
            setResult(Activity.RESULT_OK)
            finish()
        } catch (e: Exception) {
            AppLog.w("AuthAndPickActivity", "Failed to deliver credential", e)
            Toast.makeText(
                this,
                getString(R.string.decrypt_error_with_reason, e.localizedMessage.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        fun createIntent(
            context: Context,
            credentialId: Long = 0L,
            targetPackage: String = "",
            targetWebDomain: String = "",
            usernameAutofillId: AutofillId? = null,
            passwordAutofillId: AutofillId? = null,
            focusedAutofillId: AutofillId? = null,
            allEditableAutofillIds: ArrayList<AutofillId>? = null,
            isProcessText: Boolean = false,
            isProcessTextReadOnly: Boolean = false,
            slotSessionToken: Int = 0,
            slotCategory: String = ""
        ): Intent {
            return Intent(context, AuthAndPickActivity::class.java).also { intent ->
                AutofillTargetRequest(
                    credentialId = credentialId,
                    targetPackage = targetPackage,
                    targetWebDomain = targetWebDomain,
                    usernameAutofillId = usernameAutofillId,
                    passwordAutofillId = passwordAutofillId,
                    focusedAutofillId = focusedAutofillId,
                    allEditableAutofillIds = allEditableAutofillIds.orEmpty(),
                    isProcessText = isProcessText,
                    isProcessTextReadOnly = isProcessTextReadOnly,
                    slotSessionToken = slotSessionToken,
                    slotCategory = slotCategory
                ).writeToIntent(intent)
            }
        }
    }
}
