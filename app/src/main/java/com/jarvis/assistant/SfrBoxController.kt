package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SfrBoxController — SFR Box, via l'API locale documentée par le projet open source
 * hacf-fr/sfrbox-api (utilisé par l'intégration officielle Home Assistant "SFR Box"
 * depuis 2023.2) : http://<ip-box>/api/1.0/?method=namespace.methode, réponses XML.
 * Non documentée officiellement par SFR, mais activement maintenue par la communauté
 * (tests automatisés, CI) — nettement plus fiable qu'une simple page web à scraper.
 *
 * Comme pour la Bbox, pas d'écran physique de confirmation : authentification par
 * mot de passe admin local (saisi dans ⚙, jamais codé en dur — dépôt public), via
 * un jeton dérivé par hash (SHA-256 + HMAC), différent du système par cookie de la
 * Bbox ou par en-tête de la Freebox.
 *
 * Capacités confirmées par la lib de référence : Wi-Fi (lecture/écriture/liste des
 * appareils), redémarrage, infos DSL/FTTH/WAN/système. Pas de gestion de ports ni
 * de disque/USB documentée pour la SFR Box — comme pour la Bbox, ces deux capacités
 * ne sont donc PAS disponibles ici.
 */
object SfrBoxController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun defaultHost() = "http://192.168.1.1"

    fun isConfigured(context: Context): Boolean = Prefs.getBoxPassword(context).isNotBlank()

    private fun notConfiguredMessage(): String =
        "❌ SFR Box non configurée. Renseigne le mot de passe admin de ta box dans ⚙ -> 📡 Box Internet " +
            "(mot de passe visible sur l'étiquette sous la box, ou celui que tu as personnalisé)."

    private fun host(context: Context) = Prefs.getBoxHost(context, defaultHost()).trimEnd('/')

    // ─────────────────────────────────────────────────────────────────────────
    // XML minimal (pas de dépendance externe) : les réponses SFR Box sont des
    // éléments à attributs, éventuellement imbriqués (ex: <client mac="..." .../>
    // répétés dans une liste) — une extraction par attribut/regex suffit, pas
    // besoin d'un vrai parseur XML pour ce format simple.
    // ─────────────────────────────────────────────────────────────────────────

    private fun attr(xml: String, tag: String, attribute: String): String? =
        Regex("<$tag\\b[^>]*\\b$attribute=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)

    private fun allTags(xml: String, tag: String): List<String> =
        Regex("<$tag\\b[^>]*/?>").findAll(xml).map { it.value }.toList()

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun getRaw(context: Context, params: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${host(context)}/api/1.0/?$params").build()
            client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun postRaw(context: Context, params: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${host(context)}/api/1.0/?$params").post("".toRequestBody(null)).build()
            client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        } catch (_: Exception) {
            null
        }
    }

    /** Jeton d'authentification final (voir hacf-fr/sfrbox-api pour le détail de l'algorithme). */
    private suspend fun getToken(context: Context): String? {
        val password = Prefs.getBoxPassword(context)
        if (password.isBlank()) return null
        val step1 = getRaw(context, "method=auth.getToken") ?: return null
        val rawToken = attr(step1, "auth", "token") ?: return null
        val hash = hmacSha256Hex(rawToken, sha256Hex("admin")) + hmacSha256Hex(rawToken, sha256Hex(password))
        val step2 = getRaw(context, "method=auth.checkToken&token=$rawToken&hash=$hash") ?: return null
        return attr(step2, "auth", "token")
    }

    suspend fun status(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val xml = getRaw(context, "method=system.getInfo") ?: return "❌ SFR Box injoignable (vérifie le réseau/l'adresse dans ⚙)."
        val model = attr(xml, "system", "product_id") ?: "SFR Box"
        val uptime = attr(xml, "system", "uptime") ?: "?"
        return "📡 $model : active depuis ${uptime}s."
    }

    suspend fun wifiSet(context: Context, enable: Boolean): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val token = getToken(context) ?: return "❌ Mot de passe SFR Box incorrect ou box injoignable."
        val method = if (enable) "wlan.enable" else "wlan.disable"
        val result = postRaw(context, "method=$method&token=$token")
        return if (result != null) (if (enable) "✅ Wi-Fi SFR Box activé." else "✅ Wi-Fi SFR Box désactivé.")
            else "❌ Échec de la commande Wi-Fi (box injoignable)."
    }

    suspend fun listDevices(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val token = getToken(context) ?: return "❌ Mot de passe SFR Box incorrect ou box injoignable."
        val xml = getRaw(context, "method=wlan.getClientList&token=$token") ?: return "❌ SFR Box injoignable."
        val clients = allTags(xml, "client")
        if (clients.isEmpty()) return "📱 Aucun appareil Wi-Fi détecté sur la SFR Box."
        val sb = StringBuilder("📱 Appareils Wi-Fi connus de la SFR Box (${clients.size}) :\n\n")
        clients.forEach { tag ->
            val name = Regex("\\bhostname=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)?.ifBlank { null }
                ?: Regex("\\bmac=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "Appareil inconnu"
            val ip = Regex("\\bipaddress=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "?"
            sb.append("• $name — $ip\n")
        }
        return sb.toString().trim()
    }

    suspend fun reboot(context: Context): String {
        if (!isConfigured(context)) return notConfiguredMessage()
        val token = getToken(context) ?: return "❌ Mot de passe SFR Box incorrect ou box injoignable."
        val result = postRaw(context, "method=system.reboot&token=$token")
        return if (result != null) "🔄 Redémarrage de la SFR Box lancé — elle sera injoignable quelques minutes."
            else "❌ Échec du redémarrage (box injoignable)."
    }

    fun portForwardUnavailableMessage(): String =
        "❌ La gestion des redirections de ports n'est pas disponible pour la SFR Box via son API locale " +
            "(contrairement à la Freebox) — configure-la manuellement depuis l'interface d'administration " +
            "de la box (192.168.1.1)."

    fun storageUnavailableMessage(): String =
        "❌ La gestion du disque dur (interne/USB) n'est pas disponible pour la SFR Box via son API locale " +
            "(contrairement à la Freebox)."
}
