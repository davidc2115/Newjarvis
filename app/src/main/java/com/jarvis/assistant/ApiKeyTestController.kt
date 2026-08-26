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
 * ApiKeyTestController — teste EN VRAI chaque clé API configurée (un appel HTTP minimal
 * et bon marché par clé, pas juste une vérification de format) et rapporte le résultat
 * réel : valide, invalide/révoquée, ou en échec pour une autre raison (HTTP + détail).
 *
 * Créé en réponse au symptôme "la génération d'image échoue sans arrêt" : plutôt que
 * de deviner, ceci permet de vérifier directement si le problème vient d'une clé
 * invalide/expirée/mal collée — cause la plus fréquente d'un échec systématique malgré
 * un fournisseur "configuré", et jusqu'ici invisible sans regarder les logs Android.
 */
object ApiKeyTestController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private data class KeyResult(val label: String, val maskedKey: String, val ok: Boolean, val detail: String)

    suspend fun testAllConfiguredKeys(context: Context): String = withContext(Dispatchers.IO) {
        val results = mutableListOf<KeyResult>()

        for (provider in Provider.CLOUD_KEY_PROVIDERS) {
            Prefs.getApiKeysFor(context, provider).forEach { key ->
                results.add(testProviderKey(provider, key))
            }
        }

        val hfToken = Prefs.getHfToken(context)
        if (hfToken.isNotBlank()) results.add(testHuggingFace(hfToken))

        val replicateToken = Prefs.getReplicateToken(context)
        if (replicateToken.isNotBlank()) results.add(testReplicate(replicateToken))

        val firecrawlKey = Prefs.getFirecrawlApiKey(context)
        if (firecrawlKey.isNotBlank()) results.add(testFirecrawl(firecrawlKey))

        val glifToken = Prefs.getGlifApiToken(context)
        if (glifToken.isNotBlank()) results.add(testGlif(context, glifToken))

        if (results.isEmpty()) {
            return@withContext "❌ Aucune clé API configurée à tester. Configure-en au moins une dans ⚙ → Clés API, ou un jeton Hugging Face/Replicate dans 🎨 Génération."
        }

        val sb = StringBuilder("🔑 **Test des clés API configurées** :\n\n")
        results.forEach { r ->
            val icon = if (r.ok) "✅" else "❌"
            sb.append("$icon ${r.label} (…${r.maskedKey}) — ${r.detail}\n")
        }
        val failedCount = results.count { !it.ok }
        sb.append(
            if (failedCount > 0) {
                "\n⚠️ $failedCount clé(s) invalide(s) ou en échec — remplace-les dans ⚙ → Clés API (ou le champ concerné dans 🎨 Génération). " +
                    "C'est la cause la plus probable si une génération échoue systématiquement sans raison claire."
            } else {
                "\n✅ Toutes les clés testées répondent correctement — si une génération échoue quand même, la cause est ailleurs (quota épuisé, modèle indisponible, contenu refusé par le fournisseur...)."
            }
        )
        sb.toString()
    }

    private fun mask(key: String): String = if (key.length <= 4) key else key.takeLast(4)

    private fun testProviderKey(provider: Provider, key: String): KeyResult {
        return try {
            val request = when (provider) {
                Provider.CLAUDE -> {
                    val body = JSONObject()
                        .put("model", provider.defaultModel)
                        .put("max_tokens", 1)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                        .toString().toRequestBody(JSON)
                    Request.Builder()
                        .url(provider.defaultBaseUrl)
                        .addHeader("x-api-key", key)
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("Content-Type", "application/json")
                        .post(body).build()
                }
                Provider.GEMINI -> Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
                    .get().build()
                Provider.SERPAPI -> Request.Builder()
                    .url("https://serpapi.com/account.json?api_key=$key")
                    .get().build()
                else -> {
                    // Fournisseurs compatibles OpenAI : Groq, OpenAI, Mistral, DeepSeek, Perplexity, Together, OpenRouter
                    val body = JSONObject()
                        .put("model", provider.defaultModel)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                        .put("max_tokens", 1)
                        .toString().toRequestBody(JSON)
                    Request.Builder()
                        .url(provider.defaultBaseUrl)
                        .addHeader("Authorization", "Bearer $key")
                        .addHeader("Content-Type", "application/json")
                        .post(body).build()
                }
            }
            respondResult(provider.displayName, key, request)
        } catch (e: Exception) {
            KeyResult(provider.displayName, mask(key), false, "Erreur réseau : ${e.message}")
        }
    }

    private fun testHuggingFace(token: String): KeyResult {
        val label = "Hugging Face (génération d'image, moteur de secours)"
        return try {
            val request = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .addHeader("Authorization", "Bearer $token")
                .get().build()
            respondResult(label, token, request)
        } catch (e: Exception) {
            KeyResult(label, mask(token), false, "Erreur réseau : ${e.message}")
        }
    }

    private fun testReplicate(token: String): KeyResult {
        val label = "Replicate (génération vidéo)"
        return try {
            val request = Request.Builder()
                .url("https://api.replicate.com/v1/account")
                .addHeader("Authorization", "Bearer $token")
                .get().build()
            respondResult(label, token, request)
        } catch (e: Exception) {
            KeyResult(label, mask(token), false, "Erreur réseau : ${e.message}")
        }
    }

    private fun testFirecrawl(apiKey: String): KeyResult {
        val label = "Firecrawl (extraction de pages web)"
        return try {
            // /team/credit-usage : endpoint officiel dédié à la vérification de compte, ne
            // consomme aucun crédit de scraping (contrairement à un vrai appel /scrape).
            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/team/credit-usage")
                .addHeader("Authorization", "Bearer $apiKey")
                .get().build()
            respondResult(label, apiKey, request)
        } catch (e: Exception) {
            KeyResult(label, mask(apiKey), false, "Erreur réseau : ${e.message}")
        }
    }

    private suspend fun testGlif(context: Context, token: String): KeyResult {
        val label = "Glif (workflows IA)"
        val result = GlifController.testConnection(context)
        return KeyResult(label, mask(token), result.success, result.message)
    }

    private fun respondResult(label: String, key: String, request: Request): KeyResult {
        val response = client.newCall(request).execute()
        return response.use {
            val ok = it.isSuccessful
            val detail = if (ok) {
                "clé valide"
            } else {
                val bodyPreview = it.body?.string()?.take(150) ?: ""
                when (it.code) {
                    401, 403 -> "clé invalide ou révoquée (HTTP ${it.code})"
                    429 -> "clé valide mais quota/débit dépassé (HTTP 429)"
                    else -> "HTTP ${it.code} — $bodyPreview"
                }
            }
            KeyResult(label, mask(key), ok, detail)
        }
    }
}
