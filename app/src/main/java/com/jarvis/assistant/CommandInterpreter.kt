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
        // Planning d'un calendrier/compte PRECIS (ex. "planning de Thomas", "agenda du
        // compte pro") -- distinct des commandes ci-dessus qui fusionnent TOUJOURS tous les
        // calendriers synchronisés. Voir CalendarController.getEventsForCalendarMatching :
        // recherche par nom de calendrier/compte (insensible à la casse/aux accents), pas une
        // date. Ajouté suite au constat qu'une demande comme "affiche moi le planning de
        // Thomas" retombait sur une commande générique (tout fusionné) faute de correspondre à
        // TodayEvents/WeekEvents/EventsForDate.
        data class EventsForCalendar(val query: String, val periodRaw: String? = null) : Command()
        // Email (IMAP/SMTP, voir EmailController) -- demande explicite utilisateur, porté
        // depuis l'ancienne appli sans passer par Google Cloud Console.
        data class ReadInbox(val count: Int) : Command()
        object ReadUnreadEmails : Command()
        data class SearchEmail(val query: String) : Command()
        data class SendEmail(val to: String, val body: String) : Command()
        // Notes Obsidian (voir ObsidianController -- vault SAF reel choisi par l'utilisateur
        // dans Reglages, phase 1 du systeme second-brain, tache #211). [folder] optionnel sur
        // CreateNote : range la note dans un sous-dossier existant ou cree a la volee (ex.
        // "cree une note appelee Julie dans le dossier Contacts") -- voir tache #239, demande
        // explicite d'une vraie organisation par dossiers ("les dossier[s]") comme dans
        // l'ancienne appli, jusqu'ici absente de la reecriture (tout etait cree a plat a la
        // racine du vault).
        data class CreateNote(val title: String, val content: String, val folder: String? = null) : Command()
        data class ReadNote(val title: String) : Command()
        object ListNotes : Command()
        data class AppendNote(val title: String, val content: String) : Command()
        // Dossiers du vault (tache #239) -- organisation, pas juste des notes en vrac. Distinct
        // des notes : un dossier peut etre vide, renomme ou supprime independamment de tout
        // fichier .md qu'il contient.
        data class CreateFolder(val name: String) : Command()
        object ListFolders : Command()
        data class DeleteFolder(val name: String) : Command()
        data class RenameFolder(val oldName: String, val newName: String) : Command()
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
    // Pas besoin d'ancrer ni de couvrir "peux-tu"/"est-ce que tu peux" explicitement : find()
    // n'exige pas que le match commence en début de message, donc "peux-tu appeler Julie"
    // matche déjà via "appeler Julie" -- élargi ici seulement avec des VERBES alternatifs
    // ("téléphone à", "compose le numéro de", "passe un appel à") absents jusqu'ici.
    private val callContactRegex = Regex(
        "(?:appel(?:le|er)?|t[ée]l[ée]phone(?:\\s+[àa])?|compose(?:r)?\\s+le\\s+num[ée]ro\\s+d['’]?e?|" +
            "passe(?:-moi)?\\s+un\\s+appel(?:\\s+[àa])?)\\s+(?:le\\s+|au\\s+|[àa]\\s+)?([\\p{L} '\\-]{2,})",
        RegexOption.IGNORE_CASE
    )
    private val createContactRegex = Regex(
        "(?:cr[ée]e?|ajoute)[^.]*?contact\\s+([\\p{L} '\\-]+?)\\s+(?:num[ée]ro|t[ée]l[ée]phone|tel)\\s*:?\\s*(\\+?[\\d][\\d .-]{5,})",
        RegexOption.IGNORE_CASE
    )
    // Élargi avec des tournures interrogatives naturelles ("as-tu le numéro de...",
    // "donne-moi le numéro de...", "c'est quoi le numéro de...") en plus des verbes d'action
    // déjà couverts -- même logique que callContactRegex ci-dessus.
    private val findContactRegex = Regex(
        "(?:num[ée]ro de|cherche(?: le)? contact|trouve(?: le)? contact|affiche(?: le)? contact|adresse de|o[uù] habite|" +
            "as-tu (?:le )?(?:num[ée]ro|contact) de|donne(?:-moi)? (?:le )?(?:num[ée]ro|contact) de|" +
            "c['’]est quoi le num[ée]ro de)\\s+([\\p{L} '\\-]+)",
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
    // Groupe de synonymes partagé par les 3 regex ci-dessous : au-delà du nom explicite
    // ("planning"/"agenda"/...), couvre les tournures naturelles très courantes en français
    // pour demander son emploi du temps SANS jamais dire ces mots ("qu'est-ce que j'ai demain
    // ?", "je suis libre demain ?", "j'ai quoi cette semaine ?") -- demande explicite de
    // l'utilisateur d'améliorer la compréhension des requêtes en restant 100% local (sans IA
    // cloud) : élargie ici au niveau le plus fiable possible, la détection déterministe.
    private const val SCHEDULE_KEYWORDS =
        "(?:planning|agenda|[ée]v[ée]nements?|rendez-vous|" +
            "qu['’ ]est[- ]ce que j['’]ai|j['’]ai quoi|je fais quoi|qu['’]est[- ]ce que je fais|" +
            "j['’]ai (?:quelque chose|un truc)|suis[- ]je (?:libre|occup[ée]e?)|je suis (?:libre|occup[ée]e?))"
    /** Test rapide (pas de capture) : le message contient-il un mot-cle de planning/agenda ?
     *  Utilise par EntityExtractorController comme garde peu couteuse avant d'appeler l'API
     *  ML Kit (evite un appel modele pour un message qui ne parle clairement pas d'agenda) --
     *  reutilise SCHEDULE_KEYWORDS plutot que de dupliquer la liste des mots-cles ailleurs. */
    fun hasScheduleKeyword(text: String): Boolean =
        Regex(SCHEDULE_KEYWORDS, RegexOption.IGNORE_CASE).containsMatchIn(text)

    private val todayEventsRegex = Regex(
        "$SCHEDULE_KEYWORDS[^.]*aujourd'?hui",
        RegexOption.IGNORE_CASE
    )
    private val weekEventsRegex = Regex(
        "$SCHEDULE_KEYWORDS[^.]*(cette semaine|semaine prochaine|semaine derni[èe]re|la semaine)",
        RegexOption.IGNORE_CASE
    )
    // Planning d'un jour precis ("planning de demain", "agenda du 15 septembre", "qu'est-ce
    // que j'ai demain"...) -- capture large volontaire (groupe 1), CalendarController.
    // resolveLocalDate se charge de l'interpretation exacte de la chaine capturee, comme pour
    // createEventRegex.
    private val eventsForDateRegex = Regex(
        "$SCHEDULE_KEYWORDS[^.]*?\\b(?:pour|de|du|d['’])?\\s*" +
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
    // Planning d'un calendrier/compte PRECIS par nom ("planning de Thomas", "agenda du
    // compte pro") -- vérifié APRES eventsForDateRegex/upcomingEventsRegex/listCalendarsRegex
    // ci-dessus, donc ne se déclenche que si "de/du/pour X" n'était PAS une date reconnue ni
    // une des phrases fixes "à venir"/"mes calendriers". Classe de caractères volontairement
    // restreinte aux lettres/espaces/apostrophes/tirets : exclut naturellement "?" et autre
    // ponctuation de fin de phrase du nom capturé (pas besoin de nettoyage après-coup).
    private val eventsForCalendarRegex = Regex(
        "(?:planning|agenda|[ée]v[ée]nements?|rendez-vous)[^.]*?\\b(?:de|du|pour)\\s+([\\p{L} '’\\-]{2,})",
        RegexOption.IGNORE_CASE
    )
    // Planning d'un calendrier/compte PRECIS ET d'une periode/date precise EN MEME TEMPS (ex.
    // "affiche le planning de Thomas pour cette semaine", "planning de Marie-Claire pour la
    // semaine prochaine", "planning de Thomas pour le 30 aout") -- bug signale par l'utilisateur
    // : sans cette regex dediee, un message contenant a la fois un nom ET une phrase de periode
    // ("cette semaine") est intercepte par todayEventsRegex/weekEventsRegex/eventsForDateRegex
    // (verifiees plus haut dans parse(), voir plus bas) qui matchent sur la periode seule et
    // ignorent silencieusement le nom, fusionnant alors TOUS les calendriers au lieu d'un seul.
    // Cette regex est donc verifiee AVANT ces trois regex generiques dans parse() (contrairement
    // a eventsForCalendarRegex ci-dessus, verifiee APRES) : elle doit gagner la priorite des
    // qu'un nom ET une periode sont tous les deux presents.
    // Groupe 1 = nom (mots qui ne sont pas eux-memes des mots-cles de connexion/periode, evite
    // que le nom "avale" "pour"/"la"/"semaine"...). Groupe 2 = periode brute, reutilise le meme
    // vocabulaire que weekEventsRegex/eventsForDateRegex (CalendarController.resolveLocalDate
    // et getEventsForWeek se chargent de l'interpretation exacte de ce qui est capture).
    private const val CALENDAR_NAME_STOPWORDS =
        "(?:pour|de|du|d['’]|la|le|l['’]|cette|semaine|prochaine?|derni[èe]re?|aujourd|demain|" +
            "hier|apr[èe]s|avant|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)"
    private const val CALENDAR_PERIOD_ALT =
        "(cette semaine|(?:la\\s+)?semaine\\s+prochaine|(?:la\\s+)?semaine\\s+derni[èe]re|la semaine|" +
            "aujourd'?hui|demain|apr[èe]s-demain|hier|avant-hier|" +
            "(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?:\\s+prochaine?)?|" +
            "\\d{1,2}(?:er)?\\s+\\p{L}+(?:\\s+\\d{4})?|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{4}-\\d{2}-\\d{2})"
    private val eventsForCalendarWithPeriodRegex = Regex(
        "(?:planning|agenda|[ée]v[ée]nements?|rendez-vous)[^.]*?\\b(?:de|du|pour)\\s+" +
            "((?:(?!$CALENDAR_NAME_STOPWORDS\\b)[\\p{L}'’\\-]+\\s*)+)" +
            "(?:pour\\s+|de\\s+|du\\s+)?(?:le\\s+|la\\s+|l['’]\\s*)?$CALENDAR_PERIOD_ALT\\s*\\??\\s*$",
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

    // Notes Obsidian (voir ObsidianController) -- vault reel choisi par l'utilisateur, pas
    // un dossier interne. Titre capture en (.+?) non-gourmand comme createEventRegex : les
    // titres de notes sont souvent multi-mots ("Reunion client mardi"), pas un seul token.
    // "sauvegarde"/"enregistre" ajoutés comme synonymes de créer -- tournure naturelle
    // ("sauvegarde une note appelée..."). Voir aussi quickNoteRegex plus bas pour la capture
    // SANS titre explicite (bien plus fréquente en usage réel).
    // Groupe 3 optionnel ("dans le dossier X") : capture non-gourmande, backtracke sur le
    // groupe 2 (contenu) si absent -- meme principe que le contenu seul avant (voir tache
    // #239). "avec"/"contenant"/":" restent les separateurs titre/contenu habituels.
    private val createNoteRegex = Regex(
        "(?:cr[ée]e?|nouvelle|sauvegarde|enregistre)[^.]*note[^.]*appel[ée]e?\\s+(.+?)\\s*(?:avec|contenant|:)\\s*(.+?)" +
            "(?:\\s+dans\\s+(?:le\\s+)?dossier\\s+(.+?))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val appendNoteRegex = Regex(
        "(?:ajoute|compl[èe]te|mets? [àa] jour|modifie)[^.]*note\\s+(.+?)\\s*(?:avec|contenant|disant|:)\\s*(.+)",
        RegexOption.IGNORE_CASE
    )
    // Dossiers du vault (tache #239) -- "cree/nouveau dossier appele X", "supprime le dossier
    // X", "renomme le dossier X en Y", "liste/quels dossiers". Meme style que les regex notes
    // ci-dessus, verbe different pour ne pas se confondre avec createNoteRegex (mot-cle
    // "dossier" au lieu de "note").
    private val createFolderRegex = Regex(
        "(?:cr[ée]e?|nouveau|ajoute)[^.]*dossier(?:\\s+appel[ée]e?|\\s+nomm[ée]e?)?\\s+(.+?)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val deleteFolderRegex = Regex(
        "supprime[^.]*dossier\\s+(.+?)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val renameFolderRegex = Regex(
        "renomme[^.]*dossier\\s+(.+?)\\s+en\\s+(.+?)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val listFoldersRegex = Regex(
        "liste[^.]*dossiers|quels? dossiers|mes dossiers(?: obsidian)?",
        RegexOption.IGNORE_CASE
    )
    private val readNoteRegex = Regex(
        "(?:lis|lire|montre(?:-moi)?|affiche(?:-moi)?|ouvre|que dit|qu['’]y a[- ]t[- ]il dans)[^.]*note\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val listNotesRegex = Regex(
        "liste[^.]*notes|quelles? notes|mes notes(?: obsidian)?|notes? du vault",
        RegexOption.IGNORE_CASE
    )
    // Capture rapide sans titre explicite ("note que...", "prends note : ...", "n'oublie
    // pas de...") -- la facon la plus naturelle de prendre une note au vol, distincte de
    // createNoteRegex qui exige un titre explicite ("note appelee X"). Ancre en debut de
    // message (^) pour eviter les faux positifs sur une phrase qui contiendrait juste le mot
    // "note" ailleurs. Toujours un AppendNote (jamais CreateNote) vers une note fourre-tout
    // "Notes rapides" -- CreateNote echoue si la note existe deja, inutilisable pour des
    // captures repetees.
    private val quickNoteRegex = Regex(
        "^(?:note\\s*:?\\s*(?:que\\s+)?|prends?\\s+note\\s*:?\\s*(?:que\\s+)?|" +
            "n['\u2019]?oublie\\s+pas\\s+(?:de\\s+)?|ajoute\\s+[àa]\\s+m[ea]s?\\s+notes?\\s*:?\\s*)(.+)$",
        RegexOption.IGNORE_CASE
    )
    // Memoire persistante (voir ObsidianController.MEMORY_NOTE_TITLE) -- "retiens que...",
    // "souviens-toi que..." : ecrit dans une note dediee, relue automatiquement a chaque
    // message pour donner a JARVIS un vrai contexte utilisateur d'une conversation a l'autre
    // (voir MainActivity.buildConversationalPrompt).
    private val memoryRegex = Regex(
        "^(?:retiens|souviens-toi|rappelle-toi)\\s*(?:que\\s+)?(?:bien\\s+)?(.+)$",
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

        // Verifiee AVANT todayEventsRegex/weekEventsRegex/eventsForDateRegex (voir commentaire
        // sur eventsForCalendarWithPeriodRegex) : un nom + une periode doivent l'emporter sur
        // une periode seule.
        eventsForCalendarWithPeriodRegex.find(trimmed)?.let { match ->
            val query = cleanName(match.groupValues[1])
            val periodRaw = match.groupValues[2].trim()
            if (query.isNotBlank() && periodRaw.isNotBlank()) {
                return Command.EventsForCalendar(query, periodRaw)
            }
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

        eventsForCalendarRegex.find(trimmed)?.let { match ->
            val query = cleanName(match.groupValues[1])
            if (query.isNotBlank()) return Command.EventsForCalendar(query)
        }

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

        // Notes Obsidian -- ordre important : createNoteRegex/appendNoteRegex avant
        // readNoteRegex (verbe different mais toutes contiennent le mot "note").
        createNoteRegex.find(trimmed)?.let { match ->
            val title = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()
            val folder = match.groupValues[3].trim().ifBlank { null }
            if (title.isNotBlank() && text.isNotBlank()) return Command.CreateNote(title, text, folder)
        }

        appendNoteRegex.find(trimmed)?.let { match ->
            val title = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()
            if (title.isNotBlank() && text.isNotBlank()) return Command.AppendNote(title, text)
        }

        readNoteRegex.find(trimmed)?.let { match ->
            val title = cleanName(match.groupValues[1].trim())
            if (title.isNotBlank()) return Command.ReadNote(title)
        }

        if (listNotesRegex.containsMatchIn(lower)) return Command.ListNotes

        // Dossiers du vault (tache #239) -- verifie APRES les regex notes ci-dessus (mot-cle
        // "dossier" disjoint de "note", l'ordre n'a pas d'impact pratique, groupe juste par
        // lisibilite). renameFolderRegex avant deleteFolderRegex/createFolderRegex : "renomme"
        // est un verbe plus specifique, pas de risque de collision de toute facon (verbes tous
        // differents).
        renameFolderRegex.find(trimmed)?.let { match ->
            val oldName = cleanName(match.groupValues[1].trim())
            val newName = cleanName(match.groupValues[2].trim())
            if (oldName.isNotBlank() && newName.isNotBlank()) return Command.RenameFolder(oldName, newName)
        }

        deleteFolderRegex.find(trimmed)?.let { match ->
            val name = cleanName(match.groupValues[1].trim())
            if (name.isNotBlank()) return Command.DeleteFolder(name)
        }

        createFolderRegex.find(trimmed)?.let { match ->
            val name = cleanName(match.groupValues[1].trim())
            if (name.isNotBlank()) return Command.CreateFolder(name)
        }

        if (listFoldersRegex.containsMatchIn(lower)) return Command.ListFolders

        memoryRegex.find(trimmed)?.let { match ->
            val text = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (text.isNotBlank()) return Command.AppendNote(ObsidianController.MEMORY_NOTE_TITLE, "- $text")
        }

        quickNoteRegex.find(trimmed)?.let { match ->
            val text = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (text.isNotBlank()) return Command.AppendNote("Notes rapides", "- $text")
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
{"action":"events_for_calendar","query":"Thomas"}
{"action":"events_for_calendar","query":"Thomas","period":"cette semaine"}
{"action":"upcoming_events"}
{"action":"list_calendars"}
{"action":"create_event","title":"dentiste","date":"25/08","time":"14h30"}
{"action":"delete_event","query":"dentiste"}
{"action":"read_inbox"}
{"action":"read_unread_emails"}
{"action":"search_email","query":"facture"}
{"action":"send_email","to":"quelqu-un@exemple.com","body":"texte du mail"}
{"action":"create_note","title":"Idee projet","content":"texte de la note","folder":"Projets"}
{"action":"read_note","title":"Idee projet"}
{"action":"list_notes"}
{"action":"append_note","title":"Idee projet","content":"texte a ajouter"}
{"action":"create_folder","name":"Projets"}
{"action":"list_folders"}
{"action":"delete_folder","name":"Projets"}
{"action":"rename_folder","old_name":"Projets","new_name":"Projets 2026"}
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
            "events_for_calendar" -> str("query").ifBlank { null }?.let {
                Command.EventsForCalendar(it, strOrNull("period"))
            }
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
            // Titre facultatif cote IA pour append_note (defaut "Notes rapides", meme
            // capture au vol que quickNoteRegex) -- create_note garde un titre obligatoire
            // (une note explicitement NOMMEE n'a de sens que si un nom est fourni), mais le
            // contenu devient facultatif (note vierge avec juste un titre, cas valide).
            "create_note" -> str("title").ifBlank { null }?.let { Command.CreateNote(it, str("content"), strOrNull("folder")) }
            "read_note" -> str("title").ifBlank { null }?.let { Command.ReadNote(it) }
            "list_notes" -> Command.ListNotes
            "create_folder" -> str("name").ifBlank { null }?.let { Command.CreateFolder(cleanName(it)) }
            "list_folders" -> Command.ListFolders
            "delete_folder" -> str("name").ifBlank { null }?.let { Command.DeleteFolder(cleanName(it)) }
            "rename_folder" -> {
                val oldName = str("old_name").ifBlank { null }
                val newName = str("new_name").ifBlank { null }
                if (oldName != null && newName != null) Command.RenameFolder(cleanName(oldName), cleanName(newName)) else null
            }
            "append_note" -> {
                val text = str("content")
                if (text.isBlank()) null else Command.AppendNote(str("title").ifBlank { "Notes rapides" }, text)
            }
            else -> null
        }
    }
}
