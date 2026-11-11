package com.biometricpass.app.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.biometricpass.app.BiometricPassApp
import com.biometricpass.app.BuildConfig
import com.biometricpass.app.R
import com.biometricpass.app.crypto.BiometricAuthHelper
import com.biometricpass.app.crypto.VaultCrypto
import com.biometricpass.app.service.QuickActionNotificationHelper
import com.biometricpass.app.ui.theme.BiometricPassTheme
import com.biometricpass.app.util.AppSettings
import com.biometricpass.app.util.AutoLockGuard
import com.biometricpass.app.util.SensitiveClipboardHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main Vault coordinator Activity.
 *
 * Coordinates biometric unlock, active background auto-lock timers, and hardware-key recovery;
 * UI state and views live in [VaultViewModel], [BackupMigrationViewModel], [UnlockedVaultScreen],
 * and [LockedVaultScreen].
 */
class MainActivity : AppCompatActivity() {

    private val vaultViewModel: VaultViewModel by viewModels()
    private val backupViewModel: BackupMigrationViewModel by viewModels()

    private var isKeyCorrupted by mutableStateOf(false)
    private var authErrorMessage by mutableStateOf<String?>(null)
    private var lockedPendingV2QrPayload by mutableStateOf<String?>(null)
    private var lockedPassphraseError by mutableStateOf<String?>(null)
    private var lockedVerifyingPassphrase by mutableStateOf(false)
    private var incomingBackupUri by mutableStateOf<Uri?>(null)
    private var isPromptingBiometric = false

