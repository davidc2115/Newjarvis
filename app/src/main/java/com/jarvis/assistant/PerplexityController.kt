package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Perplexity Sonar API — recherche web en temps réel augmentée par IA, avec citations. Utile
 * en complément de web_search{query} (qui renvoie des liens bruts à explorer) : ici la réponse
 * arrive déjà synthétisée en une réponse directe, sourcée.
 *
 * Réutilise la clé Perplexity DÉJÀ configurable dans ⚙ → Clés API (Provider.PERPLEXITY, déjà
 * utilisée comme fournisseur de secours du mode Automatique de chat) plutôt que d'en créer une
 * deuxième entrée séparée — l'utilisateur n'a besoin de la saisir qu'une seule fois, jamais
 * codée en dur (dépôt public).
 *
 * Doc officielle : https://docs.perplexity.ai/api-reference/chat-completions-post — endpoint
 * REST compatible OpenAI chat/completions, pas de protocole MCP à implémenter ici (contrairement
 * à Glif, dont l'ancienne API REST simple a été dépréciée) : Perplexity garde une API HTTP
 * classique stable, plus simple et plus robuste à intégrer directement.
 */
object PerplexityController {

    data class Result(val success: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // recherche web + synthèse peut prendre un peu de temps
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun search(context: Context, query: String): Result = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result(false, "❌ Aucune question fournie pour Perplexity.")
        val apiKey = Prefs.getApiKeysFor(context, Provider.PERPLEXITY).firstOrNull()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result(
                false,
                "❌ Aucune clé API Perplexity configurée. Ajoute-la dans ⚙ → Clés API → Perplexity AI " +
                    "(récupérable sur perplexity.ai/settings/api)."
            )
        }

        try {
            val body = JSONObject()
                .put("model", "sonar")
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", query)
                    )
                )
                .toString()
                .toRequestBody(JSON)

            val request = Request.Builder()
                .url("https://api.perplexity.ai/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result(false, "❌ Perplexity : HTTP ${response.code} — ${bodyStr.take(300)}")
                }

                val json = JSONObject(bodyStr)
                val answer = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")

                if (answer.isNullOrBlank()) {
                    return@withContext Result(false, "❌ Perplexity : réponse HTTP 200 sans contenu exploitable — ${bodyStr.take(300)}")
                }

                // Les citations (URLs sources) arrivent dans un champ "citations" séparé, hors du
                // message lui-même — on les rattache à la fin pour que JARVIS puisse les relayer
                // fidèlement plutôt que d'inventer des sources.
                val citations = json.optJSONArray("citations")
                val sourcesText = if (citations != null && citations.length() > 0) {
                    "\n\nSources :\n" + (0 until citations.length()).joinToString("\n") { i -> "• ${citations.optString(i)}" }
                } else ""

                Result(true, "$answer$sourcesText")
            }
        } catch (e: Exception) {
            Result(false, "❌ Perplexity : exception réseau — ${e.message}")
        }
    }
}
