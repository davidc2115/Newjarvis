package com.jarvis.assistant

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
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

        explorerView.accentColor = Prefs.getAccentColor(this)
        explorerView.onNodeTapped = { node -> showNodeInfo(node) }

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

    private fun showNodeInfo(node: ObsidianController.VaultGraphNode) {
        nodeTitleText.text = node.title
        val kind = if (node.filePath != null) "note du vault" else "entrée de mémoire/génération"
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
