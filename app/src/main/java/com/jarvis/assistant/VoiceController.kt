package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Voix de JARVIS -- axe "vraie application JARVIS / Iron Man" (voir demande utilisateur).
 * Avant cette tâche, l'appli réécrite (voir tâche #182, squelette vierge) était un chat texte
 * pur : ni entrée vocale, ni sortie vocale, alors que l'ancienne appli avait tout ça (TTS,
 * wake-word...) -- non porté lors de la réécriture. On reconstruit ici la brique la plus basique
 * mais la plus fondamentale : synthèse vocale (TextToSpeech système, 100% on-device, aucune
 * clé/réseau) pour que JARVIS parle ses réponses, et un point d'entrée pour la reconnaissance
 * vocale (dictée système, via l'Intent standard RecognizerIntent -- pas de SpeechRecognizer
 * manuel pour l'instant : plus simple et fiable pour un premier jet "appuie sur le micro, parle,
 * le texte remplit le champ").
 */
object VoiceController {

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    /** À appeler une fois (ex. dans MainActivity.onCreate) avant le premier [speak]. */
    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                // Français par défaut (cohérent avec le reste de l'appli) ; repli silencieux
                // sur la langue par défaut du moteur si le français n'est pas disponible plutôt
                // que de planter -- mieux vaut parler avec un accent que ne pas parler du tout.
                val result = engine.setLanguage(Locale.FRENCH)
                ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) ready = true // on tente quand même avec la langue par défaut du moteur
                engine.setPitch(0.95f)
                engine.setSpeechRate(1.0f)
            } else {
                ready = false
            }
        }
    }

    /**
     * Lit [text] à voix haute si la synthèse est prête et activée dans les Réglages (voir
     * Prefs.isTtsEnabled). Nettoie d'abord le texte (voir [cleanForSpeech]) pour ne pas lire
     * les emojis/markdown mot pour mot -- bug déjà connu et corrigé dans l'ancienne appli.
     */
    fun speak(context: Context, text: String) {
        if (!Prefs.isTtsEnabled(context)) return
        val engine = tts ?: return
        if (!ready) return
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        engine.setOnUtteranceProgressListener(null as UtteranceProgressListener?)
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply_${System.currentTimeMillis()}")
    }

    /** Coupe immédiatement la parole en cours (ex. si l'utilisateur retape pendant que JARVIS parle). */
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    /**
     * Retire ce qui ne doit pas être lu à voix haute : URLs, emojis, marqueurs markdown
     * (**gras**, _italique_, `code`, # titres, > citations), et aplati les sauts de ligne
     * multiples en pauses naturelles.
     */
    fun cleanForSpeech(text: String): String {
        var t = text
        t = t.replace(Regex("https?://\\S+"), "")
        // Emojis + symboles pictographiques usuels (Java regex : \x{h..h} accepte les points de
        // code au-delà du BMP, donc pas besoin de gérer les paires de substituts à la main).
        t = t.replace(
            Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{2190}-\\x{21FF}]"),
            ""
        )
        t = t.replace(Regex("[*_`#>~]"), "")
        t = t.replace(Regex("[•·]"), "")
        t = t.replace(Regex("\\n{2,}"), ". ")
        t = t.replace('\n', '.')
        t = t.replace(Regex("\\s{2,}"), " ")
        return t.trim()
    }

    /**
     * Construit l'Intent de dictée système (fr-FR) à lancer via
     * registerForActivityResult(ActivityResultContracts.StartActivityForResult()). Le texte
     * reconnu est renvoyé dans le résultat sous RecognizerIntent.EXTRA_RESULTS (liste de
     * String, on prend le premier -- meilleure hypothèse du moteur).
     */
    fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Je t'écoute...")
        }
    }
}
