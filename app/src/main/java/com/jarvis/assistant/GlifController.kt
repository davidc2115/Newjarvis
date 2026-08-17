package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Glif — agent IA de composition média (image/vidéo/audio) sur glif.app, piloté via son serveur
 * MCP hébergé, JSON-RPC 2.0 plutôt qu'une API REST classique.
 *
 * Signalement utilisateur "serveur MCP injoignable" (2026-08-17) diagnostiqué via la doc
 * officielle actuelle (glif.app/mcp + glif.app/llms.txt, fetchées directement) : DEUX choses
 * avaient changé depuis l'implémentation initiale de ce contrôleur, même schéma que la
 * dépréciation Groq/le champ aspectRatio Gemini plus tôt dans cette session (un détail d'API
 * correct au moment de l'écriture a évolué depuis, silencieusement) :
 *   1. Endpoint déplacé de https://glif.app/mcp (page marketing HTML depuis, plus un endpoint
 *      JSON-RPC — d'où "injoignable", le POST atterrissait sur une page web) vers
 *      https://glif.app/api/mcp.
 *   2. Le modèle "workflow" (glifs communautaires identifiés par ID, outils search_workflows/
 *      run_workflow) a été entièrement remplacé par un modèle "projet" en langage naturel :
 *      compose_project{prompt, project_id?} démarre/continue un projet et renvoie un job_id à
 *      suivre via get_job_status{job_id} jusqu'à status "completed"/"failed" — il n'existe plus
 *      de recherche de glif communautaire par nom (list_projects ne liste que les PROPRES
 *      projets de l'utilisateur), donc search_workflows n'a plus d'équivalent direct et a été
 *      retiré plutôt que de laisser une action qui échouerait silencieusement.
 * Auth Bearer (glif_v1_...) toujours valide pour les clients HTTP directs (non-Claude) d'après
 * la doc — seul le flux "Claude connecteur" bascule sur OAuth, non concerné ici.
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

    private const val MCP_URL = "https://glif.app/api/mcp"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // un workflow (image/vidéo enchaînée) peut être long
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Une génération dure "souvent 1 à 5 minutes" d'après la doc — 30 tentatives à 10s
    // couvrent donc le cas normal avec une marge confortable sans attendre indéfiniment.
    private const val JOB_POLL_MAX_ATTEMPTS = 30
    private const val JOB_POLL_DELAY_MS = 10_000L

    /**
     * Démarre (ou continue si [continueProjectId] est fourni) un projet Glif à partir d'une
     * description en langage naturel, puis attend la fin de la génération en sondant
     * get_job_status. Remplace l'ancien run_workflow{workflowId,input} — il n'y a plus d'ID de
     * workflow à connaître, seulement une description ; voir la doc en tête de fichier.
     */
    suspend fun composeProject(context: Context, prompt: String, continueProjectId: String? = null): Result = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) return@withContext Result(false, "❌ Décris ce que tu veux que Glif génère (image/vidéo/audio).")

        val startArgs = JSONObject().put("prompt", prompt)
        if (!continueProjectId.isNullOrBlank()) startArgs.put("project_id", continueProjectId)
        val started = callTool(context, "compose_project", startArgs)
        if (!started.success) return@withContext started

        val jobId = Regex("\"job_id\"\\s*:\\s*\"([^\"]+)\"").find(started.message)?.groupValues?.get(1)
        if (jobId.isNullOrBlank()) {
            // Réponse reçue mais sans job_id exploitable (schéma inattendu) : on relaie quand
            // même le texte brut plutôt que d'échouer silencieusement — l'utilisateur/JARVIS
            // pourront au moins voir ce que Glif a réellement répondu.
            return@withContext started
        }

        var attempts = 0
        while (attempts < JOB_POLL_MAX_ATTEMPTS) {
            delay(JOB_POLL_DELAY_MS)
            attempts++
            val statusResult = callTool(context, "get_job_status", JSONObject().put("job_id", jobId))
            if (!statusResult.success) return@withContext statusResult
            val status = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(statusResult.message)?.groupValues?.get(1)
            when (status) {
                "completed" -> return@withContext statusResult
                "failed" -> return@withContext Result(false, "❌ Glif : génération échouée — ${statusResult.message}")
                // "pending"/"running"/statut inconnu/absent : on continue de sonder.
            }
        }
        Result(
            false,
            "⏳ Glif : toujours en cours après ${JOB_POLL_MAX_ATTEMPTS * JOB_POLL_DELAY_MS / 1000}s — " +
                "le projet continue en arrière-plan côté Glif, consultable sur glif.app (job $jobId)."
        )
    }

    /**
     * Vérifie qu'un jeton Glif est valide SANS appeler d'outil (donc sans consommer de crédit
     * de workflow) : ne fait que la poignée de main "initialize" du protocole MCP, qui échoue
     * déjà avec une erreur d'authentification si le jeton est invalide/révoqué. Utilisé par
     * ApiKeyTestController pour couvrir Glif dans le test global des clés API.
     */
    suspend fun testConnection(context: Context): Result = withContext(Dispatchers.IO) {
        val token = Prefs.getGlifApiToken(context)
        if (token.isBlank()) return@withContext Result(false, "❌ Aucun jeton API Glif configuré.")
        try {
            val initResp = postJsonRpc(
                token, id = 1, method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "JARVIS Android").put("version", "1.0"))
            ) ?: return@withContext Result(false, "❌ Glif : serveur MCP injoignable (glif.app/mcp).")

            val error = initResp.body.optJSONObject("error")
            if (error != null) {
                return@withContext Result(false, "❌ jeton invalide ou refusé — ${error.optString("message", "sans détail")}")
            }
            Result(true, "jeton valide")
        } catch (e: Exception) {
            Result(false, "❌ Glif : exception réseau — ${e.message}")
        }
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
