package com.jarvis.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backend IA on-device via LiteRT-LM (API Kotlin officielle et activement maintenue de Google
 * AI Edge) -- distinct de GeminiNanoController (AICore, réservé aux Pixel 8+/Galaxy S24, voir
 * sa doc) : ce module fonctionne sur N'IMPORTE QUEL appareil Android suffisamment puissant
 * (dont Xiaomi/Redmi/Poco, qui n'ont PAS AICore), au prix d'un téléchargement de modèle
 * explicite dans Réglages.
 *
 * Remplace l'ancien GemmaController (Gemma 3 1B, licence Google -- nécessitait un compte
 * Hugging Face, l'acceptation de la licence Gemma sur la page du modèle, ET un jeton d'accès
 * personnel) par un REGISTRE de modèles Qwen (licence Apache 2.0, dépôts PUBLICS sur
 * huggingface.co/litert-community, aucun compte/jeton requis) -- demande explicite de
 * l'utilisateur : "remplacer Gemma par une version sans Hugging Face". Vérifié le 25/08/2026 en
 * inspectant directement les pages des dépôts (aucune bannière de consentement, liens de
 * téléchargement directement accessibles -- contrairement aux dépôts Gemma, gatés par la
 * licence Google quel que soit l'organisme qui les héberge, donc un changement de FAMILLE de
 * modèle était nécessaire, pas juste un changement de dépôt).
 *
 * Plusieurs modèles au choix (voir AVAILABLE_MODELS) car les Xiaomi/Redmi/Poco couvrent une
 * très large gamme de RAM/puissance : Qwen3 0.6B (~500 Mo) convient au milieu de gamme,
 * Qwen2.5 1.5B (~1.6 Go) est plus capable pour les modèles avec plus de RAM. Les deux tournent
 * en CPU (Backend.CPU(), comme l'ancien GemmaController) -- le plus universellement compatible,
 * le GPU/NPU nécessitant des bibliothèques natives supplémentaires selon l'appareil.
 */
object LocalLlmController {

    data class LocalModel(
        val id: String,
        val displayName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val description: String
    )

    /** Le plus petit/rapide -- recommandé par défaut, notamment sur le milieu de gamme
     *  (la plupart des Xiaomi/Redmi/Poco) : meilleur rapport vitesse/RAM que l'ancien Gemma 3
     *  1B, et surtout aucun compte requis pour le télécharger. */
    val QWEN3_0_6B = LocalModel(
        id = "qwen3_0_6b",
        displayName = "Qwen3 0.6B (rapide, recommandé)",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
        sizeBytes = 498L * 1024 * 1024,
        description = "~500 Mo -- le plus rapide, idéal sur milieu de gamme (Xiaomi/Redmi/Poco inclus)."
    )

    /** Plus gros/plus capable -- pour les appareils avec plus de RAM disponible (Xiaomi haut de
     *  gamme notamment). */
    val QWEN2_5_1_5B = LocalModel(
        id = "qwen2_5_1_5b",
        displayName = "Qwen2.5 1.5B (plus capable)",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeBytes = 1600L * 1024 * 1024,
        description = "~1.6 Go -- réponses plus fines, recommandé si le téléphone a 6 Go de RAM ou plus."
    )

    val AVAILABLE_MODELS = listOf(QWEN3_0_6B, QWEN2_5_1_5B)

    fun modelById(id: String?): LocalModel = AVAILABLE_MODELS.find { it.id == id } ?: QWEN3_0_6B

    private var engine: Engine? = null
    private var engineModelId: String? = null

    fun modelFile(context: Context, model: LocalModel): File =
        File(context.filesDir, "models/${model.id}.litertlm")

    fun isDownloaded(context: Context, model: LocalModel): Boolean = modelFile(context, model).exists()

    /** Libère la mémoire du moteur en cours (seulement s'il correspond au modèle supprimé) ET
     *  supprime le fichier modèle du stockage. */
    fun deleteModel(context: Context, model: LocalModel): Boolean {
        if (engineModelId == model.id) {
            engine?.close()
            engine = null
            engineModelId = null
        }
        val file = modelFile(context, model)
        return if (file.exists()) file.delete() else true
    }

    /**
     * Télécharge le modèle -- dépôt PUBLIC, aucun jeton nécessaire (voir doc d'en-tête). Ne
     * s'exécute que sur demande explicite de l'utilisateur (bouton dédié dans Réglages, avec
     * confirmation affichant la taille), jamais déclenché automatiquement en arrière-plan.
     */
    suspend fun download(context: Context, model: LocalModel, onProgress: (downloaded: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val dest = modelFile(context, model)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw Exception("HTTP $code -- échec du téléchargement de ${model.displayName}.")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (!tmp.renameTo(dest)) throw Exception("Impossible de finaliser le fichier téléchargé")
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun ensureEngine(context: Context, model: LocalModel): Engine = withContext(Dispatchers.IO) {
        val current = engine
        if (current != null && engineModelId == model.id) return@withContext current
        current?.close()
        val config = EngineConfig(
            modelPath = modelFile(context, model).absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.path
        )
        Engine(config).also {
            it.initialize()
            engine = it
            engineModelId = model.id
        }
    }

    suspend fun generateReply(context: Context, model: LocalModel, prompt: String): String = withContext(Dispatchers.IO) {
        val eng = ensureEngine(context, model)
        eng.createConversation().use { conversation ->
            // Message n'expose pas de propriété .text -- le texte de la réponse se récupère
            // via toString() (délègue à Contents.toString(), qui concatène les Content.Text).
            val text = conversation.sendMessage(prompt).toString()
            if (text.isBlank()) "🤖 ${model.displayName} n'a renvoyé aucune réponse exploitable." else text
        }
    }
}
