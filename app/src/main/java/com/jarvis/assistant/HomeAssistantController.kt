package com.jarvis.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HomeAssistantController — intégration Home Assistant (REST API).
 *
 * Nécessite dans Home Assistant : Paramètres → Compte → Sécurité →
 * "Jetons d'accès à long terme" → créer un jeton, puis le coller dans
 * JARVIS (⚙ → Domotique). L'URL doit être accessible depuis le téléphone
 * (ex: http://192.168.1.50:8123 en local sur le même Wi-Fi, ou une URL
 * distante si tu as configuré Nabu Casa / un reverse-proxy).
 *
 * Documentation officielle : https://developers.home-assistant.io/docs/api/rest/
 */
object HomeAssistantController {

    data class Entity(
        val entityId: String,
        val state: String,
        val friendlyName: String,
        val domain: String,
        val unit: String?
    )

    data class ActionResult(val success: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Domaines HA les plus pertinents à afficher/contrôler depuis le mobile.
    private val CONTROLLABLE_DOMAINS = setOf(
        "light", "switch", "climate", "cover", "fan", "lock", "media_player",
        "vacuum", "scene", "script", "input_boolean", "siren", "humidifier"
    )
    private val READONLY_DOMAINS = setOf("sensor", "binary_sensor", "person", "device_tracker", "weather")

    fun isConfigured(context: Context): Boolean =
        Prefs.getHaUrl(context).isNotBlank() && Prefs.getHaToken(context).isNotBlank()

    private fun baseUrl(context: Context): String = Prefs.getHaUrl(context).trimEnd('/')

    private fun authedRequest(context: Context, path: String): Request.Builder =
        Request.Builder()
            .url("${baseUrl(context)}$path")
            .addHeader("Authorization", "Bearer ${Prefs.getHaToken(context)}")
            .addHeader("Content-Type", "application/json")

    /** Vérifie la connexion (GET /api/ → {"message":"API running."}). */
    fun testConnection(context: Context): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ URL ou jeton Home Assistant manquant.")
        return try {
            val request = authedRequest(context, "/api/").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Connecté à Home Assistant.")
                else ActionResult(false, "❌ Échec de connexion (HTTP ${response.code}). Vérifie l'URL et le jeton.")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Impossible de joindre Home Assistant : ${e.message}")
        }
    }

    /** Récupère tous les états (/api/states), limités aux domaines pertinents pour l'UI mobile. */
    fun getAllEntities(context: Context): List<Entity> {
        if (!isConfigured(context)) return emptyList()
        return try {
            val request = authedRequest(context, "/api/states").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val entityId = obj.optString("entity_id")
                    val domain = entityId.substringBefore(".")
                    if (domain !in CONTROLLABLE_DOMAINS && domain !in READONLY_DOMAINS) return@mapNotNull null
                    val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                    Entity(
                        entityId = entityId,
                        state = obj.optString("state", "unknown"),
                        friendlyName = attrs.optString("friendly_name", entityId),
                        domain = domain,
                        unit = attrs.optString("unit_of_measurement", null.toString()).takeIf { it != "null" }
                    )
                }.sortedBy { it.friendlyName.lowercase() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Recherche floue d'une entité par nom (pour les commandes vocales : "allume le salon"). */
    fun findEntity(context: Context, query: String): Entity? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val all = getAllEntities(context)
        return all.firstOrNull { it.friendlyName.lowercase() == q }
            ?: all.firstOrNull { it.friendlyName.lowercase().contains(q) }
            ?: all.firstOrNull { it.entityId.lowercase().contains(q) }
    }

    /** Appelle un service HA générique : POST /api/services/{domain}/{service}. */
    fun callService(context: Context, domain: String, service: String, entityId: String, extra: Map<String, Any> = emptyMap()): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ Home Assistant n'est pas configuré (⚙ → Domotique).")
        return try {
            val payload = JSONObject().put("entity_id", entityId)
            extra.forEach { (k, v) ->
                when (v) {
                    is Int -> payload.put(k, v)
                    is Double -> payload.put(k, v)
                    is Boolean -> payload.put(k, v)
                    else -> payload.put(k, v.toString())
                }
            }
            val request = authedRequest(context, "/api/services/$domain/$service")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Commande envoyée.")
                else ActionResult(false, "❌ Home Assistant a refusé la commande (HTTP ${response.code}).")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Erreur de communication avec Home Assistant : ${e.message}")
        }
    }

    /** Allume/éteint/bascule une entité, en devinant le bon service selon son domaine. */
    fun turnOn(context: Context, entityId: String): ActionResult = domainAction(context, entityId, "turn_on")
    fun turnOff(context: Context, entityId: String): ActionResult = domainAction(context, entityId, "turn_off")
    fun toggle(context: Context, entityId: String): ActionResult = domainAction(context, entityId, "toggle")

    private fun domainAction(context: Context, entityId: String, action: String): ActionResult {
        val domain = entityId.substringBefore(".")
        // "lock" n'a pas de turn_on/turn_off — il a lock/unlock. "cover" a open_cover/close_cover.
        val service = when {
            domain == "lock" && action == "turn_on" -> "lock"
            domain == "lock" && action == "turn_off" -> "unlock"
            domain == "cover" && action == "turn_on" -> "open_cover"
            domain == "cover" && action == "turn_off" -> "close_cover"
            domain == "vacuum" && action == "turn_on" -> "start"
            domain == "vacuum" && action == "turn_off" -> "stop"
            else -> action
        }
        return callService(context, domain, service, entityId)
    }

