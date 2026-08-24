package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.telephony.SmsManager
import java.net.URLEncoder

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

    /**
     * Envoie un SMS directement (sans passer par l'appli Messages) -- nécessite la permission
     * SEND_SMS, demandée à l'exécution par MainActivity avant d'appeler cette fonction. Découpe
     * automatiquement en plusieurs parties si le message dépasse la taille d'un SMS simple.
     */
    fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lance un appel directement (sans passer par le composeur) -- nécessite la permission
     * CALL_PHONE, demandée à l'exécution par MainActivity avant d'appeler cette fonction.
     */
    fun makeCall(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ouvre une appli Cartes (Google Maps ou équivalent) : sur la position actuelle si
     * [destination] est nul, ou en recherche/itinéraire vers [destination] sinon. Passe par
     * l'URI standard "geo:" (n'importe quelle appli Cartes installée peut la gérer), pas
     * spécifiquement Google Maps.
     */
    fun openMaps(context: Context, destination: String?): Boolean {
        return try {
            val uri = if (destination.isNullOrBlank()) {
                Uri.parse("geo:0,0?z=16")
            } else {
                Uri.parse("geo:0,0?q=" + URLEncoder.encode(destination, "UTF-8"))
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
