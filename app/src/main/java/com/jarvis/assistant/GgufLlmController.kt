package com.jarvis.assistant

import android.content.Context
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Backend IA on-device via Llamatik (wrapper Kotlin/AAR pre-construit autour de llama.cpp, voir
 * https://github.com/ferranpons/llamatik) -- moteur GGUF natif, plus rapide et plus capable que
 * LocalLlmController (LiteRT-LM) pour une taille de modele equivalente, et seul moteur capable
 * d'utiliser un backend GPU (Vulkan) le jour ou l'AAR custom compilee avec -DGGML_VULKAN=ON
 * (voir .github/workflows/build-llamatik-vulkan.yml, greffe depuis Jarvis2) est integree ici.
 *
 * Greffe demandee explicitement par l'utilisateur : reprendre cette base (Newjarvis, toutes
 * fonctionnalites -- Home Assistant, box, fiches clients, Obsidian, etc.) et y installer le
 * moteur IA le plus recent developpe sur Jarvis2 (SmolVLM2 par defaut + catalogue de modeles
 * GGUF selectionnables), plutot que de repartir de zero sur une base plus pauvre en fonctions.
 *
 * Coexiste avec LocalLlmController (LiteRT-LM) plutot que de le remplacer : l'utilisateur garde
 * le choix, et rien de l'existant n'est casse si un modele GGUF echoue a se charger sur un
 * appareil donne.
 */
object GgufLlmController {

    data class GgufModel(
        val id: String,
        val displayName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val mmprojUrl: String? = null,
        val mmprojSizeBytes: Long = 0L,
        val license: String,
        val description: String,
    )

    /** Modele par defaut de Jarvis2 : rapide, multimodal (texte+image, meme si seul le texte
     *  est cable ici pour l'instant), depot public Apache-2.0 sans compte ni jeton. */
    val SMOLVLM2 = GgufModel(
        id = "smolvlm2-500m",
        displayName = "SmolVLM2 500M (rapide, recommandé)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        sizeBytes = 436_808_704L,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        mmprojSizeBytes = 108_785_184L,
        license = "Apache-2.0",
        description = "~440 Mo + ~110 Mo -- le plus rapide, idéal sur milieu de gamme.",
    )

    val QWEN_2_5_1_5B = GgufModel(
        id = "qwen2.5-1.5b",
        displayName = "Qwen 2.5 1.5B Instruct",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_117_320_736L,
        license = "Apache-2.0",
        description = "~1.1 Go -- plus capable, recommandé à partir de 4 Go de RAM.",
    )

    val PHI_3_5_MINI = GgufModel(
        id = "phi-3.5-mini",
        displayName = "Phi-3.5 mini Instruct",
        downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
        sizeBytes = 2_393_232_672L,
        license = "MIT",
        description = "~2.4 Go -- le plus capable du catalogue, recommandé à partir de 6 Go de RAM.",
    )

    val DOLPHIN_3_QWEN = GgufModel(
        id = "dolphin3-qwen2.5-1.5b",
        displayName = "Dolphin 3.0 (Qwen2.5 1.5B)",
        downloadUrl = "https://huggingface.co/bartowski/Dolphin3.0-Qwen2.5-1.5B-GGUF/resolve/main/Dolphin3.0-Qwen2.5-1.5B-Q4_K_M.gguf",
        sizeBytes = 986_051_648L,
        license = "Apache-2.0",
        description = "~990 Mo -- variante moins censurée, mêmes besoins que Qwen 2.5 1.5B.",
    )

    val AVAILABLE_MODELS = listOf(SMOLVLM2, QWEN_2_5_1_5B, PHI_3_5_MINI, DOLPHIN_3_QWEN)

    fun modelById(id: String?): GgufModel = AVAILABLE_MODELS.find { it.id == id } ?: SMOLVLM2

    // GPU_LAYERS reste a 0 tant que l'AAR Vulkan custom (voir workflow build-llamatik-vulkan.yml,
    // branche ci/vulkan-aar de Jarvis2) n'est pas verifiee stable et integree ici -- la
    // dependance Maven standard de Llamatik est CPU-only, un gpuLayers > 0 n'aurait aucun effet
    // avec elle et pourrait meme faire echouer le chargement selon les versions.
    private const val GPU_LAYERS = 0

