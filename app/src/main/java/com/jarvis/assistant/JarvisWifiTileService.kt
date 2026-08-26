package com.jarvis.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Tuile de paramètres rapides JARVIS pour le Wi-Fi du téléphone — accessible
 * en un swipe + tap depuis n'importe quel écran, sans ouvrir l'application.
 *
 * Ouvre le panneau Wi-Fi système (un seul tap suffit ensuite côté
 * utilisateur) : depuis Android 10, aucune app tierce ne peut activer ou
 * désactiver le Wi-Fi par code sans intervention humaine — c'est une
 * restriction de sécurité Google, pas une limite de cette tuile.
 */
class JarvisWifiTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
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
