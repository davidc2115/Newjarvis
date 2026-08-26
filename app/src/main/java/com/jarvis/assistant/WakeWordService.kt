package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

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
 * ⚠️ LIMITE TECHNIQUE (openWakeWord) : c'est un détecteur PRÉ-ENTRAÎNÉ sur un
 * nombre limité de phrases fixes (voir OWW_KEYWORDS ci-dessous), chacune
 * correspondant à une empreinte acoustique précise apprise par un petit
 * réseau de neurones — « hey jarvis » fonctionne car un modèle a été
 * entraîné spécifiquement dessus, mais dire juste « Jarvis » (sans « Hey »)
 * ne produit PAS le même motif audio et ne peut donc pas être détecté de
 * façon fiable par ce modèle, quel que soit le réglage de seuil. Entraîner
 * un nouveau modèle pour un mot-clé arbitraire demanderait un pipeline
 * d'apprentissage dédié (GPU, données synthétiques, plusieurs dizaines de
 * minutes minimum) — pas faisable à la volée depuis l'app.
 *
 * SOLUTION RETENUE (demande utilisateur : « Jarvis » seul, ou n'importe quel
 * mot-clé personnalisé) : pour tout mot-clé qui n'a PAS de modèle openWakeWord
 * entraîné dédié, le service bascule automatiquement sur un second moteur —
 * reconnaissance vocale native Android (SpeechRecognizer), qui transcrit en
 * continu et déclenche le mode vocal dès que le texte reconnu CONTIENT le
 * mot-clé configuré, quel qu'il soit. Voir startKeywordSpotting() ci-dessous.
 * Contrepartie honnête : consommation batterie et latence plus élevées
 * qu'un modèle openWakeWord dédié (ce n'est pas un petit classifieur qui
 * tourne en boucle, mais une vraie reconnaissance vocale relancée en continu),
 * et peut nécessiter une connexion internet sur les téléphones sans moteur de
 * reconnaissance hors-ligne installé — c'est le compromis inévitable pour
 * supporter un mot-clé réellement arbitraire sans entraînement de modèle.
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

    // ── openWakeWord (gratuit, sans clé, basse consommation) — mots-clés avec modèle dédié ────
    private var owwEngine: WakeWordEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Reconnaissance vocale (mot-clé libre) — repli pour tout mot-clé sans modèle openWakeWord
    // dédié, voir startKeywordSpotting() ────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var keywordSpottingActive = false
    private var keywordSpottingKeyword: String = ""

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
        // BUG RÉEL CORRIGÉ (signalement utilisateur : « juste Jarvis, retire le Hey devant ») :
        // "jarvis" (seul) était auparavant mappé ici sur le même modèle que "hey jarvis" — ce
        // qui laissait croire que dire juste « Jarvis » fonctionnerait, alors que le modèle
        // hey_jarvis.onnx n'est fiable QUE sur la phrase complète « Hey Jarvis » (voir le
        // commentaire de classe ci-dessus). "jarvis" seul (et tout autre mot-clé non listé ici)
        // passe maintenant par startKeywordSpotting() (reconnaissance vocale, mot-clé libre).
        private val OWW_KEYWORDS = mapOf(
            "hey jarvis" to ("hey_jarvis.onnx" to "Hey Jarvis"),
            "alexa" to ("alexa.onnx" to "Alexa"),
            "mycroft" to ("hey_mycroft.onnx" to "Hey Mycroft"),
            "hey mycroft" to ("hey_mycroft.onnx" to "Hey Mycroft")
        )
        private const val OWW_DEFAULT_FILE = "hey_jarvis.onnx"
        private const val OWW_DEFAULT_LABEL = "Hey Jarvis"
        // BUG REEL CORRIGE (signalement utilisateur : "l'ecoute ne fonctionne toujours pas" apres
        // le retrait de Picovoice et la fiabilisation du telechargement des modeles) : 0.5f est
        // la valeur par defaut du PARAMETRE de la librairie (generique, pensee pour un modele de
        // demo), PAS un seuil realiste pour de vrais modeles openWakeWord entraines comme
        // hey_jarvis/alexa/hey_mycroft -- leur doc officielle (Re-MENTIA/openwakeword-android-kt)
        // utilise des seuils typiques de 0.08 a 0.15 dans ses propres exemples avec de vrais
        // modeles. A 0.5, le score de sortie du modele ne depassait quasiment jamais le seuil en
        // conditions reelles -> aucune detection ne se declenchait JAMAIS, silencieusement (aucune
        // erreur, le service tournait normalement, juste aucun mot-cle ne franchissait la barre).
        private const val OWW_THRESHOLD = 0.12f
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
            val usesTrainedModel = OWW_KEYWORDS.containsKey(keyword)
            val sb = StringBuilder("🎙️ Mot-clé configuré : « $keyword ».\n")

            if (usesTrainedModel) {
                val (modelFile, _) = OWW_KEYWORDS.getValue(keyword)
                val requiredAssets = listOf("melspectrogram.onnx", "embedding_model.onnx", modelFile)
                val missing = requiredAssets.filterNot { name ->
                    try { context.assets.open(name).use { true } } catch (e: Exception) { false }
                }
                sb.append("Moteur : openWakeWord (modèle pré-entraîné, faible consommation, hors-ligne).\n")
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
            } else {
                // Mot-clé sans modèle openWakeWord dédié (ex: "jarvis" seul, ou tout mot
                // personnalisé) — voir startKeywordSpotting().
                sb.append(
                    "Moteur : reconnaissance vocale (mot-clé libre) — « $keyword » n'a pas de " +
                        "modèle openWakeWord pré-entraîné dédié, JARVIS transcrit donc la parole en " +
                        "continu et se déclenche dès que le texte reconnu contient ce mot. " +
                        "Consomme plus de batterie qu'un modèle dédié et peut nécessiter internet " +
                        "sur certains téléphones sans reconnaissance hors-ligne installée.\n"
                )
                sb.append(
                    if (!SpeechRecognizer.isRecognitionAvailable(context))
                        "❌ Aucun service de reconnaissance vocale disponible sur cet appareil — ce mot-clé ne peut pas être détecté."
                    else if (lastStatusText.isNotBlank()) "Dernier statut connu du service : $lastStatusText"
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
                stopKeywordSpotting()
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
        // "hey jarvis"/"alexa"/"mycroft"/"hey mycroft" ont un vrai modèle openWakeWord entraîné
        // dessus (faible conso, hors-ligne) -- tout le reste (dont "jarvis" seul, demandé
        // explicitement par l'utilisateur, et n'importe quel mot-clé personnalisé) passe par la
        // reconnaissance vocale (mot-clé libre), seule façon de détecter une phrase pour
        // laquelle aucun modèle entraîné n'existe. Voir le commentaire de classe ci-dessus.
        if (OWW_KEYWORDS.containsKey(keyword)) {
            stopKeywordSpotting()
            startOpenWakeWord(keyword)
        } else {
            stopOpenWakeWord()
            startKeywordSpotting(keyword)
        }
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
        stopKeywordSpotting()
    }

    // ── Reconnaissance vocale (mot-clé libre) ───────────────────────────────────

    /**
     * Repli pour tout mot-clé sans modèle openWakeWord entraîné dédié (ex: « Jarvis » seul,
     * ou n'importe quel mot personnalisé) — voir le commentaire de classe pour le contexte
     * complet. SpeechRecognizer n'a pas de mode « écoute continue » natif sur Android : chaque
     * session se termine après un silence/une phrase, donc on relance une nouvelle session à
     * chaque fin (résultat, erreur, timeout) pour simuler une écoute permanente. Doit être
     * démarré/arrêté sur le thread principal (contrainte de SpeechRecognizer), d'où le passage
     * systématique par [handler].
     */
    private fun startKeywordSpotting(requestedKeyword: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("❌ reconnaissance vocale indisponible sur cet appareil — impossible d'écouter « $requestedKeyword »")
            return
        }
        keywordSpottingActive = true
        keywordSpottingKeyword = requestedKeyword
        updateNotification("mode reconnaissance vocale — écoute « $requestedKeyword » (mot-clé libre)")
        handler.post { initKeywordSpottingRecognizer() }
    }

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "la detection de mot-cle fonctionne mais
    // consomme enormement de RAM et ralentit le telephone") : la version précédente appelait
    // destroy() PUIS createSpeechRecognizer() à CHAQUE cycle (résultat, erreur, timeout — donc
    // potentiellement plusieurs fois par minute en silence), ce qui force Android à relier/
    // rebinder le service système de reconnaissance vocale à chaque fois — l'opération la plus
    // coûteuse de tout le cycle, en RAM comme en CPU. La MÊME instance de SpeechRecognizer est
    // désormais créée UNE SEULE FOIS, puis simplement relancée via startListening() à chaque
    // nouveau cycle (cancel() avant, pour repartir d'un état propre sans tout redétruire) — un
    // vrai destroy()/recreate() ne se produit plus qu'en dernier recours, si startListening()
    // échoue explicitement (état interne corrompu). Délai de relance aussi allongé (350ms →
    // 900ms) pour réduire encore la fréquence des cycles en silence prolongé.
    private fun initKeywordSpottingRecognizer() {
        if (!keywordSpottingActive) return
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) { }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            // Silence, timeout, pas de correspondance, service occupé... quelle que soit la
            // cause, on relance une nouvelle session sur la MÊME instance -- c'est ce qui
            // simule l'écoute continue sans recréer le service à chaque fois.
            override fun onError(error: Int) {
                scheduleKeywordSpottingRestart()
            }

            override fun onResults(results: Bundle?) {
                checkForKeyword(results, keywordSpottingKeyword)
                scheduleKeywordSpottingRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                checkForKeyword(partialResults, keywordSpottingKeyword)
            }
        })

        startKeywordSpottingListening()
    }

    private fun buildKeywordSpottingIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private fun startKeywordSpottingListening() {
        if (!keywordSpottingActive) return
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(buildKeywordSpottingIntent())
        } catch (e: Exception) {
            // État interne corrompu (rare) : seul cas où une VRAIE recréation reste nécessaire.
            updateNotification("❌ échec du démarrage de la reconnaissance vocale : ${e.message}")
            handler.postDelayed({ initKeywordSpottingRecognizer() }, 900L)
        }
    }

    private fun checkForKeyword(bundle: Bundle?, keyword: String) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val heard = matches.any { it.lowercase(Locale.getDefault()).contains(keyword) }
        if (heard) {
            // Coupe tout de suite la session en cours pour libérer le micro avant que
            // VoiceModeActivity (déclenché par triggerVoiceMode) ne le réclame à son tour.
            stopKeywordSpottingSession()
            triggerVoiceMode()
        }
    }

    private fun scheduleKeywordSpottingRestart() {
        if (!keywordSpottingActive) return
        handler.postDelayed({ startKeywordSpottingListening() }, 900L)
    }

    private fun stopKeywordSpottingSession() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) { }
        speechRecognizer = null
    }

    private fun stopKeywordSpotting() {
        keywordSpottingActive = false
        stopKeywordSpottingSession()
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
