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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FreeboxController — accès complet (lecture ET écriture) à l'API Freebox OS :
 * appareils du réseau local, Wi-Fi, domotique Freebox Home (Delta/Pop, capteurs,
 * prises, volets...), état de la connexion. Ré-ajouté à la demande explicite de
 * l'utilisateur (précédemment retiré au profit d'un simple accès SMB générique).
 *
 * Authentification par app_token (obtenu une fois pour toutes lors de l'appairage
 * de l'application "JARVIS Assistant" avec la Freebox, cf. Freebox OS -> Paramètres
 * -> Gestion des accès -> Applications) : JAMAIS codé en dur ici, uniquement lu
 * depuis Prefs (saisi par l'utilisateur dans ⚙ -> 📡 Box/Écoute -> Freebox OS), pour ne
 * jamais exposer ce jeton dans le code source (dépôt GitHub public).
 *
 * Flux d'authentification (documentation officielle https://dev.freebox.fr/sdk/os/login/) :
 *  1) GET  {base}/login/                -> challenge
 *  2) password = HMAC-SHA1(app_token, challenge) en hex
 *  3) POST {base}/login/session/ {app_id, password} -> session_token
 *  4) Header "X-Fbx-App-Auth: <session_token>" sur tous les appels suivants.
 * Le session_token expire après un certain temps d'inactivité : sur une réponse
 * d'erreur "invalid_token"/"auth_required", on relance le login une fois puis on
 * réessaie la requête d'origine.
 *
 * La version d'API n'est jamais supposée fixe : on l'auto-découvre via /api_version
 * (évite de casser JARVIS à chaque mise à jour majeure de Freebox OS).
 */
object FreeboxController {

