package com.jarvis.assistant

import org.json.JSONObject

/**
 * Interpréteur de commandes très simple, à base de mots-clés/regex sur ce que l'utilisateur
 * tape dans le chat -- utilisé pour déclencher une vraie action téléphone (lampe, minuteur,
 * réveil...) AVANT d'appeler le backend IA, plutôt que de dépendre du function-calling propre
 * à chaque backend (Gemini Nano via ML Kit ne le supporte pas ; Gemma via LiteRT-LM oui, mais
 * on veut un comportement identique quel que soit le modèle actif -- voir Prefs.getSelectedModel).
 *
 * Volontairement basique pour ce premier lot (lampe/réveil/minuteur) : reconnaît quelques
 * formulations françaises courantes, pas une compréhension du langage naturel complète. Si rien
 * ne correspond, le message part normalement vers l'IA comme avant.
 */
object CommandInterpreter {

    sealed class Command {
        data class Flashlight(val on: Boolean) : Command()
        data class Timer(val seconds: Int) : Command()
        data class Alarm(val hour: Int, val minute: Int) : Command()
        data class Sms(val phoneNumber: String, val message: String) : Command()
        data class Call(val phoneNumber: String) : Command()
        data class CallContact(val name: String) : Command()
        data class CreateContact(val name: String, val phoneNumber: String) : Command()
        data class FindContact(val name: String) : Command()
        object GetLocation : Command()
        data class FindFile(val query: String) : Command()
        data class DeleteFile(val name: String) : Command()
        data class OpenMaps(val destination: String?) : Command()
        data class CreatePdf(val name: String, val text: String) : Command()
        data class CreateZip(val name: String) : Command()
        data class CreateDocx(val name: String, val text: String) : Command()
        data class CreateXlsx(val name: String, val csv: String) : Command()
        data class CreateKml(val name: String, val label: String?) : Command()
        data class Notify(val text: String) : Command()
        object ShowNotifications : Command()
        object TodayEvents : Command()
        data class WeekEvents(val offset: Int) : Command()
        object UpcomingEvents : Command()
        object ListCalendars : Command()
        data class CreateEvent(val title: String, val dateStr: String, val timeStr: String?) : Command()
        data class DeleteEvent(val query: String) : Command()
        // Planning d'une date precise (demain, un jour de semaine, une date explicite --
        // voir CalendarController.resolveLocalDate pour ce qui est reconnu) -- distinct de
        // TodayEvents (aujourd'hui uniquement) et WeekEvents (semaine entiere, offset -1/0/+1).
        data class EventsForDate(val dateStr: String) : Command()
        // Email (IMAP/SMTP, voir EmailController) -- demande explicite utilisateur, porté
        // depuis l'ancienne appli sans passer par Google Cloud Console.
        data class ReadInbox(val count: Int) : Command()
        object ReadUnreadEmails : Command()
        data class SearchEmail(val query: String) : Command()
        data class SendEmail(val to: String, val body: String) : Command()
    }

    private val flashlightOnRegex = Regex("(allume|active)[^.]*(lampe|torche|flash)")
    private val flashlightOffRegex = Regex("(éteins|eteins|désactive|desactive)[^.]*(lampe|torche|flash)")
    private val timerRegex = Regex("minuteur[^.\\d]*?(\\d+)\\s*(heure|minute|seconde)")
    private val alarmRegex = Regex("(réveil|reveil|alarme)[^.\\d]*?(\\d{1,2})\\s*[h:]\\s*(\\d{0,2})")

    // Ces deux-là tournent sur le texte ORIGINAL (pas lower-case) avec IGNORE_CASE, pour
    // préserver la casse du corps du SMS : lower-caser tout aurait aussi lower-casé le
    // message à envoyer.
    private val smsRegex = Regex(
        "(?:sms|texto|message texte)[^\\d]{0,20}(\\+?[\\d ]{6,})\\D*?(?:disant|qui dit|:)\\s*(.+)",
        RegexOption.IGNORE_CASE
    )
    private val callRegex = Regex(
        "appel(?:le|er)?\\s+(?:le\\s+|au\\s+)?(\\+?[\\d][\\d .-]{5,})",
        RegexOption.IGNORE_CASE
    )

