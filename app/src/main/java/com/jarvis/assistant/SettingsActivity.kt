package com.jarvis.assistant

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.databinding.ActivitySettingsBinding

/** Réglages : choix de la couleur d'accent du thème (bulles utilisateur, bouton d'envoi...). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var swatches: List<SwatchEntry>

    private data class SwatchEntry(val color: Int, val circle: View, val check: View, val container: View)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Voir MainActivity.onCreate pour pourquoi cet appel explicite est nécessaire.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        swatches = listOf(
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_cyan), binding.swatchCyanCircle, binding.swatchCyanCheck, binding.swatchCyan),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_violet), binding.swatchVioletCircle, binding.swatchVioletCheck, binding.swatchViolet),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_rose), binding.swatchRoseCircle, binding.swatchRoseCheck, binding.swatchRose),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_vert), binding.swatchVertCircle, binding.swatchVertCheck, binding.swatchVert),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_orange), binding.swatchOrangeCircle, binding.swatchOrangeCheck, binding.swatchOrange),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_rouge), binding.swatchRougeCircle, binding.swatchRougeCheck, binding.swatchRouge),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_bleu), binding.swatchBleuCircle, binding.swatchBleuCheck, binding.swatchBleu),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_blanc), binding.swatchBlancCircle, binding.swatchBlancCheck, binding.swatchBlanc)
        )

        swatches.forEach { entry ->
            val bg = entry.circle.background?.mutate()
            if (bg is GradientDrawable) bg.setColor(entry.color)
            entry.container.setOnClickListener { selectColor(entry.color) }
        }

        updateSelection(Prefs.getAccentColor(this))

        binding.backButton.setOnClickListener { finish() }
    }

    private fun selectColor(color: Int) {
        Prefs.setAccentColor(this, color)
        updateSelection(color)
    }

    private fun updateSelection(current: Int) {
        swatches.forEach { it.check.visibility = if (it.color == current) View.VISIBLE else View.GONE }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.settingsTopBar.setPadding(
                binding.settingsTopBar.paddingLeft, bars.top,
                binding.settingsTopBar.paddingRight, binding.settingsTopBar.paddingBottom
            )
            insets
        }
    }
}
