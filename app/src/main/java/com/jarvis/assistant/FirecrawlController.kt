package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Firecrawl — extrait le contenu propre (Markdown) d'une page web réelle, y compris les pages
 * qui nécessitent du JavaScript (contrairement à un simple fetch HTML brut). Complémentaire de
 * web_search{query} : web_search trouve des liens, firecrawl_scrape{url} lit le CONTENU d'un
 * lien déjà connu (donné par l'utilisateur, ou trouvé via web_search). Clé API distincte,
 * jamais codée en dur — dépôt public, saisie uniquement par l'utilisateur dans ⚙ → Clés API.
 *
 * Doc officielle vérifiée : https://docs.firecrawl.dev/api-reference/endpoint/scrape — base
 * https://api.firecrawl.dev/v2, endpoint POST /scrape, Authorization: Bearer fc-xxx.
 */
object FirecrawlController {

    data class Result(val success: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // le rendu JS d'une page complexe peut prendre du temps
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Limite volontaire pour éviter de saturer le contexte de la conversation avec une très
    // longue page — largement suffisant pour que JARVIS résume ou cite l'essentiel.
    private const val MAX_CONTENT_CHARS = 8000

    suspend fun scrape(context: Context, url: String): Result = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext Result(false, "❌ Aucune URL fournie à lire.")
        val apiKey = Prefs.getFirecrawlApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result(
                false,
                "❌ Aucune clé API Firecrawl configurée. Ajoute-la dans ⚙ → Clés API (récupérable sur " +
                    "firecrawl.dev, préfixe \"fc-\")."
            )
        }

        try {
            val body = JSONObject()
                .put("url", url)
                .put("formats", org.json.JSONArray().put("markdown"))
                .toString()
                .toRequestBody(JSON)

            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/scrape")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result(false, "❌ Firecrawl : HTTP ${response.code} — ${bodyStr.take(300)}")
                }

                val json = JSONObject(bodyStr)
                if (!json.optBoolean("success", false)) {
                    return@withContext Result(false, "❌ Firecrawl : ${json.optString("error", "échec sans détail — $bodyStr").take(300)}")
                }

                val data = json.optJSONObject("data")
                val markdown = data?.optString("markdown")
                if (markdown.isNullOrBlank()) {
                    return@withContext Result(false, "❌ Firecrawl : page lue mais sans contenu markdown exploitable.")
                }

                val title = data.optJSONObject("metadata")?.optString("title")?.takeIf { it.isNotBlank() }
                val truncated = markdown.take(MAX_CONTENT_CHARS)
                val suffix = if (markdown.length > MAX_CONTENT_CHARS) "\n\n[…contenu tronqué, page plus longue]" else ""
                val header = if (title != null) "📄 **$title** ($url)\n\n" else "📄 $url\n\n"

                Result(true, "$header$truncated$suffix")
            }
        } catch (e: Exception) {
            Result(false, "❌ Firecrawl : exception réseau — ${e.message}")
        }
    }
}
