package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock

/**
 * Contrôle direct de fonctions matérielles/système du téléphone (lot 1 du "contrôle complet"
 * demandé -- voir les lots suivants : SMS/appels, contacts, GPS, stockage, Google).
 *
 * Lampe torche : CameraManager.setTorchMode() ne nécessite PAS la permission CAMERA depuis
 * l'API 23 (contrairement à ouvrir un aperçu caméra) -- aucune permission runtime à demander.
 *
 * Réveil/minuteur : délègue à l'appli Horloge du système via les intents implicites standard
 * AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER -- fonctionne avec n'importe quelle appli
 * Horloge installée, pas besoin de réimplémenter un vrai réveil nous-mêmes. EXTRA_SKIP_UI
 * (évite la confirmation manuelle) ne fonctionne que si l'appli détient la permission
 * SET_ALARM (permission "normale", accordée automatiquement à l'installation).
 */
object DeviceController {

    fun setFlashlight(context: Context, on: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setTimer(context: Context, seconds: Int, label: String?): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String?): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
