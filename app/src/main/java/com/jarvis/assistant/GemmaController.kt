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
 * Backend IA on-device : Gemma 3 1B via LiteRT-LM (API Kotlin officielle et activement
 * maintenue de Google AI Edge -- voir la note dans app/build.gradle sur pourquoi ce n'est
 * pas l'ancienne API MediaPipe "tasks-genai"). Contrairement à GeminiNanoController, ce
 * backend ne passe PAS par AICore : c'est un module distinct, à sélectionner explicitement
 * dans Réglages, avec son propre modèle à télécharger séparément.
 *
 * Le modèle (dépôt Hugging Face "litert-community/Gemma3-1B-IT") est soumis à la licence
 * Gemma : l'utilisateur doit avoir un compte Hugging Face, avoir accepté cette licence sur
 * la page du modèle, et avoir généré un jeton d'accès personnel (Réglages > Modèle IA).
 * Rien n'est jamais codé en dur ici -- le jeton est saisi et stocké localement par
 * l'utilisateur (voir Prefs.getHfToken).
 */
object GemmaController {

    private const val HF_MODEL_URL =
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"

    /** Taille indicative du modèle (~555 Mo), affichée à l'utilisateur avant tout téléchargement. */
    const val MODEL_SIZE_BYTES = 555L * 1024 * 1024

    private var engine: Engine? = null

    fun modelFile(context: Context): File = File(context.filesDir, "models/gemma3-1b-it-int4.litertlm")

    fun isDownloaded(context: Context): Boolean = modelFile(context).exists()

    /** Libère la mémoire du moteur en cours ET supprime le fichier modèle du stockage. */
    fun deleteModel(context: Context): Boolean {
        engine?.close()
        engine = null
        val file = modelFile(context)
        return if (file.exists()) file.delete() else true
    }

    /**
     * Télécharge le modèle depuis Hugging Face. Ne s'exécute que sur demande explicite de
     * l'utilisateur (bouton dédié dans Réglages, avec confirmation affichant la taille) --
     * jamais déclenché automatiquement en arrière-plan.
     */
    suspend fun download(context: Context, hfToken: String, onProgress: (downloaded: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val dest = modelFile(context)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            val connection = (URL(HF_MODEL_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $hfToken")
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw Exception(
                        "HTTP $code -- vérifie ton jeton Hugging Face dans Réglages, et que tu as bien " +
                            "accepté la licence Gemma sur huggingface.co/litert-community/Gemma3-1B-IT"
                    )
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

    private suspend fun ensureEngine(context: Context): Engine = withContext(Dispatchers.IO) {
        engine ?: run {
            // Backend CPU : le plus universellement compatible (GPU/NPU demandent des
            // bibliothèques natives supplémentaires selon l'appareil -- non activé pour
            // l'instant afin de garantir un fonctionnement partout).
            val config = EngineConfig(modelPath = modelFile(context).absolutePath, backend = Backend.CPU())
            Engine(config).also {
                it.initialize()
                engine = it
            }
        }
    }

    suspend fun generateReply(context: Context, prompt: String): String = withContext(Dispatchers.IO) {
        val eng = ensureEngine(context)
        eng.createConversation().use { conversation ->
            conversation.sendMessage(prompt).text?.takeIf { it.isNotBlank() }
                ?: "🤖 Gemma n'a renvoyé aucune réponse exploitable."
        }
    }
}
