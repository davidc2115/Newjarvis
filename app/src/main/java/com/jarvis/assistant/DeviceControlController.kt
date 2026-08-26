package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock

/**
 * DeviceControlController — actions matérielles directes sur le téléphone que l'utilisateur
 * a explicitement demandées : lampe torche, réveils. Distinct de WifiController/BluetoothController
 * (déjà existants) qui eux ne peuvent qu'OUVRIR le panneau système (restriction Android 10+).
 *
 * Lampe torche : CameraManager.setTorchMode() ne nécessite PAS la permission CAMERA (API conçue
 * précisément pour les applis "lampe de poche" sans accès à la caméra elle-même) et fonctionne
 * immédiatement, sans ouvrir aucun panneau ni activité — contrairement au Wi-Fi/Bluetooth.
 *
 * Réveils : Android n'expose AUCUNE API publique pour lister ou désactiver un réveil existant
 * du réveil par défaut (seule l'appli Horloge elle-même a accès à sa base de données interne) —
 * seul AlarmClock.ACTION_SET_ALARM (créer) et ACTION_SHOW_ALARMS (ouvrir la liste) sont
 * disponibles. "Désactiver un réveil" est donc honnêtement traité comme "ouvrir la liste des
 * réveils pour que l'utilisateur le désactive lui-même en un tap", jamais simulé comme réussi.
 *
 * Minuteurs : AlarmClock.ACTION_SET_TIMER délègue à l'appli Horloge par défaut, comme pour
 * les réveils — même limitation (aucune API pour lister/annuler un minuteur en cours par
 * programme), donc pas de showTimers().
 *
 * BUG RÉEL CORRIGÉ : intent.resolveActivity() est une méthode de requête soumise à la
 * restriction de visibilité des paquets (Android 11+/targetSdk 30+) — sans déclaration
 * <queries><intent> correspondante dans le manifest, elle renvoie null MÊME QUAND une vraie
 * appli Horloge est installée, ce qui faisait répondre à tort "aucune application horloge
 * n'est installée". startActivity() avec l'intent implicite aurait fonctionné directement
 * (il n'est pas soumis à cette restriction), seule la vérification préalable était en cause.
 * Voir les entrées SET_ALARM/SHOW_ALARMS/SET_TIMER ajoutées au <queries> du manifest.
 */
object DeviceControlController {

    private fun findFlashCameraId(manager: CameraManager): String? {
        return try {
            manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            null
        }
    }

    fun setFlashlight(context: Context, enable: Boolean): String {
        val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "❌ Ce téléphone n'expose pas de service caméra, impossible de contrôler la lampe torche."
        val cameraId = findFlashCameraId(manager)
            ?: return "❌ Aucune lampe torche détectée sur cet appareil."
        return try {
            manager.setTorchMode(cameraId, enable)
            if (enable) "🔦 Lampe torche allumée." else "🔦 Lampe torche éteinte."
        } catch (e: CameraAccessException) {
            "❌ Impossible d'accéder à la lampe torche : ${e.message} (une autre appli utilise peut-être déjà la caméra)."
        } catch (e: Exception) {
            "❌ Échec de la commande lampe torche : ${e.message}"
        }
    }

