package com.jarvis.assistant

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FreeboxController — contrôle du serveur Freebox (routeur/NAS) via l'API
 * officielle Freebox OS. Endpoints vérifiés sur la documentation officielle
 * https://dev.freebox.fr/sdk/os/ (fs/, login/, wifi/config/).
 *
 * Contrairement au Wi-Fi/Bluetooth du TÉLÉPHONE (bloqués par Android), le
 * Wi-Fi de la Freebox est un simple appel HTTP vers le routeur : 100%
 * pilotable par commande, sans restriction ni tap requis.
 *
 * Appairage obligatoire la première fois : JARVIS envoie une demande, puis
 * il faut valider sur l'écran de la Freebox (flèche droite) ou dans l'app
 * Freebox — jusqu'à ce que ce soit fait, aucun appel authentifié ne
 * fonctionnera. Le jeton obtenu (app_token) est ensuite stocké et réutilisé
 * indéfiniment (jusqu'à révocation manuelle dans mafreebox.freebox.fr).
 */
object FreeboxController {

    data class ActionResult(val success: Boolean, val message: String)
    data class FbxFile(val path: String, val name: String, val isDir: Boolean, val size: Long)

    private const val APP_ID = "fr.jarvis.assistant.android"
    private const val APP_NAME = "JARVIS Assistant Android"
    private const val APP_VERSION = "3.0.0"
    private const val DEVICE_NAME = "JARVIS Android"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var cachedApiBaseUrl: String? = null
    private var sessionToken: String? = null

    fun isConfigured(context: Context): Boolean = Prefs.getFreeboxAppToken(context).isNotBlank()

    private fun host(context: Context): String = Prefs.getFreeboxHost(context)

    // ─── Découverte de l'API (version dynamique selon le modèle de Freebox) ───

    private fun discoverApiBase(context: Context): String {
        cachedApiBaseUrl?.let { return it }
        return try {
            val request = Request.Builder().url("${host(context)}/api_version").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return "/api/v8/"
                val json = JSONObject(body)
                val major = json.optString("api_version", "8.0").substringBefore(".")
                val base = json.optString("api_base_url", "/api/").trim('/')
                val result = "/$base/v$major/"
                cachedApiBaseUrl = result
                result
            }
        } catch (e: Exception) {
            "/api/v8/"
        }
    }

    // ─── Appairage (une seule fois par installation) ───────────────────────────

    /**
     * Lance l'appairage. [onStatus] est appelé pour informer l'utilisateur en
     * temps réel (ex: "va valider sur l'écran de la Freebox"). Bloque jusqu'à
     * validation/refus/timeout (jusqu'à [timeoutSeconds]).
     */
    suspend fun pairApp(context: Context, timeoutSeconds: Int = 120, onStatus: (String) -> Unit = {}): ActionResult {
        val base = discoverApiBase(context)
        return try {
            val payload = JSONObject()
                .put("app_id", APP_ID)
                .put("app_name", APP_NAME)
                .put("app_version", APP_VERSION)
                .put("device_name", DEVICE_NAME)
                .toString()
                .toRequestBody(JSON)

            val request = Request.Builder().url("${host(context)}${base}login/authorize/").post(payload).build()
            val (appToken, trackId) = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return ActionResult(false, "❌ Pas de réponse de la Freebox.")
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) {
                    return ActionResult(false, "❌ Échec de la demande d'appairage : ${json.optString("msg", "erreur inconnue")}")
                }
                val result = json.getJSONObject("result")
                result.getString("app_token") to result.getString("track_id")
            }

            onStatus("👉 Valide la demande sur l'écran de ta Freebox (flèche droite), ou dans l'appli Freebox.")

            val startTime = System.currentTimeMillis()
            val trackUrl = "${host(context)}${base}login/authorize/$trackId"
            while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
                delay(2000)
                val statusRequest = Request.Builder().url(trackUrl).build()
                val status = client.newCall(statusRequest).execute().use { response ->
                    val body = response.body?.string() ?: return@use null
                    JSONObject(body).optJSONObject("result")?.optString("status")
                }
                when (status) {
                    "granted" -> {
                        Prefs.saveFreeboxAppToken(context, appToken)
                        return ActionResult(true, "✅ Freebox appairée avec succès !")
                    }
                    "denied" -> return ActionResult(false, "❌ Demande refusée sur l'écran de la Freebox.")
                    "timeout" -> return ActionResult(false, "❌ Temps écoulé sans validation sur la Freebox.")
                }
            }
            ActionResult(false, "❌ Délai d'attente dépassé (${timeoutSeconds}s) sans validation.")
        } catch (e: Exception) {
            ActionResult(false, "❌ Erreur réseau lors de l'appairage : ${e.message}")
        }
    }

    // ─── Session (challenge HMAC-SHA1, ré-ouverte automatiquement si besoin) ──

    private fun openSession(context: Context): Boolean {
        val appToken = Prefs.getFreeboxAppToken(context)
        if (appToken.isBlank()) return false
        val base = discoverApiBase(context)

        return try {
            val challengeRequest = Request.Builder().url("${host(context)}${base}login/").build()
            val challenge = client.newCall(challengeRequest).execute().use { response ->
                val body = response.body?.string() ?: return false
                JSONObject(body).optJSONObject("result")?.optString("challenge") ?: return false
            }

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(appToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val password = mac.doFinal(challenge.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

            val sessionPayload = JSONObject().put("app_id", APP_ID).put("password", password).toString().toRequestBody(JSON)
            val sessionRequest = Request.Builder().url("${host(context)}${base}login/session/").post(sessionPayload).build()
            client.newCall(sessionRequest).execute().use { response ->
                val body = response.body?.string() ?: return false
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return false
                sessionToken = json.getJSONObject("result").getString("session_token")
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Requête authentifiée générique, avec réouverture automatique de session si le jeton a expiré. */
    private fun apiRequest(context: Context, method: String, endpoint: String, payload: JSONObject? = null): JSONObject {
        if (!isConfigured(context)) {
            return JSONObject().put("success", false).put("msg", "Freebox non appairée. Va dans 🏠 → Freebox pour l'appairer.")
        }
        if (sessionToken == null && !openSession(context)) {
            return JSONObject().put("success", false).put("msg", "Impossible d'ouvrir une session avec la Freebox.")
        }

        fun doRequest(): JSONObject {
            val base = discoverApiBase(context)
            val url = "${host(context)}$base${endpoint.trimStart('/')}"
            val builder = Request.Builder().url(url).addHeader("X-Freebox-OS-Token", sessionToken ?: "")
            val body = payload?.toString()?.toRequestBody(JSON)
            when (method.uppercase()) {
                "GET" -> builder.get()
                "POST" -> builder.post(body ?: JSONObject().toString().toRequestBody(JSON))
                "PUT" -> builder.put(body ?: JSONObject().toString().toRequestBody(JSON))
                "DELETE" -> builder.delete()
            }
            return client.newCall(builder.build()).execute().use { response ->
                val respBody = response.body?.string() ?: return@use JSONObject().put("success", false).put("msg", "Réponse vide.")
                try { JSONObject(respBody) } catch (e: Exception) { JSONObject().put("success", false).put("msg", "Réponse invalide.") }
            }
        }

        var result = try { doRequest() } catch (e: Exception) {
            return JSONObject().put("success", false).put("msg", "Erreur réseau : ${e.message}")
        }

        if (!result.optBoolean("success", false) && result.optString("error_code") == "auth_required") {
            sessionToken = null
            if (openSession(context)) {
                result = try { doRequest() } catch (e: Exception) {
                    return JSONObject().put("success", false).put("msg", "Erreur réseau : ${e.message}")
                }
            }
        }
        return result
    }

    // ─── Encodage des chemins (base64 standard, requis par l'API Freebox) ────

    private fun encodePath(path: String): String = Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // ─── Stockage : fs/ls, fs/mkdir, fs/rename, fs/rm, fs/mv ──────────────────

    fun listDirectory(context: Context, path: String): List<FbxFile> {
        val result = apiRequest(context, "GET", "fs/ls/${encodePath(path)}/?removeHidden=true")
        if (!result.optBoolean("success", false)) return emptyList()
        val arr = result.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FbxFile(
                path = decodePath(o.optString("path")),
                name = o.optString("name"),
                isDir = o.optString("type") == "dir",
                size = o.optLong("size", 0)
            )
        }
    }

    private fun decodePath(base64Path: String): String =
        try { String(Base64.decode(base64Path, Base64.NO_WRAP), Charsets.UTF_8) } catch (_: Exception) { base64Path }

    fun createFolder(context: Context, parentPath: String, name: String): ActionResult {
        val payload = JSONObject().put("parent", encodePath(parentPath)).put("dirname", name)
        val result = apiRequest(context, "POST", "fs/mkdir/", payload)
        return if (result.optBoolean("success", false)) ActionResult(true, "📁 Dossier « $name » créé sur la Freebox.")
        else ActionResult(false, "❌ Échec de création : ${result.optString("msg", "erreur inconnue")}")
    }

    /** Renommage synchrone (contrairement à mv/rm, pas de tâche asynchrone). */
    fun renameEntry(context: Context, path: String, newName: String): ActionResult {
        val payload = JSONObject().put("src", encodePath(path)).put("dst", newName)
        val result = apiRequest(context, "POST", "fs/rename/", payload)
        return if (result.optBoolean("success", false)) ActionResult(true, "✏️ Renommé en « $newName » sur la Freebox.")
        else ActionResult(false, "❌ Échec du renommage : ${result.optString("msg", "erreur inconnue")}")
    }

    /** Suppression (tâche asynchrone) — attend jusqu'à [timeoutSeconds] que la tâche se termine. */
    suspend fun deleteEntry(context: Context, path: String, timeoutSeconds: Int = 20): ActionResult {
        val payload = JSONObject().put("files", JSONArray().put(encodePath(path)))
        val result = apiRequest(context, "POST", "fs/rm/", payload)
        if (!result.optBoolean("success", false)) {
            return ActionResult(false, "❌ Échec de la suppression : ${result.optString("msg", "erreur inconnue")}")
        }
        val taskId = result.optJSONObject("result")?.optInt("id", -1) ?: -1
        if (taskId < 0) return ActionResult(true, "🗑️ Suppression lancée sur la Freebox.")
        return waitForTask(context, taskId, timeoutSeconds, "🗑️ Supprimé de la Freebox avec succès.", "❌ Échec de la suppression sur la Freebox.")
    }

    /** Déplacement (tâche asynchrone) vers un dossier de destination. */
    suspend fun moveEntry(context: Context, path: String, destDirPath: String, timeoutSeconds: Int = 30): ActionResult {
        val payload = JSONObject()
            .put("files", JSONArray().put(encodePath(path)))
            .put("dst", encodePath(destDirPath))
            .put("mode", "overwrite")
        val result = apiRequest(context, "POST", "fs/mv/", payload)
        if (!result.optBoolean("success", false)) {
            return ActionResult(false, "❌ Échec du déplacement : ${result.optString("msg", "erreur inconnue")}")
        }
        val taskId = result.optJSONObject("result")?.optInt("id", -1) ?: -1
        if (taskId < 0) return ActionResult(true, "📦 Déplacement lancé sur la Freebox.")
        return waitForTask(context, taskId, timeoutSeconds, "📦 Déplacé avec succès sur la Freebox.", "❌ Échec du déplacement sur la Freebox.")
    }

    private suspend fun waitForTask(context: Context, taskId: Int, timeoutSeconds: Int, successMsg: String, failMsg: String): ActionResult {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutSeconds * 1000L) {
            val result = apiRequest(context, "GET", "fs/tasks/$taskId")
            val state = result.optJSONObject("result")?.optString("state")
            when (state) {
                "done" -> return ActionResult(true, successMsg)
                "failed" -> {
                    val err = result.optJSONObject("result")?.optString("error") ?: "erreur inconnue"
                    return ActionResult(false, "$failMsg ($err)")
                }
            }
            delay(1000)
        }
        return ActionResult(true, "$successMsg (toujours en cours en arrière-plan sur la Freebox — les gros transferts peuvent prendre plus de temps).")
    }

    fun formatDirectoryListing(path: String, files: List<FbxFile>): String {
        if (files.isEmpty()) return "📦 Le dossier « $path » est vide (ou introuvable)."
        val sb = StringBuilder("📦 **$path** :\n\n")
        files.sortedWith(compareByDescending<FbxFile> { it.isDir }.thenBy { it.name.lowercase() }).forEach { f ->
            val icon = if (f.isDir) "📁" else "📄"
            val sizeStr = if (!f.isDir) " (${formatSize(f.size)})" else ""
            sb.append("$icon ${f.name}$sizeStr\n")
        }
        return sb.toString().trim()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return "%.1f %s".format(value, units[unitIndex.coerceAtLeast(0)])
    }

    // ─── Wi-Fi de la Freebox (réellement pilotable, sans restriction Android) ─

    fun getWifiStatus(context: Context): ActionResult {
        val result = apiRequest(context, "GET", "wifi/config/")
        if (!result.optBoolean("success", false)) {
            return ActionResult(false, "❌ Impossible de lire l'état du Wi-Fi Freebox : ${result.optString("msg", "erreur inconnue")}")
        }
        val enabled = result.optJSONObject("result")?.optBoolean("enabled", false) ?: false
        return ActionResult(true, if (enabled) "📶 Le Wi-Fi de la Freebox est activé." else "📶 Le Wi-Fi de la Freebox est désactivé.")
    }

    fun setWifiState(context: Context, enabled: Boolean): ActionResult {
        val payload = JSONObject().put("enabled", enabled)
        val result = apiRequest(context, "PUT", "wifi/config/", payload)
        return if (result.optBoolean("success", false)) {
            ActionResult(true, if (enabled) "📶 Wi-Fi de la Freebox activé." else "📶 Wi-Fi de la Freebox désactivé.")
        } else {
            ActionResult(false, "❌ Échec de la modification du Wi-Fi Freebox : ${result.optString("msg", "erreur inconnue")}")
        }
    }
}
