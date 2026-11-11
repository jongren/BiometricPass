package com.biometricpass.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager

/**
 * 1x1 pixel invisible Activity that briefly acquires window focus so Android 10+ allows
 * reading/clearing the system clipboard even when the user has switched to another app.
 * It only clears the clipboard when it still holds the exact item BiometricPass copied.
 */
class ClipboardClearActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitionAnimation(opening = true)

        window?.apply {
            setGravity(Gravity.TOP or Gravity.START)
            val params = attributes
            params.x = 0
            params.y = 0
            params.width = 1
            params.height = 1
            params.dimAmount = 0f
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            attributes = params
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handled = true
            val token = intent?.getStringExtra(EXTRA_TOKEN)
            if (token != null) {
                clearIfStillOurs(this, token)
            }
            finish()
            disableTransitionAnimation(opening = false)
        }
    }

    private fun disableTransitionAnimation(opening: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                if (opening) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE,
                0,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    enum class ClearResult { CLEARED, NOT_OURS, NO_ACCESS }

    companion object {
        const val EXTRA_TOKEN = "extra_clip_token"

        /**
         * Clears the clipboard only if its current item was created by BiometricPass with [token].
         * Returns [ClearResult.NO_ACCESS] when the clipboard cannot be read (App not focused).
         */
        fun clearIfStillOurs(context: Context, token: String): ClearResult {
            return try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return ClearResult.NO_ACCESS
                val description = clipboard.primaryClipDescription
                    ?: return if (clipboard.hasPrimaryClip()) ClearResult.NO_ACCESS else ClearResult.NOT_OURS
                val owner = description.extras?.getString(SensitiveClipboardHelper.EXTRA_OWNER_TOKEN)
                if (owner != token) return ClearResult.NOT_OURS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                ClearResult.CLEARED
            } catch (_: Exception) {
                ClearResult.NO_ACCESS
            }
        }
    }
}
