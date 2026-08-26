package com.jarvis.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Tuile de paramètres rapides JARVIS pour le Bluetooth du téléphone.
 * Même principe et même limitation honnête que JarvisWifiTileService :
 * ouvre l'écran Bluetooth (pas de panneau flottant public pour le
 * Bluetooth contrairement au Wi-Fi), un tap suffit ensuite.
 */
class JarvisBluetoothTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        launchAndCollapse(intent)
    }

    private fun launchAndCollapse(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
