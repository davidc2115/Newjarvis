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
 * Télécharge des modèles IA locaux pour JARVIS.
 *
 * ## Pourquoi certains modèles nécessitent-ils un compte ?
 * Les modèles LLM (Gemma, LLaMA, Phi, Mistral) sont soumis à des licences
 * spécifiques par leurs créateurs (Google, Meta, Microsoft, Mistral AI).
 * HuggingFace et Kaggle imposent l'acceptation de ces licences via un compte.
 *
 * ## Comment télécharger sans compte (méthode recommandée) :
 * 1. Cliquez sur "Ouvrir la page de téléchargement" dans JARVIS.
 * 2. Le navigateur s'ouvre sur la page du modèle (HuggingFace ou Kaggle).
 * 3. Téléchargez le fichier manuellement (le navigateur gère la session).
 * 4. Revenez dans JARVIS → Modèles Locaux → "Importer un fichier".
 *
 * ## Téléchargement automatique (méthode avancée) :
 * Générez un jeton gratuit sur huggingface.co/settings/tokens
 * et collez-le dans le champ "Jeton HuggingFace" ci-dessus.
 */
object ModelDownloader {

    sealed class Progress {
        data class Percent(val value: Int) : Progress()
        data class Done(val file: File) : Progress()
        data class Error(val message: String) : Progress()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Catalogue de modèles — uniquement des entrées vérifiées manuellement
    // (chaque URL a été confirmée réellement existante via l'API HuggingFace
    // avant d'être ajoutée ici — jamais de nom de fichier deviné).
    //
    // Demande utilisateur (remplace l'ancien catalogue Stable Diffusion/Qwen/
    // Gemma 3) : GGUF (Llama 3.2 1B/3B, Qwen 2.5 0.5B/1.5B, Gemma 2 2B),
    // MediaPipe .task (Gemma 2 2B), et Phi-3.5/Phi-3 mini.
    //
    // Note honnête sur Llama 3.2 1B en MediaPipe : aucun fichier .task public
    // vérifié n'a été trouvé pour ce modèle (seul un fichier .litertlm existe
    // chez litert-community — format LiteRT-LM, un moteur DIFFÉRENT et
    // incompatible avec com.google.mediapipe.tasks.genai.llminference.LlmInference
    // utilisé ici). Cette entrée est donc absente du catalogue MediaPipe ;
    // Llama 3.2 1B reste disponible en GGUF, qui fonctionne réellement.
    //
    // Note honnête sur Phi-3.5/Phi-3 mini : demandés initialement en ONNX
    // (ONNX Runtime GenAI), mais Microsoft ne publie cette bibliothèque que
    // pour Python et .NET -- AUCUNE distribution Android/Java officielle
    // (absente de Maven Central), et la compiler nous-mêmes depuis les
    // sources demanderait un pipeline NDK C++ complet, hors de portée ici.
    // Choix confirmé par l'utilisateur : remplacés par leurs équivalents GGUF
    // (bartowski/Phi-3.5-mini-instruct-GGUF, bartowski/Phi-3-mini-4k-instruct-GGUF),
    // qui fonctionnent immédiatement avec le moteur llama.cpp déjà intégré.
    // ─────────────────────────────────────────────────────────────────────────

    data class ModelEntry(
        val key: String,                   // identifiant stable (nom de fichier + registre), jamais affiché
        val label: String,
        val url: String,                   // URL de téléchargement direct (HF, fichier unique)
        val pageUrl: String,               // Page web à ouvrir dans le navigateur
        val format: LocalLlmManager.LocalModelFormat,
        val sizeHint: String,
        val needsHfToken: Boolean = false,
        val description: String = "",
        val creator: String = ""
    )