    /** Elapsed-realtime timestamp when the unlocked vault went to the background (0 = foreground). */
    private var backgroundedAtElapsedMs = 0L
    private var pendingUnlockOnResume = false
    private val backgroundLockHandler = Handler(Looper.getMainLooper())

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                lockVault()
            }
        }
    }

    private fun lockVault() {
        cancelBackgroundLockTimer()
        if (vaultViewModel.lockVault()) {
            backupViewModel.clearSessionOnLock()
            pendingUnlockOnResume = true
        }
    }

    /**
     * Actively wipes the DEK once the auto-lock delay elapses in the background, so keys never
     * linger in memory while the user is in other Apps. [onStart] still re-checks with
     * elapsedRealtime as a backstop, because Handler delays pause during deep sleep.
     */
    private val backgroundLockRunnable = Runnable { lockVault() }

    private fun scheduleBackgroundLockTimer() {
        cancelBackgroundLockTimer()
        val delayMs = AppSettings.backgroundLockDelayMs(
            autoLockSeconds = AppSettings.getAutoLockSeconds(this),
            remainingGraceMs = AutoLockGuard.remainingGraceMs()
        )
        if (delayMs <= 0L) {
            lockVault()
        } else {
            backgroundLockHandler.postDelayed(backgroundLockRunnable, delayMs)
        }
    }

    private fun cancelBackgroundLockTimer() {
        backgroundLockHandler.removeCallbacks(backgroundLockRunnable)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            lockVault()
        } else if (vaultViewModel.unlockedDek != null && !isChangingConfigurations) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
            scheduleBackgroundLockTimer()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SensitiveClipboardHelper.clearDeferredIfNeeded(this)
        }
    }

    override fun onStart() {
        super.onStart()
        cancelBackgroundLockTimer()
        val hasGrace = AutoLockGuard.consumeGrace()
        if (vaultViewModel.unlockedDek != null &&
            AppSettings.shouldAutoLockAfterBackground(
                backgroundedAtElapsedMs = backgroundedAtElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                autoLockSeconds = AppSettings.getAutoLockSeconds(this),
                hasExternalUiGrace = hasGrace
            )
        ) {
            lockVault()
        }
        backgroundedAtElapsedMs = 0L
    }

    override fun onResume() {
        super.onResume()
        if (pendingUnlockOnResume && vaultViewModel.unlockedDek == null && !isKeyCorrupted) {
            pendingUnlockOnResume = false
            window.decorView.post { triggerBiometricUnlock(application as BiometricPassApp) }
        }
    }

    override fun onDestroy() {
        cancelBackgroundLockTimer()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots / screen recording and hide vault content in the Recents thumbnail
        // on production Release builds (relaxed on Debug builds so developers can capture screenshots).
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val app = application as BiometricPassApp
        QuickActionNotificationHelper.showIfEnabled(this)

        extractIncomingBackupUri(intent)?.let { uri ->
            incomingBackupUri = uri
        }

        if (vaultViewModel.unlockedDek == null && !isKeyCorrupted) {
            triggerBiometricUnlock(app)
        }

        setContent {
            BiometricPassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val currentDek by vaultViewModel.unlockedDekFlow.collectAsState()
                    val activeDek = currentDek
                    when {
                        activeDek != null -> {
                            BackHandler {
                                lockVault()
                                finish()
                            }
                            UnlockedVaultScreen(
                                dek = activeDek,
                                vaultViewModel = vaultViewModel,
                                backupViewModel = backupViewModel,
                                incomingBackupUri = incomingBackupUri,
                                onIncomingBackupConsumed = { incomingBackupUri = null }
                            )
                        }

                        isKeyCorrupted -> {
                            KeyCorruptedRecoveryScreen(
                                errorMessage = authErrorMessage,
                                passphraseError = lockedPassphraseError,
                                isVerifyingPassphrase = lockedVerifyingPassphrase,
                                pendingV2QrPayload = lockedPendingV2QrPayload,
                                onDismissPassphraseDialog = {
                                    lockedPendingV2QrPayload = null
                                    lockedPassphraseError = null
                                },
                                onConfirmPassphrase = { qrPayload, passphrase ->
                                    verifyAndRestoreLockedKey(app, qrPayload, passphrase)
                                },
                                onRetryUnlockClick = { triggerBiometricUnlock(app) },
                                onRecoveryQrScanned = { qrPayload ->
                                    handleQrScannedWhileLocked(app, qrPayload)
                                }
                            )
                        }

                        else -> {
                            BackHandler { finish() }
                            LockedVaultScreen(
                                onUnlockClick = { triggerBiometricUnlock(app) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingBackupUri(intent)?.let { uri ->
            incomingBackupUri = uri
        }
    }

    private fun extractIncomingBackupUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }

    private fun triggerBiometricUnlock(app: BiometricPassApp) {
        if (isPromptingBiometric) return
        isPromptingBiometric = true
        authErrorMessage = null
        BiometricAuthHelper.authenticateAndUnlockDek(
            activity = this,
            cryptoManager = app.cryptoManager,
            title = getString(R.string.unlock_vault_title),
            subtitle = getString(R.string.unlock_vault_subtitle),
            onSuccess = { dek ->
                isPromptingBiometric = false
                vaultViewModel.onVaultUnlocked(dek)
                isKeyCorrupted = false
                authErrorMessage = null
            },
            onError = { _ ->
                // Stay on LockedVaultScreen so accidental prompt dismissal doesn't close the app.
                isPromptingBiometric = false
            },
            onKeyCorrupted = { err ->
                isPromptingBiometric = false
                isKeyCorrupted = true
                authErrorMessage = err
            },
            onTransientFailure = { err ->
                isPromptingBiometric = false
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun handleQrScannedWhileLocked(app: BiometricPassApp, qrPayload: String) {
        val trimmed = qrPayload.trim()
        if (!VaultCrypto.isRecoveryQrPayload(trimmed)) {
            Toast.makeText(this, getString(R.string.scan_recovery_qr_prompt_toast), Toast.LENGTH_LONG).show()
            return
        }
        if (VaultCrypto.isPassphraseProtectedRecoveryQr(trimmed)) {
            lockedPassphraseError = null
            lockedPendingV2QrPayload = trimmed
            return
        }
        verifyAndRestoreLockedKey(app, trimmed, passphrase = null)
    }

    private fun verifyAndRestoreLockedKey(
        app: BiometricPassApp,
        qrPayload: String,
        passphrase: String?
    ) {
        lockedVerifyingPassphrase = true
        lifecycleScope.launch {
            val compatible = try {
                val candidate = withContext(Dispatchers.Default) {
                    app.cryptoManager.decodeRecoveryDek(qrPayload, passphrase)
                }
                app.repository.isKeyCompatibleWithVault(candidate)
            } catch (_: VaultCrypto.WrongRecoveryPassphraseException) {
                lockedVerifyingPassphrase = false
                lockedPassphraseError = getString(R.string.passphrase_wrong_retry)
                return@launch
            } catch (_: Exception) {
                lockedVerifyingPassphrase = false
                lockedPendingV2QrPayload = null
                authErrorMessage = getString(R.string.recovery_qr_invalid_error)
                return@launch
            }

            lockedVerifyingPassphrase = false
            lockedPendingV2QrPayload = null

            if (!compatible) {
                authErrorMessage = getString(R.string.recovery_key_mismatch_error)
                return@launch
            }

            BiometricAuthHelper.authenticateForQrRecovery(
                activity = this@MainActivity,
                cryptoManager = app.cryptoManager,
                qrPayload = qrPayload,
                passphrase = passphrase,
                onSuccess = { recoveredDek ->
                    vaultViewModel.onVaultUnlocked(recoveredDek)
                    backupViewModel.markBackupKeySaved()
                    isKeyCorrupted = false
                    authErrorMessage = null
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.vault_restored_toast),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { err ->
                    authErrorMessage = err
                }
            )
        }
    }
}
