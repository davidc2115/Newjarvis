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
    // MediaPipe .task (Gemma 2 2B, Llama 3.2 1B), ONNX (Phi-3.5 mini, Phi-3 mini).
    //
    // Note honnête sur Llama 3.2 1B en MediaPipe : aucun fichier .task public
    // vérifié n'a été trouvé pour ce modèle (seul un fichier .litertlm existe
    // chez litert-community — format LiteRT-LM, un moteur DIFFÉRENT et
    // incompatible avec com.google.mediapipe.tasks.genai.llminference.LlmInference
    // utilisé ici). Plutôt que d'inventer une URL qui échouerait au chargement,
    // cette entrée est absente du catalogue MediaPipe ci-dessous ; Llama 3.2 1B
    // reste disponible en GGUF (natif, fonctionne réellement dans cette app).
    // ─────────────────────────────────────────────────────────────────────────

    data class ModelEntry(
        val label: String,
        val url: String,                   // URL de téléchargement direct (HF, fichier unique)
        val pageUrl: String,               // Page web à ouvrir dans le navigateur
        val format: LocalLlmManager.LocalModelFormat,
        val sizeHint: String,
        val needsHfToken: Boolean = false,
        val description: String = "",
        val creator: String = "",
        // Modèles ONNX Runtime GenAI réels : plusieurs fichiers obligatoires
        // (poids .onnx + .onnx.data + config/tokenizer), jamais un seul fichier
        // autonome contrairement à GGUF/.task — quand renseigné, remplace le
        // téléchargement mono-fichier ci-dessus par un téléchargement de dossier
        // complet (voir ModelDownloader.downloadMultiFile).
        val multiFiles: List<Pair<String, String>>? = null, // (URL complète, nom de fichier local)
        val folderName: String = ""
    )

    val MODEL_CATALOG: List<ModelEntry> = listOf(

        // ─── GGUF (llama.cpp natif, compilé dans l'app) ──────────────────────
        ModelEntry(
            label        = "🦙 Llama 3.2 1B — GGUF, LIBRE sans compte (0.8 Go)",
            url          = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~0.8 Go",
            needsHfToken = false,
            creator      = "Meta (Llama 3.2)",
            description  = "Très léger et rapide, aucun compte requis. Bon choix par défaut sur téléphone modeste."
        ),
        ModelEntry(
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
            label        = "🟣 Qwen2.5 0.5B — GGUF, LIBRE sans compte (400 Mo)",
            url          = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~400 Mo",
            needsHfToken = false,
            creator      = "Alibaba (Qwen)",
            description  = "Très léger et rapide, aucun compte requis. Licence Apache 2.0 ouverte."
        ),
        ModelEntry(
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
            label        = "🟢 Gemma 2 2B — GGUF, LIBRE sans compte (1.7 Go)",
            url          = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true",
            pageUrl      = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF",
            format       = LocalLlmManager.LocalModelFormat.GGUF,
            sizeHint     = "~1.7 Go",
            needsHfToken = false,
            creator      = "Google (Gemma 2)",
            description  = "Bonne qualité de réponse, aucun compte requis (mirroir GGUF non verrouillé)."
        ),

        // ─── MediaPipe (.task) — moteur officiel Google, licence Gemma (gating réel) ───
        ModelEntry(
            label        = "🟢 Gemma 2 2B — MediaPipe .task, officiel Google (2.7 Go)",
            url          = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2_q8_multi-prefill-seq_ekv1280.task",
            pageUrl      = "https://huggingface.co/litert-community/Gemma2-2B-IT",
            format       = LocalLlmManager.LocalModelFormat.TASK,
            sizeHint     = "~2.7 Go",
            needsHfToken = true,
            creator      = "Google",
            description  = "Format MediaPipe officiel (moteur intégré à l'app). Nécessite un compte HuggingFace + acceptation de la licence Gemma."
        ),

        // ─── ONNX Runtime GenAI — fichiers officiels Microsoft, multi-fichiers ───
        // (poids + config + tokenizer dans un même dossier, téléchargés ensemble
        // par downloadMultiFile ci-dessous — impossible avec un lien unique).
        ModelEntry(
            label        = "🔷 Phi-3.5 mini — ONNX, officiel Microsoft (~2.5 Go)",
            url          = "",
            pageUrl      = "https://huggingface.co/microsoft/Phi-3.5-mini-instruct-onnx/tree/main/cpu_and_mobile/cpu-int4-awq-block-128-acc-level-4",
            format       = LocalLlmManager.LocalModelFormat.ONNX,
            sizeHint     = "~2.5 Go (8 fichiers)",
            needsHfToken = false,
            creator      = "Microsoft (Phi-3.5)",
            description  = "Quantifié int4, pensé CPU/mobile. Aucun compte requis. Téléchargement en plusieurs fichiers (dossier complet). ⚠️ La génération de texte via ce modèle nécessite la bibliothèque native onnxruntime-genai, pas encore embarquée dans cette app (absente de Maven Central, intégration native à faire séparément) — le téléchargement fonctionne, l'inférence pas encore.",
            folderName   = "local_onnx_phi35_mini",
            multiFiles   = listOf(
                "config.json" to "config.json",
                "configuration_phi3.py" to "configuration_phi3.py",
                "genai_config.json" to "genai_config.json",
                "phi-3.5-mini-instruct-cpu-int4-awq-block-128-acc-level-4.onnx" to "model.onnx",
                "phi-3.5-mini-instruct-cpu-int4-awq-block-128-acc-level-4.onnx.data" to "model.onnx.data",
                "special_tokens_map.json" to "special_tokens_map.json",
                "tokenizer.json" to "tokenizer.json",
                "tokenizer_config.json" to "tokenizer_config.json"
            ).map { (remote, local) ->
                "https://huggingface.co/microsoft/Phi-3.5-mini-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-awq-block-128-acc-level-4/$remote?download=true" to local
            }
        ),
        ModelEntry(
            label        = "🔷 Phi-3 mini — ONNX, officiel Microsoft (~2.4 Go)",
            url          = "",
            pageUrl      = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/tree/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4",
            format       = LocalLlmManager.LocalModelFormat.ONNX,
            sizeHint     = "~2.4 Go (10 fichiers)",
            needsHfToken = false,
            creator      = "Microsoft (Phi-3)",
            description  = "Quantifié int4, pensé CPU/mobile. Aucun compte requis. Téléchargement en plusieurs fichiers (dossier complet). ⚠️ Même limitation que Phi-3.5 mini : l'inférence nécessite onnxruntime-genai, pas encore embarqué dans cette app.",
            folderName   = "local_onnx_phi3_mini",
            multiFiles   = listOf(
                "added_tokens.json" to "added_tokens.json",
                "config.json" to "config.json",
                "configuration_phi3.py" to "configuration_phi3.py",
                "genai_config.json" to "genai_config.json",
                "phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx" to "model.onnx",
                "phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx.data" to "model.onnx.data",
                "special_tokens_map.json" to "special_tokens_map.json",
                "tokenizer.json" to "tokenizer.json",
                "tokenizer.model" to "tokenizer.model",
                "tokenizer_config.json" to "tokenizer_config.json"
            ).map { (remote, local) ->
                "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/$remote?download=true" to local
            }
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement automatique (avec ou sans jeton HF) — fichier unique
    // ─────────────────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
        format: LocalLlmManager.LocalModelFormat = LocalLlmManager.LocalModelFormat.TASK,
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

                val extension = when (format) {
                    LocalLlmManager.LocalModelFormat.GGUF -> "gguf"
                    LocalLlmManager.LocalModelFormat.ONNX -> "onnx"
                    LocalLlmManager.LocalModelFormat.TASK -> "task"
                    LocalLlmManager.LocalModelFormat.STABLE_DIFFUSION -> "bin"
                }
                val destFileName = if (format == LocalLlmManager.LocalModelFormat.STABLE_DIFFUSION) {
                    "local_sd_model.$extension"
                } else {
                    "local_model.$extension"
                }
                val destFile = File(context.filesDir, destFileName)
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

                if (format == LocalLlmManager.LocalModelFormat.STABLE_DIFFUSION) {
                    Prefs.saveLocalSdModelPath(context, destFile.absolutePath)
                    NativeStableDiffusion.unload()
                } else {
                    Prefs.saveLocalModelPath(context, destFile.absolutePath)
                    Prefs.saveLocalModelFormat(context, format.name)
                    LocalLlmManager.unload()
                }
                onProgress(Progress.Done(destFile))
            }
        } catch (e: Exception) {
            onProgress(Progress.Error("Erreur réseau : ${e.message}"))
        }
    }

    /** Surcharge rétrocompatible. */
    suspend fun download(
        context: Context,
        url: String,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = download(context, url, hfToken, LocalLlmManager.LocalModelFormat.TASK, onProgress)

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement multi-fichiers (ONNX Runtime GenAI réel : poids .onnx +
    // .onnx.data + genai_config.json + tokenizer... jamais un seul fichier
    // autonome, contrairement à GGUF/.task) -- demande utilisateur explicite
    // d'ajouter Phi-3.5 mini / Phi-3 mini en ONNX. LocalLlmManager.detectFormat
    // savait déjà reconnaître un DOSSIER comme un modèle ONNX
    // (java.io.File(modelPath).isDirectory), mais rien ne permettait jusqu'ici
    // de télécharger automatiquement ce dossier complet -- seul un import
    // fichier-par-fichier manuel aurait été possible. Cette fonction télécharge
    // chaque fichier du modèle l'un après l'autre dans un sous-dossier dédié de
    // l'app, puis pointe Prefs.localModelPath vers CE DOSSIER (pas un fichier).
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun downloadMultiFile(
        context: Context,
        entry: ModelEntry,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val files = entry.multiFiles
        if (files.isNullOrEmpty()) {
            onProgress(Progress.Error("Erreur interne : aucun fichier à télécharger pour ce modèle."))
            return@withContext
        }
        val folder = File(context.filesDir, entry.folderName.ifBlank { "local_onnx_model" })
        try {
            if (!folder.exists()) folder.mkdirs()

            files.forEachIndexed { index, (fileUrl, localName) ->
                val requestBuilder = Request.Builder().url(fileUrl)
                if (hfToken.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $hfToken")
                }
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        throw java.io.IOException(
                            "🔒 Accès refusé (${response.code}) sur $localName.\n\n" +
                            "→ Appuyez sur \"Ouvrir dans le navigateur\" pour télécharger manuellement.\n" +
                            "→ Ou générez un jeton gratuit sur huggingface.co/settings/tokens"
                        )
                    }
                    if (!response.isSuccessful) {
                        throw java.io.IOException("Échec sur $localName (${response.code}) : ${response.message}")
                    }
                    val body = response.body ?: throw java.io.IOException("Réponse vide du serveur pour $localName.")
                    val destFile = File(folder, localName)
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
                                    // Progression globale : fichier (index+1)/total, pondérée par
                                    // l'avancement du fichier en cours -- évite de rester bloqué à
                                    // 0% pendant tout le premier petit fichier de config.
                                    val fileFraction = downloaded.toDouble() / totalBytes.toDouble()
                                    val overall = ((index + fileFraction) / files.size * 100).toInt()
                                    if (overall != lastPercent) {
                                        lastPercent = overall
                                        onProgress(Progress.Percent(overall))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Prefs.saveLocalModelPath(context, folder.absolutePath)
            Prefs.saveLocalModelFormat(context, LocalLlmManager.LocalModelFormat.ONNX.name)
            LocalLlmManager.unload()
            onProgress(Progress.Done(folder))
        } catch (e: Exception) {
            onProgress(Progress.Error(e.message ?: "Erreur réseau pendant le téléchargement multi-fichiers."))
        }
    }
}
