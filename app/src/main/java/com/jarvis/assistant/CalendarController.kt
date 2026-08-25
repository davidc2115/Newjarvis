package com.jarvis.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Accès à l'agenda -- PORTÉ depuis l'ancienne version de l'appli (avant la remise à zéro,
 * voir commit c24b16e), à la demande explicite de l'utilisateur : "regarde comment tu avais
 * fait sur l'ancienne appli pour connexion sans passer par console cloud".
 *
 * POURQUOI ÇA MARCHE SANS OAUTH NI GOOGLE CLOUD CONSOLE : on ne parle PAS à l'API Google
 * Calendar sur internet. On lit/écrit le calendrier LOCAL d'Android (android.provider.
 * CalendarContract), la même base de données que consulte l'appli Google Agenda elle-même.
 * Quand le compte Google du téléphone est configuré avec la synchronisation Agenda activée
 * (réglage standard, déjà actif par défaut), c'est le SYSTÈME ANDROID -- pas JARVIS -- qui se
 * charge en arrière-plan de synchroniser ce calendrier local avec les serveurs Google (avec
 * son propre jeton OAuth interne, invisible et déjà géré par Android). JARVIS n'a donc besoin
 * que de READ_CALENDAR/WRITE_CALENDAR (permissions runtime standard) : toute création/lecture
 * ici remonte automatiquement sur le vrai Google Agenda en ligne en quelques secondes/minutes,
 * sans jamais passer par un ID client OAuth. C'est une vraie limite en revanche : ceci ne
 * fonctionne QUE si le compte Google est déjà ajouté sur le téléphone (Réglages > Comptes) --
 * pas de "connexion" applicative séparée à faire, elle est déjà faite au niveau du téléphone.
 * Gmail n'a pas d'équivalent (pas de ContentProvider public pour les mails) -- voir
 * GoogleAccountController pour ce cas, qui lui a vraiment besoin d'OAuth.
 */
object CalendarController {