    /**
     * Renomme une entité DANS Home Assistant lui-même (registre d'entités,
     * via l'API WebSocket — pas de route REST équivalente). Le nouveau nom
     * apparaît alors partout dans Home Assistant, pas seulement dans JARVIS.
     */
    suspend fun renameEntity(context: Context, entityId: String, newName: String): ActionResult {
        if (newName.isBlank()) return ActionResult(false, "❌ Le nouveau nom ne peut pas être vide.")
        val command = JSONObject()
            .put("type", "config/entity_registry/update")
            .put("entity_id", entityId)
            .put("name", newName)
        val result = HomeAssistantWsClient.sendCommand(context, command)
        return if (result.optBoolean("success", false)) {
            ActionResult(true, "✅ Entité renommée en « $newName ».")
        } else {
            val errMsg = result.optJSONObject("error")?.optString("message")
                ?: result.optString("error", "erreur inconnue")
            ActionResult(false, "❌ Échec du renommage : $errMsg")
        }
    }

    /**
     * Supprime une entité du registre Home Assistant (API WebSocket).
     * ⚠️ Si l'entité est toujours fournie par une intégration active (ex:
     * une ampoule physiquement connectée), elle réapparaîtra probablement
     * au prochain redémarrage de Home Assistant — la suppression de
     * registre sert surtout à nettoyer des entités orphelines/désactivées.
     */
    suspend fun deleteEntity(context: Context, entityId: String): ActionResult {
        val command = JSONObject()
            .put("type", "config/entity_registry/remove")
            .put("entity_id", entityId)
        val result = HomeAssistantWsClient.sendCommand(context, command)
        return if (result.optBoolean("success", false)) {
            ActionResult(true, "✅ Entité supprimée du registre Home Assistant.")
        } else {
            val errMsg = result.optJSONObject("error")?.optString("message")
                ?: result.optString("error", "erreur inconnue")
            ActionResult(false, "❌ Échec de la suppression : $errMsg")
        }
    }

    /**
     * Parcourt les sources média exposées par un media_player Home Assistant
     * (dossiers locaux, partages réseau/NAS configurés dans HA...) — LECTURE
     * SEULE : c'est la seule API de "stockage" que Home Assistant expose
     * réellement (pas de create/rename/delete de fichiers via HA).
     * https://developers.home-assistant.io/docs/core/websocket_api#browse-media
     */
    suspend fun browseMedia(
        context: Context,
        entityId: String,
        mediaContentId: String? = null,
        mediaContentType: String? = null
    ): String {
        val command = JSONObject().put("type", "media_player/browse_media").put("entity_id", entityId)
        if (!mediaContentId.isNullOrBlank()) command.put("media_content_id", mediaContentId)
        if (!mediaContentType.isNullOrBlank()) command.put("media_content_type", mediaContentType)

        val result = HomeAssistantWsClient.sendCommand(context, command)
        if (!result.optBoolean("success", false)) {
            val err = result.optJSONObject("error")?.optString("message") ?: result.optString("error", "erreur inconnue")
            return "❌ Impossible de parcourir les médias : $err"
        }

        val res = result.optJSONObject("result") ?: return "❌ Réponse vide de Home Assistant."
        val title = res.optString("title", "Racine")
        val children = res.optJSONArray("children") ?: JSONArray()
        if (children.length() == 0) return "📂 « $title » est vide (ou ne contient rien de navigable)."

        val sb = StringBuilder("📂 **$title** :\n\n")
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            val icon = if (child.optBoolean("can_expand", false)) "📁" else "🎵"
            sb.append("$icon ${child.optString("title", "?")}\n")
        }
        return sb.toString().trim()
    }

    /** Formatte la liste des entités en texte lisible pour l'IA / la voix. */
    fun summarize(context: Context, filter: String = ""): String {
        if (!isConfigured(context)) {
            return "❌ Home Assistant n'est pas configuré. Va dans ⚙ → Domotique pour renseigner l'URL et le jeton d'accès."
        }
        val entities = getAllEntities(context)
        if (entities.isEmpty()) return "❌ Aucune entité récupérée depuis Home Assistant (vérifie la connexion)."

        val filtered = if (filter.isBlank()) entities else entities.filter {
            it.friendlyName.lowercase().contains(filter.lowercase()) ||
                it.domain.lowercase().contains(filter.lowercase())
        }
        if (filtered.isEmpty()) return "🏠 Aucun appareil Home Assistant ne correspond à « $filter »."

        val byDomain = filtered.groupBy { it.domain }
        val sb = StringBuilder("🏠 **Maison connectée** :\n\n")
        byDomain.forEach { (domain, list) ->
            sb.append("• ${domainLabel(domain)} :\n")
            list.take(20).forEach { e ->
                val unitStr = e.unit?.let { " $it" } ?: ""
                sb.append("   - ${e.friendlyName} : ${e.state}$unitStr\n")
            }
        }
        return sb.toString().trim()
    }

    private fun domainLabel(domain: String): String = when (domain) {
        "light" -> "💡 Lumières"
        "switch" -> "🔌 Prises / interrupteurs"
        "climate" -> "🌡️ Chauffage / clim"
        "cover" -> "🪟 Volets"
        "fan" -> "🌀 Ventilateurs"
        "lock" -> "🔒 Serrures"
        "media_player" -> "🎵 Lecteurs multimédia"
        "vacuum" -> "🧹 Aspirateurs robots"
        "sensor" -> "📊 Capteurs"
        "binary_sensor" -> "🔘 Capteurs binaires"
        "person" -> "🧑 Personnes"
        "device_tracker" -> "📍 Appareils suivis"
        "weather" -> "⛅ Météo"
        "scene" -> "🎬 Scènes"
        "script" -> "📜 Scripts"
        else -> domain
    }
}
