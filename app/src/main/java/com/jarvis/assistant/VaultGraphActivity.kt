package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityVaultGraphBinding
import kotlinx.coroutines.launch

/**
 * "Toile Obsidian" interactive (tâche #226, demande explicite : "vue de la toile" comme
 * l'ancienne appli) -- vraie représentation visuelle du vault en nœuds/arêtes (voir
 * VaultGraphView), distincte de VaultActivity (liste + lecture texte). Taper un nœud ouvre
 * directement la note correspondante dans VaultActivity (EXTRA_OPEN_NOTE_TITLE) plutôt que de
 * dupliquer ici l'affichage/la navigation par wikilinks déjà écrite là-bas.
 */
class VaultGraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultGraphBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityVaultGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        binding.graphBackButton.setOnClickListener { finish() }
        binding.graphView.setAccentColor(Prefs.getAccentColor(this))
        binding.graphView.onNodeTap = { title ->
            startActivity(
                Intent(this, VaultActivity::class.java)
                    .putExtra(VaultActivity.EXTRA_OPEN_NOTE_TITLE, title)
            )
        }

        loadGraph()
    }

    override fun onBackPressed() {
        finish()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.graphTopBar.setPadding(
                binding.graphTopBar.paddingLeft, bars.top,
                binding.graphTopBar.paddingRight, binding.graphTopBar.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun loadGraph() {
        if (!ObsidianController.hasVault(this)) {
            binding.graphEmptyText.text = getString(R.string.vault_no_vault_message)
            binding.graphEmptyText.visibility = View.VISIBLE
            binding.graphView.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val result = ObsidianController.loadGraph(this@VaultGraphActivity)
            val nodes = result.getOrNull().orEmpty()
            if (nodes.isEmpty()) {
                binding.graphEmptyText.text = getString(R.string.vault_empty_message)
                binding.graphEmptyText.visibility = View.VISIBLE
                binding.graphView.visibility = View.GONE
            } else {
                binding.graphEmptyText.visibility = View.GONE
                binding.graphView.visibility = View.VISIBLE
                binding.graphView.setGraph(nodes)
            }
        }
    }
}