    private val FRENCH_WEEKDAYS = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY
    )

    private val FRENCH_MONTHS = mapOf(
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6,
        "juillet" to 7, "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12
    )

    /**
     * Résout une date en langage naturel FRANÇAIS en [LocalDate], calculée depuis l'horloge
     * RÉELLE de l'appareil (jamais laissé à l'IA, qui ne connaît pas fiablement "aujourd'hui").
     *
     * java.time (JSR-310) au lieu de java.util.Calendar -- disponible nativement depuis l'API 26
     * (minSdk du projet), aucune dépendance/désucrage nécessaire. Remplace l'ancienne version
     * Calendar suite à la demande explicite de l'utilisateur d'élargir la compréhension des
     * dates (voir aussi historique du bug #181 sur les dates relatives, source de confusions
     * répétées avec l'arithmétique manuelle de Calendar).
     *
     * Phrases reconnues en plus de l'existant : "<jour> prochain" (ex. "mardi prochain" = le
     * mardi de la semaine SUIVANTE, distinct de juste "mardi" qui vise la prochaine occurrence,
     * même si c'est cette semaine) ; "dans N jours"/"dans N semaines" ; dates avec nom de mois
     * ("15 septembre", "15 septembre 2027").
     */
    fun resolveLocalDate(dateStr: String): LocalDate {
        val today = LocalDate.now()
        val raw = dateStr.trim().lowercase()
            .replace("é", "e").replace("è", "e").replace("ê", "e").replace("'", "")
        val prochain = Regex("\\bprochaine?\\b").containsMatchIn(raw)
        val d = raw.replace(Regex("\\bprochaine?\\b"), "").trim()

        Regex("^dans\\s+(\\d+)\\s+jours?$").find(d)?.let { return today.plusDays(it.groupValues[1].toLong()) }
        Regex("^dans\\s+(\\d+)\\s+semaines?$").find(d)?.let { return today.plusWeeks(it.groupValues[1].toLong()) }

        FRENCH_WEEKDAYS[d]?.let { target ->
            var result = today.with(TemporalAdjusters.next(target))
            if (prochain) result = result.plusWeeks(1)
            return result
        }

        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) return LocalDate.parse(d)

        Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$").find(d)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
            return try { LocalDate.of(year, month, day) } catch (e: Exception) { today }
        }

        Regex("^(\\d{1,2})\\s+(\\p{L}+)(?:\\s+(\\d{4}))?$").find(d)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = FRENCH_MONTHS[m.groupValues[2]]
            val year = m.groupValues[3].toIntOrNull() ?: today.year
            if (month != null) {
                return try { LocalDate.of(year, month, day) } catch (e: Exception) { today }
            }
        }

        return when {
            d.isBlank() || d == "aujourdhui" || d == "auj" -> today
            d == "demain" -> today.plusDays(1)
            d == "apres-demain" || d == "apresdemain" || d == "apres demain" -> today.plusDays(2)
            d == "hier" -> today.minusDays(1)
            d == "avant-hier" || d == "avanthier" || d == "avant hier" -> today.minusDays(2)
            else -> today
        }
    }

    /** Résout une heure en langage libre ("14:30", "14h30", "14h") en [LocalTime]. */
    fun resolveLocalTime(timeStr: String, defaultHour: Int = 9, defaultMinute: Int = 0): LocalTime {
        val t = timeStr.trim().lowercase().replace("h", ":").trim(':')
        if (t.isBlank()) return LocalTime.of(defaultHour, defaultMinute)
        val parts = t.split(":").filter { it.isNotBlank() }
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: defaultHour
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: defaultMinute
        return LocalTime.of(hour, minute)
    }

    fun getTodayEvents(context: Context): String {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return getEventsTimeRange(context, startOfDay, endOfDay, "📅 Événements aujourd'hui")
    }

    fun getUpcomingEvents(context: Context, days: Int = 7): String {
        val start = Calendar.getInstance().timeInMillis
        val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }.timeInMillis
        return getEventsTimeRange(context, start, end, "📅 Événements des $days prochains jours")
    }

    /** @param weekOffset 0 = semaine en cours, -1 = semaine dernière, 1 = semaine prochaine. */
    fun getEventsForWeek(context: Context, weekOffset: Int = 0): String {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.WEEK_OF_YEAR, weekOffset)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = cal.apply {
            add(Calendar.DAY_OF_YEAR, 6)
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val sdf = SimpleDateFormat("dd/MM", Locale.FRENCH)
        val label = when {
            weekOffset == 0 -> "cette semaine"
            weekOffset == -1 -> "la semaine dernière"
            weekOffset == 1 -> "la semaine prochaine"
            else -> "la semaine du ${sdf.format(Date(start))}"
        }
        return getEventsTimeRange(context, start, end, "📅 Événements de $label (${sdf.format(Date(start))} – ${sdf.format(Date(end))})")
    }

    /** Construit une table ID de calendrier -> "Nom (compte)", pour annoter les événements. */
    private fun buildCalendarNameMap(context: Context): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    map[c.getLong(0)] = "${c.getString(1) ?: "?"} (${c.getString(2) ?: ""})"
                }
            }
        } catch (_: Exception) { /* table vide en cas d'erreur, pas bloquant */ }
        return map
    }

    /**
     * IMPORTANT : on utilise [CalendarContract.Instances], pas [CalendarContract.Events] --
     * Events ne stocke qu'une ligne par événement récurrent (RRULE) avec le DTSTART de sa
     * toute première occurrence, donc un filtre "DTSTART entre début et fin" sur Events ferait
     * disparaître quasi tous les événements récurrents. Instances développe les récurrences en
     * occurrences réelles pour la plage demandée.
     */
    private fun getEventsTimeRange(context: Context, startMillis: Long, endMillis: Long, title: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }
        val calendarNames = buildCalendarNameMap(context)

        // Par défaut, on se limite aux calendriers Google (voir getGoogleCalendarIds) pour ne
        // JAMAIS faire remonter un calendrier LOCAL du fabricant (Xiaomi/MIUI...) -- demande
        // explicite de l'utilisateur : "Forcer Google Agenda uniquement".
        var selection = "1 = 1"
        val selectionArgsList = mutableListOf<String>()
        val googleIds = getGoogleCalendarIds(context)
        if (googleIds.isNotEmpty()) {
            selection += " AND ${CalendarContract.Instances.CALENDAR_ID} IN (${googleIds.joinToString(",")})"
        }

        return try {
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, startMillis)
            ContentUris.appendId(uriBuilder, endMillis)

            val cursor = context.contentResolver.query(
                uriBuilder.build(),
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.CALENDAR_ID
                ),
                selection, selectionArgsList.toTypedArray(),
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "$title :\n\n  aucun événement trouvé."

                data class Row(val eventId: Long, val eventTitle: String, val dtStart: Long, val location: String, val calendarId: Long)
                val rows = mutableListOf<Row>()
                while (c.moveToNext()) {
                    rows.add(Row(c.getLong(0), c.getString(1) ?: "Sans titre", c.getLong(2), c.getString(3) ?: "", c.getLong(4)))
                }
                val distinctCalendarCount = rows.map { it.calendarId }.distinct().size
                val dayFmt = SimpleDateFormat("dd/MM", Locale.FRENCH)
                val timeFmt = SimpleDateFormat("HH'h'mm", Locale.FRENCH)

                val sb = StringBuilder("$title :\n\n")
                var lastDay: String? = null
                rows.forEach { row ->
                    val day = dayFmt.format(Date(row.dtStart))
                    if (day != lastDay) {
                        sb.append("🔹 $day\n")
                        lastDay = day
                    }
                    sb.append("🕐 ${timeFmt.format(Date(row.dtStart))} — ${row.eventTitle}\n")
                    if (distinctCalendarCount > 1) {
                        sb.append("   🗓️ ${calendarNames[row.calendarId] ?: "Calendrier inconnu"}\n")
                    }
                    if (row.location.isNotBlank()) {
                        val locationPrefixed = if (row.location.trimStart().startsWith("🏠")) row.location else "🏠 ${row.location}"
                        sb.append("📍 $locationPrefixed\n")
                    }
                    sb.append("\n")
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de l'accès à l'agenda."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture de l'agenda : ${e.message}"
        }
    }

    fun createEvent(context: Context, title: String, startTimeMillis: Long, endTimeMillis: Long, location: String = ""): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }
        val calendarId = getDefaultCalendarId(context)
            ?: return "❌ Aucun calendrier disponible sur cet appareil pour ajouter l'événement."

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val sdf = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRENCH)
                val calendarName = buildCalendarNameMap(context)[calendarId] ?: "calendrier par défaut"
                "✅ Événement « $title » créé pour le ${sdf.format(Date(startTimeMillis))} (calendrier : $calendarName)."
            } else {
                "❌ Impossible de créer l'événement."
            }
        } catch (e: Exception) {
            "❌ Échec de la création de l'événement : ${e.message}"
        }
    }

    /** Supprime le PROCHAIN événement à venir dont le titre correspond à [query] (recherche
     *  partielle). Reste volontairement simple (pas de gestion d'ID explicite ici, l'utilisateur
     *  n'a aucun moyen de connaître l'ID via la commande vocale) -- si plusieurs événements
     *  correspondent, seul le plus proche dans le temps est supprimé. */
    fun deleteEventByTitle(context: Context, query: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }
        val now = System.currentTimeMillis()
        val eventId = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DTSTART),
                "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.DTSTART} >= ?",
                arrayOf("%$query%", now.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (e: Exception) {
            return "❌ Erreur lors de la recherche : ${e.message}"
        } ?: return "🔍 Aucun événement à venir trouvé pour « $query »."

        return try {
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString())
            )
            if (rows > 0) "🗑️ Événement supprimé." else diagnoseWriteFailure(context, eventId, "suppression")
        } catch (e: Exception) {
            "❌ Erreur lors de la suppression : ${e.message}"
        }
    }

    /**
     * L'événement existe mais delete()/update() a affecté 0 ligne : sur certains Android
     * personnalisés (MIUI/Xiaomi notamment), la modification par une appli tierce peut être
     * silencieusement bloquée par une restriction supplémentaire (distincte de WRITE_CALENDAR,
     * déjà accordée) plutôt que de lever une exception.
     */
    private fun diagnoseWriteFailure(context: Context, eventId: Long, action: String): String {
        val calendarId = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.CALENDAR_ID),
                "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) { null }
        val calendarName = calendarId?.let { buildCalendarNameMap(context)[it] } ?: "inconnu"
        return "⚠️ La $action a été refusée par le système alors que l'événement existe (calendrier : $calendarName). " +
            "Sur certains téléphones (Xiaomi/MIUI notamment), il faut activer manuellement : Paramètres > " +
            "Applications > JARVIS > Autorisations supplémentaires > activer « Modifier l'agenda »."
    }

    fun getCalendarList(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
                null, null, "${CalendarContract.Calendars.ACCOUNT_NAME} ASC"
            )
            cursor?.use { c ->
                if (c.count == 0) return "📅 Aucun calendrier disponible."
                val sb = StringBuilder("📅 Calendriers disponibles :\n\n")
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: "Inconnu"
                    val account = c.getString(2) ?: "?"
                    val isGoogle = c.getString(3) == "com.google"
                    sb.append("• $name (compte : $account)${if (isGoogle) " — Google" else ""}\n")
                }
                sb.toString().trimEnd()
            } ?: "❌ Erreur lors de la récupération des calendriers."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    /** IDs de tous les calendriers rattachés à un compte Google (account_type == "com.google"),
     *  utilisé pour restreindre par défaut la LECTURE et exclure tout calendrier LOCAL du
     *  fabricant (Xiaomi/MIUI...) -- demande explicite : "Forcer Google Agenda uniquement". */
    private fun getGoogleCalendarIds(context: Context): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == "com.google") ids.add(c.getLong(0))
                }
            }
        } catch (_: Exception) { /* liste vide en cas d'erreur, pas bloquant */ }
        return ids
    }

    /**
     * BUG RÉEL CORRIGÉ dans l'ancienne appli : sans filtre ni tri, une requête naïve renvoie le
     * TOUT PREMIER calendrier de la table -- souvent un calendrier LOCAL du fabricant
     * (account_type="LOCAL"), jamais synchronisé vers Google. Ordre de préférence choisi ici :
     *  1. Calendrier Google DE L'UTILISATEUR (pas un calendrier partagé/abonné), synchronisé.
     *  2. N'importe quel calendrier Google synchronisé.
     *  3. N'importe quel calendrier synchronisé (autre compte).
     *  4. Le tout premier calendrier trouvé (dernier repli).
     */
    private fun getDefaultCalendarId(context: Context): Long? {
        data class Candidate(val id: Long, val accountType: String, val isOwn: Boolean, val syncEvents: Boolean)
        val candidates = mutableListOf<Candidate>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.SYNC_EVENTS
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val accountName = c.getString(2) ?: ""
                val owner = c.getString(3) ?: ""
                candidates.add(
                    Candidate(
                        id = c.getLong(0), accountType = c.getString(1) ?: "",
                        isOwn = owner.isNotBlank() && owner == accountName, syncEvents = c.getInt(4) != 0
                    )
                )
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.accountType == "com.google" && it.isOwn && it.syncEvents }?.id
            ?: candidates.firstOrNull { it.accountType == "com.google" && it.syncEvents }?.id
            ?: candidates.firstOrNull { it.syncEvents }?.id
            ?: candidates.first().id
    }
}
