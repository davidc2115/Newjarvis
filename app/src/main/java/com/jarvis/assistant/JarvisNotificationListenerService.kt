package com.jarvis.assistant

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Lecture des notifications système des AUTRES applis (pas celles envoyées par JARVIS lui-même,
 * voir NotificationController pour ça -- clarification explicite de l'utilisateur : "je parlais
 * des notifications d'application Android").
 *
 * Accès spécial (comme MANAGE_EXTERNAL_STORAGE pour le stockage, voir StorageController) : ce
 * n'est PAS une permission runtime classique, impossible à demander via permissionLauncher.
 * L'utilisateur doit l'activer lui-même dans Réglages > Notifications > Accès aux notifications
 * (voir settingsIntent() ci-dessous), Android l'impose ainsi car c'est un accès très sensible
 * (lit le contenu de TOUTES les notifications, y compris codes de vérification, messages...).
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    data class CapturedNotification(
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Long
    )

    companion object {
        private const val MAX_HISTORY = 50

        // En mémoire seulement (pas de persistance disque) : historique remis à zéro si le
        // système tue le service, acceptable pour un simple "montre-moi mes dernières
        // notifications" (pas un journal permanent).
        private val history = ArrayDeque<CapturedNotification>()

        fun recent(limit: Int = 10): List<CapturedNotification> = synchronized(history) {
            history.toList().takeLast(limit).reversed()
        }

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // On ignore nos propres notifications (voir NotificationController) pour ne pas se
        // citer soi-même dans l'historique.
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        synchronized(history) {
            history.addLast(CapturedNotification(appLabel, title, text, sbn.postTime))
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Rien à faire : on garde l'historique même après disparition de la notif d'origine
        // (l'utilisateur peut vouloir revoir une notif déjà balayée).
    }
}