    /**
     * Crée un vrai réveil via l'appli Horloge par défaut. skipUi=true = créé silencieusement
     * sans ouvrir l'appli (comportement standard "réveil pour demain 7h" en vocal) ; certains
     * fabricants (ex: Samsung) ignorent skipUi et ouvrent quand même l'appli pour confirmation.
     */
    fun setAlarm(context: Context, hour: Int, minute: Int, message: String = "", daysOfWeek: List<Int> = emptyList(), skipUi: Boolean = true): String {
        if (hour !in 0..23 || minute !in 0..59) return "❌ Heure invalide ($hour:$minute) — précise une heure entre 0 et 23 et des minutes entre 0 et 59."
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                if (daysOfWeek.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(daysOfWeek))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return "❌ Aucune appli Horloge/Réveil trouvée sur ce téléphone pour créer le réveil."
            }
            context.startActivity(intent)
            val heureStr = "%02d:%02d".format(hour, minute)
            "⏰ Réveil réglé à $heureStr${if (message.isNotBlank()) " (« $message »)" else ""}."
        } catch (e: SecurityException) {
            // BUG RÉEL CORRIGÉ : sur certains ROM/OEM, com.android.deskclock exige la
            // permission custom com.android.alarm.permission.SET_ALARM en plus de la
            // permission standard SET_ALARM (déjà déclarées toutes les deux dans le
            // manifest) — mais si l'appli a été installée AVANT cet ajout au manifest,
            // Android ne réévalue les permissions qu'à la réinstallation, pas à la simple
            // mise à jour. On retente ici en ouvrant directement l'appli Horloge (sans
            // skipUi) plutôt que de relayer tel quel le message technique "Permission
            // Denial" illisible pour l'utilisateur.
            try {
                val fallback = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    if (daysOfWeek.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(daysOfWeek))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                val heureStr = "%02d:%02d".format(hour, minute)
                "⏰ J'ouvre l'appli Horloge pour confirmer le réveil de $heureStr — la création silencieuse est bloquée par une restriction de ce téléphone (réinstalle l'app si ça persiste, Android ne prend parfois en compte une nouvelle permission qu'après une réinstallation complète)."
            } catch (e2: Exception) {
                "❌ Impossible de créer le réveil : ton téléphone bloque cette action (permission manquante). Réinstalle l'app JARVIS pour appliquer la permission nécessaire, ou crée le réveil manuellement dans ton appli Horloge."
            }
        } catch (e: Exception) {
            "❌ Échec de la création du réveil : ${e.message}"
        }
    }

    /**
     * Android ne permet PAS à une appli tierce de désactiver/supprimer un réveil existant par
     * programme (aucune API publique) — ouvre la liste des réveils de l'appli Horloge par
     * défaut pour que l'utilisateur le désactive lui-même en un tap, plutôt que de prétendre
     * l'avoir fait.
     */
    fun showAlarms(context: Context): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return "❌ Aucune appli Horloge/Réveil trouvée sur ce téléphone."
            }
            context.startActivity(intent)
            "⏰ Liste des réveils ouverte — désactive celui que tu veux d'un tap (Android ne permet à aucune appli, y compris JARVIS, de le faire directement à ta place)."
        } catch (e: Exception) {
            "❌ Impossible d'ouvrir la liste des réveils : ${e.message}"
        }
    }

    /**
     * Crée un minuteur via l'appli Horloge par défaut (même mécanisme que setAlarm).
     * durationSeconds : durée totale en secondes (ex: 5 min -> 300).
     */
    fun setTimer(context: Context, durationSeconds: Int, message: String = "", skipUi: Boolean = true): String {
        if (durationSeconds <= 0) return "❌ Durée de minuteur invalide — précise une durée positive."
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return "❌ Aucune appli Horloge/Minuteur trouvée sur ce téléphone."
            }
            context.startActivity(intent)
            val dureeStr = formatDuration(durationSeconds)
            "⏱️ Minuteur de $dureeStr lancé${if (message.isNotBlank()) " (« $message »)" else ""}."
        } catch (e: SecurityException) {
            try {
                val fallback = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                val dureeStr = formatDuration(durationSeconds)
                "⏱️ J'ouvre l'appli Horloge pour confirmer le minuteur de $dureeStr — le lancement silencieux est bloqué par une restriction de ce téléphone."
            } catch (e2: Exception) {
                "❌ Impossible de lancer le minuteur : ton téléphone bloque cette action (permission manquante)."
            }
        } catch (e: Exception) {
            "❌ Échec du lancement du minuteur : ${e.message}"
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("${h}h")
        if (m > 0) parts.add("${m}min")
        if (s > 0 || parts.isEmpty()) parts.add("${s}s")
        return parts.joinToString(" ")
    }
}
