package com.jarvis.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HomeAssistantController — intégration Home Assistant (REST + WebSocket API),
 * conçue comme le point de contrôle CENTRAL de la maison depuis JARVIS : c'est
 * délibérément par ici que passe tout pilotage réseau qui doit aussi fonctionner
 * hors du réseau local (là où un accès direct au téléphone/à un appareil précis
 * ne fonctionnerait plus), tant qu'une URL distante Home Assistant est configurée.
 *
 * Nécessite dans Home Assistant : Paramètres → Compte → Sécurité →
 * "Jetons d'accès à long terme" → créer un jeton, puis le coller dans
 * JARVIS (⚙ → Domotique). Deux URLs peuvent être configurées :
 *  • URL locale (ex: http://192.168.1.50:8123) — utilisée en priorité, la plus rapide.
 *  • URL distante (ex: https://xxxx.ui.nabu.casa, ou un DDNS/reverse-proxy perso) —
 *    utilisée automatiquement en repli si l'URL locale est injoignable (donc
 *    aussi hors du Wi-Fi domestique). Sans URL distante configurée, JARVIS reste
 *    honnêtement limité au réseau local pour la domotique, comme avant.
 *
 * Documentation officielle : https://developers.home-assistant.io/docs/api/rest/
 */
object HomeAssistantController {

    data class Entity(
        val entityId: String,
        val state: String,
        val friendlyName: String,
        val domain: String,
        val unit: String?,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class ActionResult(val success: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Domaines HA les plus pertinents à afficher/contrôler depuis le mobile.
    // "automation" et "script" inclus : turn_on/turn_off active/désactive une
    // automatisation existante, et automation.trigger permet de la déclencher
    // manuellement — le pilotage réel qu'expose honnêtement l'API HA.
    // Élargi pour couvrir les intégrations tierces courantes (télé, poêle à granulés...) :
    // "remote" (télécommandes — ex: intégration Samsung TV/LG webOS/Android TV, envoi de
    // touches via remote.send_command), "select"/"input_select" (menus déroulants — ex:
    // mode d'un poêle à granulés : Auto/Manuel/Eco/Chrono), "number"/"input_number"
    // (curseurs — ex: niveau de puissance d'un poêle), "valve", "water_heater", "button",
    // "alarm_control_panel". Sans ces domaines ici, leurs entités seraient invisibles pour
    // JARVIS (filtrées silencieusement) même si ha_call_service pourrait techniquement les
    // piloter — c'était un vrai trou dans le "contrôle total" pour tout appareil qui n'utilise
    // pas un domaine HA "classique".
    private val CONTROLLABLE_DOMAINS = setOf(
        "light", "switch", "climate", "cover", "fan", "lock", "media_player",
        "vacuum", "scene", "script", "input_boolean", "siren", "humidifier", "automation",
        "remote", "select", "input_select", "number", "input_number", "valve",
        "water_heater", "button", "alarm_control_panel"
    )
    private val READONLY_DOMAINS = setOf("sensor", "binary_sensor", "person", "device_tracker", "weather")

    fun isConfigured(context: Context): Boolean =
        (Prefs.getHaUrl(context).isNotBlank() || Prefs.getHaRemoteUrl(context).isNotBlank()) &&
            Prefs.getHaToken(context).isNotBlank()

    private fun authedRequestBuilder(baseUrl: String, token: String, path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")

    /**
     * Exécute une requête REST Home Assistant en essayant d'abord l'URL locale
     * (rapide, réseau Wi-Fi domestique), puis l'URL distante de secours si elle
     * est configurée ET que l'URL locale est injoignable ou échoue — c'est ce
     * mécanisme qui permet de piloter la maison même hors réseau local. Si
     * aucune des deux ne fonctionne, l'erreur réelle de la dernière tentative
     * remonte telle quelle (pas de faux succès).
     */
    private fun executeWithFallback(
        context: Context,
        path: String,
        configure: (Request.Builder) -> Request.Builder = { it }
    ): Response {
        val token = Prefs.getHaToken(context)
        val localUrl = Prefs.getHaUrl(context).trimEnd('/')
        val remoteUrl = Prefs.getHaRemoteUrl(context).trimEnd('/')

        if (localUrl.isBlank() && remoteUrl.isBlank()) {
            throw IOException("Aucune URL Home Assistant configurée (⚙ → Domotique).")
        }

        if (localUrl.isNotBlank()) {
            try {
                val request = configure(authedRequestBuilder(localUrl, token, path)).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful || remoteUrl.isBlank() || remoteUrl == localUrl) return response
                response.close()
            } catch (e: Exception) {
                if (remoteUrl.isBlank() || remoteUrl == localUrl) throw e
            }
        }
        val request = configure(authedRequestBuilder(remoteUrl, token, path)).build()
        return client.newCall(request).execute()
    }

    /** Vérifie la connexion (GET /api/ → {"message":"API running."}). */
    fun testConnection(context: Context): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ URL ou jeton Home Assistant manquant.")
        return try {
            executeWithFallback(context, "/api/").use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Connecté à Home Assistant.")
                else ActionResult(false, "❌ Échec de connexion (HTTP ${response.code}). Vérifie l'URL et le jeton.")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Impossible de joindre Home Assistant (local et distant) : ${e.message}")
        }
    }

    /** Récupère tous les états (/api/states), limités aux domaines pertinents pour l'UI mobile. */
    fun getAllEntities(context: Context): List<Entity> {
        if (!isConfigured(context)) return emptyList()
        return try {
            executeWithFallback(context, "/api/states").use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val entityId = obj.optString("entity_id")
                    val domain = entityId.substringBefore(".")
                    if (domain !in CONTROLLABLE_DOMAINS && domain !in READONLY_DOMAINS) return@mapNotNull null
                    val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                    // Latitude/longitude uniquement pour "person" : c'est le point de localisation
                    // précis exposé par HA sous une entité person.xxx (GPS du téléphone/tracker
                    // associé). HA n'affiche par défaut que le nom de la ZONE (ex: "home",
                    // "not_home") comme état — la position réelle est bien là, dans les attributs,
                    // juste pas montrée par défaut. On ne le fait QUE pour "person" (jamais
                    // device_tracker, potentiellement nombreux) pour ne pas ralentir un scan général.
                    val lat = if (domain == "person") attrs.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() } else null
                    val lon = if (domain == "person") attrs.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() } else null
                    Entity(
                        entityId = entityId,
                        state = obj.optString("state", "unknown"),
                        friendlyName = attrs.optString("friendly_name", entityId),
                        domain = domain,
                        unit = attrs.optString("unit_of_measurement", null.toString()).takeIf { it != "null" },
                        latitude = lat,
                        longitude = lon
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
    fun callService(context: Context, domain: String, service: String, entityId: String? = null, extra: Map<String, Any> = emptyMap()): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ Home Assistant n'est pas configuré (⚙ → Domotique).")
        return try {
            val payload = JSONObject()
            if (!entityId.isNullOrBlank()) payload.put("entity_id", entityId)
            extra.forEach { (k, v) ->
                when (v) {
                    is Int -> payload.put(k, v)
                    is Double -> payload.put(k, v)
                    is Boolean -> payload.put(k, v)
                    else -> payload.put(k, v.toString())
                }
            }
            executeWithFallback(context, "/api/services/$domain/$service") {
                it.post(payload.toString().toRequestBody(JSON))
            }.use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Commande envoyée.")
                else ActionResult(false, "❌ Home Assistant a refusé la commande (HTTP ${response.code}) : ${response.body?.string()?.take(200) ?: ""}")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Erreur de communication avec Home Assistant : ${e.message}")
        }
    }

    /**
     * Appelle N'IMPORTE QUEL service Home Assistant sur N'IMPORTE QUEL domaine, avec des
     * données arbitraires — le contrôle le plus étendu que l'API REST de HA autorise
     * honnêtement (c'est littéralement ce que le tableau de bord HA utilise en interne
     * pour chaque bouton/action). Sert à couvrir tout ce que les actions dédiées
     * (ha_turn_on/ha_set/etc.) ne couvrent pas explicitement.
     */
    fun callServiceRaw(context: Context, domain: String, service: String, dataJson: JSONObject): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ Home Assistant n'est pas configuré (⚙ → Domotique).")
        if (domain.isBlank() || service.isBlank()) return ActionResult(false, "❌ Domaine ou service manquant.")
        return try {
            executeWithFallback(context, "/api/services/$domain/$service") {
                it.post(dataJson.toString().toRequestBody(JSON))
            }.use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Service $domain.$service appelé avec succès.")
                else ActionResult(false, "❌ Home Assistant a refusé l'appel (HTTP ${response.code}) : ${response.body?.string()?.take(300) ?: ""}")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Erreur de communication avec Home Assistant : ${e.message}")
        }
    }

    // ─── Automatisations (API de configuration REST) ───────────────────────────
    // https://developers.home-assistant.io/docs/api/rest/#config-api

    /** Liste toutes les automatisations avec leur configuration réelle (déclencheurs/actions), pas juste leur état. */
    fun listAutomations(context: Context): String {
        if (!isConfigured(context)) return "❌ Home Assistant n'est pas configuré (⚙ → Domotique)."
        return try {
            executeWithFallback(context, "/api/config/automation/config").use { response ->
                if (!response.isSuccessful) return "❌ Impossible de récupérer les automatisations (HTTP ${response.code})."
                val body = response.body?.string() ?: return "❌ Réponse vide."
                val arr = JSONArray(body)
                if (arr.length() == 0) return "📜 Aucune automatisation configurée dans Home Assistant."
                val sb = StringBuilder("📜 **Automatisations Home Assistant** :\n\n")
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val id = a.optString("id", "?")
                    val alias = a.optString("alias", "(sans nom)")
                    sb.append("• $alias (id: $id)\n")
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    /**
     * Crée une nouvelle automatisation, ou remplace entièrement celle d'[id] si elle
     * existe déjà — [configJson] doit contenir la configuration complète au format HA
     * (alias, trigger, condition?, action). C'est la même route que l'éditeur
     * d'automatisations intégré de Home Assistant utilise.
     */
    fun createOrUpdateAutomation(context: Context, id: String, configJson: JSONObject): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ Home Assistant n'est pas configuré (⚙ → Domotique).")
        if (id.isBlank()) return ActionResult(false, "❌ Identifiant d'automatisation manquant.")
        return try {
            executeWithFallback(context, "/api/config/automation/config/$id") {
                it.post(configJson.toString().toRequestBody(JSON))
            }.use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Automatisation « $id » enregistrée.")
                else ActionResult(false, "❌ Home Assistant a refusé l'automatisation (HTTP ${response.code}) : ${response.body?.string()?.take(300) ?: ""}")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Erreur : ${e.message}")
        }
    }

    fun deleteAutomation(context: Context, id: String): ActionResult {
        if (!isConfigured(context)) return ActionResult(false, "❌ Home Assistant n'est pas configuré (⚙ → Domotique).")
        if (id.isBlank()) return ActionResult(false, "❌ Identifiant d'automatisation manquant.")
        return try {
            executeWithFallback(context, "/api/config/automation/config/$id") { it.delete() }.use { response ->
                if (response.isSuccessful) ActionResult(true, "✅ Automatisation « $id » supprimée.")
                else ActionResult(false, "❌ Échec de la suppression (HTTP ${response.code}).")
            }
        } catch (e: Exception) {
            ActionResult(false, "❌ Erreur : ${e.message}")
        }
    }

    /** Déclenche manuellement une automatisation, sans attendre son déclencheur normal. */
    fun triggerAutomation(context: Context, entityId: String): ActionResult =
        callService(context, "automation", "trigger", entityId)

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
     * Ajuste une valeur précise sur une entité — au-delà du simple allumer/éteindre :
     * luminosité et couleur d'une lumière, température d'un thermostat, volume d'un
     * lecteur média, position d'un volet, vitesse d'un ventilateur. Devine le bon
     * service Home Assistant selon le domaine de l'entité.
     *
     * Ceci couvre la quasi-totalité des demandes courantes de "réglage" — mais reste
     * différent d'une reconfiguration profonde (créer une automatisation, ajouter une
     * intégration, éditer le YAML) : Home Assistant n'expose pas ça via une API REST/WS
     * simple et sûre à piloter depuis un mobile, ce n'est donc pas fait ici pour éviter
     * de casser une configuration existante sans supervision.
     */
    fun setValue(
        context: Context,
        entityId: String,
        brightnessPct: Int? = null,
        colorName: String? = null,
        temperature: Double? = null,
        volumePct: Int? = null,
        positionPct: Int? = null,
        speedPct: Int? = null,
        hvacMode: String? = null,
        presetMode: String? = null,
        fanMode: String? = null,
        option: String? = null,
        numberValue: Double? = null,
        remoteCommand: String? = null,
        source: String? = null
    ): ActionResult {
        val domain = entityId.substringBefore(".")
        return when (domain) {
            "light" -> {
                val extra = mutableMapOf<String, Any>()
                brightnessPct?.let { extra["brightness_pct"] = it.coerceIn(0, 100) }
                colorName?.let { extra["color_name"] = it }
                if (extra.isEmpty()) return ActionResult(false, "❌ Précise une luminosité (0-100) ou une couleur pour cette lumière.")
                callService(context, "light", "turn_on", entityId, extra)
            }
            "climate" -> {
                // Un poêle à granulés/thermostat exposé en "climate" propose souvent PLUSIEURS
                // réglages distincts (pas juste la température) : hvac_mode (ex: off/heat/auto),
                // preset_mode (souvent utilisé pour le niveau de puissance : Eco/Comfort/1-5...),
                // fan_mode. On applique chaque paramètre fourni, dans l'ordre logique
                // (mode général d'abord), et on rapporte le premier échec réel le cas échéant.
                val actions = buildList<Pair<String, Map<String, Any>>> {
                    hvacMode?.let { add("set_hvac_mode" to mapOf("hvac_mode" to it)) }
                    presetMode?.let { add("set_preset_mode" to mapOf("preset_mode" to it)) }
                    fanMode?.let { add("set_fan_mode" to mapOf("fan_mode" to it)) }
                    temperature?.let { add("set_temperature" to mapOf("temperature" to it)) }
                }
                if (actions.isEmpty()) {
                    return ActionResult(
                        false,
                        "❌ Précise ce que tu veux régler pour « $entityId » : température, mode (hvacMode : off/heat/auto...), " +
                            "préréglage (presetMode : eco/comfort/niveau de puissance...) ou vitesse de ventilation (fanMode)."
                    )
                }
                var last = ActionResult(true, "")
                for ((service, extra) in actions) {
                    last = callService(context, "climate", service, entityId, extra)
                    if (!last.success) return last
                }
                ActionResult(true, "✅ Réglage(s) appliqué(s) sur « $entityId ».")
            }
            "media_player" -> {
                val actions = buildList<Pair<String, Map<String, Any>>> {
                    volumePct?.let { add("volume_set" to mapOf("volume_level" to (it.coerceIn(0, 100) / 100.0))) }
                    source?.let { add("select_source" to mapOf("source" to it)) }
                }
                if (actions.isEmpty()) return ActionResult(false, "❌ Précise le volume souhaité (0 à 100) ou la source à sélectionner (ex: \"HDMI 1\").")
                var last = ActionResult(true, "")
                for ((service, extra) in actions) {
                    last = callService(context, "media_player", service, entityId, extra)
                    if (!last.success) return last
                }
                ActionResult(true, "✅ Réglage(s) appliqué(s) sur « $entityId ».")
            }
            "cover" -> {
                if (positionPct == null) return ActionResult(false, "❌ Précise la position du volet souhaitée (0 = fermé, 100 = ouvert).")
                callService(context, "cover", "set_cover_position", entityId, mapOf("position" to positionPct.coerceIn(0, 100)))
            }
            "fan" -> {
                if (speedPct == null) return ActionResult(false, "❌ Précise la vitesse du ventilateur souhaitée (0 à 100).")
                callService(context, "fan", "set_percentage", entityId, mapOf("percentage" to speedPct.coerceIn(0, 100)))
            }
            // "remote" : télécommandes réseau exposées par Home Assistant pour de nombreuses
            // télés (Samsung, LG webOS, Android TV...) — remoteCommand = nom exact de la
            // touche tel qu'attendu par l'intégration HA concernée (ex: "KEY_VOLUP",
            // "KEY_HOME", "KEY_SOURCE" pour Samsung ; varie selon l'intégration, à vérifier
            // dans Home Assistant → Outils de développement → Actions si le nom exact est incertain).
            "remote" -> {
                if (remoteCommand.isNullOrBlank()) return ActionResult(false, "❌ Précise la commande à envoyer à la télécommande (ex: \"KEY_VOLUP\", \"KEY_HOME\").")
                callService(context, "remote", "send_command", entityId, mapOf("command" to remoteCommand))
            }
            "select", "input_select" -> {
                if (option.isNullOrBlank()) return ActionResult(false, "❌ Précise l'option à sélectionner pour « $entityId ».")
                callService(context, domain, "select_option", entityId, mapOf("option" to option))
            }
            "number", "input_number" -> {
                if (numberValue == null) return ActionResult(false, "❌ Précise la valeur numérique à appliquer pour « $entityId ».")
                callService(context, domain, "set_value", entityId, mapOf("value" to numberValue))
            }
            "water_heater" -> {
                if (temperature == null) return ActionResult(false, "❌ Précise la température souhaitée pour ce chauffe-eau.")
                callService(context, "water_heater", "set_temperature", entityId, mapOf("temperature" to temperature))
            }
            "valve" -> {
                if (positionPct == null) return ActionResult(false, "❌ Précise la position de la vanne souhaitée (0 = fermée, 100 = ouverte).")
                callService(context, "valve", "set_valve_position", entityId, mapOf("position" to positionPct.coerceIn(0, 100)))
            }
            else -> ActionResult(false, "❌ « $entityId » ($domain) ne propose pas de réglage précis depuis JARVIS — seulement allumer/éteindre/basculer (ou utilise ha_call_service pour un service HA précis).")
        }
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

    /**
     * Formatte la liste des entités en texte lisible pour l'IA / la voix.
     *
     * @param filter Filtre par nom (ex: le prénom d'une personne) — sous-chaîne insensible à la casse.
     * @param domain Filtre par domaine HA exact (ex: "person" pour ne récupérer QUE la localisation,
     *   "sensor" pour ne récupérer QUE les capteurs). Laisser vide pour ne pas filtrer par domaine.
     *
     * IMPORTANT : sans filtre de domaine, une recherche par nom seul (ex: "Marie") remonte TOUTES
     * les entités dont le nom contient "Marie" — l'entité de localisation (person.marie) MAIS AUSSI
     * d'éventuels capteurs annexes (batterie du téléphone, nombre de pas, etc.) si Home Assistant les
     * nomme avec le même prénom. Pour répondre précisément à une question ciblée ("où est Marie ?"),
     * il faut préciser domain="person" afin de n'obtenir QUE l'information demandée.
     */
    fun summarize(context: Context, filter: String = "", domain: String = ""): String {
        if (!isConfigured(context)) {
            return "❌ Home Assistant n'est pas configuré. Va dans ⚙ → Domotique pour renseigner l'URL et le jeton d'accès."
        }
        val entities = getAllEntities(context)
        if (entities.isEmpty()) return "❌ Aucune entité récupérée depuis Home Assistant (vérifie la connexion)."

        val byDomainFirst = if (domain.isBlank()) entities else entities.filter {
            it.domain.equals(domain.trim(), ignoreCase = true)
        }
        val filtered = if (filter.isBlank()) byDomainFirst else byDomainFirst.filter {
            it.friendlyName.lowercase().contains(filter.lowercase()) ||
                (domain.isBlank() && it.domain.lowercase().contains(filter.lowercase()))
        }
        if (filtered.isEmpty()) {
            val quoi = listOfNotNull(filter.takeIf { it.isNotBlank() }, domain.takeIf { it.isNotBlank() }).joinToString(" / ")
            return "🏠 Aucun appareil Home Assistant ne correspond à « $quoi »."
        }

        val byDomain = filtered.groupBy { it.domain }
        val sb = StringBuilder("🏠 **Maison connectée** :\n\n")
        byDomain.forEach { (d, list) ->
            sb.append("• ${domainLabel(d)} :\n")
            list.take(20).forEach { e ->
                val unitStr = e.unit?.let { " $it" } ?: ""
                // Pour une "person", Home Assistant ne renvoie par défaut que le nom de la
                // ZONE comme état (ex: "home", "not_home", ou le nom d'une zone perso) — pas
                // une adresse. Les coordonnées GPS précises existent bien dans les attributs
                // de l'entité ; on les convertit ici en adresse réelle via le même moteur de
                // géocodage inverse déjà utilisé pour la position du téléphone (Geocoder
                // Android), pour donner une réponse concrète plutôt que juste "à la maison".
                val addressStr = if (d == "person" && e.latitude != null && e.longitude != null) {
                    val address = LocationController.reverseGeocode(context, e.latitude, e.longitude)
                    " ($address)"
                } else ""
                sb.append("   - ${e.friendlyName} : ${e.state}$unitStr$addressStr\n")
            }
        }

        // Si une grande partie des entités renvoie "unknown"/"unavailable" alors que
        // Home Assistant lui-même affiche des valeurs normales, ce n'est presque jamais
        // un bug de JARVIS : /api/states renvoie fidèlement ce que Home Assistant lui a
        // répondu à cet instant précis. Cause la plus fréquente et vérifiable : le jeton
        // d'accès à long terme a été créé depuis un compte UTILISATEUR restreint (pas
        // administrateur) — Home Assistant peut alors renvoyer un état masqué/non à jour
        // pour des entités hors des zones autorisées à ce compte, même si l'administrateur
        // les voit normalement dans sa propre session.
        val unknownCount = filtered.count { it.state.equals("unknown", true) || it.state.equals("unavailable", true) }
        if (filtered.isNotEmpty() && unknownCount.toDouble() / filtered.size > 0.4) {
            sb.append(
                "\n⚠️ $unknownCount appareil(s) sur ${filtered.size} renvoient un état « unknown »/« unavailable » " +
                    "alors que Home Assistant les affiche normalement de ton côté. Causes les plus probables, par ordre de fréquence :\n" +
                    "   1. Le jeton d'accès à long terme utilisé par JARVIS a été créé depuis un compte non-administrateur " +
                    "(recrée-le depuis Profil → tout en bas → « Jetons d'accès à long terme », en étant connecté avec le compte admin).\n" +
                    "   2. L'URL configurée (⚙ → Domotique) pointe vers une autre instance/un cache (ex: URL Nabu Casa mise en " +
                    "cache par un proxy) que celle affichée dans ton navigateur.\n" +
                    "   3. Home Assistant vient de redémarrer et ces entités n'ont pas encore été repolies (rare si tu viens de vérifier à l'instant).\n"
            )
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
