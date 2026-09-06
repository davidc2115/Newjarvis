package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * GenerationService — exécute les générations IA (image / vidéo / site web)
 * en tâche de fond, indépendamment de l'écran ouvert : on peut lancer une
 * génération depuis 🎨 Génération OU depuis le chat, fermer l'application,
 * et recevoir une notification quand c'est prêt (ou en échec).
 *
 * Un service au premier plan est nécessaire ici car une génération vidéo ou
 * une image Stable Diffusion embarquée peut prendre plusieurs minutes —
 * Android tuerait le traitement s'il tournait dans un simple coroutine lié
 * à une Activity fermée.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = AtomicInteger(0)
    private var resultNotifId = RESULT_NOTIF_ID_BASE

    companion object {
        const val EXTRA_TYPE = "type"     // "image" | "video" | "website"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_ID = "id"

        private const val CHANNEL_ID = "jarvis_generation"
        private const val PROGRESS_NOTIF_ID = 501
        private const val RESULT_NOTIF_ID_BASE = 600

        /** Enregistre une génération dans l'historique et démarre le service pour l'exécuter. */
        fun enqueue(context: Context, type: String, prompt: String): String {
            val id = "${System.currentTimeMillis()}_${(0..9999).random()}"
            Prefs.addGenerationRecord(
                context,
                Prefs.GenerationRecord(id = id, type = type, prompt = prompt, status = "pending", timestamp = System.currentTimeMillis())
            )
            val intent = Intent(context, GenerationService::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_ID, id)
            }
            ContextCompat.startForegroundService(context, intent)
            return id
        }

        private fun labelFor(type: String): String = when (type) {
            "image" -> "Image"
            "video" -> "Vidéo"
            "website" -> "Site web"
            else -> "Génération"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra(EXTRA_TYPE)
        val prompt = intent?.getStringExtra(EXTRA_PROMPT)
        val id = intent?.getStringExtra(EXTRA_ID)

        if (type == null || prompt == null || id == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }

        activeJobs.incrementAndGet()
        startForeground(PROGRESS_NOTIF_ID, buildProgressNotification())

        scope.launch {
            runJob(id, type, prompt)
            if (activeJobs.decrementAndGet() <= 0) {
                stopIfIdle()
            }
        }

        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (activeJobs.get() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runJob(id: String, type: String, prompt: String) {
        var success = false
        var message = "❌ Type de génération inconnu."
        var resultPath: String? = null

        try {
            when (type) {
                "image" -> {
                    val r = ImageGenController.generateImage(applicationContext, prompt)
                    success = r.base64 != null
                    message = r.message
                    resultPath = r.savedPath
                }
                "video" -> {
                    val r = VideoGenController.generateVideo(applicationContext, prompt)
                    success = r.success
                    message = r.message
                    resultPath = r.localPath
                }
                "website" -> {
                    val r = WebsiteGenController.generateWebsite(applicationContext, prompt)
                    success = r.success
                    message = r.message
                    resultPath = r.filePath
                }
            }
        } catch (e: Exception) {
            success = false
            message = "❌ Erreur inattendue : ${e.message}"
        }

        Prefs.updateGenerationRecord(applicationContext, id) { record ->
            record.copy(
                status = if (success) "success" else "failed",
                resultPath = resultPath,
                errorMessage = if (!success) message else null
            )
        }

        postResultNotification(type, prompt, success, message)
    }

    // ─── Notifications ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS — Génération IA", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Progression et résultats des générations d'image, vidéo et site web."
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS génère du contenu...")
            .setContentText("Ça continue même si tu fermes l'application.")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openGenerationActivityIntent())
            .build()
    }

    private fun postResultNotification(type: String, prompt: String, success: Boolean, message: String) {
        val label = labelFor(type)
        val title = if (success) "✅ $label prête" else "❌ $label échouée"
        val icon = if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(prompt.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(400)))
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openGenerationActivityIntent())
            .build()

        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(resultNotifId++, notification)
        } catch (_: SecurityException) {
            // Permission de notification refusée par l'utilisateur — le résultat reste
            // consultable dans l'historique de l'écran Génération.
        }
    }

    private fun openGenerationActivityIntent(): PendingIntent {
        val intent = Intent(this, GenerationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
