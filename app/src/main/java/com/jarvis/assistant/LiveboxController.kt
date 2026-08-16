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
 * LiveboxController — Livebox (Orange), via le protocole "sysbus" (aucune API
 * officielle publiée par Orange — reverse engineering documenté et activement
 * maintenu par la communauté, notamment cyr-ius/aiosysbus qui alimente
 * l'intégration officielle Home Assistant "Livebox").
 *
 * Comme pour la Bbox/SFR Box, pas d'écran physique de confirmation : authentification
 * par mot de passe admin local (saisi dans ⚙, jamais codé en dur — dépôt public).
 *
 * Flux d'authentification confirmé (aiosysbus/auth.py) :
 *  1) GET  {base}/         -> amorce un cookie de session (la Livebox renvoie un
 *     Set-Cookie non conforme au standard, d'où une extraction manuelle plutôt
 *     que de s'appuyer sur le CookieJar strict d'OkHttp)
 *  2) POST {base}/ws  headers: Content-Type: application/x-sah-ws-1-call+json,
 *     Authorization: X-Sah-Login  body: {"service":"sah.Device.Information",
 *     "method":"createContext","parameters":{"applicationName":"so_sdkut",
 *     "username":"admin","password":"<motdepasse>"}} -> data.contextID
 *  3) Appels authentifiés : POST {base}/ws  headers: X-Context: <contextID>,
 *     Content-Type: application/x-sah-ws-1-call+json; charset=UTF-8
 *     body: {"service":"...", "method":"...", "parameters":{...}} -> result.*
 *
 * Endpoints confirmés (aiosysbus + doc communautaire) : NMC.getWANStatus (état
 * connexion), NMC.reboot (redémarrage), NMC.Wifi.set (Wi-Fi), Hosts.getDevices
 * (appareils), Firewall.setPortForwarding/deletePortForwarding (redirection de
 * ports — la Livebox EST capable de ceci, contrairement à la Bbox/SFR Box).
 * Pas d'endpoint disque/USB confirmé pour la Livebox -> capacité non disponible ici.
 */
