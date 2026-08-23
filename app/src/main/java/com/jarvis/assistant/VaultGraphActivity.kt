package com.jarvis.assistant

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Écran plein-écran dédié à l'exploration interactive de la Toile Obsidian (graphe réel du
 * vault) -- demandé explicitement par l'utilisateur en plus de l'orb compact déjà affiché en
 * arrière-plan du chat (MainActivity.chatBackgroundOrb). Charge un graphe beaucoup plus complet
 * (maxNodes=400 contre 26 pour le petit orb) puisque c'est justement ici que l'utilisateur vient
 * pour "voir chaque lien et point" en détail.
 */
class VaultGraphActivity : AppCompatActivity() {

    private lateinit var explorerView: VaultGraphExplorerView
    private lateinit var infoPanelScroll: ScrollView
    private lateinit var nodeTitleText: TextView
    private lateinit var nodeMetaText: TextView
    private lateinit var nodeContentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault_graph)

        explorerView    = findViewById(R.id.vaultGraphExplorer)
        infoPanelScroll = findViewById(R.id.vaultGraphInfoPanelScroll)
        nodeTitleText   = findViewById(R.id.vaultGraphNodeTitle)
        nodeMetaText    = findViewById(R.id.vaultGraphNodeMeta)
        nodeContentText = findViewById(R.id.vaultGraphNodeContent)

        findViewById<TextView>(R.id.vaultGraphBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.vaultGraphNewFolderButton).setOnClickListener { promptCreateFolder() }

        explorerView.accentColor = Prefs.getAccentColor(this)
        explorerView.onNodeTapped = { node -> showNodeInfo(node) }

        reloadGraph()
    }

    /** (Re)charge le graphe réel du vault et l'applique à la vue -- appelé au lancement de
     * l'écran ET après création d'un nouveau dossier (bouton +) pour que la toile reflète
     * immédiatement le changement, sans devoir fermer/rouvrir l'écran. */
    private fun reloadGraph() {
        val graph = ObsidianController.buildVaultGraph(this, maxNodes = 400)
        explorerView.setGraph(graph)

        if (graph == null || graph.nodes.isEmpty()) {
            nodeTitleText.text = "🌌 Vault vide ou inaccessible"
            nodeMetaText.text = ""
            nodeContentText.text = "Crée quelques notes dans Second Brain Obsidian (ou laisse " +
                "JARVIS le faire) pour voir apparaître la toile ici."
            infoPanelScroll.visibility = View.VISIBLE
        }
    }

    /** Bouton + de la barre du haut : création d'un nouveau dossier directement depuis la Toile
     * Obsidian, demande utilisateur explicite ("la possibilité de créer de nouveau dossier") en
     * plus de la voie vocale déjà existante (obsidian_create_folder). Réutilise le contrôleur
     * Obsidian existant (ObsidianController.createFolder), aucune nouvelle logique de fichiers. */
    private fun promptCreateFolder() {
        val input = EditText(this).apply {
            hint = "Nom du dossier (ex: Projets)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📁 Nouveau dossier")
            .setView(container)
            .setPositiveButton("Créer") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Nom de dossier vide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val result = ObsidianController.createFolder(this, name)
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                reloadGraph()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showNodeInfo(node: ObsidianController.VaultGraphNode) {
        nodeTitleText.text = if (node.isFolder) "📁 ${node.title}" else node.title
        val kind = when {
            node.isFolder -> "dossier du vault"
            node.filePath != null -> "note du vault"
            else -> "entrée de mémoire/génération"
        }
        nodeMetaText.text = "🔗 ${node.degree} lien(s) · $kind"
        nodeContentText.text = if (node.filePath != null) {
            val raw = runCatching { File(node.filePath).readText() }.getOrDefault("")
            if (raw.length > 2000) raw.take(2000) + "…" else raw.ifBlank { "(note vide)" }
        } else {
            "" // le titre du nœud contient déjà tout le contenu utile pour ces entrées synthétiques
        }
        infoPanelScroll.visibility = View.VISIBLE
    }
}
