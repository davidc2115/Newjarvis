package com.jarvis.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CalendarController {

    fun getTodayEvents(context: Context, calendarRef: String? = null): String {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val title = "📅 **Événements prévus aujourd'hui**" + calendarLabelSuffix(context, calendarRef)
        return getEventsTimeRange(context, startOfDay, endOfDay, title, calendarRef)
    }

    fun getUpcomingEvents(context: Context, days: Int = 7, calendarRef: String? = null): String {
        val start = Calendar.getInstance().timeInMillis
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis

        val title = "📅 **Événements des $days prochains jours**" + calendarLabelSuffix(context, calendarRef)
        return getEventsTimeRange(context, start, end, title, calendarRef)
    }

    private fun calendarLabelSuffix(context: Context, calendarRef: String?): String {
        if (calendarRef.isNullOrBlank()) return ""
        val id = findCalendarId(context, calendarRef) ?: return ""
        val name = buildCalendarNameMap(context)[id] ?: return ""
        return " — $name"
    }

    /** Construit une table ID de calendrier -> "Nom (compte)" pour annoter les événements. */
    private fun buildCalendarNameMap(context: Context): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "?"
                    val account = c.getString(2) ?: ""
                    val nickname = Prefs.getCalendarNickname(context, id)
                    map[id] = nickname.ifBlank { "$name ($account)" }
                }
            }
        } catch (_: Exception) { /* table vide en cas d'erreur, pas bloquant */ }
        return map
    }

    /**
     * Interroge les occurrences d'événements dans une plage de dates.
     *
     * IMPORTANT : on utilise la table [CalendarContract.Instances] et NON
     * [CalendarContract.Events]. Events ne stocke qu'UNE seule ligne par
     * événement récurrent (RRULE), avec le DTSTART de sa toute première
     * occurrence — un filtre "DTSTART entre début et fin de journée" sur
     * Events fait donc disparaître quasiment tous les événements récurrents
     * (réunion hebdomadaire, rappel quotidien, etc.) sauf le jour exact où
     * la série a commencé. Instances développe correctement les récurrences
     * en occurrences réelles pour la plage demandée — c'est la cause la
     * plus probable des "événements incohérents" (récurrents manquants ou
     * mal datés).
     */
    private fun getEventsTimeRange(context: Context, startMillis: Long, endMillis: Long, title: String, calendarRef: String? = null): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val calendarNames = buildCalendarNameMap(context)

        var filterCalendarId: Long? = null
        if (!calendarRef.isNullOrBlank()) {
            filterCalendarId = findCalendarId(context, calendarRef)
            if (filterCalendarId == null) {
                return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles, puis donne-lui un surnom avec name_calendar si besoin."
            }
        }

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID
        )

        var selection = "1 = 1"
        val selectionArgsList = mutableListOf<String>()
        if (filterCalendarId != null) {
            selection += " AND ${CalendarContract.Instances.CALENDAR_ID} = ?"
            selectionArgsList.add(filterCalendarId.toString())
        }

        return try {
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, startMillis)
            ContentUris.appendId(uriBuilder, endMillis)

            val cursor: Cursor? = context.contentResolver.query(
                uriBuilder.build(),
                projection,
                selection,
                selectionArgsList.toTypedArray(),
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "$title :\n\n  aucun événement trouvé."

                val sb = StringBuilder("$title :\n\n")
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext()) {
                    val eventId = c.getLong(0)
                    val eventTitle = c.getString(1) ?: "Sans titre"
                    val dtStart = c.getLong(2)
                    val location = c.getString(3) ?: ""
                    val calendarId = c.getLong(4)
                    val calendarName = calendarNames[calendarId] ?: "Calendrier inconnu"
                    val timeStr = sdf.format(Date(dtStart))

                    sb.append("${idx + 1}. **$eventTitle** — $timeStr (ID: $eventId)\n")
                    sb.append("   🗓️ Calendrier : $calendarName\n")
                    if (location.isNotBlank()) sb.append("   📍 $location\n")
                    sb.append("\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de l'accès à l'agenda."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture de l'agenda : ${e.message}"
        }
    }

    fun createEvent(
        context: Context,
        title: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        description: String = "",
        location: String = "",
        calendarRef: String? = null
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        val calendarId = resolveCalendarId(context, calendarRef)
            ?: return if (calendarRef.isNullOrBlank())
                "❌ Aucun calendrier disponible sur cet appareil pour ajouter l'événement."
            else
                "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles, ou omets le paramètre calendar pour utiliser le calendrier par défaut."

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val sdf = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRENCH)
                "✅ Événement **$title** créé avec succès pour le ${sdf.format(Date(startTimeMillis))} !"
            } else {
                "❌ Impossible de créer l'événement."
            }
        } catch (e: Exception) {
            "❌ Échec de la création de l'événement : ${e.message}"
        }
    }

    fun deleteEvent(context: Context, eventId: Long): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        return try {
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            if (rows > 0) "🗑️ Événement supprimé." else "❌ Événement introuvable."
        } catch (e: Exception) {
            "❌ Erreur lors de la suppression : ${e.message}"
        }
    }

    /**
     * Modifie un événement existant : renommer, changer les horaires,
     * le lieu ou la description. Seuls les champs fournis (non nuls)
     * sont modifiés, les autres restent inchangés.
     */
    fun updateEvent(
        context: Context,
        eventId: Long,
        newTitle: String? = null,
        newStartTimeMillis: Long? = null,
        newEndTimeMillis: Long? = null,
        newDescription: String? = null,
        newLocation: String? = null
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        return try {
            val values = ContentValues().apply {
                newTitle?.let { put(CalendarContract.Events.TITLE, it) }
                newStartTimeMillis?.let { put(CalendarContract.Events.DTSTART, it) }
                newEndTimeMillis?.let { put(CalendarContract.Events.DTEND, it) }
                newDescription?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                newLocation?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            if (values.size() == 0) return "❌ Aucune modification à appliquer."

            val rows = context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            if (rows > 0) "✏️ Événement mis à jour avec succès." else "❌ Événement introuvable."
        } catch (e: Exception) {
            "❌ Erreur lors de la modification : ${e.message}"
        }
    }

    fun searchEvents(context: Context, query: String, calendarRef: String? = null): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        var filterCalendarId: Long? = null
        if (!calendarRef.isNullOrBlank()) {
            filterCalendarId = findCalendarId(context, calendarRef)
            if (filterCalendarId == null) {
                return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles."
            }
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )

        return try {
            var selection = "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} = 0"
            val argsList = mutableListOf("%$query%")
            if (filterCalendarId != null) {
                selection += " AND ${CalendarContract.Events.CALENDAR_ID} = ?"
                argsList.add(filterCalendarId.toString())
            }
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                argsList.toTypedArray(),
                "${CalendarContract.Events.DTSTART} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "🔍 Aucun événement trouvé pour « $query »."

                val sb = StringBuilder("🔍 **Résultats de recherche dans l'agenda pour « $query »** :\n\n")
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext() && idx < 10) {
                    val eventId = c.getLong(0)
                    val title = c.getString(1) ?: "Sans titre"
                    val date = c.getLong(2)
                    val location = c.getString(3) ?: ""

                    sb.append("${idx + 1}. **$title** — ${sdf.format(Date(date))} (ID: $eventId)\n")
                    if (location.isNotBlank()) sb.append("   📍 $location\n")
                    sb.append("\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de la recherche dans l'agenda."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    fun getCalendarList(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )

        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "📅 Aucun calendrier disponible."

                val sb = StringBuilder("📅 **Calendriers disponibles** :\n\n")
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "Inconnu"
                    val account = c.getString(2) ?: "?"
                    val nickname = Prefs.getCalendarNickname(context, id)
                    val nicknameStr = if (nickname.isNotBlank()) " — surnom : « $nickname »" else ""
                    sb.append("• **$name** (compte : $account, ID: $id)$nicknameStr\n")
                }
                sb.append(
                    "\n💡 Pour distinguer deux calendriers similaires, donne-leur un surnom avec " +
                        "l'action name_calendar — tu peux référencer le calendrier par son nom affiché, " +
                        "son compte (email), ou son ID (ex : « appelle le calendrier de untel@gmail.com 'Perso' »). " +
                        "Ensuite utilise ce surnom comme paramètre calendar dans today_events/upcoming_events/search_event " +
                        "pour n'afficher que ce planning précis."
                )
                sb.toString()
            } ?: "❌ Erreur lors de la récupération des calendriers."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    data class EventDetails(
        val id: Long,
        val title: String,
        val startMillis: Long,
        val location: String,
        val description: String
    )

    /** Récupère les détails complets d'un événement (utilisé notamment pour créer une fiche client depuis un RDV). */
    fun getEventDetails(context: Context, eventId: Long): EventDetails? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                EventDetails(
                    id = eventId,
                    title = c.getString(1) ?: "Sans titre",
                    startMillis = c.getLong(2),
                    location = c.getString(3) ?: "",
                    description = c.getString(4) ?: ""
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Attribue un surnom mémorisable à un calendrier (ex: "Perso", "Boulot"), pour le
     * distinguer facilement. [calendarRef] accepte un ID numérique, un surnom déjà
     * existant, ou un nom affiché / nom de compte (recherche partielle, insensible à
     * la casse) — pas besoin de connaître l'ID à l'avance.
     */
    fun nameCalendar(context: Context, calendarRef: String, nickname: String): String {
        val id = findCalendarId(context, calendarRef)
            ?: return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les noms/comptes disponibles."
        Prefs.saveCalendarNickname(context, id, nickname)
        val currentName = buildCalendarNameMap(context)[id] ?: calendarRef
        return "✅ Le calendrier « $currentName » s'appellera désormais « $nickname »."
    }

    /**
     * Résout un identifiant de calendrier à partir d'un surnom, d'un nom affiché, d'un
     * compte, ou d'un ID numérique direct. Retourne null si [calendarRef] est fourni
     * mais ne correspond à AUCUN calendrier — ne retombe JAMAIS silencieusement sur un
     * autre calendrier, pour éviter d'afficher les événements du mauvais planning sans
     * prévenir.
     */
    private fun findCalendarId(context: Context, calendarRef: String): Long? {
        calendarRef.toLongOrNull()?.let { id ->
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) return id }
        }

        Prefs.findCalendarIdByNickname(context, calendarRef)?.let { return it }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: ""
                val account = c.getString(2) ?: ""
                if (name.contains(calendarRef, ignoreCase = true) || account.contains(calendarRef, ignoreCase = true)) {
                    return c.getLong(0)
                }
            }
        }
        return null
    }

    /** Comme [findCalendarId], mais une référence vide/absente renvoie le calendrier par défaut de l'appareil. */
    private fun resolveCalendarId(context: Context, calendarRef: String?): Long? {
        if (calendarRef.isNullOrBlank()) return getDefaultCalendarId(context)
        return findCalendarId(context, calendarRef)
    }

    private fun getDefaultCalendarId(context: Context): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null,
            null,
            null
        )

        cursor?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }
}
