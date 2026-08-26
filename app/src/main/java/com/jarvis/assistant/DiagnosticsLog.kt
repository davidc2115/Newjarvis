package com.jarvis.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de diagnostics PERSISTANT (filesDir/diagnostics_log.txt) — distinct de
 * JarvisApplication/crash_log.txt qui ne capture QUE les plantages non gérés. Beaucoup
 * d'échecs réels (cascade IA texte "Toutes les IA configurées ont échoué", cascade image,
 * appairage box...) ne sont PAS des crashs : l'app continue de tourner normalement, le
 * message d'erreur s'affiche une fois dans le chat puis disparaît de l'écran au message
 * suivant — sans aucune trace consultable ensuite. Résultat concret signalé par
 * l'utilisateur : "j'ai souvent une erreur toutes les API configurées ont échoué" sans
 * pouvoir en dire plus, faute d'avoir gardé le détail exact affiché sur le moment.
 *
 * JARVIS n'a AUCUN accès distant/live au téléphone de l'utilisateur (pas de connexion
 * réseau vers son appareil, aucune télémétrie envoyée nulle part) — la seule façon honnête
 * de "voir les logs" est que ce journal soit consultable PAR L'UTILISATEUR directement en
 * conversation (action read_debug_logs), pour qu'il puisse ensuite copier/coller le
 * contenu ici si besoin d'aide au diagnostic.
 *
 * Ring buffer borné (MAX_LINES) pour ne jamais grossir indéfiniment sur un usage prolongé.
 */
object DiagnosticsLog {

    private const val FILE_NAME = "diagnostics_log.txt"
    private const val MAX_LINES = 400
    private val timeFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    /**
     * Enregistre une ligne horodatée. Ne doit JAMAIS faire échouer l'appelant : toute
     * exception d'écriture est avalée silencieusement (la journalisation est un
     * complément de diagnostic, pas une fonctionnalité critique).
     */
    fun log(context: Context, tag: String, message: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val line = "[${timeFormat.format(Date())}] [$tag] ${message.replace("\n", " ⏎ ")}"
            val existing = if (file.exists()) file.readLines() else emptyList()
            val updated = (existing + line).takeLast(MAX_LINES)
            file.writeText(updated.joinToString("\n"))
        } catch (e: Exception) {
            // Volontairement ignoré — voir doc de la fonction.
        }
    }

    /** Les [count] dernières entrées, les plus récentes en dernier (ordre chronologique). */
    fun readRecent(context: Context, count: Int = 60): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "ℹ️ Aucun événement journalisé pour l'instant."
        val lines = try { file.readLines() } catch (e: Exception) { return "❌ Erreur de lecture du journal : ${e.message}" }
        if (lines.isEmpty()) return "ℹ️ Aucun événement journalisé pour l'instant."
        return lines.takeLast(count).joinToString("\n")
    }

    fun clear(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists() && file.delete()) "✅ Journal de diagnostics vidé." else "ℹ️ Journal déjà vide."
    }
}
