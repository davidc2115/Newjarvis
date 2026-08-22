package com.jarvis.assistant

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Interface vers le moteur d'inférence natif llama.cpp (compilé depuis les
 * sources officielles via CMake/NDK — voir app/src/main/cpp/).
 * Permet de faire tourner de vrais modèles .gguf directement sur l'appareil,
 * sans aucune licence propriétaire ni jeton d'accès (Qwen, Llama, Mistral,
 * Phi... tout modèle GGUF standard est compatible).
 */
object NativeLlama {

    private var libraryLoaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("jarvis_llama")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun getLoadError(): String? = loadError

    external fun loadModel(modelPath: String): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun unload()

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "toujours extrêmement long voire ne répond
    // pas, toujours bloqué sur réfléchit" -- MÊME après le délai de 90s ajouté côté Kotlin dans
    // ApiClient.sendLocal via withTimeoutOrNull) : withTimeoutOrNull ne peut annuler qu'un point
    // de suspension coopératif (delay(), yield()...). Ici le code annulé était un appel JNI
    // BLOQUANT et SYNCHRONE (generate() ci-dessus) -- une fois entré dans le code natif, il n'y
    // a plus AUCUN point où la coroutine peut reprendre la main tant que l'appel natif n'est pas
    // terminé. withTimeoutOrNull attendait donc silencieusement la fin de l'appel natif, exact-
    // ement comme sans lui : le délai de 90s n'avait donc AUCUN EFFET RÉEL, ce qui explique que
    // le blocage persiste malgré son ajout. Un vrai timeout sur du code bloquant nécessite un
    // thread séparé qu'on peut abandonner : Future.get(timeout) rend la main au bout du délai
    // MÊME SI la tâche sous-jacente continue de tourner sur son thread (elle est simplement
    // abandonnée -- inoffensif ici car un seul appel natif à la fois, sérialisé par cet
    // executor mono-thread, qui protège aussi g_model/g_ctx côté C++ qui ne sont PAS thread-safe
    // : sans cette sérialisation, un nouvel essai pendant qu'un appel abandonné tourne encore
    // pourrait faire planter le moteur natif par accès concurrent).
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-llama-native").apply { isDaemon = true }
    }

    /** Comme loadModel(), mais avec un vrai timeout (voir commentaire ci-dessus). */
    fun loadModelSafe(modelPath: String, timeoutMs: Long = 60_000): Boolean {
        val future: Future<Boolean> = executor.submit(Callable { loadModel(modelPath) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Comme generate(), mais avec un vrai timeout (voir commentaire ci-dessus). Renvoie un
     *  message "[ERREUR] ..." si le délai est dépassé, jamais une exception. */
    fun generateSafe(prompt: String, maxTokens: Int, timeoutMs: Long = 90_000): String {
        val future: Future<String> = executor.submit(Callable { generate(prompt, maxTokens) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            "[ERREUR] Le modèle local met trop de temps à répondre (plus de ${timeoutMs / 1000}s)."
        } catch (e: Exception) {
            "[ERREUR] Le moteur local a rencontré un problème : ${e.message}"
        }
    }
}
