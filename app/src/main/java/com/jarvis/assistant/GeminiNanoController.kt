package com.jarvis.assistant

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation

/**
 * Backend IA on-device : Gemini Nano via AICore (ML Kit GenAI Prompt API). Gratuit, sans clé
 * API, tourne entièrement sur l'appareil (aucune donnée envoyée à un serveur) une fois le
 * modèle téléchargé -- mais seulement disponible sur les appareils compatibles AICore
 * (Pixel 8/8 Pro/8a/9 et Galaxy S24 notamment). Suit exactement l'API officielle décrite sur
 * developers.google.com/ml-kit/genai/prompt/android/get-started (checkStatus renvoie une
 * des constantes FeatureStatus, download() est un Flow de DownloadStatus).
 */
object GeminiNanoController {

    private val generativeModel by lazy { Generation.getClient() }

    /** Renvoie une constante FeatureStatus.{AVAILABLE,DOWNLOADABLE,DOWNLOADING,UNAVAILABLE}. */
    suspend fun checkStatus(): Int = generativeModel.checkStatus()

    suspend fun downloadModel(onFailed: (String) -> Unit, onCompleted: () -> Unit) {
        generativeModel.download().collect { status ->
            when (status) {
                DownloadStatus.DownloadCompleted -> onCompleted()
                is DownloadStatus.DownloadFailed -> onFailed(status.e.message ?: "échec du téléchargement")
                else -> Unit
            }
        }
    }

    suspend fun generateReply(prompt: String): String {
        val response = generativeModel.generateContent(prompt)
        return response.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: "🤖 Gemini Nano n'a renvoyé aucune réponse exploitable."
    }
}
