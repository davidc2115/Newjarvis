package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Google Agenda via la vraie API REST officielle (Calendar API v3), authentifiée par OAuth --
 * demande explicite de l'utilisateur (remplace CalendarController/CalendarContract, qui ne
 * nécessitait aucune configuration mais que l'utilisateur ne veut plus utiliser).
 *
 * Chaque fonction reçoit un jeton d'accès déjà valide (voir MainActivity.ensureGoogleToken --
 * obtenu via GoogleAccountController.requestAuthorization, jamais géré ici) : ce contrôleur ne
 * fait que des appels REST purs, sans dépendance à une Activity.
 *
 * calendarId "primary" = alias standard de l'agenda principal du compte authentifié (voir
 * developers.google.com/workspace/calendar/api/v3/reference/events).
 */
object GoogleCalendarApiController {

    private const val BASE = "https://www.googleapis.com/calendar/v3"
    private const val TIMEOUT_MS = 15000

    private fun rfc3339(cal: Calendar): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = cal.timeZone
        return sdf.format(cal.time)
    }

    private fun request(url: String, token: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        return code to text
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        return when (code) {
            401 -> "❌ Session Google expirée ou révoquée -- reconnecte-toi dans Réglages > Compte(s) Google. (${detail ?: "401 Unauthorized"})"
            403 -> "❌ Accès refusé par Google -- vérifie que l'API \"Google Calendar API\" est bien activée dans ton projet Cloud Console. (${detail ?: "403 Forbidden"})"
            else -> "❌ Erreur Google Agenda ($code) : ${detail ?: body.take(200)}"
        }
    }

    /**
     * Liste TOUS les calendriers auxquels le compte a accès (principal + partagés/abonnés --
     * voir users.calendarList.list, distinct de calendars.get sur un seul calendrier). Le libellé
     * est laissé vide pour le calendrier principal (jamais affiché en suffixe -- inutile, la
     * grande majorité des événements en viennent) et rempli pour les autres (utile pour
     * distinguer un agenda partagé dans la liste fusionnée). Retourne null si l'appel lui-même
     * échoue (ex. jeton expiré) -- distinct d'une liste vide (compte sans aucun calendrier, cas
     * qui ne devrait jamais arriver en pratique).
     */
    private fun listCalendarEntries(token: String): List<Pair<String, String>>? {
        val (code, body) = request("$BASE/users/me/calendarList", token)
        if (code !in 200..299) return null
        val items = try {
            JSONObject(body).optJSONArray("items")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val cal = items.getJSONObject(i)
            val isPrimary = cal.optBoolean("primary", false)
            val label = if (isPrimary) "" else cal.optString("summary", cal.optString("id"))
            result.add(cal.optString("id") to label)
        }
        return result
    }

    /**
     * Interroge TOUS les calendriers de listCalendarEntries() sur la même période et fusionne
     * les événements (triés par heure de début) -- avant ça, seul "primary" était interrogé,
     * donc les agendas partagés (voir signalement utilisateur : "je ne vois que le planning
     * principal, pas les planning partagés") n'apparaissaient jamais. Un calendrier individuel
     * qui échoue (ex. scope insuffisant sur un agenda partagé précis) est ignoré plutôt que de
     * faire échouer toute la requête -- l'erreur n'est remontée que si AUCUN calendrier n'a pu
     * être lu (ex. jeton expiré, cas qui affecte tous les calendriers pareil).
     */
    private fun fetchMergedEvents(token: String, start: Calendar, end: Calendar): Pair<String?, List<JSONObject>> {
        val timeMinEnc = URLEncoder.encode(rfc3339(start), "UTF-8")
        val timeMaxEnc = URLEncoder.encode(rfc3339(end), "UTF-8")
        val calendars = listCalendarEntries(token) ?: listOf("primary" to "")
        val merged = mutableListOf<JSONObject>()
        var lastError: String? = null
        var anySuccess = false
        for ((id, label) in calendars) {
            val encodedId = URLEncoder.encode(id, "UTF-8")
            val url = "$BASE/calendars/$encodedId/events?timeMin=$timeMinEnc&timeMax=$timeMaxEnc" +
                "&singleEvents=true&orderBy=startTime"
            val (code, body) = request(url, token)
            if (code !in 200..299) {
                lastError = errorMessage(code, body)
                continue
            }
            anySuccess = true
            val items = try {
                JSONObject(body).optJSONArray("items")
            } catch (e: Exception) {
                null
            } ?: continue
            for (i in 0 until items.length()) {
                val ev = items.getJSONObject(i)
                if (label.isNotBlank()) ev.put("_calendarName", label)
                merged.add(ev)
            }
        }
        merged.sortBy { ev ->
            val s = ev.optJSONObject("start")
            s?.optString("dateTime")?.ifBlank { null } ?: s?.optString("date") ?: ""
        }
        return (if (!anySuccess) lastError else null) to merged
    }

    private fun formatEvents(events: List<JSONObject>, emptyMessage: String, title: String): String {
        if (events.isEmpty()) return emptyMessage
        return buildString {
            append("$title\n\n")
            for (ev in events) {
                val summary = ev.optString("summary", "(sans titre)")
                val start = ev.optJSONObject("start")
                val startStr = start?.optString("dateTime")?.ifBlank { null } ?: start?.optString("date")
                val calendarName = ev.optString("_calendarName")
                append("• $summary")
                if (startStr != null) append(" -- $startStr")
                if (calendarName.isNotBlank()) append(" [$calendarName]")
                append("\n")
            }
        }
    }

    suspend fun getTodayEvents(token: String): String = withContext(Dispatchers.IO) {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val (error, events) = fetchMergedEvents(token, start, end)
        if (error != null) return@withContext error
        formatEvents(events, "✅ Rien de prévu aujourd'hui sur ton Agenda Google.", "📅 Aujourd'hui")
    }

    suspend fun getEventsForWeek(token: String, weekOffset: Int): String = withContext(Dispatchers.IO) {
        val start = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, weekOffset)
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
        val (error, events) = fetchMergedEvents(token, start, end)
        if (error != null) return@withContext error
        val label = when {
            weekOffset > 0 -> "📅 Semaine prochaine"
            weekOffset < 0 -> "📅 Semaine dernière"
            else -> "📅 Cette semaine"
        }
        formatEvents(events, "✅ Rien de prévu cette semaine-là sur ton Agenda Google.", label)
    }

    /**
     * Planning d'UN jour precis (ex. "demain", "le 15 septembre") -- distinct de
     * getTodayEvents (toujours aujourd'hui) et getEventsForWeek (semaine entiere) : voir
     * CommandInterpreter.Command.EventsForDate + CalendarController.resolveLocalDate pour la
     * resolution de la date a partir du texte utilisateur, faite avant d'appeler cette fonction.
     */
    suspend fun getEventsForDate(token: String, date: LocalDate): String = withContext(Dispatchers.IO) {
        val start = Calendar.getInstance().apply {
            set(date.year, date.monthValue - 1, date.dayOfMonth, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val (error, events) = fetchMergedEvents(token, start, end)
        if (error != null) return@withContext error
        val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
        val label = "\uD83D\uDCC5 " + date.format(formatter).replaceFirstChar { it.uppercase() }
        formatEvents(events, "\u2705 Rien de pr\u00e9vu ce jour-l\u00e0 sur ton Agenda Google.", label)
    }

    suspend fun getUpcomingEvents(token: String, days: Int = 7): String = withContext(Dispatchers.IO) {
        val start = Calendar.getInstance()
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, days) }
        val (error, events) = fetchMergedEvents(token, start, end)
        if (error != null) return@withContext error
        formatEvents(events, "✅ Rien de prévu dans les $days prochains jours sur ton Agenda Google.", "📅 À venir")
    }

    suspend fun getCalendarList(token: String): String = withContext(Dispatchers.IO) {
        val (code, body) = request("$BASE/users/me/calendarList", token)
        if (code !in 200..299) return@withContext errorMessage(code, body)
        val obj = JSONObject(body)
        val items = obj.optJSONArray("items") ?: return@withContext "Aucun calendrier trouvé."
        buildString {
            append("📋 ${items.length()} calendrier(s) :\n\n")
            for (i in 0 until items.length()) {
                val cal = items.getJSONObject(i)
                append("• ${cal.optString("summary")}")
                if (cal.optBoolean("primary", false)) append(" (principal)")
                append("\n")
            }
        }
    }

    suspend fun createEvent(token: String, title: String, startMillis: Long, endMillis: Long): String = withContext(Dispatchers.IO) {
        val tz = TimeZone.getDefault().id
        val startCal = Calendar.getInstance().apply { timeInMillis = startMillis }
        val endCal = Calendar.getInstance().apply { timeInMillis = endMillis }
        val payload = JSONObject().apply {
            put("summary", title)
            put("start", JSONObject().apply { put("dateTime", rfc3339(startCal)); put("timeZone", tz) })
            put("end", JSONObject().apply { put("dateTime", rfc3339(endCal)); put("timeZone", tz) })
        }
        val (code, body) = request("$BASE/calendars/primary/events", token, "POST", payload.toString())
        if (code !in 200..299) return@withContext errorMessage(code, body)
        val created = JSONObject(body)
        "📅 Événement « $title » créé dans ton Agenda Google.\n${created.optString("htmlLink")}"
    }

    /** Cherche l'événement à venir le plus proche dont le titre contient [query] et le supprime. */
    suspend fun deleteEventByTitle(token: String, query: String): String = withContext(Dispatchers.IO) {
        val start = Calendar.getInstance()
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 3) }
        val url = "$BASE/calendars/primary/events?timeMin=${URLEncoder.encode(rfc3339(start), "UTF-8")}" +
            "&timeMax=${URLEncoder.encode(rfc3339(end), "UTF-8")}&singleEvents=true&orderBy=startTime" +
            "&q=${URLEncoder.encode(query, "UTF-8")}"
        val (code, body) = request(url, token)
        if (code !in 200..299) return@withContext errorMessage(code, body)
        val items = JSONObject(body).optJSONArray("items")
        if (items == null || items.length() == 0) return@withContext "🔍 Aucun événement à venir trouvé pour « $query »."

        val target = items.getJSONObject(0)
        val eventId = target.optString("id")
        val summary = target.optString("summary", "(sans titre)")
        val (delCode, delBody) = request("$BASE/calendars/primary/events/$eventId", token, "DELETE")
        if (delCode !in 200..299 && delCode != 410) return@withContext errorMessage(delCode, delBody)
        "🗑️ Événement « $summary » supprimé de ton Agenda Google."
    }
}
