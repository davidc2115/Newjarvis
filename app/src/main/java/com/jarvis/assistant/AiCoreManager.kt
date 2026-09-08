package com.jarvis.assistant

import android.util.Log
import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IA locale de JARVIS — Gemini Nano exécuté directement sur l'appareil via AICore, le
 * service système Android de Google (voir https://developer.android.com/ai/gemini-nano).
 *
 * Remplace complètement les anciens moteurs embarqués maison (MediaPipe LLM Inference
 * pour les fichiers .task, llama.cpp compilé nativement pour les .gguf, ONNX Runtime
 * GenAI pour les .onnx — voir l'historique de LocalLlmManager.kt) : plus besoin que
 * l'utilisateur trouve, télécharge et importe lui-même un fichier de modèle. AICore gère
 * le téléchargement, la mise à jour et le cycle de vie du modèle au niveau du système
 * Android — l'appli se contente d'appeler l'API ML Kit GenAI Prompt.
 *
 * Contrepartie technique importante : contrairement aux fournisseurs cloud (et aux
 * anciens moteurs embarqués, qui n'avaient pas de limite stricte imposée par l'API
 * elle-même), AICore impose une limite dure d'environ 4000 tokens sur l'ENSEMBLE de la
 * requête (prompt système + historique + message utilisateur). Le catalogue complet
 * d'actions JARVIS_CMD (SYSTEM_PROMPT dans ApiClient.kt, ~17 000 caractères) le
 * dépasserait à lui seul dès le premier appel — ce moteur utilise donc son propre prompt
 * système, volontairement très court, plutôt que celui reçu en paramètre par
 * dispatchToProvider (voir buildLocalPrompt ci-dessous).
 *
 * Disponibilité réelle (appareil + Android 14+, ex: Pixel 8 ou plus récent) — voir
 * checkStatus(). Nécessite : implementation("com.google.mlkit:genai-prompt:...") dans
 * app/build.gradle.
 */
object AiCoreManager {

    private const val TAG = "AiCoreManager"

    enum class Status { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE, ERROR }

    sealed class DownloadEvent {
        data class Progress(val bytesDownloaded: Long) : DownloadEvent()
        object Completed : DownloadEvent()
        data class Failed(val message: String) : DownloadEvent()
    }

    private val generativeModel by lazy { Generation.getClient() }

    suspend fun checkStatus(): Status = withContext(Dispatchers.IO) {
        try {
            when (generativeModel.checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
                else -> Status.UNAVAILABLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus() a échoué", e)
            Status.ERROR
        }
    }

    /** Lance le téléchargement du modèle Gemini Nano par AICore. [onEvent] est appelé sur
     *  chaque mise à jour de progression — l'appelant est responsable de repasser sur le
     *  thread UI s'il touche des vues Android depuis ce callback. */
    suspend fun download(onEvent: (DownloadEvent) -> Unit) = withContext(Dispatchers.IO) {
        try {
            generativeModel.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> Unit
                    is DownloadStatus.DownloadProgress -> onEvent(DownloadEvent.Progress(status.totalBytesDownloaded))
                    DownloadStatus.DownloadCompleted -> onEvent(DownloadEvent.Completed)
                    is DownloadStatus.DownloadFailed -> onEvent(DownloadEvent.Failed(status.e.message ?: "Erreur inconnue"))
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "download() a échoué", e)
            onEvent(DownloadEvent.Failed(e.message ?: "Erreur inconnue"))
        }
    }

    /** Précharge le modèle en mémoire pour réduire la latence du tout premier appel —
     *  facultatif, appelé une fois le téléchargement terminé (voir SettingsActivity). */
    suspend fun warmup() = withContext(Dispatchers.IO) {
        try {
            generativeModel.warmup()
        } catch (e: Exception) {
            Log.w(TAG, "warmup() a échoué (non bloquant) : ${e.message}")
        }
    }

    // Prompt système dédié au mode local — volontairement minuscule (voir note de tête de
    // fichier). Pas de catalogue d'actions JARVIS_CMD : Gemini Nano sert ici de secours
    // conversationnel hors-ligne, pas d'assistant domotique complet comme les fournisseurs
    // cloud. S'il émet malgré tout un JARVIS_CMD reconnu par coïncidence, JarvisCommandParser
    // l'exécutera quand même (le parsing ne dépend pas du fournisseur d'origine).
    private const val LOCAL_SYSTEM_PROMPT =
        "Tu es JARVIS, assistant IA vocal. Tu fonctionnes ici en mode local hors-ligne " +
            "(Gemini Nano sur l'appareil), sans accès à Internet ni aux données du " +
            "téléphone (fichiers, agenda, SMS, contacts...). Réponds en français, phrases " +
            "courtes, sans markdown. Si on te demande une action nécessitant ces données ou " +
            "une connexion Internet, dis clairement que tu es en mode local et que ça " +
            "nécessite de repasser sur un fournisseur en ligne (⚙ Paramètres → Config)."

    // Nombre de tours d'historique conservés pour le mode local — volontairement bien plus
    // bas que MAX_HISTORY_MESSAGES (16, pour les fournisseurs cloud) vu le budget d'environ
    // 4000 tokens partagé entre prompt système + historique + message.
    private const val MAX_HISTORY_MESSAGES_LOCAL = 4

    // Garde-fou supplémentaire en caractères (~3 caractères/token, marge sous les 4000
    // tokens réels imposés par AICore) — tronque par sécurité même si un message individuel
    // est anormalement long, plutôt que de laisser l'appel échouer sans explication claire.
    private const val MAX_PROMPT_CHARS = 9000

    private fun buildLocalPrompt(history: List<HistoryEntry>): String {
        val recent = history.takeLast(MAX_HISTORY_MESSAGES_LOCAL)
        val sb = StringBuilder(LOCAL_SYSTEM_PROMPT).append("\n\n")
        for (entry in recent) {
            val label = if (entry.role == "user") "Utilisateur" else "JARVIS"
            // Mode texte uniquement : les pièces jointes (images, documents) ne sont pas
            // transmises ici, le budget de tokens de Gemini Nano ne le permet pas de toute
            // façon de manière fiable.
            sb.append(label).append(": ").append(entry.text.take(500)).append("\n")
        }
        sb.append("JARVIS: ")
        val full = sb.toString()
        return if (full.length > MAX_PROMPT_CHARS) full.takeLast(MAX_PROMPT_CHARS) else full
    }

    suspend fun generate(history: List<HistoryEntry>): String = withContext(Dispatchers.IO) {
        val status = checkStatus()
        if (status != Status.AVAILABLE) {
            return@withContext when (status) {
                Status.DOWNLOADABLE ->
                    "❌ L'IA locale (Gemini Nano) n'est pas encore téléchargée sur cet appareil. " +
                        "Ouvre ⚙ Paramètres → onglet « Local » et lance le téléchargement " +
                        "(gratuit, géré automatiquement par Android)."
                Status.DOWNLOADING ->
                    "⏳ L'IA locale (Gemini Nano) est en cours de téléchargement sur cet appareil — réessaie dans quelques instants."
                Status.ERROR ->
                    "❌ Impossible de vérifier la disponibilité de l'IA locale (AICore). " +
                        "Vérifie que l'application système AICore est à jour sur cet appareil."
                else ->
                    "❌ L'IA locale (Gemini Nano / AICore) n'est pas disponible sur cet appareil. " +
                        "Elle nécessite un téléphone compatible (Pixel 8 ou plus récent, ou équivalent " +
                        "Samsung/Snapdragon récent) et Android 14 ou plus récent."
            }
        }

        try {
            val response = generativeModel.generateContent(buildLocalPrompt(history))
            response.candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotBlank() }
                ?: "JARVIS n'a rien à répondre pour l'instant — reformule ta question."
        } catch (e: Exception) {
            Log.e(TAG, "generateContent() a échoué", e)
            "❌ Erreur de l'IA locale (AICore) : ${e.message}"
        }
    }

    /** AICore gère lui-même le cycle de vie du modèle (chargement/déchargement) — rien à
     *  libérer manuellement côté appli, contrairement aux anciens moteurs embarqués
     *  directement dans le processus (GGUF/MediaPipe/ONNX). Conservé pour compatibilité
     *  d'appel avec le reste du code (ex: après suppression d'un modèle dans les Réglages). */
    fun unload() {
        // Rien à faire — voir commentaire ci-dessus.
    }
}