    data class ActionResult(val success: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Cache en mémoire (par processus app) : base d'API découverte + session active.
    private var cachedApiBase: String? = null
    private var cachedApiBaseHost: String? = null
    private var cachedSessionToken: String? = null
    private var cachedSessionHost: String? = null

    fun isConfigured(context: Context): Boolean =
        Prefs.getFreeboxAppId(context).isNotBlank() && Prefs.getFreeboxAppToken(context).isNotBlank()

    private fun notConfiguredMessage(): String =
        "❌ Freebox non configurée. Renseigne dans ⚙ -> 📡 Box/Écoute -> Freebox OS : l'app_id et l'app_token " +
            "obtenus lors de l'appairage de l'application avec ta Freebox (Freebox OS -> Paramètres -> " +
            "Gestion des accès -> Applications autorisées)."

    // ─────────────────────────────────────────────────────────────────────────
    // Découverte de la version d'API + login
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun getApiBase(context: Context): String? = withContext(Dispatchers.IO) {
        val host = Prefs.getFreeboxHost(context).trimEnd('/')
        if (cachedApiBase != null && cachedApiBaseHost == host) return@withContext cachedApiBase
        try {
            val req = Request.Builder().url("$host/api_version").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val apiBaseUrl = json.optString("api_base_url", "/api/")
                val apiVersion = json.optString("api_version", "8.0")
                val major = apiVersion.substringBefore(".").ifBlank { "8" }
                val base = "$host${apiBaseUrl}v$major/"
                cachedApiBase = base
                cachedApiBaseHost = host
                base
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hmacSha1Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private suspend fun login(context: Context, apiBase: String): String? = withContext(Dispatchers.IO) {
        val appId = Prefs.getFreeboxAppId(context)
        val appToken = Prefs.getFreeboxAppToken(context)
        if (appId.isBlank() || appToken.isBlank()) return@withContext null
        try {
            // 1) challenge
            val challengeReq = Request.Builder().url("${apiBase}login/").get().build()
            val challenge = client.newCall(challengeReq).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return@withContext null
                json.optJSONObject("result")?.optString("challenge")
            } ?: return@withContext null

            // 2) password = HMAC-SHA1(app_token, challenge)
            val password = hmacSha1Hex(appToken, challenge)

            // 3) session
            val payload = JSONObject().apply { put("app_id", appId); put("password", password) }
            val sessionReq = Request.Builder()
                .url("${apiBase}login/session/")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(sessionReq).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return@withContext null
                val token = json.optJSONObject("result")?.optString("session_token")
                if (token.isNullOrBlank()) return@withContext null
                cachedSessionToken = token
                cachedSessionHost = Prefs.getFreeboxHost(context).trimEnd('/')
                token
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Requête authentifiée générique, avec un ré-essai automatique après relogin si la session a expiré. */
    private suspend fun authedRequest(
        context: Context,
        method: String,
        path: String,
        jsonBody: JSONObject? = null
    ): Pair<Boolean, JSONObject>? = withContext(Dispatchers.IO) {
        val apiBase = getApiBase(context) ?: return@withContext null
        val host = Prefs.getFreeboxHost(context).trimEnd('/')
        var token = if (cachedSessionHost == host) cachedSessionToken else null
        if (token == null) token = login(context, apiBase) ?: return@withContext null

        fun buildRequest(sessionToken: String): Request {
            val builder = Request.Builder()
                .url("$apiBase$path")
                .addHeader("X-Fbx-App-Auth", sessionToken)
            when (method) {
                "PUT" -> builder.put((jsonBody ?: JSONObject()).toString().toRequestBody(JSON))
                "POST" -> builder.post((jsonBody ?: JSONObject()).toString().toRequestBody(JSON))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            return builder.build()
        }

        try {
            var json = client.newCall(buildRequest(token)).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                runCatching { JSONObject(body) }.getOrNull()
            }
            // Session expirée / invalide -> un seul relogin + un seul nouvel essai.
            val errCode = json?.optString("error_code", "")
            if (json != null && !json.optBoolean("success", true) &&
                (errCode == "invalid_token" || errCode == "auth_required" || errCode == "invalid_session")
            ) {
                val newToken = login(context, apiBase) ?: return@withContext (false to (json ?: JSONObject()))
                json = client.newCall(buildRequest(newToken)).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    runCatching { JSONObject(body) }.getOrNull()
                }
            }
            if (json == null) return@withContext null
            (json.optBoolean("success", false)) to json
        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture : état de connexion
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun status(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val (ok, json) = authedRequest(context, "GET", "connection/") ?: return "❌ Freebox injoignable (vérifie que le téléphone est bien connecté au réseau de la Freebox, ou l'adresse configurée dans ⚙)."
        if (!ok) return "❌ Erreur Freebox : ${json.optString("msg", json.optString("error_code", "inconnue"))}"
        val r = json.optJSONObject("result") ?: return "❌ Réponse Freebox inattendue."
        val state = r.optString("state", "?")
        val media = r.optString("media", "?")
        val rateDown = r.optLong("rate_down", 0) / 1000
        val rateUp = r.optLong("rate_up", 0) / 1000
        val ipv4 = r.optString("ipv4", "?")
        return "📡 Freebox : connexion $state ($media). Débit actuel : ↓${rateDown} Ko/s / ↑${rateUp} Ko/s. IP publique : $ipv4."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture/écriture : Wi-Fi
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun wifiStatus(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val (ok, json) = authedRequest(context, "GET", "wifi/config/") ?: return "❌ Freebox injoignable."
        if (!ok) return "❌ Erreur Freebox : ${json.optString("msg", "inconnue")}"
        val enabled = json.optJSONObject("result")?.optBoolean("enabled", false) ?: false
        return "📶 Wi-Fi Freebox : ${if (enabled) "activé" else "désactivé"}."
    }

    suspend fun wifiSet(context: Context, enable: Boolean): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val body = JSONObject().apply { put("enabled", enable) }
        val (ok, json) = authedRequest(context, "PUT", "wifi/config/", body) ?: return "❌ Freebox injoignable."
        if (!ok) return "❌ Erreur Freebox : ${json.optString("msg", "inconnue")}"
        return if (enable) "✅ Wi-Fi Freebox activé." else "✅ Wi-Fi Freebox désactivé."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture : appareils du réseau local (LAN browser)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun listDevices(context: Context, filter: String = ""): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val (okIf, ifJson) = authedRequest(context, "GET", "lan/browser/interfaces/") ?: return "❌ Freebox injoignable."
        if (!okIf) return "❌ Erreur Freebox : ${ifJson.optString("msg", "inconnue")}"
        val interfaces = ifJson.optJSONArray("result") ?: JSONArray()
        val allDevices = StringBuilder()
        var total = 0
        for (i in 0 until interfaces.length()) {
            val ifaceName = interfaces.optJSONObject(i)?.optString("name") ?: continue
            val (okDev, devJson) = authedRequest(context, "GET", "lan/browser/$ifaceName/") ?: continue
            if (!okDev) continue
            val hosts = devJson.optJSONArray("result") ?: continue
            for (h in 0 until hosts.length()) {
                val host = hosts.optJSONObject(h) ?: continue
                val name = host.optString("primary_name", "").ifBlank { "(sans nom)" }
                if (filter.isNotBlank() && !name.contains(filter, ignoreCase = true)) continue
                val active = host.optBoolean("active", false)
                val vendor = host.optString("vendor_name", "")
                val ipEntry = host.optJSONArray("l3connectivities")?.optJSONObject(0)
                val ip = ipEntry?.optString("addr", "") ?: ""
                total++
                allDevices.append("${if (active) "🟢" else "⚪"} $name")
                if (vendor.isNotBlank()) allDevices.append(" ($vendor)")
                if (ip.isNotBlank()) allDevices.append(" — $ip")
                allDevices.append("\n")
            }
        }
        if (total == 0) return if (filter.isBlank()) "📡 Aucun appareil détecté sur le réseau Freebox." else "📡 Aucun appareil correspondant à « $filter »."
        return "📡 Appareils réseau Freebox ($total) :\n${allDevices.toString().trim()}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture/écriture : domotique Freebox Home (Delta/Pop — prises, volets,
    // capteurs, alarme...) via l'API home/nodes + home/endpoints.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun homeDevices(context: Context, filter: String = ""): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val (ok, json) = authedRequest(context, "GET", "home/nodes/") ?: return "❌ Freebox injoignable."
        if (!ok) {
            val err = json.optString("error_code", "")
            if (err == "invalid_route" || err == "nodev") return "❌ Aucun module Freebox Home détecté sur cette Freebox (pas d'abonnement/matériel domotique associé)."
            return "❌ Erreur Freebox : ${json.optString("msg", "inconnue")}"
        }
        val nodes = json.optJSONArray("result") ?: JSONArray()
        if (nodes.length() == 0) return "🏠 Aucun appareil domotique Freebox Home enregistré."
        val sb = StringBuilder()
        var count = 0
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val name = node.optString("label", node.optString("name", "?"))
            if (filter.isNotBlank() && !name.contains(filter, ignoreCase = true)) continue
            val category = node.optString("category", "?")
            val id = node.optInt("id", -1)
            count++
            sb.append("🏠 $name (catégorie: $category, id: $id)\n")
            val eps = node.optJSONObject("type")?.optJSONArray("endpoints")
            if (eps != null) {
                for (e in 0 until eps.length()) {
                    val ep = eps.optJSONObject(e) ?: continue
                    if (ep.optString("ep_type") != "slot") continue // slot = commandable en écriture
                    val epName = ep.optString("name", "?")
                    val epId = ep.optInt("id", -1)
                    sb.append("   ↳ commande disponible : $epName (endpoint id: $epId)\n")
                }
            }
        }
        if (count == 0) return "🏠 Aucun appareil domotique correspondant à « $filter »."
        return sb.toString().trim()
    }

    /**
     * Contrôle un appareil domotique Freebox Home : recherche le nœud par nom
     * (partiel, insensible à la casse), puis envoie une valeur sur le premier
     * endpoint "slot" (commandable) trouvé — booléen pour un simple on/off,
     * numérique pour une position/intensité selon l'appareil.
     */
    suspend fun homeSet(context: Context, device: String, boolValue: Boolean? = null, numValue: Double? = null): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        if (device.isBlank()) return "❌ Précise le nom de l'appareil domotique à contrôler."
        val (ok, json) = authedRequest(context, "GET", "home/nodes/") ?: return "❌ Freebox injoignable."
        if (!ok) return "❌ Erreur Freebox : ${json.optString("msg", "inconnue")}"
        val nodes = json.optJSONArray("result") ?: JSONArray()
        var targetNode: JSONObject? = null
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val name = node.optString("label", node.optString("name", ""))
            if (name.contains(device, ignoreCase = true)) { targetNode = node; break }
        }
        if (targetNode == null) return "❌ Aucun appareil domotique Freebox Home nommé « $device » trouvé. Utilise freebox_home_devices pour voir la liste exacte."
        val nodeId = targetNode.optInt("id", -1)
        val eps = targetNode.optJSONObject("type")?.optJSONArray("endpoints")
        var slot: JSONObject? = null
        if (eps != null) {
            for (e in 0 until eps.length()) {
                val ep = eps.optJSONObject(e) ?: continue
                if (ep.optString("ep_type") == "slot") { slot = ep; break }
            }
        }
        if (slot == null) return "❌ « $device » n'a pas de commande pilotable trouvée (peut-être un simple capteur en lecture seule)."
        val epId = slot.optInt("id", -1)
        val valueType = slot.optString("value_type", "bool")
        val body = JSONObject().apply {
            put("value", when {
                numValue != null -> numValue
                boolValue != null -> boolValue
                else -> true
            })
        }
        val (okSet, setJson) = authedRequest(context, "PUT", "home/endpoints/$nodeId/$epId/", body) ?: return "❌ Freebox injoignable."
        if (!okSet) return "❌ Erreur lors du contrôle de « $device » : ${setJson.optString("msg", "inconnue")}"
        return "✅ « $device » : commande envoyée (${if (boolValue != null) (if (boolValue) "activé" else "désactivé") else "valeur $numValue"}, type $valueType)."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Redirection de port (fw/redir/) — expose un service tournant sur ce téléphone
    // (voir LocalWebServerController) au reste d'internet via la Freebox, gratuit,
    // sans aucun tiers. Combiné à DuckDnsController pour une adresse stable.
    // ─────────────────────────────────────────────────────────────────────────

    /** Adresse IPv4 locale du téléphone sur le réseau actuel, ou null si indisponible. */
    private fun localIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Crée (ou remplace) une redirection de port sur la Freebox : le trafic entrant sur
     * [wanPort] (port public, vu depuis internet) est redirigé vers [lanPort] sur ce
     * téléphone. Nécessaire pour qu'un serveur local (LocalWebServerController) soit
     * accessible depuis l'extérieur du réseau, pas seulement en Wi-Fi local.
     */
    suspend fun configurePortForward(context: Context, wanPort: Int, lanPort: Int, comment: String = "Site JARVIS"): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val lanIp = localIpAddress()
            ?: return "❌ Impossible de déterminer l'adresse IP locale du téléphone (es-tu bien connecté au Wi-Fi de cette Freebox ?)."

        // Liste les redirections existantes pour éviter les doublons sur le même port WAN.
        val (okList, listJson) = authedRequest(context, "GET", "fw/redir/") ?: return "❌ Freebox injoignable."
        if (okList) {
            val existing = listJson.optJSONArray("result") ?: JSONArray()
            for (i in 0 until existing.length()) {
                val redir = existing.optJSONObject(i) ?: continue
                if (redir.optInt("wan_port_start") == wanPort) {
                    val id = redir.optInt("id", -1)
                    if (id >= 0) authedRequest(context, "DELETE", "fw/redir/$id/")
                }
            }
        }

        val body = JSONObject().apply {
            put("enabled", true)
            put("ip_proto", "tcp")
            put("wan_port_start", wanPort)
            put("wan_port_end", wanPort)
            put("lan_port", lanPort)
            put("lan_ip", lanIp)
            put("comment", comment)
        }
        val (ok, json) = authedRequest(context, "POST", "fw/redir/", body) ?: return "❌ Freebox injoignable."
        if (!ok) return "❌ Erreur lors de la création de la redirection de port : ${json.optString("msg", json.optString("error_code", "inconnue"))}"

        val statusPair = authedRequest(context, "GET", "connection/")
        val publicIp = if (statusPair != null && statusPair.first) statusPair.second.optJSONObject("result")?.optString("ipv4", "") ?: "" else ""

        val sb = StringBuilder("✅ Redirection de port créée sur la Freebox : le port public $wanPort pointe maintenant vers ce téléphone ($lanIp:$lanPort).\n")
        if (publicIp.isNotBlank()) sb.append("🌍 Accessible depuis internet : http://$publicIp:$wanPort/\n")
        if (DuckDnsController.isConfigured(context)) {
            sb.append("🦆 Ou via ton adresse DuckDNS (une fois à jour avec duckdns_update) : http://${DuckDnsController.fullDomain(context)}:$wanPort/")
        }
        return sb.toString().trim()
    }

    /** Supprime la redirection de port créée pour [wanPort], si elle existe. */
    suspend fun removePortForward(context: Context, wanPort: Int): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val (okList, listJson) = authedRequest(context, "GET", "fw/redir/") ?: return "❌ Freebox injoignable."
        if (!okList) return "❌ Erreur Freebox : ${listJson.optString("msg", "inconnue")}"
        val existing = listJson.optJSONArray("result") ?: JSONArray()
        for (i in 0 until existing.length()) {
            val redir = existing.optJSONObject(i) ?: continue
            if (redir.optInt("wan_port_start") == wanPort) {
                val id = redir.optInt("id", -1)
                if (id >= 0) {
                    val (ok, delJson) = authedRequest(context, "DELETE", "fw/redir/$id/") ?: return "❌ Freebox injoignable."
                    return if (ok) "🗑 Redirection du port $wanPort supprimée." else "❌ Erreur : ${delJson.optString("msg", "inconnue")}"
                }
            }
        }
        return "ℹ️ Aucune redirection trouvée pour le port $wanPort."
    }
}
