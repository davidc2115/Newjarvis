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
 * Glif — exécute des workflows IA (« glifs ») créés sur glif.app (chaînes de modèles
 * texte/image/audio/vidéo partagées par la communauté). Contrairement à Perplexity/Firecrawl,
 * Glif n'expose PLUS d'API REST simple : leur ancienne API (simple-api.glif.app) a été
 * dépréciée le 2026-05-20 lors du passage à "Glif 2.0" — le seul point d'entrée programmatique
 * restant est leur serveur MCP hébergé (https://glif.app/mcp), qui parle JSON-RPC 2.0 plutôt
 * qu'une API REST classique. Ce contrôleur implémente donc un client MCP minimal, dédié à ce
 * seul serveur (pas un système générique multi-serveurs MCP — choix explicite de l'utilisateur),
 * limité aux 2 outils utiles côté JARVIS : search_workflows et run_workflow (voir
 * github.com/glifxyz/glif-mcp-server pour la liste complète des noms d'outils exposés par ce
 * serveur, réutilisés tels quels ici puisque le serveur hébergé expose la même interface que la
 * version locale historique du serveur).
 *
 * Transport HTTP "Streamable" du protocole MCP (spec 2025-06-18) : chaque requête JSON-RPC est
 * un POST simple ; le serveur peut répondre soit en JSON direct (Content-Type: application/json)
 * soit en flux SSE à un seul événement (Content-Type: text/event-stream) — les deux formes sont
 * gérées ci-dessous. Pas de session persistante entre deux appels JARVIS différents : chaque
 * fonction publique refait sa propre poignée de main initialize → notifications/initialized →
 * tools/call, plus simple et plus robuste qu'un cache de session qui pourrait expirer.
 */
object GlifController {

    data class Result(val success: Boolean, val message: String)

    private const val MCP_URL = "https://glif.app/mcp"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // un workflow (image/vidéo enchaînée) peut être long
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun runWorkflow(context: Context, workflowId: String, input: String): Result = withContext(Dispatchers.IO) {
        if (workflowId.isBlank()) return@withContext Result(false, "❌ Aucun identifiant de workflow Glif fourni.")
        callTool(
            context, "run_workflow",
            JSONObject().put("id", workflowId).put("inputs", JSONArray().put(input))
        )
    }

    suspend fun searchWorkflows(context: Context, query: String): Result = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result(false, "❌ Aucun terme de recherche fourni pour chercher un glif.")
        callTool(context, "search_workflows", JSONObject().put("query", query))
    }

    /** Poignée de main + appel d'outil MCP, factorisée pour run_workflow et search_workflows. */
    private fun callTool(context: Context, toolName: String, arguments: JSONObject): Result {
        val token = Prefs.getGlifApiToken(context)
        if (token.isBlank()) {
            return Result(
                false,
                "❌ Aucun jeton API Glif configuré. Ajoute-le dans ⚙ → Clés API (récupérable sur " +
                    "glif.app/settings/api-tokens)."
            )
        }

        try {
            // 1) initialize — obligatoire avant tout appel d'outil MCP, annonce la version de
            // protocole supportée et récupère un éventuel identifiant de session côté serveur.
            val initResp = postJsonRpc(
                token, id = 1, method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "JARVIS Android").put("version", "1.0"))
            ) ?: return Result(false, "❌ Glif : échec de connexion au serveur MCP (glif.app/mcp injoignable).")

            if (initResp.body.optJSONObject("error") != null) {
                return Result(false, "❌ Glif : initialisation refusée — ${initResp.body.optJSONObject("error")?.optString("message")}")
            }
            val sessionId = initResp.sessionId

            // 2) notifications/initialized — accusé de réception requis par le protocole MCP
            // avant d'appeler un outil (pas de réponse attendue, c'est une notification).
            postJsonRpc(token, id = null, method = "notifications/initialized", params = JSONObject(), sessionId = sessionId)

            // 3) tools/call — l'appel réel de l'outil demandé.
            val callResp = postJsonRpc(
                token, id = 2, method = "tools/call",
                params = JSONObject().put("name", toolName).put("arguments", arguments),
                sessionId = sessionId
            ) ?: return Result(false, "❌ Glif : le serveur MCP n'a pas répondu à l'appel de l'outil « $toolName ».")

            val error = callResp.body.optJSONObject("error")
            if (error != null) {
                return Result(false, "❌ Glif : ${error.optString("message", "erreur MCP sans détail")}")
            }

            val result = callResp.body.optJSONObject("result")
            val isToolError = result?.optBoolean("isError", false) == true
            val contentArr = result?.optJSONArray("content")
            val text = if (contentArr != null) {
                (0 until contentArr.length())
                    .mapNotNull { i -> contentArr.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }
                    .joinToString("\n")
            } else null

            if (text.isNullOrBlank()) {
                return Result(false, "❌ Glif : réponse reçue mais sans contenu exploitable (${callResp.body.toString().take(300)}).")
            }
            return Result(!isToolError, if (isToolError) "❌ Glif (« $toolName ») : $text" else text)
        } catch (e: Exception) {
            return Result(false, "❌ Glif : exception réseau — ${e.message}")
        }
    }

    private data class RpcResponse(val body: JSONObject, val sessionId: String?)

    /**
     * POST JSON-RPC brut vers le serveur MCP Glif. [id] null = notification (pas de réponse
     * attendue, on ignore le corps). Gère les 2 formes de réponse autorisées par le transport
     * HTTP "Streamable" du protocole MCP : JSON direct, ou un flux SSE à un seul événement
     * "data: {...}" (le cas le plus courant pour un appel d'outil simple non-streamé).
     */
    private fun postJsonRpc(
        token: String, id: Int?, method: String, params: JSONObject, sessionId: String? = null
    ): RpcResponse? {
        val rpcBody = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (id != null) rpcBody.put("id", id)

        val requestBuilder = Request.Builder()
            .url(MCP_URL)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(rpcBody.toString().toRequestBody(JSON))
        sessionId?.let { requestBuilder.addHeader("Mcp-Session-Id", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val respSessionId = response.header("Mcp-Session-Id")
            if (id == null) return RpcResponse(JSONObject(), respSessionId) // notification : pas de corps à lire
            if (!response.isSuccessful) return null

            val bodyStr = response.body?.string() ?: return null
            val contentType = response.header("Content-Type") ?: ""

            val jsonText = if (contentType.contains("text/event-stream")) {
                // Flux SSE : on prend la dernière ligne "data: {...}" (le message JSON-RPC final).
                bodyStr.lines()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.isNotBlank() }
            } else bodyStr

            if (jsonText.isNullOrBlank()) return null
            return try {
                RpcResponse(JSONObject(jsonText), respSessionId)
            } catch (e: Exception) {
                null
            }
        }
    }
}
