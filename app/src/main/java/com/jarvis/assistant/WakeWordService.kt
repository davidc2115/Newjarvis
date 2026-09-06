package com.jarvis.assistant

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
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
 * Deux moteurs possibles, choisis automatiquement, TOUS DEUX 100% hors-ligne
 * et basse consommation (aucun des deux n'utilise la reconnaissance vocale
 * continue en ligne — c'est justement ce qu'on cherchait à éviter) :
 *
 * 1. Porcupine (ai.picovoice) — moteur dédié Picovoice, utilisé UNIQUEMENT si
 *    une clé d'accès est configurée (⚙ → Réglages) ET que le mot-clé choisi
 *    fait partie des mots-clés intégrés Porcupine. Optionnel.
 *
 * 2. openWakeWord — moteur GRATUIT et SANS CLÉ, utilisé par défaut. Trois
 *    petits modèles ONNX (quelques Mo au total) exécutés localement via
 *    onnxruntime-android : un modèle de spectrogramme, un modèle d'embedding
 *    audio, et un classifieur spécifique au mot-clé. Consommation batterie
 *    très faible car ce n'est PAS de la reconnaissance vocale généraliste,
 *    juste un petit réseau de neurones qui écoute en boucle un motif précis.
 *    Aucune donnée audio ne quitte jamais le téléphone. Projet open-source
 *    (Apache-2.0) : https://github.com/dscripka/openWakeWord
 *
 * ⚠️ LIMITE HONNÊTE À CONNAÎTRE : contrairement à l'ancien moteur de repli
 * (reconnaissance vocale standard Android, retiré à la demande explicite de
 * l'utilisateur car trop gourmand en batterie et dépendant d'internet), ces
 * deux moteurs sont des détecteurs PRÉ-ENTRAÎNÉS sur un nombre limité de
 * mots-clés fixes (voir BUILT_IN_KEYWORDS / OWW_KEYWORDS ci-dessous) — pas un
 * mot totalement arbitraire tapé par l'utilisateur. Si le mot-clé choisi dans
 * les réglages ne correspond à aucun des deux, le service écoute automatiquement
 * « Jarvis » à la place et le signale clairement dans sa notification
 * permanente, plutôt que de laisser croire qu'un mot non supporté fonctionne.
 *
 * Modèles openWakeWord téléchargés au moment du build (pas commités dans git) —
 * voir la tâche downloadWakeWordModels dans app/build.gradle.
 */
class WakeWordService : Service() {

    // ── Moteur 1 : Porcupine (Picovoice, optionnel) ─────────────────────────────
    private var porcupineManager: PorcupineManager? = null

    // ── Moteur 2 : openWakeWord (gratuit, sans clé, basse consommation) ────────
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
         * permanente (Porcupine ou openWakeWord) continue de monopoliser le micro et
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

        /** Mots-clés intégrés Porcupine (moteur 1 — nécessite une clé Picovoice). */
        private val BUILT_IN_KEYWORDS = mapOf(
            "jarvis" to Porcupine.BuiltInKeyword.JARVIS,
            "alexa" to Porcupine.BuiltInKeyword.ALEXA,
            "computer" to Porcupine.BuiltInKeyword.COMPUTER,
            "ordinateur" to Porcupine.BuiltInKeyword.COMPUTER,
            "picovoice" to Porcupine.BuiltInKeyword.PICOVOICE,
            "porcupine" to Porcupine.BuiltInKeyword.PORCUPINE,
            "terminator" to Porcupine.BuiltInKeyword.TERMINATOR,
            "blueberry" to Porcupine.BuiltInKeyword.BLUEBERRY,
            "bumblebee" to Porcupine.BuiltInKeyword.BUMBLEBEE,
            "grapefruit" to Porcupine.BuiltInKeyword.GRAPEFRUIT,
            "grasshopper" to Porcupine.BuiltInKeyword.GRASSHOPPER
        )

        /** Modèles ONNX intégrés openWakeWord (moteur 2 — gratuit, sans clé).
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
                stopPorcupine()
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
        val accessKey = Prefs.getPicovoiceKey(this)
        val keyword = Prefs.getWakeWord(this).lowercase().trim().ifBlank { "jarvis" }
        val builtIn = BUILT_IN_KEYWORDS[keyword]

        if (accessKey.isNotBlank() && builtIn != null) {
            if (startPorcupine(accessKey, builtIn)) {
                updateNotification("moteur Porcupine (Picovoice) — très faible consommation")
                return
            }
        }

        startOpenWakeWord(keyword)
    }

    private fun startPorcupine(accessKey: String, keyword: Porcupine.BuiltInKeyword): Boolean {
        return try {
            val callback = PorcupineManagerCallback {
                triggerVoiceMode()
            }
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(keyword)
                .build(applicationContext, callback)
            porcupineManager?.start()
            true
        } catch (e: Exception) {
            // Clé invalide, quota dépassé, appareil non supporté... on bascule sur openWakeWord.
            porcupineManager = null
            false
        }
    }

    private fun stopPorcupine() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (_: Exception) { }
        porcupineManager = null
    }

    // ── Moteur 2 : openWakeWord (gratuit, sans clé, basse consommation) ────────

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
        stopPorcupine()
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
        // Porcupine et openWakeWord tournent en continu tout seuls après une détection —
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
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }
}
