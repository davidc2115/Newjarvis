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

        return null
    }
}
