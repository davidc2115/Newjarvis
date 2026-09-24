package com.jarvis.assistant

import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mode immersif « explosion de l'astre » : graphe de mémoire plein écran,
 * rotation accélérée, nœuds qui s'écartent du centre puis se stabilisent.
 */
class KnowledgeGraphFullscreenActivity : AppCompatActivity() {

    private lateinit var graph: KnowledgeGraphView
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_knowledge_graph_fullscreen)

        graph = findViewById(R.id.fullscreenGraph)
        subtitle = findViewById(R.id.fullscreenSubtitle)
        graph.accentColor = Prefs.getAccentColor(this)
        graph.explodeMode = true
        graph.onNodeTap = { node ->
            subtitle.text = "✦ ${node.label}" + if (node.folder.isNotBlank()) " · ${node.folder}" else ""
        }

        findViewById<TextView>(R.id.btnCloseGraph).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnExplodeAgain).setOnClickListener {
            graph.triggerExplosion()
        }
        findViewById<TextView>(R.id.btnRefreshFullscreenGraph).setOnClickListener { loadGraph() }

        WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        loadGraph()
    }

    private fun loadGraph() {
        subtitle.text = "Chargement de la mémoire…"
        CoroutineScope(Dispatchers.Main).launch {
            val g = withContext(Dispatchers.IO) {
                ObsidianController.buildKnowledgeGraph(this@KnowledgeGraphFullscreenActivity, maxNodes = 200)
            }
            if (g.nodes.isEmpty()) {
                graph.setEmptyMessage("Vault vide — crée des notes avec [[liens]]")
                graph.setGraph(emptyList(), emptyList())
                subtitle.text = "Aucune note"
            } else {
                graph.setGraph(
                    g.nodes.map { KnowledgeGraphView.GraphNode(it.id, it.label, it.folder, path = it.path) },
                    g.edges.map { KnowledgeGraphView.GraphEdge(it.from, it.to) }
                )
                graph.triggerExplosion()
                subtitle.text = "${g.nodes.size} notes · ${g.edges.size} liens — tape un nœud"
            }
        }
    }
}
