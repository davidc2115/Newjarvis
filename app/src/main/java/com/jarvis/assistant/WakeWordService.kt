package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service qui écoute en permanence en arrière-plan et déclenche le mode
 * vocal dès que le mot-clé configuré est prononcé.
 *
 * Moteur : openWakeWord — 100% GRATUIT, SANS CLÉ NI COMPTE, hors-ligne et basse
 * consommation. Trois petits modèles ONNX (quelques Mo au total) exécutés
 * localement via onnxruntime-android : un modèle de spectrogramme, un modèle
 * d'embedding audio, et un classifieur spécifique au mot-clé. Consommation
 * batterie très faible car ce n'est PAS de la reconnaissance vocale généraliste,
 * juste un petit réseau de neurones qui écoute en boucle un motif précis.
 * Aucune donnée audio ne quitte jamais le téléphone. Projet open-source
 * (Apache-2.0) : https://github.com/dscripka/openWakeWord
 *
 * Picovoice/Porcupine (moteur alternatif nécessitant un compte/une clé
 * d'accès) retiré à la demande explicite de l'utilisateur — plus aucune
 * dépendance à un service tiers payant/à compte pour l'écoute permanente.
 *
 * ⚠️ LIMITE HONNÊTE À CONNAÎTRE : contrairement à l'ancien moteur de repli
 * (reconnaissance vocale standard Android, retiré à la demande explicite de
 * l'utilisateur car trop gourmand en batterie et dépendant d'internet),
 * openWakeWord est un détecteur PRÉ-ENTRAÎNÉ sur un nombre limité de
 * mots-clés fixes (voir OWW_KEYWORDS ci-dessous) — pas un mot totalement
 * arbitraire tapé par l'utilisateur. Si le mot-clé choisi dans les réglages
 * ne correspond à aucun de ceux supportés, le service écoute automatiquement
 * « Jarvis » à la place et le signale clairement dans sa notification
 * permanente, plutôt que de laisser croire qu'un mot non supporté fonctionne.
 *
 * Modèles téléchargés au moment du build (pas commités dans git, ~qq Mo) —
 * voir la tâche downloadWakeWordModels dans app/build.gradle, qui FAIT
 * ÉCHOUER le build si le téléchargement échoue (signalement utilisateur :
 * l'ancien comportement silencieux permettait à un build CI de réussir sans
 * ces fichiers, livrant une écoute permanente cassée sans que personne ne le
 * sache). wakeword_status (voir statusReport ci-dessous) permet de
 * diagnostiquer en conversation si ce cas se reproduit malgré tout.
 */
class WakeWordService : Service() {

    // ── openWakeWord (gratuit, sans clé, basse consommation) — seul moteur ─────
    private var owwEngine: WakeWordEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastDetectionAt = 0L

    companion object {
        const val CHANNEL_ID = "jarvis_wakeword_channel"
        const val NOTIFICATION_ID = 4242
        const val ACTION_STOP = "com.jarvis.assistant.STOP_WAKEWORD"
        const val ACTION_PAUSE = "com.jarvis.assistant.PAUSE_WAKEWORD"
        const val ACTION_RESUME = "com.jarvis.assistant.RESUME_WAKEWORD"

        /**
         * À appeler par TOUT autre composant qui a besoin du micro (mode vocal manuel,
         * déclenché par le bouton micro OU par le mot-clé) : libère temporairement le
         * micro tenu par l'écoute permanente en arrière-plan. Android ne permet qu'UNE
         * seule capture audio active à la fois — sans cette mise en pause, l'écoute
         * permanente (openWakeWord) continue de monopoliser le micro et
         * aucune autre fonctionnalité vocale (chat, mode vocal) ne reçoit plus le
         * moindre son, même en parlant normalement.
         */
        fun pauseListening(context: Context) {
            if (!Prefs.isWakeWordEnabled(context)) return
            context.startService(Intent(context, WakeWordService::class.java).apply { action = ACTION_PAUSE })
        }

        /** Reprend l'écoute permanente après la mise en pause ci-dessus. */
        fun resumeListening(context: Context) {
            if (!Prefs.isWakeWordEnabled(context)) return
            context.startService(Intent(context, WakeWordService::class.java).apply { action = ACTION_RESUME })
        }

        /** Modèles ONNX intégrés openWakeWord (moteur unique — gratuit, sans clé).
         *  Paire (nom de fichier dans assets/, libellé affiché). */
        private val OWW_KEYWORDS = mapOf(
            "jarvis" to ("hey_jarvis.onnx" to "Hey Jarvis"),
            "hey jarvis" to ("hey_jarvis.onnx" to "Hey Jarvis"),
            "alexa" to ("alexa.onnx" to "Alexa"),
            "mycroft" to ("hey_mycroft.onnx" to "Hey Mycroft"),
            "hey mycroft" to ("hey_mycroft.onnx" to "Hey Mycroft")
        )
        private const val OWW_DEFAULT_FILE = "hey_jarvis.onnx"
        private const val OWW_DEFAULT_LABEL = "Hey Jarvis"
        private const val OWW_THRESHOLD = 0.5f
        private const val ANTI_DOUBLON_MS = 1500L

        @Volatile private var lastStatusText: String = ""

        /**
         * Diagnostic conversationnel (voir wakeword_status dans JarvisCommandParser) : jusqu'ici,
         * la seule façon de savoir pourquoi l'écoute permanente ne se déclenchait pas était de
         * lire la notification permanente — beaucoup moins découvrable qu'une simple question à
         * JARVIS. Fonctionne même si le service n'est pas démarré (lit directement les assets
         * et les préférences, pas besoin d'un bind au service).
         */
        fun statusReport(context: Context): String {
            if (!Prefs.isWakeWordEnabled(context)) {
                return "🔇 Écoute permanente désactivée (⚙ → Réglages → Mot-clé d'activation)."
            }
            val keyword = Prefs.getWakeWord(context).lowercase().trim().ifBlank { "jarvis" }
            val requiredAssets = listOf("melspectrogram.onnx", "embedding_model.onnx", OWW_DEFAULT_FILE)
            val missing = requiredAssets.filterNot { name ->
                try { context.assets.open(name).use { true } } catch (e: Exception) { false }
            }
            val sb = StringBuilder("🎙️ Mot-clé configuré : « $keyword ».\n")
            if (missing.isNotEmpty()) {
                sb.append(
                    "❌ Modèles openWakeWord manquants dans l'app (${missing.joinToString(", ")}) — " +
                        "le téléchargement a échoué au moment du build CI. Ce n'est PAS réparable " +
                        "depuis le téléphone : il faut relancer un build (voir GitHub Actions) avec " +
                        "une connexion internet capable d'atteindre github.com."
                )
            } else {
                sb.append("✅ Modèles présents dans l'app.\n")
                sb.append(
                    if (lastStatusText.isNotBlank()) "Dernier statut connu du service : $lastStatusText"
                    else "Le service ne s'est pas encore lancé depuis le dernier démarrage de l'app — ouvre/ferme l'app une fois, ou vérifie que la permission microphone est accordée."
                )
            }
            return sb.toString()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                // Un composant obligatoire (Service au premier plan) doit toujours appeler
                // startForeground rapidement après un startService — on le fait même ici,
                // en mode pause, pour rester valide vis-à-vis d'Android.
                startForeground(NOTIFICATION_ID, buildNotification("⏸ en pause — micro utilisé ailleurs (chat/mode vocal)"))
                stopOpenWakeWord()
                return START_STICKY
            }
            ACTION_RESUME -> {
                startForeground(NOTIFICATION_ID, buildNotification(""))
                isRunning = true
                startBestAvailableEngine()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(""))
                if (!isRunning) {
                    isRunning = true
                    startBestAvailableEngine()
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    // ─────────────────────────────────────────────────────────────────────────

    private fun startBestAvailableEngine() {
        val keyword = Prefs.getWakeWord(this).lowercase().trim().ifBlank { "jarvis" }
        startOpenWakeWord(keyword)
    }

    // ── openWakeWord (gratuit, sans clé, basse consommation) ───────────────────

    private fun startOpenWakeWord(requestedKeyword: String) {
        val match = OWW_KEYWORDS[requestedKeyword]
        val (modelFile, label) = match ?: (OWW_DEFAULT_FILE to OWW_DEFAULT_LABEL)

        if (!assetExists(modelFile) || !assetExists("melspectrogram.onnx") || !assetExists("embedding_model.onnx")) {
            updateNotification("⚠️ modèles openWakeWord introuvables — recompile l'app avec une connexion internet active pour les télécharger")
            return
        }

        try {
            owwEngine?.release()
            val engine = WakeWordEngine(
                context = this,
                models = listOf(WakeWordModel(label, modelFile, threshold = OWW_THRESHOLD)),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2500L,
                scope = serviceScope
            )
            owwEngine = engine
            engine.start()

            serviceScope.launch {
                engine.detections.collect {
                    triggerVoiceMode()
                }
            }

            val note = if (match == null && requestedKeyword != OWW_DEFAULT_LABEL.lowercase()) {
                "moteur gratuit openWakeWord — « $requestedKeyword » non reconnu par ce moteur, écoute « $label » à la place"
            } else {
                "moteur gratuit openWakeWord (« $label ») — faible consommation, 100% hors-ligne"
            }
            updateNotification(note)
        } catch (e: Exception) {
            updateNotification("❌ échec du démarrage de l'écoute basse consommation : ${e.message}")
        }
    }

    private fun stopOpenWakeWord() {
        try {
            owwEngine?.stop()
            owwEngine?.release()
        } catch (_: Exception) { }
        owwEngine = null
    }

    private fun assetExists(name: String): Boolean = try {
        assets.open(name).use { true }
    } catch (e: Exception) {
        false
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun stopListening() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopOpenWakeWord()
    }

    private fun triggerVoiceMode() {
        val now = System.currentTimeMillis()
        if (now - lastDetectionAt < ANTI_DOUBLON_MS) return
        lastDetectionAt = now

        val intent = Intent(this, VoiceModeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(VoiceModeActivity.EXTRA_TRIGGERED_BY_WAKEWORD, true)
        }
        startActivity(intent)
        // openWakeWord tourne en continu tout seul après une détection —
        // contrairement à l'ancien moteur de repli, il n'y a rien à relancer manuellement.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification (obligatoire pour un service au premier plan)
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS — Écoute permanente", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indique que JARVIS écoute en arrière-plan pour le mot-clé d'activation."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): android.app.Notification {
        val keyword = Prefs.getWakeWord(this).ifBlank { "Jarvis" }
        val stopIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS écoute « $keyword »")
            .setContentText(status.ifBlank { "en veille" })
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter l'écoute", stopPendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        lastStatusText = status
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }
}