    // Distinct du regex ci-dessus : "appelle Julie" (nom, pas un numéro) -- cherche dans les
    // contacts natifs puis compose avec le premier numéro trouvé (voir MainActivity.CallContact).
    private val callContactRegex = Regex(
        "appel(?:le|er)?\\s+(?:le\\s+|au\\s+)?([\\p{L} '\\-]{2,})",
        RegexOption.IGNORE_CASE
    )
    private val createContactRegex = Regex(
        "(?:cr[ée]e?|ajoute)[^.]*?contact\\s+([\\p{L} '\\-]+?)\\s+(?:num[ée]ro|t[ée]l[ée]phone|tel)\\s*:?\\s*(\\+?[\\d][\\d .-]{5,})",
        RegexOption.IGNORE_CASE
    )
    private val findContactRegex = Regex(
        "(?:num[ée]ro de|cherche(?: le)? contact|trouve(?: le)? contact|affiche(?: le)? contact|adresse de|o[uù] habite)\\s+([\\p{L} '\\-]+)",
        RegexOption.IGNORE_CASE
    )
    private val locationRegex = Regex(
        "(o[uù] (suis|est)-je|o[uù] je suis|ma position|(?:ma )?localisation actuelle)",
        RegexOption.IGNORE_CASE
    )
    private val findFileRegex = Regex(
        "(?:cherche|trouve)[^.]*fichier\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val deleteFileRegex = Regex(
        "(?:supprime|efface)[^.]*fichier\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val navigateRegex = Regex(
        "(?:navigue|itin[ée]raire|indique-moi le chemin)\\s+(?:vers|jusqu'?[àa])\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val openMapsRegex = Regex(
        "ouvre[^.]*(?:le )?gps|ouvre[^.]*(?:le )?(?:la )?carte",
        RegexOption.IGNORE_CASE
    )
    private val createPdfRegex = Regex(
        "cr[ée]e?[^.]*pdf[^.]*appel[ée]\\s+([^\\s]+)\\s+(?:avec|contenant)\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val createZipRegex = Regex(
        "cr[ée]e?[^.]*(?:archive )?zip[^.]*appel[ée]e?\\s+([^\\s]+)",
        RegexOption.IGNORE_CASE
    )
    private val createDocxRegex = Regex(
        "cr[ée]e?[^.]*(?:word|document)[^.]*appel[ée]e?\\s+([^\\s]+)\\s+(?:avec|contenant)\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val createXlsxRegex = Regex(
        "cr[ée]e?[^.]*(?:excel|tableur|xlsx)[^.]*appel[ée]e?\\s+([^\\s]+)\\s+(?:avec|contenant)\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val createKmlRegex = Regex(
        "cr[ée]e?[^.]*kml[^.]*appel[ée]e?\\s+([^\\s]+)(?:\\s+(?:avec|pour)\\s+(.+))?",
        RegexOption.IGNORE_CASE
    )
    // Agenda (voir CalendarController -- accès via CalendarContract, sans OAuth ni Google
    // Cloud Console, voir son commentaire d'en-tête pour le pourquoi).
    private val todayEventsRegex = Regex(
        "(?:planning|agenda|[ée]v[ée]nements?|rendez-vous)[^.]*aujourd'?hui",
        RegexOption.IGNORE_CASE
    )
    private val weekEventsRegex = Regex(
        "(?:planning|agenda|[ée]v[ée]nements?)[^.]*(cette semaine|semaine prochaine|semaine derni[èe]re|la semaine)",
        RegexOption.IGNORE_CASE
    )
    // Planning d'un jour precis ("planning de demain", "agenda du 15 septembre"...) --
    // capture large volontaire (groupe 1), CalendarController.resolveLocalDate se charge de
    // l'interpretation exacte de la chaine capturee, comme pour createEventRegex.
    private val eventsForDateRegex = Regex(
        "(?:planning|agenda|[ée]v[ée]nements?|rendez-vous)[^.]*?\\b(?:pour|de|du|d['\u2019])?\\s*" +
            "(demain|apr[èe]s-demain|hier|avant-hier|" +
            "(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?:\\s+prochaine?)?|" +
            "\\d{1,2}(?:er)?\\s+\\p{L}+(?:\\s+\\d{4})?|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|" +
            "\\d{4}-\\d{2}-\\d{2})",
        RegexOption.IGNORE_CASE
    )
    // Suivi conversationnel bref sans mot-cle "planning/agenda" repete (ex: l'utilisateur
    // demande d'abord "planning de demain ?" puis enchaine juste "et la semaine prochaine ?") --
    // CommandInterpreter.parse() ne voit qu'un message a la fois (pas d'historique), donc ce
    // repli ne matche que si le message ENTIER (une fois les mots de liaison retires) se reduit
    // a une simple phrase de periode/date, pour eviter les faux positifs sur des phrases plus
    // longues qui parleraient d'autre chose.
    // Prefixe de remplissage repetable ("et", "pour", "la", "le"...) retire avant de
    // comparer au coeur de la phrase -- gere "et la semaine prochaine ?" (et + la + semaine
    // prochaine), pas juste un seul mot de liaison isole.
    private val bareWeekFollowupRegex = Regex(
        "^(?:(?:et|pour|alors|ok|la|le|du|de)\\s+)*(cette semaine|semaine prochaine|semaine derni[èe]re|semaine)\\s*\\??\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val bareDateFollowupRegex = Regex(
        "^(?:(?:et|pour|alors|ok|le)\\s+)*(demain|apr[èe]s-demain|hier|avant-hier|aujourd'?hui|" +
            "(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?:\\s+prochaine?)?|" +
            "\\d{1,2}(?:er)?\\s+\\p{L}+(?:\\s+\\d{4})?|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|" +
            "\\d{4}-\\d{2}-\\d{2})\\s*\\??\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val upcomingEventsRegex = Regex(
        "(?:prochains? )?[ée]v[ée]nements? [àa] venir|planning[^.]*[àa] venir",
        RegexOption.IGNORE_CASE
    )
    private val listCalendarsRegex = Regex(
        "liste[^.]*calendriers|quels? calendriers|mes calendriers",
        RegexOption.IGNORE_CASE
    )
    // (.+?) au lieu de (\S+) pour le groupe date : permet de capturer des dates a plusieurs
    // mots ("mardi prochain", "15 septembre", "dans 3 jours"), pas juste un seul token -- voir
    // CalendarController.resolveLocalDate pour ce qui est desormais reconnu.
    private val createEventRegex = Regex(
        "(?:ajoute|cr[ée]e?)[^.]*(?:[ée]v[ée]nement|rendez-vous|rdv)[^.]*appel[ée]e?\\s+(.+?)\\s+le\\s+(.+?)(?:\\s+[àa]\\s+([\\dh:]+))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val deleteEventRegex = Regex(
        "(?:supprime|annule|efface)[^.]*(?:[ée]v[ée]nement|rendez-vous|rdv)\\s+(.+)",
        RegexOption.IGNORE_CASE
    )

