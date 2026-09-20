package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Recherche web — priorité :
 * 1. Gemini avec Google Search grounding (réponse directe dans le chat / vocal)
 * 2. SerpAPI si clé configurée
 * 3. Ouverture navigateur en dernier recours uniquement
 *
 * Gemini Nano (AICore) ne peut PAS faire de recherche (hors-ligne).
 */
object WebSearchController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun search(context: Context, query: String): String {
        if (query.isBlank()) return "❌ Aucune requête de recherche fournie."

        // 1. Gemini grounding (meilleure qualité + réponse orale native)
        val geminiResult = tryGeminiGroundedSearch(context, query)
        if (geminiResult != null) return geminiResult

        // 2. SerpAPI
        val serpResult = tryFetchSerpResults(context, query)
        if (serpResult != null) return serpResult

        // 3. Dernier recours : navigateur (mais message clair)
        return openInBrowser(context, query)
    }

    /**
     * Utilise l'API Gemini avec l'outil google_search (grounding).
     * Le modèle décide de chercher et synthétise une réponse naturelle.
     */
    private fun tryGeminiGroundedSearch(context: Context, query: String): String? {
        val keys = Prefs.getApiKeysFor(context, Provider.GEMINI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val baseUrl = Provider.GEMINI.defaultBaseUrl
                val separator = if (baseUrl.contains("?")) "&" else "?"
                val url = "$baseUrl${separator}key=$apiKey"

                val contents = JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", query)))
                )

                val body = JSONObject()
                    .put("contents", contents)
                    .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "Tu es JARVIS. Réponds en français, de façon concise et naturelle (phrases courtes, pas de markdown). " +
                                        "Utilise la recherche Google pour donner une réponse factuelle à jour. " +
                                        "Cite brièvement les sources si utile, sans listes à puces."
                                )
                            )
                        )
                    )
                    .toString()
                    .toRequestBody(JSON)

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: return@use
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code == 401) {
                            Prefs.markKeyFailed(context, Provider.GEMINI, apiKey)
                        }
                        return@use
                    }
                    val json = JSONObject(bodyStr)
                    val candidates = json.optJSONArray("candidates") ?: return@use
                    if (candidates.length() == 0) return@use
                    val content = candidates.getJSONObject(0).optJSONObject("content") ?: return@use
                    val parts = content.optJSONArray("parts") ?: return@use
                    val text = parts.getJSONObject(0).optString("text", "").trim()
                    if (text.isNotBlank()) {
                        return "🔍 $text"
                    }
                }
            } catch (_: Exception) {
                // essaie la clé suivante
            }
        }
        return null
    }

    private fun tryFetchSerpResults(context: Context, query: String): String? {
        val keys = Prefs.getApiKeysFor(context, Provider.SERPAPI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val url = "https://serpapi.com/search?q=" +
                    java.net.URLEncoder.encode(query, "UTF-8") +
                    "&api_key=$apiKey&engine=google&hl=fr&gl=fr&num=5"
                val request = Request.Builder().url(url).get().build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code == 401) {
                            Prefs.markKeyFailed(context, Provider.SERPAPI, apiKey)
                        }
                        return@use
                    }
                    val bodyStr = response.body?.string() ?: return@use
                    val json = JSONObject(bodyStr)

                    val answerBox = json.optJSONObject("answer_box")
                    if (answerBox != null) {
                        val direct = answerBox.optString("answer", "").ifBlank {
                            answerBox.optString("snippet", "")
                        }
                        if (direct.isNotBlank()) return "🔍 $direct"
                    }

                    val localResults = json.optJSONArray("local_results")
                    if (localResults != null && localResults.length() > 0) {
                        val place = localResults.getJSONObject(0)
                        val sb = StringBuilder()
                        sb.append(place.optString("title", query)).append(" — ")
                        place.optJSONObject("hours")?.let { sb.append("horaires : $it. ") }
                        place.optString("address", "").let { if (it.isNotBlank()) sb.append("Adresse : $it. ") }
                        place.optString("type", "").let { if (it.isNotBlank()) sb.append("($it) ") }
                        return "🔍 ${sb}"
                    }

                    val organic = json.optJSONArray("organic_results")
                    if (organic != null && organic.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until minOf(3, organic.length())) {
                            val item = organic.getJSONObject(i)
                            sb.append(item.optString("title")).append(" : ")
                                .append(item.optString("snippet")).append("\n")
                        }
                        return "🔍 ${sb.toString().trim()}"
                    }
                }
            } catch (_: Exception) {
                // clé suivante
            }
        }
        return null
    }

    private fun openInBrowser(context: Context, query: String): String {
        return try {
            val searchUri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "🔍 J'ai ouvert Google pour « $query ». " +
                "Pour que je te réponde directement dans le chat (et à la voix), configure une clé Gemini " +
                "dans ⚙ → Clés API (recommandé) ou une clé SerpAPI."
        } catch (e: Exception) {
            "❌ Échec de la recherche : ${e.message}"
        }
    }
}
