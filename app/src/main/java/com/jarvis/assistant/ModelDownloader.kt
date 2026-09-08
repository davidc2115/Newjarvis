package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Télécharge des modèles Stable Diffusion (génération d'image embarquée) pour JARVIS.
 *
 * Ne concerne plus l'IA conversationnelle locale : celle-ci utilise désormais Gemini
 * Nano via AICore (voir AiCoreManager.kt), géré directement par le système Android — plus
 * besoin de trouver/télécharger/importer un fichier de modèle de langage soi-même. Ce
 * fichier gère uniquement les modèles Stable Diffusion (.gguf), soumis à aucune licence
 * bloquante, donc aucun jeton n'est requis pour les entrées du catalogue ci-dessous.
 */
object ModelDownloader {

    sealed class Progress {
        data class Percent(val value: Int) : Progress()
        data class Done(val file: File) : Progress()
        data class Error(val message: String) : Progress()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Catalogue de modèles Stable Diffusion — uniquement des entrées vérifiées
    // manuellement : fichiers réels et publics, aucune licence à accepter.
    // ─────────────────────────────────────────────────────────────────────────

    data class ModelEntry(
        val label: String,
        val url: String,                   // URL de téléchargement direct (HF API)
        val pageUrl: String,               // Page web à ouvrir dans le navigateur
        val sizeHint: String,
        val needsHfToken: Boolean = false,
        val description: String = "",
        val creator: String = ""
    )

    val MODEL_CATALOG: List<ModelEntry> = listOf(
        ModelEntry(
            label        = "🎨 Stable Diffusion 1.5 — léger (2.7 Go)",
            url          = "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q4_0.gguf?download=true",
            pageUrl      = "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF",
            sizeHint     = "~2.7 Go",
            needsHfToken = false,
            creator      = "Runway ML (quantifié par kostakoff)",
            description  = "Version compressée, plus rapide sur CPU mobile. Qualité correcte."
        ),
        ModelEntry(
            label        = "🎨 Stable Diffusion 1.5 — qualité (4.5 Go)",
            url          = "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q8_0.gguf?download=true",
            pageUrl      = "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF",
            sizeHint     = "~4.5 Go",
            needsHfToken = false,
            creator      = "Runway ML (quantifié par kostakoff)",
            description  = "Meilleure qualité d'image, plus lent à générer. Téléphone récent recommandé."
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement automatique (avec ou sans jeton HF)
    // ─────────────────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder().url(url)
            if (hfToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $hfToken")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        onProgress(Progress.Error(
                            "🔒 Accès refusé (${response.code}).\n\n" +
                            "Ce modèle nécessite un compte HuggingFace.\n" +
                            "→ Appuyez sur \"Ouvrir dans le navigateur\" pour télécharger manuellement.\n" +
                            "→ Ou générez un jeton gratuit sur huggingface.co/settings/tokens"
                        ))
                        return@withContext
                    }
                    !response.isSuccessful -> {
                        onProgress(Progress.Error("Échec (${response.code}) : ${response.message}"))
                        return@withContext
                    }
                }

                val body = response.body ?: run {
                    onProgress(Progress.Error("Réponse vide du serveur."))
                    return@withContext
                }

                val destFile = File(context.filesDir, "local_sd_model.bin")
                val totalBytes = body.contentLength()
                var downloaded = 0L
                var lastPercent = -1

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(1024 * 256)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val percent = ((downloaded * 100) / totalBytes).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(Progress.Percent(percent))
                                }
                            }
                        }
                    }
                }

                Prefs.saveLocalSdModelPath(context, destFile.absolutePath)
                NativeStableDiffusion.unload()
                onProgress(Progress.Done(destFile))
            }
        } catch (e: Exception) {
            onProgress(Progress.Error("Erreur réseau : ${e.message}"))
        }
    }
}