    private var loadedModelId: String? = null
    private var ready = false

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models/gguf")

    fun modelFile(context: Context, model: GgufModel): File = File(modelsDir(context), "${model.id}.gguf")

    fun mmprojFile(context: Context, model: GgufModel): File = File(modelsDir(context), "${model.id}.mmproj.gguf")

    fun isDownloaded(context: Context, model: GgufModel): Boolean {
        val modelOk = modelFile(context, model).exists()
        return if (model.mmprojUrl != null) modelOk && mmprojFile(context, model).exists() else modelOk
    }

    /** Libere la memoire native du moteur en cours (seulement s'il correspond au modele
     *  supprime) ET supprime le(s) fichier(s) modele du stockage. */
    fun deleteModel(context: Context, model: GgufModel): Boolean {
        if (loadedModelId == model.id) {
            runCatching { LlamaBridge.shutdown() }
            loadedModelId = null
            ready = false
        }
        var ok = true
        val modelFile = modelFile(context, model)
        if (modelFile.exists() && !modelFile.delete()) ok = false
        val mmproj = mmprojFile(context, model)
        if (mmproj.exists() && !mmproj.delete()) ok = false
        return ok
    }

    /** Telecharge le(s) fichier(s) du modele -- depots PUBLICS, aucun jeton necessaire. Ne
     *  s'execute que sur demande explicite de l'utilisateur (bouton dedie dans Reglages). */
    suspend fun download(context: Context, model: GgufModel, onProgress: (downloaded: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            downloadOne(model.downloadUrl, modelFile(context, model), model.sizeBytes, onProgress)
            val mmprojUrl = model.mmprojUrl
            if (mmprojUrl != null) {
                downloadOne(mmprojUrl, mmprojFile(context, model), model.mmprojSizeBytes, onProgress)
            }
        }
    }

    private fun downloadOne(url: String, dest: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        if (dest.exists() && (expectedSize <= 0L || dest.length() == expectedSize)) return
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} -- échec du téléchargement de ${dest.name}.")
            }
            val body = response.body ?: throw Exception("Réponse vide pour $url")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            var done = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) throw Exception("Impossible de finaliser le fichier téléchargé (${dest.name})")
    }

    /** Meme logique que DeviceCapabilities.recommendedInferenceThreads() dans Jarvis2 : garde
     *  2 coeurs libres pour l'UI/le systeme plutot que de saturer tous les coeurs disponibles. */
    private fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 6)
    }

    private suspend fun ensureLoaded(context: Context, model: GgufModel) = withContext(Dispatchers.IO) {
        if (ready && loadedModelId == model.id) return@withContext
        if (loadedModelId != null) runCatching { LlamaBridge.shutdown() }
        ready = false
        loadedModelId = null

        // Reglages anti-repetition alignes sur SmolVlmEngine/SelectableLlmEngine de Jarvis2 :
        // un petit modele quantifie part plus facilement en boucle de repetition sans ca.
        LlamaBridge.updateGenerateParams(
            temperature = 0.6f,
            maxTokens = 400,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.3f,
            contextLength = 4096,
            numThreads = recommendedThreads(),
            useMmap = true,
            flashAttention = true,
            batchSize = 512,
            gpuLayers = GPU_LAYERS,
        )

        val loaded = LlamaBridge.initGenerateModel(modelFile(context, model).absolutePath)
        if (!loaded) {
            throw IllegalStateException("Échec du chargement de ${model.displayName}")
        }
        ready = true
        loadedModelId = model.id
    }

    /** [prompt] est deja le texte complet (systeme + historique + tour utilisateur), construit
     *  en amont par ApiClient.buildPromptFromHistory -- meme contrat que LocalLlmController. */
    suspend fun generateReply(context: Context, model: GgufModel, prompt: String): String = withContext(Dispatchers.IO) {
        ensureLoaded(context, model)
        val text = LlamaBridge.generate(prompt)
        if (text.isBlank()) "🤖 ${model.displayName} n'a renvoyé aucune réponse exploitable." else text
    }
}
