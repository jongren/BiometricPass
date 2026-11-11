package com.biometricpass.app.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Builds an Activity [PendingIntent] with explicit Background Activity Launch (BAL) opt-in on
 * Android 14+ (API 34–36+), required when `targetSdk >= 35` so `AutofillService`, `TileService`,
 * and notification shortcuts can reliably launch transparent auth/save overlays.
 */
object PendingIntentBalHelper {

    fun getActivityAllowingBal(
        context: Context,
        requestCode: Int,
        intent: Intent,
        flags: Int
    ): PendingIntent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val options = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(mode)
                .toBundle()
            return PendingIntent.getActivity(context, requestCode, intent, flags, options)
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
