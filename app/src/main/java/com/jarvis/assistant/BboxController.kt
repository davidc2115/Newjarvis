package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BboxController — Bbox (Bouygues Telecom), via l'API officielle documentée
 * https://developer.bouyguestelecom.fr/news/router-api-summary (doc complète :
 * https://api.bbox.fr/doc/apirouter/index.html). Contrairement à la Freebox, la
 * Bbox n'a pas d'écran physique de confirmation d'appairage : l'authentification
 * se fait par mot de passe admin local (saisi par l'utilisateur dans ⚙, jamais
 * codé en dur — dépôt public), via une session cookie, comme le montre l'exemple
 * officiel : curl -c cookie.jar -XPOST https://mabbox.bytel.fr/api/v1/login -d
 * "password=XXX".
 *
 * Capacités confirmées par la doc officielle : Wi-Fi (lecture/écriture), infos
 * appareil, redémarrage, appareils connus (hosts), infos WAN/xDSL. Pas de gestion
 * de ports (redirection NAT) ni de disque/USB documentée publiquement pour la
 * Bbox — contrairement à la Freebox, ces deux capacités ne sont donc PAS
 * disponibles ici (le dire honnêtement plutôt que de prétendre le contraire).
 */
object BboxController {

    private const val JSON_MEDIA = "application/x-www-form-urlencoded"
    private val JSON = JSON_MEDIA.toMediaType()

    // Cookie jar en mémoire (simple, un seul hôte à la fois) — la Bbox authentifie
    // par cookie de session, contrairement à la Freebox (jeton en en-tête) et à la
    // SFR Box (jeton en paramètre de requête).
    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: emptyList()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun defaultHost() = "http://bbox.lan"

    fun isConfigured(context: Context): Boolean = Prefs.getBoxPassword(context).isNotBlank()

    private fun notConfiguredMessage(): String =
        "❌ Bbox non configurée. Renseigne le mot de passe admin de ta Bbox dans ⚙ -> 📡 Box Internet " +
            "(mot de passe visible sur l'étiquette sous la Bbox, ou celui que tu as personnalisé)."

    private fun host(context: Context) = Prefs.getBoxHost(context, defaultHost()).trimEnd('/')

    private suspend fun login(context: Context): Boolean = withContext(Dispatchers.IO) {
        val password = Prefs.getBoxPassword(context)
        if (password.isBlank()) return@withContext false
        try {
            val body = "password=${java.net.URLEncoder.encode(password, "UTF-8")}".toRequestBody(JSON)
            val req = Request.Builder().url("${host(context)}/api/v1/login").post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** [needsAuth] : la plupart des endpoints d'écriture/détail nécessitent une session active. */
    private suspend fun request(
        context: Context,
        method: String,
        path: String,
        needsAuth: Boolean = true,
        body: okhttp3.RequestBody? = null
    ): JSONArray? = withContext(Dispatchers.IO) {
        if (needsAuth && !login(context)) return@withContext null
        try {
            val builder = Request.Builder().url("${host(context)}/api/v1$path")
            when (method) {
                "POST" -> builder.post(body ?: "".toRequestBody(JSON))
                "PUT" -> builder.put(body ?: "".toRequestBody(JSON))
                else -> builder.get()
            }
            val text = client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null
            // Toutes les réponses Bbox sont un TABLEAU JSON contenant un seul objet
            // (ex: [{"wireless":{...}}]) — voir la doc officielle Bouygues.
            runCatching { JSONArray(text) }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun firstObject(arr: JSONArray?, key: String): JSONObject? =
        arr?.optJSONObject(0)?.optJSONObject(key)

    suspend fun status(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val summary = request(context, "GET", "/summary") ?: return "❌ Bbox injoignable (vérifie le réseau/l'adresse dans ⚙)."
        val device = firstObject(summary, "device")
        val wan = firstObject(summary, "wan")
        val model = device?.optString("modelname", "Bbox") ?: "Bbox"
        val wanState = wan?.optString("status", "?") ?: "?"
        return "📡 $model : connexion WAN $wanState."
    }

    suspend fun wifiSet(context: Context, enable: Boolean): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val body = "radio.enable=${if (enable) 1 else 0}".toRequestBody(JSON)
        val result = request(context, "PUT", "/wireless", body = body) ?: return "❌ Bbox injoignable ou mot de passe incorrect."
        return if (enable) "✅ Wi-Fi Bbox activé." else "✅ Wi-Fi Bbox désactivé."
    }

    suspend fun listDevices(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val hosts = request(context, "GET", "/hosts") ?: return "❌ Bbox injoignable ou mot de passe incorrect."
        val list = firstObject(hosts, "hosts")?.optJSONArray("list") ?: JSONArray()
        if (list.length() == 0) return "📱 Aucun appareil détecté sur le réseau Bbox."
        val sb = StringBuilder("📱 Appareils connus de la Bbox (${list.length()}) :\n\n")
        for (i in 0 until list.length()) {
            val h = list.optJSONObject(i) ?: continue
            val name = h.optString("hostname", "").ifBlank { h.optString("macaddress", "Appareil inconnu") }
            val ip = h.optString("ipaddress", "?")
            val active = h.optInt("active", 0) == 1
            sb.append("${if (active) "🟢" else "⚪"} $name — $ip\n")
        }
        return sb.toString().trim()
    }

    suspend fun reboot(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        // btoken (jeton anti-CSRF) exigé par l'API pour les actions sensibles — récupéré
        // depuis /device (champ "btoken", présent dans la doc officielle Bouygues).
        val device = request(context, "GET", "/device") ?: return "❌ Bbox injoignable ou mot de passe incorrect."
        val btoken = firstObject(device, "device")?.optString("btoken", "") ?: ""
        val path = if (btoken.isNotBlank()) "/device/reboot?btoken=$btoken" else "/device/reboot"
        val result = request(context, "POST", path)
        return if (result != null) "🔄 Redémarrage de la Bbox lancé — elle sera injoignable quelques minutes."
            else "❌ Échec du redémarrage (mot de passe incorrect ou Bbox injoignable)."
    }

    fun portForwardUnavailableMessage(): String =
        "❌ La gestion des redirections de ports n'est pas disponible pour la Bbox via son API publique " +
            "(contrairement à la Freebox) — configure-la manuellement depuis l'interface d'administration " +
            "de la Bbox (bbox.lan)."

    fun storageUnavailableMessage(): String =
        "❌ La gestion du disque dur (interne/USB) n'est pas disponible pour la Bbox via son API publique " +
            "(contrairement à la Freebox)."
}
