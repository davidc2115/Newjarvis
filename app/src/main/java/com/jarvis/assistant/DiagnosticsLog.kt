package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // ─── Auto-envoi vers GitHub (demande utilisateur : recuperer les logs "directement" sans
    //     etape manuelle) : throttle simple en memoire pour ne pas spammer l'API GitHub si
    //     plusieurs erreurs tombent d'affilee (ex: cascade IA qui echoue sur chaque
    //     fournisseur) -- un seul envoi reel toutes les AUTO_UPLOAD_MIN_INTERVAL_MS au
    //     maximum, le journal complet le plus recent partant a chaque fois de toute facon.
    private var lastAutoUploadAtMs = 0L
    private const val AUTO_UPLOAD_MIN_INTERVAL_MS = 30_000L

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

    /** Contenu INTÉGRAL du journal (déjà borné à MAX_LINES par [log]), sans la troncature
     *  supplémentaire de [readRecent] -- utilisé pour l'export fichier (voir
     *  FileGenController.exportDebugLogs), où contrairement à l'affichage en conversation il
     *  n'y a pas besoin d'économiser des tokens. */
    fun readAll(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "ℹ️ Aucun événement journalisé pour l'instant."
        return try {
            file.readText()
        } catch (e: Exception) {
            "❌ Erreur de lecture du journal : ${e.message}"
        }
    }

    fun clear(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists() && file.delete()) "✅ Journal de diagnostics vidé." else "ℹ️ Journal déjà vide."
    }

    /**
     * Comme [log], mais pour une VRAIE erreur système (cascade IA totalement échouée,
     * exception d'exécution de commande...) -- déclenche en plus, si configuré, un envoi
     * automatique et silencieux du journal complet vers un Gist GitHub privé
     * (GitHubController.uploadLogs), pour que les logs soient consultables directement sans
     * que l'utilisateur ait besoin d'exporter/partager manuellement à chaque fois. N'utilise
     * PAS cette fonction pour de la simple trace (dispatch, début/fin d'appel...) : seuls les
     * vrais échecs doivent déclencher un envoi réseau.
     */
    fun logError(context: Context, tag: String, message: String) {
        log(context, tag, message)
        if (!Prefs.isLogsAutoUploadEnabled(context)) return
        if (Prefs.getGithubAccounts(context).isEmpty()) return // pas configuré -- on n'insiste pas
        val now = System.currentTimeMillis()
        if (now - lastAutoUploadAtMs < AUTO_UPLOAD_MIN_INTERVAL_MS) return
        lastAutoUploadAtMs = now
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GitHubController.uploadLogs(appContext, readAll(appContext))
            } catch (_: Exception) {
                // Best-effort : un échec d'envoi ne doit jamais faire planter l'appelant,
                // le journal reste de toute façon consultable localement (readAll/readRecent).
            }
        }
    }
}
