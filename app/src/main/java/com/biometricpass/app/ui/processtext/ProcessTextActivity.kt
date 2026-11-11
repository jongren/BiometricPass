package com.biometricpass.app.ui.processtext

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.biometricpass.app.ui.autofill.AuthAndPickActivity

/**
 * Entry point for `android.intent.action.PROCESS_TEXT` from the floating text selection menu.
 * Allows summoning the BiometricPass Vault Picker / Password Generator even in apps or custom
 * views that block standard Android Autofill.
 */
class ProcessTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val callerPackage = callingPackage.orEmpty()

        val pickerIntent = AuthAndPickActivity.createIntent(
            context = this,
            credentialId = 0L,
            targetPackage = callerPackage,
            isProcessText = true,
            isProcessTextReadOnly = readOnly
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }

        startActivity(pickerIntent)
        finish()
    }
}
