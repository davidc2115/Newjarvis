package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Menu principal de JARVIS : point d'entrée unique vers TOUTES les fonctionnalités
 * de l'app (domotique, création IA, productivité, système), organisées en sections
 * claires — remplace la dispersion précédente entre icônes du header et écrans isolés.
 */
class SmartHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home)

        findViewById<LinearLayout>(R.id.btnOpenHomeAssistant).setOnClickListener {
            startActivity(Intent(this, HomeAssistantActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenNetwork).setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenFreebox).setOnClickListener {
            startActivity(Intent(this, FreeboxActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenGeneration).setOnClickListener {
            startActivity(Intent(this, GenerationActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenMusic).setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenObsidian).setOnClickListener {
            startActivity(Intent(this, ObsidianActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenPhoneControl).setOnClickListener {
            startActivity(Intent(this, PhoneControlActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenGitHub).setOnClickListener {
            startActivity(Intent(this, GitHubActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