    val MODEL_CATALOG: List<ModelEntry> = listOf(

        // ─── GGUF (llama.cpp natif, compilé dans l'app) ──────────────────────
        ModelEntry(
            key          = "llama32-1b-gguf",
            label        = "🦙 Llama 3.2 1B — GGUF, LIBRE sans compte (0.8 Go)",
            url          = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~0.8 Go",
            needsHfToken = false,
            creator      = "Meta (Llama 3.2)",
            description  = "Très léger et ultra rapide, aucun compte requis. Bon choix par défaut sur téléphone modeste."
        ),
        ModelEntry(
            key          = "llama32-3b-gguf",
            label        = "🦙 Llama 3.2 3B — GGUF, LIBRE sans compte (2 Go)",
            url          = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~2 Go",
            needsHfToken = false,
            creator      = "Meta (Llama 3.2)",
            description  = "Meilleure qualité que la 1B, aucun compte requis. Téléphone récent recommandé."
        ),
        ModelEntry(
            key          = "qwen25-05b-gguf",
            label        = "🟣 Qwen2.5 0.5B — GGUF, LIBRE sans compte (400 Mo)",
            url          = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~400 Mo",
            needsHfToken = false,
            creator      = "Alibaba (Qwen)",
            description  = "Le plus léger et le plus rapide du catalogue, aucun compte requis. Licence Apache 2.0 ouverte."
        ),
        ModelEntry(
            key          = "qwen25-15b-gguf",
            label        = "🟣 Qwen2.5 1.5B — GGUF, LIBRE sans compte (1 Go)",
            url          = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~1 Go",
            needsHfToken = false,
            creator      = "Alibaba (Qwen)",
            description  = "Bon compromis vitesse/qualité, aucun compte requis. Licence Apache 2.0."
        ),
        ModelEntry(
            key          = "gemma2-2b-gguf",
            label        = "🟢 Gemma 2 2B — GGUF, LIBRE sans compte (1.7 Go)",
            url          = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~1.7 Go",
            needsHfToken = false,
            creator      = "Google (Gemma 2)",
            description  = "Bonne qualité de réponse, aucun compte requis (mirroir GGUF non verrouillé)."
        ),
        ModelEntry(
            key          = "phi35-mini-gguf",
            label        = "🔷 Phi-3.5 mini — GGUF, LIBRE sans compte (2.5 Go)",
            url          = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~2.5 Go",
            needsHfToken = false,
            creator      = "Microsoft (Phi-3.5)",
            description  = "Très bon en raisonnement/code pour sa taille, aucun compte requis. Licence MIT."
        ),
        ModelEntry(
            key          = "phi3-mini-gguf",
            label        = "🔷 Phi-3 mini — GGUF, LIBRE sans compte (2.4 Go)",
            url          = "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~2.4 Go",
            needsHfToken = false,
            creator      = "Microsoft (Phi-3)",
            description  = "Version précédente de Phi, toujours solide, aucun compte requis. Licence MIT."
        ),

        // ─── MediaPipe (.task) — moteur officiel Google, licence Gemma (gating réel) ───
        ModelEntry(
            key          = "gemma2-2b-task",
            label        = "🟢 Gemma 2 2B — MediaPipe .task, officiel Google (2.7 Go)",
            url          = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2_q8_multi-prefill-seq_ekv1280.task",
            pageUrl      = "https://huggingface.co/litert-community/Gemma2-2B-IT",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~2.7 Go",
            needsHfToken = true,
            creator      = "Google",
            description  = "Format MediaPipe officiel (moteur intégré à l'app). Nécessite un compte HuggingFace + acceptation de la licence Gemma."
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement automatique (avec ou sans jeton HF)
    // ─────────────────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * BUG RÉEL CORRIGÉ (signalement utilisateur : demande d'une "encoche" sur les modèles déjà
     * téléchargés + "rotation entre les modèles") : jusqu'ici CHAQUE téléchargement écrasait
     * le même nom de fichier fixe ("local_model.gguf"/".task"/".onnx") -- impossible d'avoir
     * plusieurs modèles locaux installés en même temps, donc impossible de savoir dans l'écran
     * de téléchargement lesquels étaient déjà présents, et impossible de basculer sur un autre
     * modèle si le modèle actif échouait. Chaque modèle a maintenant un nom de fichier unique
     * dérivé de son "key" stable (voir ModelEntry), et est enregistré dans
     * Prefs.getLocalModelsRegistry -- voir ApiClient.sendLocal pour la rotation qui en découle.
     */
    suspend fun download(
        context: Context,
        entry: ModelEntry,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder().url(entry.url)
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

                val extension = when (entry.format) {
                    LocalLlmManager.LocalModelFormat.GGUF -> "gguf"
                    LocalLlmManager.LocalModelFormat.ONNX -> "onnx"
                    LocalLlmManager.LocalModelFormat.TASK -> "task"
                    LocalLlmManager.LocalModelFormat.STABLE_DIFFUSION -> "bin"
                }
                val destFile = File(context.filesDir, "local_model_${entry.key}.$extension")
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

                Prefs.saveLocalModelPath(context, destFile.absolutePath)
                Prefs.saveLocalModelFormat(context, entry.format.name)
                Prefs.addLocalModelToRegistry(context, destFile.absolutePath, entry.format.name, entry.label)
                LocalLlmManager.unload()
                onProgress(Progress.Done(destFile))
            }
        } catch (e: Exception) {
            onProgress(Progress.Error("Erreur réseau : ${e.message}"))
        }
    }
}
