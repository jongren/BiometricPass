package com.biometricpass.app.crypto

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.biometricpass.app.R
import com.biometricpass.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.SecretKey

/**
 * Helper for invoking Android [BiometricPrompt] with adaptive fallback to Device Credential
 * (Pattern / PIN / Password):
 * - When Class 3 strong biometrics are available & enrolled: shows a single unified system prompt
 *   with fingerprint + the native `"Use pattern / PIN"` button (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`).
 * - When the device has NO fingerprint hardware, no enrolled biometrics, or the fingerprint sensor
 *   fails / hits lockout (`ERROR_HW_UNAVAILABLE`, `ERROR_LOCKOUT`, etc.): automatically falls back
 *   to pure Device Credential (Pattern / PIN / Password) so the user is never locked out.
 */
object BiometricAuthHelper {

    private const val ALLOWED_STRONG_OR_CREDENTIAL =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private const val FALLBACK_FRAGMENT_TAG = "bp_device_credential_fallback"

    internal fun shouldFallbackToDeviceCredential(errorCode: Int): Boolean {
        return when (errorCode) {
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> true
            else -> false
        }
    }

    private fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    private fun isStrongBiometricReady(context: Context): Boolean {
        return try {
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    private fun buildUnifiedPromptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(ALLOWED_STRONG_OR_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }

    /**
     * Prompts the user for fingerprint / biometric or device credential fallback,
     * and upon success initializes or unwraps the DEK from AndroidKeyStore.
     *
     * - [onKeyCorrupted] is invoked ONLY for permanent failures ([CryptoManager.VaultKeyCorruptedException]).
     * - [onTransientFailure] is invoked for retryable Keystore errors after a successful prompt
     *   (defaults to [onError]), so a temporary glitch never leads into the destructive recovery flow.
     */
    fun authenticateAndUnlockDek(
        activity: FragmentActivity,
        cryptoManager: CryptoManager,
        title: String = activity.getString(R.string.biometric_default_title),
        subtitle: String = activity.getString(R.string.biometric_default_subtitle),
        onSuccess: (SecretKey) -> Unit,
        onError: (String) -> Unit,
        onKeyCorrupted: ((String) -> Unit)? = null,
        onTransientFailure: ((String) -> Unit)? = null
    ) {
        if (!isDeviceSecure(activity)) {
            onError(activity.getString(R.string.biometric_no_screen_lock_error))
            return
        }

        val handleAuthenticated = {
            try {
                val dek = cryptoManager.initializeOrUnlockDek()
                onSuccess(dek)
            } catch (e: CryptoManager.VaultKeyCorruptedException) {
                AppLog.w("BiometricAuthHelper", "Permanent Keystore key failure", e)
                val msg = activity.getString(R.string.biometric_key_unreadable)
                (onKeyCorrupted ?: onError)(msg)
            } catch (e: Exception) {
                AppLog.w("BiometricAuthHelper", "Transient Keystore unlock failure", e)
                val msg = activity.getString(R.string.biometric_unlock_retry_error)
                (onTransientFailure ?: onError)(msg)
            }
        }

        authenticateAdaptive(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onAuthenticated = handleAuthenticated,
            onError = onError
        )
    }

    /**
     * Prompts the user for biometric / device credential authentication before re-wrapping a DEK
     * recovered from a backup unlock key QR Code (V1 or V2) on the locked recovery screen.
     */
    fun authenticateForQrRecovery(
        activity: FragmentActivity,
        cryptoManager: CryptoManager,
        qrPayload: String,
        passphrase: String? = null,
        onSuccess: (SecretKey) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isDeviceSecure(activity)) {
            onError(activity.getString(R.string.biometric_no_screen_lock_error))
            return
        }

        // Decode & verify the QR payload (including PBKDF2 passphrase derivation) BEFORE prompting
        // so: (1) a wrong passphrase fails immediately without prompting biometric, and (2) post-auth
        // Keystore wrapping completes in milliseconds well inside the 5-second Keystore window.
        activity.lifecycleScope.launch {
            val decodeOutcome = withContext(Dispatchers.Default) {
                try {
                    Result.success(VaultCrypto.decodeRecoveryQrPayload(qrPayload, passphrase))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val decoded = decodeOutcome.getOrElse { e ->
                if (e is VaultCrypto.WrongRecoveryPassphraseException) {
                    onError(activity.getString(R.string.biometric_recovery_wrong_passphrase))
                } else {
                    onError(activity.getString(R.string.biometric_recovery_failed))
                }
                return@launch
            }

            try {
                // Prepare the new Keystore KEK BEFORE showing BiometricPrompt so the user's authentication
                // event timestamp is newer than the key creation timestamp.
                cryptoManager.prepareKeystoreKekForRecovery()
            } catch (_: Exception) {
                decoded.kek2Bytes.fill(0)
                decoded.dekBytes.fill(0)
                onError(activity.getString(R.string.biometric_keystore_init_error))
                return@launch
            }

            authenticateAdaptive(
                activity = activity,
                title = activity.getString(R.string.biometric_recovery_title),
                subtitle = activity.getString(R.string.biometric_recovery_subtitle),
                onAuthenticated = {
                    try {
                        val dek = cryptoManager.rewrapDecodedRecoveryPayload(decoded)
                        onSuccess(dek)
                    } catch (e: Exception) {
                        AppLog.w("BiometricAuthHelper", "Failed to rewrap decoded recovery key", e)
                        decoded.kek2Bytes.fill(0)
                        decoded.dekBytes.fill(0)
                        onError(activity.getString(R.string.biometric_recovery_failed))
                    }
                },
                onError = { err ->
                    decoded.kek2Bytes.fill(0)
                    decoded.dekBytes.fill(0)
                    onError(err)
                }
            )
        }
    }

    private fun authenticateAdaptive(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        // If the phone has no fingerprint hardware, no biometrics enrolled, or the sensor is offline,
        // skip the biometric prompt and go straight to Pattern / PIN / Password.
        if (!isStrongBiometricReady(activity)) {
            launchDeviceCredentialOnly(activity, title, subtitle, onAuthenticated, onError)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = buildUnifiedPromptInfo(title, subtitle)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (shouldFallbackToDeviceCredential(errorCode) &&
                        !activity.isFinishing && !activity.isDestroyed
                    ) {
                        AppLog.d(
                            "BiometricAuthHelper",
                            "Biometric unavailable/locked out (code=$errorCode); falling back to device credential"
                        )
                        launchDeviceCredentialOnly(activity, title, subtitle, onAuthenticated, onError)
                    } else {
                        onError(errString.toString())
                    }
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    private fun launchDeviceCredentialOnly(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = ContextCompat.getMainExecutor(activity)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()

            val credentialPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onAuthenticated()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onError(errString.toString())
                    }
                }
            )
            credentialPrompt.authenticate(promptInfo)
        } else {
            // Android 10 (API 29): BiometricPrompt does not accept DEVICE_CREDENTIAL alone, so use
            // KeyguardManager.createConfirmDeviceCredentialIntent via a headless Fragment.
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            @Suppress("DEPRECATION")
            val confirmIntent = km?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (confirmIntent == null) {
                onError(activity.getString(R.string.biometric_no_screen_lock_error))
                return
            }
            val fm = activity.supportFragmentManager
            (fm.findFragmentByTag(FALLBACK_FRAGMENT_TAG) as? DeviceCredentialFallbackFragment)?.let { stale ->
                fm.beginTransaction().remove(stale).commitNowAllowingStateLoss()
            }
            val fragment = DeviceCredentialFallbackFragment().apply {
                this.onAuthenticated = onAuthenticated
                this.onCancelled = { onError(activity.getString(android.R.string.cancel)) }
            }
            fm.beginTransaction().add(fragment, FALLBACK_FRAGMENT_TAG).commitNowAllowingStateLoss()
            fragment.launchConfirmIntent(confirmIntent)
        }
    }

    /**
     * Headless Fragment used exclusively on Android 10 (API 29) to launch and receive the result of
     * `KeyguardManager.createConfirmDeviceCredentialIntent` when biometric hardware is absent or locked out.
     */
    class DeviceCredentialFallbackFragment : Fragment() {
        internal var onAuthenticated: (() -> Unit)? = null
        internal var onCancelled: (() -> Unit)? = null

        fun launchConfirmIntent(intent: Intent) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CONFIRM_CREDENTIAL)
        }

        @Deprecated("Used only for API 29 ConfirmDeviceCredential fallback")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode != REQUEST_CONFIRM_CREDENTIAL) return
            val successCb = onAuthenticated
            val cancelCb = onCancelled
            onAuthenticated = null
            onCancelled = null
            parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
            if (resultCode == Activity.RESULT_OK) {
                successCb?.invoke()
            } else {
                cancelCb?.invoke()
            }
        }

        private companion object {
            const val REQUEST_CONFIRM_CREDENTIAL = 9401
        }
    }
}
