package com.biometricpass.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.biometricpass.app.R
import com.biometricpass.app.ui.autofill.AuthAndPickActivity
import com.biometricpass.app.ui.generate.GenerateAndSaveActivity
import com.biometricpass.app.util.PendingIntentBalHelper

/**
 * Provides a persistent Quick-Action Bar in the pull-down Notification Center
 * so the user can summon the Password Picker (`🔍 密碼庫選取`), Password Generator (`✨ 產生/儲存密碼`),
 * or close the notification assistant (`關閉`) with one tap from the pull-down shade.
 */
object QuickActionNotificationHelper {

    private const val CHANNEL_ID = "biometric_pass_quick_access"
    private const val NOTIFICATION_ID = 8801
    private const val PREFS_NAME = "biometric_pass_quick_access_prefs"
    private const val KEY_DISMISSED_BY_USER = "dismissed_by_user"
    const val ACTION_DISMISS_QUICK_ACCESS = "com.biometricpass.app.action.DISMISS_QUICK_ACCESS"

    /**
     * Persistent notification bar shortcut is only needed on Android 10 (API 29) and below,
     * where keyboard InlineSuggestions (magnetic chips above the keyboard) are not available.
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun isDismissedByUser(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISMISSED_BY_USER, false)
    }

    fun setDismissedByUser(context: Context, dismissed: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISMISSED_BY_USER, dismissed).apply()
    }

    fun showIfEnabled(context: Context) {
        if (!isSupported()) return
        if (!isDismissedByUser(context)) {
            showQuickAccessNotification(context, forceEnable = false)
        }
    }

    fun dismissQuickAccessNotification(context: Context) {
        val appContext = context.applicationContext
        setDismissedByUser(appContext, true)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
    }

    fun showQuickAccessNotification(context: Context, forceEnable: Boolean = true) {
        if (!isSupported()) return
        val appContext = context.applicationContext
        if (forceEnable) {
            setDismissedByUser(appContext, false)
        } else if (isDismissedByUser(appContext)) {
            return
        }

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        // Tap main notification or Action 1 -> open AuthAndPickActivity picker overlay
        val pickerIntent = AuthAndPickActivity.createIntent(
            context = appContext,
            credentialId = 0L
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pickerPendingIntent = PendingIntentBalHelper.getActivityAllowingBal(
            context = appContext,
            requestCode = 8802,
            intent = pickerIntent,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Open GenerateAndSaveActivity BottomSheet
        val generateIntent = GenerateAndSaveActivity.createIntent(
            context = appContext
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val generatePendingIntent = PendingIntentBalHelper.getActivityAllowingBal(
            context = appContext,
            requestCode = 8803,
            intent = generateIntent,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 3: Close / Dismiss the pull-down notification assistant
        val dismissIntent = Intent(appContext, QuickAccessDismissReceiver::class.java).apply {
            action = ACTION_DISMISS_QUICK_ACCESS
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            appContext,
            8804,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_key)
            .setContentTitle(appContext.getString(R.string.notif_title))
            .setContentText(appContext.getString(R.string.notif_body))
            .setContentIntent(pickerPendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_tile_key,
                appContext.getString(R.string.notif_action_pick),
                pickerPendingIntent
            )
            .addAction(
                R.drawable.ic_tile_key,
                appContext.getString(R.string.notif_action_generate),
                generatePendingIntent
            )
            .addAction(
                R.drawable.ic_tile_key,
                appContext.getString(R.string.action_close),
                dismissPendingIntent
            )
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}

class QuickAccessDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        QuickActionNotificationHelper.dismissQuickAccessNotification(context)
    }
}
