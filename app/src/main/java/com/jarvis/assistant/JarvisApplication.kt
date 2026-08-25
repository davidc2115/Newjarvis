package com.jarvis.assistant

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * BUG REEL DEJA CORRIGE UNE FOIS (commit 82edaa0, perdu lors de la réécriture complète de
 * l'appli sur un squelette vierge -- voir historique) : le thème (Theme.MaterialComponents.
 * DayNight.*) bascule en variante CLAIRE dès que le système Android est en mode clair, alors que
 * toute l'interface de l'app est conçue pour un thème sombre unique (aucune ressource -night/
 * -light n'existe dans le projet). Les écrans custom codent leurs couleurs en dur donc restent
 * sombres quoi qu'il arrive, mais les AlertDialog/DatePicker/TimePicker système suivent le thème
 * DayNight et passent en fond blanc -- avec colorPrimary=accent (souvent ambre/orange,
 * indépendant du mode jour/nuit) toujours appliqué aux titres/boutons, d'où un texte quasi
 * invisible sur fond blanc. C'est ce qui a rendu les fenêtres d'erreur OAuth invisibles pour
 * l'utilisateur (le clic déclenchait bien le code, mais le dialogue affiché n'était pas
 * perceptible). Forcer le mode sombre globalement résout ça sans toucher chaque dialogue un par
 * un.
 */
class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
