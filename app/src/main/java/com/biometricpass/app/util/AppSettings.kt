package com.biometricpass.app.util

import android.content.Context
import android.os.SystemClock
import com.biometricpass.app.R

enum class VaultSortOption {
    RECENT,
    TITLE
}

/**
 * Small user preferences store (non-secret settings only).
 */
object AppSettings {

    private const val PREFS_NAME = "biometric_pass_settings"
    private const val KEY_AUTO_LOCK_SECONDS = "auto_lock_seconds"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"
    private const val KEY_BACKUP_KEY_SAVED = "backup_key_saved"
    private const val KEY_SORT_OPTION = "vault_sort_option"
    private const val KEY_DYNAMIC_COLOR = "use_dynamic_color"

    /** Selectable auto-lock delays (seconds). 0 = lock immediately when leaving the App. */
    val AUTO_LOCK_OPTIONS = listOf(0, 30, 60, 300)
    const val DEFAULT_AUTO_LOCK_SECONDS = 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAutoLockSeconds(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_LOCK_SECONDS, DEFAULT_AUTO_LOCK_SECONDS)

    fun setAutoLockSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_AUTO_LOCK_SECONDS, seconds).apply()
    }

    fun shouldAutoLockAfterBackground(
        backgroundedAtElapsedMs: Long,
        nowElapsedMs: Long,
        autoLockSeconds: Int,
        hasExternalUiGrace: Boolean
    ): Boolean {
        if (hasExternalUiGrace || backgroundedAtElapsedMs <= 0L) return false
        val elapsedMs = (nowElapsedMs - backgroundedAtElapsedMs).coerceAtLeast(0L)
        return elapsedMs >= autoLockSeconds * 1000L
    }

    /**
     * Delay before the vault is actively wiped from memory after it moves to the background.
     * While a trusted external UI (file picker, share sheet) is open, the remaining grace period
     * is used instead so the vault stays unlocked until the user returns.
     */
    fun backgroundLockDelayMs(autoLockSeconds: Int, remainingGraceMs: Long): Long =
        if (remainingGraceMs > 0L) remainingGraceMs else autoLockSeconds.coerceAtLeast(0) * 1000L

    fun autoLockLabel(context: Context, seconds: Int): String = when (seconds) {
        0 -> context.getString(R.string.auto_lock_immediate)
        in 1..59 -> context.getString(R.string.auto_lock_seconds, seconds)
        else -> context.getString(R.string.auto_lock_minutes, seconds / 60)
    }

    fun getSortOption(context: Context): VaultSortOption {
        val raw = prefs(context).getString(KEY_SORT_OPTION, VaultSortOption.RECENT.name)
        return VaultSortOption.entries.firstOrNull { it.name == raw } ?: VaultSortOption.RECENT
    }

    fun setSortOption(context: Context, option: VaultSortOption) {
        prefs(context).edit().putString(KEY_SORT_OPTION, option.name).apply()
    }

    fun isDynamicColorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** Whether the user has confirmed saving the backup unlock key at least once. */
    fun isBackupKeySaved(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKUP_KEY_SAVED, false)

    fun setBackupKeySaved(context: Context, saved: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKUP_KEY_SAVED, saved).apply()
    }
}

/**
 * Lets the vault screen launch trusted system UIs (file picker, share sheet, image picker)
 * without the auto-lock timer locking the vault while the user is busy in that UI.
 */
object AutoLockGuard {
    private const val MAX_GRACE_MS = 10 * 60 * 1000L

    @Volatile
    private var graceUntilElapsedMs: Long = 0L

    /** Call right before launching an external Activity that returns to the vault. */
    fun expectExternalActivity() {
        graceUntilElapsedMs = SystemClock.elapsedRealtime() + MAX_GRACE_MS
    }

    /** Returns true (once) when the current return to the App came from such an external Activity. */
    fun consumeGrace(): Boolean {
        val active = SystemClock.elapsedRealtime() < graceUntilElapsedMs
        graceUntilElapsedMs = 0L
        return active
    }

    /** Cancels a grace period that turned out not to be needed (e.g. an in-window biometric prompt). */
    fun clearGrace() {
        graceUntilElapsedMs = 0L
    }

    /** Remaining grace time in milliseconds without consuming it (0 when no grace is active). */
    fun remainingGraceMs(): Long =
        (graceUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
}
