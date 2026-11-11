package com.biometricpass.app.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import com.biometricpass.app.R

/**
 * Helper for copying credentials to the system clipboard marked as sensitive
 * (`ClipDescription.EXTRA_IS_SENSITIVE`) and automatically clearing the clipboard after 30 seconds.
 *
 * - Only clears the clipboard if it still contains the item BiometricPass put there, so anything
 *   the user copied afterwards is never wiped.
 * - On Android 13+ (API 33+), the OS natively masks sensitive clipboard previews and auto-expires
 *   clipboard content; if the user is typing in another App at the 30-second mark, clearing is
 *   deferred until BiometricPass next gains window focus instead of launching a 1x1 Activity that
 *   would briefly steal window focus or dismiss the soft keyboard (`U2`).
 */
object SensitiveClipboardHelper {

    private const val AUTO_CLEAR_DELAY_MS = 30_000L

    /** Extra key marking clips created by BiometricPass, with a per-copy random token as value. */
    internal const val EXTRA_OWNER_TOKEN = "com.biometricpass.app.extra.CLIP_TOKEN"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingClearRunnable: Runnable? = null

    @Volatile
    internal var currentToken: String? = null
        private set

    @Volatile
    private var deferredClearToken: String? = null

    fun copySensitive(
        context: Context,
        label: String,
        secretText: String,
        showToast: Boolean = true
    ) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return

        val token = java.util.UUID.randomUUID().toString()
        val clipData = ClipData.newPlainText(label, secretText)
        val extras = PersistableBundle().apply {
            val extraKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            putBoolean(extraKey, true)
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putBoolean("com.samsung.android.content.clip.EXTRA_IS_SENSITIVE", true)
            putString(EXTRA_OWNER_TOKEN, token)
        }
        clipData.description.extras = extras
        clipboard.setPrimaryClip(clipData)
        currentToken = token
        deferredClearToken = null

        if (showToast) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.clipboard_copied_toast, label),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Cancel any previous clear timer and schedule a new 30-second auto-clear
        pendingClearRunnable?.let { mainHandler.removeCallbacks(it) }
        val clearRunnable = Runnable {
            // Try direct clear first (succeeds with zero focus change when BiometricPass is focused)
            when (ClipboardClearActivity.clearIfStillOurs(appContext, token)) {
                ClipboardClearActivity.ClearResult.CLEARED,
                ClipboardClearActivity.ClearResult.NOT_OURS -> {
                    deferredClearToken = null
                }
                ClipboardClearActivity.ClearResult.NO_ACCESS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ already masks EXTRA_IS_SENSITIVE previews across system UI;
                        // defer clearing until BiometricPass next receives focus so we never interrupt typing.
                        deferredClearToken = token
                    } else {
                        // Android 10–12L: briefly launch 1x1 invisible Activity to verify ownership and clear.
                        try {
                            val clearIntent = Intent(appContext, ClipboardClearActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                )
                                putExtra(ClipboardClearActivity.EXTRA_TOKEN, token)
                            }
                            appContext.startActivity(clearIntent)
                        } catch (_: Exception) {
                            deferredClearToken = token
                        }
                    }
                }
            }
        }
        pendingClearRunnable = clearRunnable
        mainHandler.postDelayed(clearRunnable, AUTO_CLEAR_DELAY_MS)
    }

    /**
     * Silently clears any expired BiometricPass clipboard item when one of our Activities regains
     * window focus on Android 13+.
     */
    fun clearDeferredIfNeeded(context: Context) {
        val token = deferredClearToken ?: return
        when (ClipboardClearActivity.clearIfStillOurs(context.applicationContext, token)) {
            ClipboardClearActivity.ClearResult.CLEARED,
            ClipboardClearActivity.ClearResult.NOT_OURS -> {
                deferredClearToken = null
            }
            ClipboardClearActivity.ClearResult.NO_ACCESS -> Unit
        }
    }
}
