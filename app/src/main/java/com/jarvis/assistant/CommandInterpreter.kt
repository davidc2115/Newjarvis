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
    }

    private val flashlightOnRegex = Regex("(allume|active)[^.]*(lampe|torche|flash)")
    private val flashlightOffRegex = Regex("(éteins|eteins|désactive|desactive)[^.]*(lampe|torche|flash)")
    private val timerRegex = Regex("minuteur[^.\\d]*?(\\d+)\\s*(heure|minute|seconde)")
    private val alarmRegex = Regex("(réveil|reveil|alarme)[^.\\d]*?(\\d{1,2})\\s*[h:]\\s*(\\d{0,2})")

    fun parse(text: String): Command? {
        val lower = text.lowercase().trim()

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

        return null
    }
}