object LiveboxController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val CALL_JSON = "application/x-sah-ws-1-call+json".toMediaType()
    private val CALL_JSON_UTF8 = "application/x-sah-ws-1-call+json; charset=UTF-8".toMediaType()

    private fun defaultHost() = "http://192.168.1.1"

    fun isConfigured(context: Context): Boolean = Prefs.getBoxPassword(context).isNotBlank()

    private fun notConfiguredMessage(): String =
        "❌ Livebox non configurée. Renseigne le mot de passe admin de ta Livebox dans ⚙ -> 📡 Box Internet " +
            "(mot de passe visible sur l'étiquette sous la Livebox, ou celui que tu as personnalisé)."

    private fun host(context: Context) = Prefs.getBoxHost(context, defaultHost()).trimEnd('/')

    // Cache en mémoire (par processus app) : cookie de session + contexte authentifié.
    private var cachedCookie: String? = null
    private var cachedCookieHost: String? = null
    private var cachedContextId: String? = null
    private var cachedContextHost: String? = null

    private suspend fun primeCookie(context: Context) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${host(context)}/").build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.headers("Set-Cookie").firstOrNull()
                if (raw != null) {
                    cachedCookie = raw.substringBefore(';')
                    cachedCookieHost = host(context)
                }
            }
        } catch (_: Exception) {
            // Pas grave : certaines Livebox n'exigent pas de cookie de priming.
        }
    }

    private suspend fun login(context: Context, forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedContextId != null && cachedContextHost == host(context)) return@withContext cachedContextId
        val password = Prefs.getBoxPassword(context)
        if (password.isBlank()) return@withContext null
        try {
            if (cachedCookieHost != host(context)) primeCookie(context)
            val payload = JSONObject().apply {
                put("service", "sah.Device.Information")
                put("method", "createContext")
                put("parameters", JSONObject().apply {
                    put("applicationName", "so_sdkut")
                    put("username", "admin")
                    put("password", password)
                })
            }
            val builder = Request.Builder()
                .url("${host(context)}/ws")
                .addHeader("Content-Type", "application/x-sah-ws-1-call+json")
                .addHeader("Authorization", "X-Sah-Login")
            cachedCookie?.let { builder.addHeader("Cookie", it) }
            builder.post(payload.toString().toRequestBody(CALL_JSON))
            val text = client.newCall(builder.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                ?: return@withContext null
            val contextId = JSONObject(text).optJSONObject("data")?.optString("contextID")?.ifBlank { null }
            if (contextId != null) {
                cachedContextId = contextId
                cachedContextHost = host(context)
            }
            contextId
        } catch (_: Exception) {
            null
        }
    }

    /** Appel authentifié générique {service}.{method}. Relance le login une seule fois si le contexte a expiré. */
    private suspend fun call(
        context: Context,
        service: String,
        method: String,
        parameters: JSONObject = JSONObject(),
        retry: Boolean = true
    ): JSONObject? = withContext(Dispatchers.IO) {
        val contextId = login(context) ?: return@withContext null
        try {
            val payload = JSONObject().apply {
                put("service", service)
                put("method", method)
                put("parameters", parameters)
            }
            val builder = Request.Builder()
                .url("${host(context)}/ws")
                .addHeader("X-Context", contextId)
                .addHeader("Content-Type", "application/x-sah-ws-1-call+json; charset=UTF-8")
            cachedCookie?.let { builder.addHeader("Cookie", it) }
            builder.post(payload.toString().toRequestBody(CALL_JSON_UTF8))
            val text = client.newCall(builder.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                ?: return@withContext null
            val json = JSONObject(text)
            val result = json.optJSONObject("result")
            val errors = result?.optJSONArray("errors")
            if (retry && (result == null || (errors != null && errors.length() > 0))) {
                // Contexte probablement expiré : on relance une fois avec un login forcé.
                login(context, forceRefresh = true) ?: return@withContext null
                return@withContext call(context, service, method, parameters, retry = false)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /** Certains appels renvoient les champs sous "data", d'autres directement dans "result". */
    private fun payload(result: JSONObject?): JSONObject? = result?.optJSONObject("data") ?: result

    suspend fun status(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val result = call(context, "NMC", "getWANStatus")
            ?: return "❌ Livebox injoignable ou mot de passe incorrect (vérifie l'adresse/le mot de passe dans ⚙)."
        val data = payload(result)
        val wanState = data?.optString("WanState")?.ifBlank { null }
            ?: data?.optString("ConnectionState")?.ifBlank { null } ?: "?"
        val ip = data?.optString("IPAddress")?.ifBlank { null } ?: ""
        val sb = StringBuilder("📡 Livebox : connexion WAN $wanState.")
        if (ip.isNotBlank()) sb.append(" IP publique : $ip.")
        return sb.toString()
    }

    suspend fun wifiSet(context: Context, enable: Boolean): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val params = JSONObject().apply {
            put("Enable", enable)
            put("Status", enable)
        }
        val result = call(context, "NMC.Wifi", "set", params)
            ?: return "❌ Livebox injoignable ou mot de passe incorrect."
        return if (enable) "✅ Wi-Fi Livebox activé." else "✅ Wi-Fi Livebox désactivé."
    }

    suspend fun listDevices(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val result = call(context, "Hosts", "getDevices")
            ?: return "❌ Livebox injoignable ou mot de passe incorrect."
        val data = payload(result)
        // La forme exacte de la réponse (dictionnaire de clés hôtes vs tableau) varie selon
        // les versions de firmware Livebox — on gère les deux plutôt que de deviner et
        // d'afficher des données fausses.
        val entries = mutableListOf<Triple<String, String, Boolean>>()
        val statusObj = data?.optJSONObject("status")
        if (statusObj != null) {
            statusObj.keys().forEach { key ->
                val host = statusObj.optJSONObject(key) ?: return@forEach
                val name = host.optString("Name").ifBlank { host.optString("HostName") }.ifBlank { key }
                val ip = host.optString("IPAddress", "?")
                val active = host.optBoolean("Active", false)
                entries += Triple(name, ip, active)
            }
        }
        if (entries.isEmpty()) {
            val arr = data?.optJSONArray("status") ?: result.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val host = arr.optJSONObject(i) ?: continue
                    val name = host.optString("Name").ifBlank { host.optString("HostName") }.ifBlank { "Appareil inconnu" }
                    val ip = host.optString("IPAddress", "?")
                    val active = host.optBoolean("Active", false)
                    entries += Triple(name, ip, active)
                }
            }
        }
        if (entries.isEmpty()) {
            return "ℹ️ La Livebox a répondu mais dans un format non reconnu — vérifie la liste des appareils " +
                "directement depuis l'interface de la Livebox (${host(context)})."
        }
        val sb = StringBuilder("📱 Appareils connus de la Livebox (${entries.size}) :\n\n")
        entries.forEach { (name, ip, active) -> sb.append("${if (active) "🟢" else "⚪"} $name — $ip\n") }
        return sb.toString().trim()
    }

    suspend fun reboot(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val result = call(context, "NMC", "reboot")
        return if (result != null) "🔄 Redémarrage de la Livebox lancé — elle sera injoignable quelques minutes."
            else "❌ Échec du redémarrage (mot de passe incorrect ou Livebox injoignable)."
    }

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

    suspend fun configurePortForward(context: Context, wanPort: Int, lanPort: Int, comment: String = "Site JARVIS"): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val lanIp = localIpAddress()
            ?: return "❌ Impossible de déterminer l'adresse IP locale du téléphone (es-tu bien connecté au Wi-Fi de cette Livebox ?)."
        val params = JSONObject().apply {
            put("id", "jarvis_$wanPort")
            put("origin", "webui")
            put("sourceInterface", "data")
            put("externalPort", wanPort.toString())
            put("internalPort", lanPort.toString())
            put("destinationIPAddress", lanIp)
            put("protocol", "6,17")
            put("enable", true)
            put("persistent", true)
            put("description", comment)
        }
        val result = call(context, "Firewall", "setPortForwarding", params)
            ?: return "❌ Livebox injoignable, mot de passe incorrect, ou redirection de ports non prise en charge par ce modèle/firmware."
        return "✅ Redirection de port créée sur la Livebox : le port public $wanPort pointe maintenant vers ce téléphone ($lanIp:$lanPort)."
    }

    suspend fun removePortForward(context: Context, wanPort: Int): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val params = JSONObject().apply {
            put("id", "jarvis_$wanPort")
            put("origin", "webui")
        }
        val result = call(context, "Firewall", "deletePortForwarding", params)
            ?: return "❌ Livebox injoignable ou mot de passe incorrect."
        return "🗑 Redirection du port $wanPort supprimée."
    }

    fun storageUnavailableMessage(): String =
        "❌ La gestion du disque dur (interne/USB) n'est pas disponible pour la Livebox via le protocole sysbus " +
            "(contrairement à la Freebox)."
}
