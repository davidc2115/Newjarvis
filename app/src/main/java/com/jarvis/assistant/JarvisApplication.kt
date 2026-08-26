package com.jarvis.assistant

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture tout crash non géré de l'application et l'enregistre dans un fichier
 * texte lisible (filesDir/crash_log.txt). Au prochain lancement, MainActivity
 * affiche ce contenu dans une fenêtre copiable — plus besoin d'ADB ou de
 * brancher le téléphone à un PC pour diagnostiquer un plantage.
 */
class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // BUG REEL CORRIGE (signalement utilisateur : "les fenetres s'affichent sur fond blanc,
        // texte orange completement illisible") : le theme (Theme.MaterialComponents.DayNight.*)
        // bascule en variante CLAIRE des que le systeme Android est en mode clair, alors que
        // TOUTE l'interface de l'app est concue pour un theme sombre unique (voir colors.xml,
        // "Apex Studio" - aucune ressource -night/light n'existe nulle part dans le projet). Les
        // ecrans custom (item_message.xml etc.) codent leurs couleurs en dur donc restent sombres
        // quoi qu'il arrive, mais les AlertDialog/DatePicker/TimePicker systeme suivent le theme
        // DayNight et passaient en fond blanc - avec colorPrimary=cyan_accent (orange/ambre,
        // agnostique jour/nuit) toujours applique aux titres/boutons, d'ou le orange sur blanc
        // illisible. Forcer le mode sombre globalement resout ca sans toucher chaque dialogue un
        // par un.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))

                val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())
                val report = buildString {
                    append("═══ CRASH JARVIS — $timestamp ═══\n")
                    append("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                    append("Thread : ${thread.name}\n\n")
                    append(sw.toString())
                }

                File(filesDir, "crash_log.txt").writeText(report)
            } catch (e: Exception) {
                // Si l'écriture du log échoue elle-même, on ne bloque pas le crash normal.
            }

            // Laisse Android gérer le crash normalement ensuite (fermeture de l'app).
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