    // Email (voir EmailController -- IMAP/SMTP, mot de passe d'application, aucune Console
    // Cloud). "mails"/"emails" volontairement large -- ne doit pas capter "sms"/"message texte"
    // déjà couverts par smsRegex, vérifié à l'usage.
    private val readInboxRegex = Regex(
        "mes (?:derniers? )?mails?|mes (?:derniers? )?emails?|ma bo[iî]te mail|ma bo[iî]te e-?mail",
        RegexOption.IGNORE_CASE
    )
    private val readUnreadEmailsRegex = Regex(
        "mails? non lus?|emails? non lus?",
        RegexOption.IGNORE_CASE
    )
    private val searchEmailRegex = Regex(
        "(?:cherche|recherche)[^.]*(?:mail|email)[^.]*sur\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val sendEmailRegex = Regex(
        "envoie[^.]*(?:mail|email)[^.]*[àa]\\s+(\\S+@\\S+)\\D*?(?:disant|qui dit|:)\\s*(.+)",
        RegexOption.IGNORE_CASE
    )

    private val notifyRegex = Regex(
        "(?:envoie|affiche)[^.]*notification\\s+(?:disant|qui dit|:)?\\s*(.+)",
        RegexOption.IGNORE_CASE
    )

    // Lecture des notifications système des AUTRES applis (JarvisNotificationListenerService),
    // distinct de notifyRegex ci-dessus qui ENVOIE une notification créée par JARVIS lui-même.
    private val showNotificationsRegex = Regex(
        "mes notifications|notifications r[ée]centes|derni[èe]res notifications|" +
            "lis(?:-moi)? mes notifications|montre(?:-moi)? mes notifications",
        RegexOption.IGNORE_CASE
    )

