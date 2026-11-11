package com.biometricpass.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.biometricpass.app.R
import com.biometricpass.app.ui.autofill.AuthAndPickActivity
import com.biometricpass.app.util.PendingIntentBalHelper

/**
 * Quick Settings Tile (`PassTileService`):
 * Pull down the notification shade and tap this tile when any target app blocks Autofill
 * to summon the BiometricPass Picker / Password Generator BottomSheet overlay on top of the active app.
 */
class PassTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(R.string.tile_service_subtitle)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        QuickActionNotificationHelper.showQuickAccessNotification(this, forceEnable = true)
        val summonIntent = AuthAndPickActivity.createIntent(
            context = this,
            credentialId = 0L
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntentBalHelper.getActivityAllowingBal(
                context = this,
                requestCode = 9001,
                intent = summonIntent,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(summonIntent)
        }
    }
}
