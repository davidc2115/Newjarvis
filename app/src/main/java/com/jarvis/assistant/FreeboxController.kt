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

    /**
     * Raison précise du dernier échec d'ouverture de session (challenge injoignable,
     * réponse vide, jeton invalide/révoqué, appairage jamais validé...). Auparavant
     * openSession() avalait silencieusement toute erreur en renvoyant juste false,
     * ce qui faisait afficher un message générique "Impossible d'ouvrir une session"
     * ou "Freebox non appairée" sans dire POURQUOI — impossible à diagnostiquer pour
     * l'utilisateur quand l'appairage et les permissions semblaient pourtant en ordre.
     */
    private var lastSessionError: String? = null

    /**
     * Permissions accordées à l'appli, renvoyées par login/session/ (champ
     * "permissions" — confirmé sur la doc officielle dev.freebox.fr/sdk/os/login).
     * Un champ absent équivaut à false. Mise à jour à chaque ouverture de session.
     */
    private var lastPermissions: JSONObject? = null

    private val PERMISSION_LABELS = mapOf(
        "explorer" to "📁 Gestionnaire de fichiers (disques durs, NAS)",
        "settings" to "⚙️ Paramètres de la Freebox (nécessaire pour freebox_status)",
        "contacts" to "👤 Contacts",
        "calls" to "📞 Journal d'appels",
        "downloader" to "⬇️ Téléchargements",
        "parental" to "🔒 Contrôle parental",
        "pvr" to "📺 Enregistreur (PVR)"
    )

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
                        // L'appairage seul n'accorde pas forcément l'accès aux disques durs ni
                        // aux paramètres — certains modèles de Freebox demandent une validation
                        // séparée par permission. On vérifie donc immédiatement et on prévient
                        // l'utilisateur s'il manque quelque chose, plutôt que de le laisser
                        // découvrir un échec silencieux plus tard en listant un dossier.
                        sessionToken = null
                        val permMsg = if (openSession(context)) permissionsSummary() else "⚠️ Session non ouverte juste après l'appairage : ${lastSessionError ?: "raison inconnue"} (réessaie freebox_permissions dans quelques secondes)."
                        val suffix = if (permMsg != null) "\n\n$permMsg" else ""
                        return ActionResult(true, "✅ Freebox appairée avec succès !$suffix")
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
        if (appToken.isBlank()) {
            lastSessionError = "Aucun jeton d'application enregistré sur ce téléphone — l'appairage n'a jamais abouti ou les données de l'app ont été effacées. Réappaire depuis 🏠 → Freebox."
            return false
        }
        val base = discoverApiBase(context)
        val hostUrl = host(context)

        return try {
            val challengeRequest = Request.Builder().url("${hostUrl}${base}login/").build()
            val challengeResponse = client.newCall(challengeRequest).execute()
            val challengeBody = challengeResponse.use { it.body?.string() }
            if (challengeBody == null) {
                lastSessionError = "Réponse vide de la Freebox à $hostUrl — vérifie que le téléphone est bien sur le même réseau Wi-Fi que la Freebox et que l'adresse est correcte."
                return false
            }
            val challengeJson = JSONObject(challengeBody)
            val challenge = challengeJson.optJSONObject("result")?.optString("challenge")
            if (challenge.isNullOrBlank()) {
                lastSessionError = "Challenge d'authentification introuvable dans la réponse de la Freebox (${challengeJson.optString("msg", "réponse inattendue")})."
                return false
            }

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(appToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val password = mac.doFinal(challenge.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

            val sessionPayload = JSONObject().put("app_id", APP_ID).put("password", password).toString().toRequestBody(JSON)
            val sessionRequest = Request.Builder().url("${hostUrl}${base}login/session/").post(sessionPayload).build()
            val sessionResponse = client.newCall(sessionRequest).execute()
            val sessionBody = sessionResponse.use { it.body?.string() }
            if (sessionBody == null) {
                lastSessionError = "Réponse vide de la Freebox lors de l'ouverture de session."
                return false
            }
            val json = JSONObject(sessionBody)
            if (!json.optBoolean("success", false)) {
                val errorCode = json.optString("error_code", "")
                val rawMsg = json.optString("msg", "")
                lastSessionError = when (errorCode) {
                    "invalid_token" -> "Jeton d'application invalide ou révoqué sur la Freebox — réappaire depuis 🏠 → Freebox (l'ancien appairage a été annulé côté Freebox, par exemple après un reset du mot de passe admin)."
                    "pending_token" -> "L'appairage n'a pas encore été validé sur l'écran de la Freebox — retourne sur 🏠 → Freebox et valide la demande (flèche droite)."
                    "denied_from_external_ip" -> "La Freebox refuse cette connexion car elle ne vient pas du réseau local — vérifie que le téléphone est bien sur le Wi-Fi de la Freebox, pas en 4G/5G."
                    else -> rawMsg.ifBlank { "erreur $errorCode".takeIf { errorCode.isNotBlank() } ?: "erreur inconnue" }
                }
                return false
            }
            val result = json.getJSONObject("result")
            sessionToken = result.getString("session_token")
            lastPermissions = result.optJSONObject("permissions")
            lastSessionError = null
            true
        } catch (e: Exception) {
            lastSessionError = "Erreur réseau (${e.message}) — vérifie l'adresse de la Freebox (${hostUrl}) et que le téléphone est sur le même Wi-Fi."
            false
        }
    }

    /** Requête authentifiée générique, avec réouverture automatique de session si le jeton a expiré. */
    private fun apiRequest(context: Context, method: String, endpoint: String, payload: JSONObject? = null): JSONObject {
        if (!isConfigured(context)) {
            return JSONObject().put("success", false).put("msg", "Freebox non appairée. Va dans 🏠 → Freebox pour l'appairer.")
        }
        if (sessionToken == null && !openSession(context)) {
            return JSONObject().put("success", false).put("msg", "Session Freebox impossible à ouvrir : ${lastSessionError ?: "raison inconnue"}")
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
            } else {
                // La session a expiré ET son renouvellement a échoué : on complète le
                // message d'origine ("auth_required") avec la vraie raison du refus de
                // renouvellement, sinon l'utilisateur ne voit qu'un message d'auth vague.
                return JSONObject().put("success", false)
                    .put("msg", "Session Freebox expirée, renouvellement impossible : ${lastSessionError ?: "raison inconnue"}")
            }
        }
        return result
    }

    // ─── Encodage des chemins (base64 standard, requis par l'API Freebox) ────

    private fun encodePath(path: String): String = Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // ─── Stockage : fs/ls, fs/mkdir, fs/rename, fs/rm, fs/mv ──────────────────

    data class ListResult(val files: List<FbxFile>, val error: String?)

    /**
     * Liste un dossier. En cas d'échec, [ListResult.error] contient le message
     * exact renvoyé par la Freebox — le plus souvent "insufficient_rights" si
     * l'appli n'a pas la permission "Accès aux disques durs" (à activer sur
     * mafreebox.freebox.fr → Paramètres → Gestion des accès → Applications),
     * ou "path_not_found" si le chemin n'existe pas.
     */
    fun listDirectory(context: Context, path: String): ListResult {
        val result = apiRequest(context, "GET", "fs/ls/${encodePath(path)}/?removeHidden=true")
        if (!result.optBoolean("success", false)) {
            val errorCode = result.optString("error_code", "")
            val msg = result.optString("msg", "").ifBlank {
                when (errorCode) {
                    "insufficient_rights" -> "Permission « Accès aux disques durs » non accordée à JARVIS. Va sur mafreebox.freebox.fr → Paramètres de la Freebox → Gestion des accès → Applications, et active cette permission pour JARVIS."
                    "path_not_found" -> "Ce chemin n'existe pas sur la Freebox."
                    "disk_unavailable" -> "Le disque n'est pas monté sur la Freebox."
                    else -> errorCode.ifBlank { "erreur inconnue" }
                }
            }
            return ListResult(emptyList(), msg)
        }
        val arr = result.optJSONArray("result") ?: JSONArray()
        val files = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FbxFile(
                path = decodePath(o.optString("path")),
                name = o.optString("name"),
                isDir = o.optString("type") == "dir",
                size = o.optLong("size", 0)
            )
        }
        return ListResult(files, null)
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

    fun formatDirectoryListing(path: String, result: ListResult): String {
        if (result.error != null) return "❌ Impossible de lister « $path » : ${result.error}"
        if (result.files.isEmpty()) return "📦 Le dossier « $path » est vide."
        val sb = StringBuilder("📦 **$path** :\n\n")
        result.files.sortedWith(compareByDescending<FbxFile> { it.isDir }.thenBy { it.name.lowercase() }).forEach { f ->
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

    // ─── État général de la box (système + connexion internet) ───────────────

    /**
     * Statut complet de la Freebox : modèle, version firmware, température,
     * temps de fonctionnement, et état de la connexion internet (débit,
     * type de ligne, état). Note : sur mafreebox.freebox.fr → Paramètres →
     * Gestion des accès → Applications, la permission "Accès aux paramètres
     * de la Freebox" doit être activée pour JARVIS pour lire system/.
     */
    fun getSystemStatus(context: Context): ActionResult {
        val sys = apiRequest(context, "GET", "system/")
        if (!sys.optBoolean("success", false)) {
            val errorCode = sys.optString("error_code", "")
            val msg = sys.optString("msg", "").ifBlank {
                if (errorCode == "insufficient_rights")
                    "Permission « Accès aux paramètres de la Freebox » non accordée à JARVIS. Active-la sur mafreebox.freebox.fr → Paramètres de la Freebox → Gestion des accès → Applications."
                else errorCode.ifBlank { "erreur inconnue" }
            }
            return ActionResult(false, "❌ Impossible de lire l'état de la Freebox : $msg")
        }
        val s = sys.optJSONObject("result") ?: JSONObject()
        val sb = StringBuilder("📡 **État de la Freebox**\n\n")
        sb.append("• Modèle : ${s.optString("model_info", s.optString("board_name", "inconnu"))}\n")
        sb.append("• Firmware : ${s.optString("firmware_version", "?")}\n")
        val uptimeSec = s.optLong("uptime_val", -1)
        if (uptimeSec >= 0) sb.append("• Allumée depuis : ${formatUptime(uptimeSec)}\n")
        val temp = s.optInt("temp_cpum", s.optInt("temp_sw", -1))
        if (temp > 0) sb.append("• Température : $temp°C\n")

        val conn = apiRequest(context, "GET", "connection/")
        if (conn.optBoolean("success", false)) {
            val c = conn.optJSONObject("result") ?: JSONObject()
            val state = c.optString("state", "inconnu")
            val stateFr = when (state) {
                "up" -> "connectée ✅"
                "down" -> "déconnectée ❌"
                "going_up" -> "connexion en cours…"
                "going_down" -> "déconnexion en cours…"
                else -> state
            }
            sb.append("• Connexion internet : $stateFr\n")
            sb.append("• Type de ligne : ${c.optString("media", "?")}\n")
            val rateDown = c.optLong("rate_down", -1)
            val rateUp = c.optLong("rate_up", -1)
            if (rateDown >= 0) sb.append("• Débit descendant actuel : ${formatBitrate(rateDown)}\n")
            if (rateUp >= 0) sb.append("• Débit montant actuel : ${formatBitrate(rateUp)}\n")
            val bwDown = c.optLong("bandwidth_down", -1)
            val bwUp = c.optLong("bandwidth_up", -1)
            if (bwDown >= 0) sb.append("• Bande passante max descendante : ${formatBitrate(bwDown)}\n")
            if (bwUp >= 0) sb.append("• Bande passante max montante : ${formatBitrate(bwUp)}\n")
        } else {
            sb.append("• Connexion internet : impossible à lire (${conn.optString("msg", "erreur inconnue")})\n")
        }
        return ActionResult(true, sb.toString().trim())
    }

    // ─── Permissions accordées à JARVIS sur la Freebox ────────────────────────

    /**
     * Vérifie et affiche précisément quelles permissions Freebox JARVIS possède.
     * Contrairement à deviner depuis un message d'erreur générique, ceci lit
     * directement le champ "permissions" renvoyé par login/session/.
     */
    fun getPermissionsStatus(context: Context): ActionResult {
        if (!isConfigured(context)) {
            return ActionResult(false, "❌ Freebox non appairée. Va dans 🏠 → Freebox pour l'appairer.")
        }
        sessionToken = null
        if (!openSession(context)) {
            return ActionResult(false, "❌ Impossible d'ouvrir une session Freebox : ${lastSessionError ?: "raison inconnue"}")
        }
        return ActionResult(true, permissionsSummary() ?: "❌ La Freebox n'a pas renvoyé la liste des permissions.")
    }

    private fun permissionsSummary(): String? {
        val perms = lastPermissions ?: return null
        val sb = StringBuilder("🔑 **Permissions accordées à JARVIS sur la Freebox** :\n\n")
        val missing = mutableListOf<String>()
        for ((key, label) in PERMISSION_LABELS) {
            val granted = perms.optBoolean(key, false)
            sb.append(if (granted) "✅ $label\n" else "❌ $label\n")
            if (!granted) missing.add(label)
        }
        if (missing.isNotEmpty()) {
            sb.append(
                "\n⚠️ Pour activer les permissions manquantes : mafreebox.freebox.fr → " +
                    "Paramètres de la Freebox → Gestion des accès → Applications → JARVIS Assistant Android, " +
                    "et coche les cases correspondantes (certains modèles ne les accordent pas automatiquement " +
                    "à l'appairage, il faut les activer manuellement une fois)."
            )
        }
        return sb.toString().trim()
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}j ${hours}h"
            hours > 0 -> "${hours}h ${minutes}min"
            else -> "${minutes}min"
        }
    }

    private fun formatBitrate(bitsPerSec: Long): String {
        // Les valeurs Freebox sont en bits/s ; on affiche en Mbit/s comme repère habituel.
        val mbps = bitsPerSec / 1_000_000.0
        return if (mbps >= 1) "%.1f Mbit/s".format(mbps) else "${bitsPerSec / 1000} Kbit/s"
    }
}
