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
        const val EXTRA_TYPE = "type"     // "image" | "image_batch" | "video" | "website" | "website_edit"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_FORMAT = "format" // "carre" (défaut) | "portrait" | "paysage" — uniquement type="image"/"image_batch"
        const val EXTRA_ID = "id"
        const val EXTRA_EXISTING_PATH = "existingPath"
        const val EXTRA_DURATION = "durationSeconds"
        const val EXTRA_BATCH_IDS = "batchIds"
        const val EXTRA_IMAGE_PATHS = "imagePaths"

        private const val CHANNEL_ID = "jarvis_generation"
        private const val PROGRESS_NOTIF_ID = 501
        private const val RESULT_NOTIF_ID_BASE = 600

        /**
         * Enregistre une génération dans l'historique et démarre le service pour l'exécuter.
         * [count] > 1 (uniquement pour type="image") lance une série d'images à la suite —
         * chaque image est sa propre entrée "pending" dans l'historique dès le départ, donc
         * visible immédiatement dans la carte de progression (⏳ Image 1/5, ⏳ Image 2/5...),
         * puis mises à jour une par une au fil de la génération.
         */
        fun enqueue(context: Context, type: String, prompt: String, existingPath: String? = null, durationSeconds: Int? = null, count: Int = 1, imagePaths: List<String>? = null, format: String = "carre"): String {
            if (type == "image" && count > 1) {
                return enqueueImageBatch(context, prompt, count, format)
            }
            val id = "${System.currentTimeMillis()}_${(0..9999).random()}"
            Prefs.addGenerationRecord(
                context,
                Prefs.GenerationRecord(id = id, type = type, prompt = prompt, status = "pending", timestamp = System.currentTimeMillis())
            )
            val intent = Intent(context, GenerationService::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_FORMAT, format)
                existingPath?.let { putExtra(EXTRA_EXISTING_PATH, it) }
                durationSeconds?.let { putExtra(EXTRA_DURATION, it) }
                imagePaths?.takeIf { it.isNotEmpty() }?.let { putExtra(EXTRA_IMAGE_PATHS, it.toTypedArray()) }
            }
            ContextCompat.startForegroundService(context, intent)
            return id
        }

        private fun enqueueImageBatch(context: Context, prompt: String, count: Int, format: String = "carre"): String {
            val safeCount = count.coerceIn(2, 20) // borne raisonnable pour éviter un abus involontaire
            val ids = (1..safeCount).map { i ->
                val id = "${System.currentTimeMillis()}_${(0..9999).random()}_$i"
                Prefs.addGenerationRecord(
                    context,
                    Prefs.GenerationRecord(
                        id = id, type = "image", prompt = "$prompt (image $i/$safeCount)",
                        status = "pending", timestamp = System.currentTimeMillis()
                    )
                )
                id
            }
            val intent = Intent(context, GenerationService::class.java).apply {
                putExtra(EXTRA_TYPE, "image_batch")
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_ID, ids.first())
                putExtra(EXTRA_BATCH_IDS, ids.toTypedArray())
                putExtra(EXTRA_FORMAT, format)
            }
            ContextCompat.startForegroundService(context, intent)
            return "lot de $safeCount images"
        }

        private fun labelFor(type: String): String = when (type) {
            "image", "image_batch" -> "Image"
            "video" -> "Vidéo"
            "website" -> "Site web"
            "website_edit" -> "Modification de site"
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
        val existingPath = intent?.getStringExtra(EXTRA_EXISTING_PATH)
        val durationSeconds = if (intent?.hasExtra(EXTRA_DURATION) == true) intent.getIntExtra(EXTRA_DURATION, VideoGenController.DEFAULT_DURATION_S) else null
        val batchIds = intent?.getStringArrayExtra(EXTRA_BATCH_IDS)
        val imagePaths = intent?.getStringArrayExtra(EXTRA_IMAGE_PATHS)?.toList()
        val format = intent?.getStringExtra(EXTRA_FORMAT) ?: "carre"

        if (type == null || prompt == null || id == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }

        activeJobs.incrementAndGet()
        startForeground(PROGRESS_NOTIF_ID, buildProgressNotification())

        scope.launch {
            if (type == "image_batch" && batchIds != null) {
                runImageBatchJob(prompt, batchIds.toList(), format)
            } else {
                runJob(id, type, prompt, existingPath, durationSeconds, imagePaths, format)
            }
            if (activeJobs.decrementAndGet() <= 0) {
                stopIfIdle()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Génère plusieurs images à la suite (une par une, pas en parallèle — évite de saturer
     * les limites de débit des fournisseurs). Chaque image a déjà son entrée "pending"
     * créée par enqueueImageBatch : ici on les met juste à jour une par une, avec la
     * notification de progression rafraîchie à chaque étape (« Image 3/5... »), et un seul
     * résumé final plutôt qu'une notification par image (trop de bruit pour un lot).
     */
    private suspend fun runImageBatchJob(basePrompt: String, ids: List<String>, format: String = "carre") {
        val total = ids.size
        var successCount = 0
        val failures = mutableListOf<String>()

        ids.forEachIndexed { index, id ->
            updateNotification(buildProgressNotification("Image ${index + 1}/$total en cours..."))
            var success = false
            var message = "❌ Erreur inattendue."
            var resultPath: String? = null
            try {
                val r = ImageGenController.generateImage(applicationContext, basePrompt, format)
                success = r.base64 != null
                message = r.message
                resultPath = r.savedPath
            } catch (e: Exception) {
                message = "❌ Erreur inattendue : ${e.message}"
            }

            Prefs.updateGenerationRecord(applicationContext, id) { record ->
                record.copy(
                    status = if (success) "success" else "failed",
                    resultPath = resultPath,
                    errorMessage = if (!success) message else null
                )
            }
            if (success) successCount++ else failures.add("Image ${index + 1} : ${message.take(100)}")
        }

        val summary = if (failures.isEmpty()) {
            "✅ $successCount image(s) sur $total générée(s) avec succès."
        } else {
            "⚠️ $successCount/$total réussie(s).\n" + failures.joinToString("\n") { "• $it" }
        }
        postResultNotification("image_batch", "$total images — $basePrompt", failures.isEmpty(), summary)
    }

    private fun stopIfIdle() {
        if (activeJobs.get() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runJob(id: String, type: String, prompt: String, existingPath: String?, durationSeconds: Int? = null, imagePaths: List<String>? = null, format: String = "carre") {
        var success = false
        var message = "❌ Type de génération inconnu."
        var resultPath: String? = null

        try {
            when (type) {
                "image" -> {
                    val r = ImageGenController.generateImage(applicationContext, prompt, format)
                    success = r.base64 != null
                    message = r.message
                    resultPath = r.savedPath
                }
                "video" -> {
                    val r = VideoGenController.generateVideo(applicationContext, prompt, durationSeconds ?: VideoGenController.DEFAULT_DURATION_S)
                    success = r.success
                    message = r.message
                    resultPath = r.localPath
                }
                "website" -> {
                    val r = WebsiteGenController.generateWebsite(applicationContext, prompt, imagePaths ?: emptyList())
                    success = r.success
                    message = r.message
                    resultPath = r.filePath
                }
                "website_edit" -> {
                    if (existingPath.isNullOrBlank()) {
                        message = "❌ Aucun site existant à modifier."
                    } else {
                        val r = WebsiteGenController.editWebsite(applicationContext, existingPath, prompt)
                        success = r.success
                        message = r.message
                        resultPath = r.filePath
                    }
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

    private fun buildProgressNotification(statusText: String? = null): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS génère du contenu...")
            .setContentText(statusText ?: "Ça continue même si tu fermes l'application.")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openGenerationActivityIntent())
            .build()
    }

    /** Met à jour la notification de progression en place (même ID que startForeground). */
    private fun updateNotification(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(PROGRESS_NOTIF_ID, notification)
        } catch (_: SecurityException) { }
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
