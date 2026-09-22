package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.AlarmClock

/**
 * Actions matérielles : lampe torche, réveils, minuteurs.
 * Sur beaucoup de ROM (MIUI, ColorOS, OneUI…), resolveActivity(ACTION_SET_ALARM) renvoie null
 * même si une appli Horloge existe — d'où l'ancien message "aucune appli trouvée".
 * On tente plusieurs stratégies : intent standard, packages connus, queryIntentActivities.
 */
object DeviceControlController {

    private val KNOWN_CLOCK_PACKAGES = listOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.samsung.android.app.clockpackage",
        "com.miui.clock",
        "com.android.BBKClock",
        "com.coloros.alarmclock",
        "com.oppo.alarmclock",
        "com.oneplus.deskclock",
        "com.huawei.deskclock",
        "com.android.alarmclock",
        "com.sonyericsson.organizer",
        "com.asus.deskclock",
        "com.motorola.deskclock"
    )

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
            "❌ Impossible d'accéder à la lampe torche : ${e.message}"
        } catch (e: Exception) {
            "❌ Erreur lampe torche : ${e.message}"
        }
    }

    /** True si au moins une activité peut gérer cet intent (y compris packages non "default"). */
    private fun canHandle(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager
        if (intent.resolveActivity(pm) != null) return true
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        if (list.isNotEmpty()) return true
        // Dernier recours : packages horloge connus installés
        return KNOWN_CLOCK_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun launchAlarmIntent(context: Context, base: Intent): Boolean {
        // 1. Intent standard
        try {
            if (base.resolveActivity(context.packageManager) != null) {
                context.startActivity(base)
                return true
            }
        } catch (_: Exception) { }

        // 2. Forcer un package horloge connu
        for (pkg in KNOWN_CLOCK_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                val targeted = Intent(base).setPackage(pkg)
                if (targeted.resolveActivity(context.packageManager) != null) {
                    context.startActivity(targeted)
                    return true
                }
            } catch (_: Exception) { }
        }

        // 3. Première activité qui répond à l'action
        try {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.queryIntentActivities(
                    base, PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(base, 0)
            }
            val ri = list.firstOrNull() ?: return false
            val explicit = Intent(base).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            context.startActivity(explicit)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        message: String = "",
        daysOfWeek: List<Int> = emptyList(),
        skipUi: Boolean = true
    ): String {
        val heureStr = "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                if (daysOfWeek.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(daysOfWeek))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (launchAlarmIntent(context, intent)) {
                "⏰ Réveil réglé pour $heureStr."
            } else {
                // Retry sans skip UI
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (launchAlarmIntent(context, intent)) {
                    "⏰ Ouverture de l'horloge pour confirmer le réveil de $heureStr."
                } else {
                    "❌ Impossible de créer le réveil : aucune appli Horloge compatible détectée. " +
                        "Installe « Horloge » Google ou ouvre ton appli réveil manuellement."
                }
            }
        } catch (e: Exception) {
            "❌ Échec de la création du réveil : ${e.message}"
        }
    }

    /**
     * Minuteur (countdown). durationSeconds = durée totale en secondes.
     */
    fun setTimer(context: Context, durationSeconds: Int, message: String = ""): String {
        val secs = durationSeconds.coerceAtLeast(1)
        val label = when {
            secs < 60 -> "$secs secondes"
            secs % 60 == 0 -> "${secs / 60} minute${if (secs >= 120) "s" else ""}"
            else -> "${secs / 60} min ${secs % 60} s"
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, secs)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (launchAlarmIntent(context, intent)) {
                "⏱️ Minuteur de $label lancé."
            } else {
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (launchAlarmIntent(context, intent)) {
                    "⏱️ Ouverture de l'horloge pour confirmer le minuteur de $label."
                } else {
                    "❌ Impossible de lancer le minuteur : aucune appli compatible trouvée. " +
                        "Installe « Horloge » Google Play, ou utilise le minuteur de ton téléphone manuellement."
                }
            }
        } catch (e: Exception) {
            "❌ Échec du minuteur : ${e.message}"
        }
    }

    fun showAlarms(context: Context): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (launchAlarmIntent(context, intent)) {
                "⏰ Liste des réveils ouverte — désactive celui que tu veux d'un tap."
            } else {
                "❌ Aucune appli Horloge/Réveil trouvée sur ce téléphone."
            }
        } catch (e: Exception) {
            "❌ Impossible d'ouvrir la liste des réveils : ${e.message}"
        }
    }
}
