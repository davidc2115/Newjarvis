package com.jarvis.assistant

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.databinding.ActivityMainBinding

/**
 * Point d'entrée du projet après remise à zéro complète (à la demande explicite de
 * l'utilisateur : "recommençons le projet complètement"). Tout l'ancien code (180+
 * fonctionnalités : appels, SMS, contacts, agenda, Obsidian, IA, box internet, etc.)
 * a été retiré du dépôt — l'historique reste consultable via `git log` sur les commits
 * précédents si besoin de retrouver un bout de logique en reconstruisant une fonctionnalité.
 *
 * Prochaine étape : reconstruire les fonctionnalités une par une, dans l'ordre choisi
 * par l'utilisateur, à partir de cette base minimale qui compile et se lance.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
