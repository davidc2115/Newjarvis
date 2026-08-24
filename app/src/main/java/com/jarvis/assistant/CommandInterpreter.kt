package com.jarvis.assistant

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
        data class Notify(val text: String) : Command()
        object ShowNotifications : Command()
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
            val name = match.groupValues[1].trim()
            if (name.isNotBlank()) return Command.CallContact(name)
        }

        createContactRegex.find(trimmed)?.let { match ->
            val name = match.groupValues[1].trim()
            val number = match.groupValues[2].filter { it.isDigit() || it == '+' }
            if (name.isNotBlank() && number.length >= 6) return Command.CreateContact(name, number)
        }

        findContactRegex.find(trimmed)?.let { match ->
            val name = match.groupValues[1].trim()
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

        if (showNotificationsRegex.containsMatchIn(lower)) return Command.ShowNotifications

        notifyRegex.find(trimmed)?.let { match ->
            val text = match.groupValues[1].trim()
            if (text.isNotBlank()) return Command.Notify(text)
        }

        return null
    }
}