    // BUG SIGNALÉ : "aucun contact trouvé" alors que le contact existe bien -- cause réelle :
    // les regex de recherche/appel par nom capturent TOUT ce qui suit ("le contact de Julie",
    // "adresse de Julie" une fois le "de" déjà consommé par une autre variante, "appelle Julie
    // maintenant"...) et la préposition ou le mot de politesse en trop finit dans la recherche
    // ContactsController.findContact (LIKE '%...%'), qui ne matche alors plus rien. cleanName()
    // retire ces mots parasites AVANT la recherche.
    private val leadingArticleRegex = Regex("^(?:de|d['’]|le|la|l['’]|du|des)\\s+", RegexOption.IGNORE_CASE)
    private val trailingFillerRegex = Regex(
        "\\s+(?:s['’]il\\s+te\\s+pla[iî]t|stp|maintenant|tout\\s+de\\s+suite|merci|please)\\s*$",
        RegexOption.IGNORE_CASE
    )

    private fun cleanName(raw: String): String {
        var name = trailingFillerRegex.replace(raw.trim(), "").trim()
        var match = leadingArticleRegex.find(name)
        while (match != null) {
            name = name.substring(match.range.last + 1).trim()
            match = leadingArticleRegex.find(name)
        }
        return name
    }

    fun parse(text: String): Command? {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (flashlightOnRegex.containsMatchIn(lower)) return Command.Flashlight(true)
        if (flashlightOffRegex.containsMatchIn(lower)) return Command.Flashlight(false)

        timerRegex.find(lower)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2]
            val seconds = when {
                unit.startsWith("heure") -> amount * 3600
                unit.startsWith("minute") -> amount * 60
                else -> amount
            }
            return Command.Timer(seconds)
        }

        alarmRegex.find(lower)?.let { match ->
            val hour = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: 0
            if (hour in 0..23 && minute in 0..59) return Command.Alarm(hour, minute)
        }

        smsRegex.find(trimmed)?.let { match ->
            val number = match.groupValues[1].filter { it.isDigit() || it == '+' }
            val body = match.groupValues[2].trim()
            if (number.length >= 6 && body.isNotBlank()) return Command.Sms(number, body)
        }

        callRegex.find(trimmed)?.let { match ->
            val number = match.groupValues[1].filter { it.isDigit() || it == '+' }
            if (number.length >= 6) return Command.Call(number)
        }

        callContactRegex.find(trimmed)?.let { match ->
            val name = cleanName(match.groupValues[1])
            if (name.isNotBlank()) return Command.CallContact(name)
        }

        createContactRegex.find(trimmed)?.let { match ->
            val name = cleanName(match.groupValues[1])
            val number = match.groupValues[2].filter { it.isDigit() || it == '+' }
            if (name.isNotBlank() && number.length >= 6) return Command.CreateContact(name, number)
        }

        findContactRegex.find(trimmed)?.let { match ->
            val name = cleanName(match.groupValues[1])
            if (name.isNotBlank()) return Command.FindContact(name)
        }

        if (locationRegex.containsMatchIn(lower)) return Command.GetLocation

        deleteFileRegex.find(trimmed)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotBlank()) return Command.DeleteFile(name)
        }

        findFileRegex.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) return Command.FindFile(query)
        }

        navigateRegex.find(trimmed)?.let { match ->
            val destination = match.groupValues[1].trim()
            if (destination.isNotBlank()) return Command.OpenMaps(destination)
        }

        if (openMapsRegex.containsMatchIn(lower)) return Command.OpenMaps(null)

        createPdfRegex.find(trimmed)?.let { match ->
            var name = match.groupValues[1].trim()
            if (!name.endsWith(".pdf", ignoreCase = true)) name += ".pdf"
            val text = match.groupValues[2].trim()
            if (text.isNotBlank()) return Command.CreatePdf(name, text)
        }

        createZipRegex.find(trimmed)?.let { match ->
            var name = match.groupValues[1].trim()
            if (!name.endsWith(".zip", ignoreCase = true)) name += ".zip"
            return Command.CreateZip(name)
        }

        createDocxRegex.find(trimmed)?.let { match ->
            var name = match.groupValues[1].trim()
            if (!name.endsWith(".docx", ignoreCase = true)) name += ".docx"
            val text = match.groupValues[2].trim()
            if (text.isNotBlank()) return Command.CreateDocx(name, text)
        }

        createXlsxRegex.find(trimmed)?.let { match ->
            var name = match.groupValues[1].trim()
            if (!name.endsWith(".xlsx", ignoreCase = true)) name += ".xlsx"
            val csv = match.groupValues[2].trim()
            if (csv.isNotBlank()) return Command.CreateXlsx(name, csv)
        }

        createKmlRegex.find(trimmed)?.let { match ->
            var name = match.groupValues[1].trim()
            if (!name.endsWith(".kml", ignoreCase = true)) name += ".kml"
            val label = match.groupValues[2].trim().ifBlank { null }
            return Command.CreateKml(name, label)
        }

        if (todayEventsRegex.containsMatchIn(lower)) return Command.TodayEvents

        weekEventsRegex.find(lower)?.let { match ->
            val phrase = match.groupValues[1]
            val offset = when {
                phrase.contains("prochaine") -> 1
                phrase.contains("derni") -> -1
                else -> 0
            }
            return Command.WeekEvents(offset)
        }

        eventsForDateRegex.find(trimmed)?.let { match ->
            val dateStr = match.groupValues[1].trim()
            if (dateStr.isNotBlank()) return Command.EventsForDate(dateStr)
        }

        if (upcomingEventsRegex.containsMatchIn(lower)) return Command.UpcomingEvents

        if (listCalendarsRegex.containsMatchIn(lower)) return Command.ListCalendars

        bareWeekFollowupRegex.find(trimmed)?.let { match ->
            val phrase = match.groupValues[1].lowercase()
            val offset = when {
                phrase.contains("prochaine") -> 1
                phrase.contains("derni") -> -1
                else -> 0
            }
            return Command.WeekEvents(offset)
        }

        bareDateFollowupRegex.find(trimmed)?.let { match ->
            val dateStr = match.groupValues[1].trim()
            if (dateStr.isNotBlank()) return Command.EventsForDate(dateStr)
        }

        createEventRegex.find(trimmed)?.let { match ->
            val title = match.groupValues[1].trim()
            val dateStr = match.groupValues[2].trim()
            val timeStr = match.groupValues[3].trim().ifBlank { null }
            if (title.isNotBlank()) return Command.CreateEvent(title, dateStr, timeStr)
        }

        deleteEventRegex.find(trimmed)?.let { match ->
            val query = cleanName(match.groupValues[1])
            if (query.isNotBlank()) return Command.DeleteEvent(query)
        }

        if (showNotificationsRegex.containsMatchIn(lower)) return Command.ShowNotifications

        // Email : ordre important -- sendEmailRegex avant readInboxRegex/searchEmailRegex
        // (une phrase d'envoi contient aussi le mot "mail", mais "envoie...à...disant..." est
        // plus spécifique, donc vérifiée avant les regex de lecture, plus larges).
        sendEmailRegex.find(trimmed)?.let { match ->
            val to = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (to.contains("@") && body.isNotBlank()) return Command.SendEmail(to, body)
        }

        searchEmailRegex.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) return Command.SearchEmail(query)
        }

        if (readUnreadEmailsRegex.containsMatchIn(lower)) return Command.ReadUnreadEmails

        if (readInboxRegex.containsMatchIn(lower)) return Command.ReadInbox(5)

        notifyRegex.find(trimmed)?.let { match ->
            val text = match.groupValues[1].trim()
            if (text.isNotBlank()) return Command.Notify(text)
        }

        return null
    }

    // --- Tool-calling IA (demande explicite utilisateur : "TOOLCALLING") ---------------------
    // Le système ci-dessus est volontairement basique (regex/mots-clés) : si aucune formulation
    // reconnue ne correspond, on ne veut plus abandonner directement vers une réponse générique
    // de l'IA ("je suis un grand modèle linguistique...") -- on demande d'abord au modèle actif
    // (Gemini Nano ou Gemma, peu importe -- voir MainActivity.classifyIntent) de classifier
    // l'intention lui-même, sous forme d'un unique objet JSON strict, qu'on retraduit ici en
    // Command exécutable par le même pipeline (executeDeviceCommand/runDeviceCommand) que les
    // commandes reconnues par regex. Ni Gemini Nano (ML Kit GenAI Prompt API) ni Gemma via
    // LiteRT-LM n'exposent de function-calling natif sur Android à ce jour -- ceci reproduit le
    // comportement par prompt structuré, seule option disponible on-device pour les deux backends.

    /**
     * Prompt de classification envoyé au backend IA actif quand aucune regex n'a matché.
     * Répond uniquement par un objet JSON sur une seule ligne (schéma détaillé ci-dessous), ou
     * {"action":"none"} si le message est une simple question/conversation sans action associée.
     */
    fun buildClassificationPrompt(userText: String): String {
        val safeText = userText.replace("\"", "'").replace("\n", " ").trim()
        return """
Tu es un classifieur d'intentions pour un assistant qui contrôle un téléphone Android. Lis le message de l'utilisateur et réponds UNIQUEMENT par un objet JSON sur une seule ligne, sans aucun texte ni explication autour, correspondant à UNE SEULE des actions ci-dessous si le message correspond clairement à une demande d'action sur le téléphone. Si le message est une question générale, une conversation, ou ne correspond à AUCUNE de ces actions, réponds EXACTEMENT {"action":"none"} -- ne devine jamais une action au hasard.

Actions possibles (respecte exactement les noms des champs, JSON valide, une seule ligne) :
{"action":"flashlight","on":true}
{"action":"flashlight","on":false}
{"action":"set_timer","seconds":300}
{"action":"set_alarm","hour":7,"minute":30}
{"action":"send_sms","number":"0612345678","message":"texte du sms"}
{"action":"call_number","number":"0612345678"}
{"action":"call_contact","name":"Julie"}
{"action":"create_contact","name":"Julie","number":"0612345678"}
{"action":"find_contact","name":"Julie"}
{"action":"get_location"}
{"action":"find_file","query":"facture"}
{"action":"delete_file","name":"facture.pdf"}
{"action":"open_maps","destination":"Tour Eiffel"}
{"action":"create_pdf","name":"notes","text":"contenu"}
{"action":"create_zip","name":"archive"}
{"action":"create_docx","name":"rapport","text":"contenu"}
{"action":"create_xlsx","name":"tableau","csv":"a,b\n1,2"}
{"action":"create_kml","name":"trajet","label":"maison"}
{"action":"notify","text":"texte de la notification"}
{"action":"show_notifications"}
{"action":"today_events"}
{"action":"week_events","offset":0}
{"action":"events_for_date","date":"demain"}
{"action":"upcoming_events"}
{"action":"list_calendars"}
{"action":"create_event","title":"dentiste","date":"25/08","time":"14h30"}
{"action":"delete_event","query":"dentiste"}
{"action":"read_inbox"}
{"action":"read_unread_emails"}
{"action":"search_email","query":"facture"}
{"action":"send_email","to":"quelqu-un@exemple.com","body":"texte du mail"}
{"action":"none"}

Message de l'utilisateur : "$safeText"
JSON :"""
    }

    /**
     * Retraduit la réponse JSON du modèle (voir buildClassificationPrompt) en Command exécutable.
     * Robuste par nature : un petit modèle on-device peut ajouter du texte autour du JSON, mal
     * fermer une accolade, ou halluciner un champ -- toute erreur de parsing ou action inconnue
     * renvoie simplement null (fallback normal vers une réponse conversationnelle), jamais de
     * plantage.
     */
    fun fromAiJson(raw: String): Command? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        val obj = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }
        val action = obj.optString("action", "none").trim().lowercase()

        fun str(key: String): String = obj.optString(key, "").trim()
        fun strOrNull(key: String): String? = str(key).ifBlank { null }
        fun withExt(name: String, ext: String): String {
            var n = name
            if (!n.endsWith(ext, ignoreCase = true)) n += ext
            return n
        }

        return when (action) {
            "flashlight" -> Command.Flashlight(obj.optBoolean("on", true))
            "set_timer" -> {
                val seconds = obj.optInt("seconds", -1)
                if (seconds > 0) Command.Timer(seconds) else null
            }
            "set_alarm" -> {
                val hour = obj.optInt("hour", -1)
                val minute = obj.optInt("minute", 0)
                if (hour in 0..23 && minute in 0..59) Command.Alarm(hour, minute) else null
            }
            "send_sms" -> {
                val number = str("number").filter { it.isDigit() || it == '+' }
                val message = str("message")
                if (number.length >= 6 && message.isNotBlank()) Command.Sms(number, message) else null
            }
            "call_number" -> {
                val number = str("number").filter { it.isDigit() || it == '+' }
                if (number.length >= 6) Command.Call(number) else null
            }
            "call_contact" -> str("name").ifBlank { null }?.let { Command.CallContact(cleanName(it)) }
            "create_contact" -> {
                val name = cleanName(str("name"))
                val number = str("number").filter { it.isDigit() || it == '+' }
                if (name.isNotBlank() && number.length >= 6) Command.CreateContact(name, number) else null
            }
            "find_contact" -> str("name").ifBlank { null }?.let { Command.FindContact(cleanName(it)) }
            "get_location" -> Command.GetLocation
            "find_file" -> str("query").ifBlank { null }?.let { Command.FindFile(it) }
            "delete_file" -> str("name").ifBlank { null }?.let { Command.DeleteFile(it) }
            "open_maps" -> Command.OpenMaps(strOrNull("destination"))
            "create_pdf" -> {
                val text = str("text")
                if (text.isBlank()) null else Command.CreatePdf(withExt(str("name").ifBlank { "document" }, ".pdf"), text)
            }
            "create_zip" -> Command.CreateZip(withExt(str("name").ifBlank { "archive" }, ".zip"))
            "create_docx" -> {
                val text = str("text")
                if (text.isBlank()) null else Command.CreateDocx(withExt(str("name").ifBlank { "document" }, ".docx"), text)
            }
            "create_xlsx" -> {
                val csv = str("csv")
                if (csv.isBlank()) null else Command.CreateXlsx(withExt(str("name").ifBlank { "tableau" }, ".xlsx"), csv)
            }
            "create_kml" -> Command.CreateKml(withExt(str("name").ifBlank { "trajet" }, ".kml"), strOrNull("label"))
            "notify" -> str("text").ifBlank { null }?.let { Command.Notify(it) }
            "show_notifications" -> Command.ShowNotifications
            "today_events" -> Command.TodayEvents
            "week_events" -> {
                val offset = obj.optInt("offset", 0).coerceIn(-1, 1)
                Command.WeekEvents(offset)
            }
            "events_for_date" -> str("date").ifBlank { null }?.let { Command.EventsForDate(it) }
            "upcoming_events" -> Command.UpcomingEvents
            "list_calendars" -> Command.ListCalendars
            "create_event" -> {
                val title = str("title")
                val date = str("date")
                if (title.isBlank() || date.isBlank()) null else Command.CreateEvent(title, date, strOrNull("time"))
            }
            "delete_event" -> str("query").ifBlank { null }?.let { Command.DeleteEvent(cleanName(it)) }
            "read_inbox" -> Command.ReadInbox(5)
            "read_unread_emails" -> Command.ReadUnreadEmails
            "search_email" -> str("query").ifBlank { null }?.let { Command.SearchEmail(it) }
            "send_email" -> {
                val to = str("to")
                val body = str("body")
                if (to.contains("@") && body.isNotBlank()) Command.SendEmail(to, body) else null
            }
            else -> null
        }
    }
}
