package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Mode Vocal Interactif avec "Barge-In" (Interruption de l'IA quand l'utilisateur parle)
 * et écoute automatique continue à la fin de la parole.
 */
class VoiceModeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_TRIGGERED_BY_WAKEWORD = "triggered_by_wakeword"
    }

    private lateinit var orbView: OrbView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var imageOverlay: ImageView
    private lateinit var ramUsageText: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isBusy = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else {
            statusText.text = "Permission micro requise pour le mode vocal."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_mode)

        orbView = findViewById(R.id.orbView)
        statusText = findViewById(R.id.voiceStatusText)
        transcriptText = findViewById(R.id.voiceTranscriptText)
        imageOverlay = findViewById(R.id.voiceImageOverlay)
        ramUsageText = findViewById(R.id.ramUsageText)
        val closeButton = findViewById<TextView>(R.id.closeVoiceButton)
        val micToggle = findViewById<TextView>(R.id.micToggleButton)

        orbView.accentColor = Prefs.getAccentColor(this)
        // Déclenché par le mot-clé d'activation ("Jarvis") : toujours l'animation
        // "sphère réseau" façon Obsidian, même si l'utilisateur a choisi le style
        // pulsation par défaut ailleurs — c'est le rendu demandé pour ce cas précis.
        // Même style d'orbe partout : mode vocal manuel (bouton micro) et
        // détection par mot d'activation affichent désormais exactement la même
        // animation, pilotée uniquement par la préférence utilisateur.
        orbView.visualStyle = try {
            OrbView.VisualStyle.valueOf(Prefs.getOrbStyle(this))
        } catch (e: IllegalArgumentException) {
            OrbView.VisualStyle.NETWORK_SPHERE
        }
        // Charge le VRAI graphe du vault (notes + wikilinks, voir ObsidianController.buildVaultGraph)
        // en arrière-plan pour le style "Toile Obsidian" — un scan de fichiers ne doit jamais
        // bloquer le thread principal, même si le style choisi n'est pas celui-ci pour l'instant
        // (l'utilisateur peut changer de style sans revenir sur cet écran).
        CoroutineScope(Dispatchers.Main).launch {
            val graph = withContext(Dispatchers.IO) { ObsidianController.buildVaultGraph(this@VoiceModeActivity) }
            orbView.graphData = graph
        }
        // Compteur RAM (demande utilisateur, voir aussi MainActivity.refreshRamUsage) -- boucle
        // liée à lifecycleScope, donc automatiquement annulée à la fermeture de cet écran
        // (fermer le mode vocal détruit toujours cette Activity, pas de fuite possible).
        refreshRamUsage()
        lifecycleScope.launch {
            while (true) {
                delay(5_000)
                refreshRamUsage()
            }
        }
        tts = TextToSpeech(this, this)

        // Le mode vocal a besoin du micro en exclusivité — l'écoute permanente en
        // arrière-plan (openWakeWord) est mise en pause tant que cet écran
        // est ouvert, sinon les deux se disputent le même flux audio et aucune parole
        // n'est plus captée nulle part (chat, mode vocal, écoute permanente incluse).
        WakeWordService.pauseListening(this)

        closeButton.setOnClickListener {
            stopSpeechAndTts()
            finish()
        }

        micToggle.setOnClickListener {
            stopSpeechAndTts()
            checkPermissionAndListen()
        }

        checkPermissionAndListen()
    }

    private fun checkPermissionAndListen() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun stopSpeechAndTts() {
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {}
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Reconnaissance vocale indisponible sur cet appareil."
            return
        }

        // Barge-In : Si l'IA parle encore, la stopper immédiatement
        stopSpeechAndTts()

        isBusy = true
        orbView.state = OrbView.OrbState.LISTENING
        statusText.text = "Je vous écoute…"
        transcriptText.text = ""
        imageOverlay.visibility = View.GONE

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {
                    // Barge-In instantané : dès que l'utilisateur commence à parler, couper l'IA
                    stopSpeechAndTts()
                    orbView.state = OrbView.OrbState.LISTENING
                    statusText.text = "Je vous écoute…"
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Si le volume micro dépasse un seuil pendant la parole TTS, interrompre l'IA
                    if (rmsdB > 6f && tts?.isSpeaking == true) {
                        stopSpeechAndTts()
                    }
                    // Réutilise ce niveau RMS déjà fourni par SpeechRecognizer pour faire vibrer
                    // le style d'orbe "Neural Core" en fonction du son réel — voir OrbView.audioLevel.
                    // Pas de second flux micro ouvert exprès pour ça (rmsdB couvre grossièrement
                    // -2..10 en pratique, normalisé ici sur une échelle 0..1).
                    orbView.audioLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    orbView.state = OrbView.OrbState.THINKING
                    orbView.audioLevel = 0f // le micro n'écoute plus, retombe au repos plutôt que figé au dernier niveau
                    statusText.text = "JARVIS réfléchit…"
                }

                override fun onError(error: Int) {
                    isBusy = false
                    orbView.state = OrbView.OrbState.IDLE
                    orbView.audioLevel = 0f
                    statusText.text = "Touchez l'orbe ou le micro pour parler."
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        transcriptText.text = spoken
                        handleUserSpeech(spoken)
                    } else {
                        isBusy = false
                        orbView.state = OrbView.OrbState.IDLE
                        statusText.text = "Je n'ai rien entendu. Touchez le micro pour réessayer."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            startListening(intent)
        }
    }

    private fun handleUserSpeech(text: String) {
        ConversationStore.addUser(text)
        ConversationStore.persist(this)
        CoroutineScope(Dispatchers.Main).launch {
            val result = ApiClient.sendChat(this@VoiceModeActivity, ConversationStore.history)
            ConversationStore.addAssistant(result.text, result.imageBase64, result.imageMime)
            ConversationStore.persist(this@VoiceModeActivity)
            transcriptText.text = result.text
            showImageOverlay(result.imageBase64)
            speak(MarkdownUtils.stripForSpeech(result.text))
        }
    }

    /**
     * Affiche un graphique/image généré directement par-dessus l'orbe (ex: create_chart,
     * generate_image demandés au vocal) plutôt que de forcer l'utilisateur à ouvrir le
     * chat pour le voir. Masqué automatiquement au tour de parole suivant.
     */
    private fun showImageOverlay(base64: String?) {
        if (base64.isNullOrBlank()) {
            imageOverlay.visibility = View.GONE
            return
        }
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                imageOverlay.setImageBitmap(bitmap)
                imageOverlay.visibility = View.VISIBLE
            }
        } catch (_: Exception) {
            imageOverlay.visibility = View.GONE
        }
    }

    private fun speak(text: String) {
        stopSpeechAndTts()
        orbView.state = OrbView.OrbState.SPEAKING
        statusText.text = "JARVIS répond…"

        if (!ttsReady) {
            isBusy = false
            orbView.state = OrbView.OrbState.IDLE
            statusText.text = "Touchez le micro pour parler."
            return
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isBusy = false
                    // Écoute automatique continue à la fin de la réponse JARVIS
                    checkPermissionAndListen()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isBusy = false
                    orbView.state = OrbView.OrbState.IDLE
                }
            }
        })

        // BUG RÉEL CORRIGÉ : contrairement à MainActivity (voir addMessage), cette fonction
        // envoyait le texte BRUT au moteur TTS — tout le markdown/emojis échappait donc à
        // stripForSpeech et était lu tel quel en mode vocal (signalement utilisateur : JARVIS
        // prononce les emojis, les tirets/astérisques de liste...).
        tts?.speak(MarkdownUtils.stripForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /** Compteur RAM (demande utilisateur) -- voir RamMonitor.kt et le même mécanisme dans
     *  MainActivity.refreshRamUsage(). */
    private fun refreshRamUsage() {
        ramUsageText.text = RamMonitor.usageLabel(this)
    }

    override fun onDestroy() {
        stopSpeechAndTts()
        try {
            speechRecognizer?.destroy()
            tts?.shutdown()
        } catch (_: Exception) {}
        // Rend le micro à l'écoute permanente en arrière-plan maintenant que le mode
        // vocal manuel n'en a plus besoin.
        WakeWordService.resumeListening(this)
        super.onDestroy()
    }
}
