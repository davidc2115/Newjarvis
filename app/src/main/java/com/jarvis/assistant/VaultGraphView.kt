package com.jarvis.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Vue Canvas custom pour la "toile Obsidian" (graphe visuel du vault, tâche #226) : les notes
 * sont des nœuds (cercles), les [[wikilinks]] entre elles sont des arêtes (traits). Layout
 * volontairement simple et DÉTERMINISTE (pas de simulation physique/force-directed, ce qui
 * demanderait un moteur d'itération continue coûteux en CPU pour un gain visuel marginal ici) :
 * la note "Mémoire JARVIS" (voir ObsidianController.MEMORY_NOTE_TITLE), quand elle existe, sert
 * de hub central ; les notes qui lui sont directement reliées forment un anneau intérieur,
 * toutes les autres un anneau extérieur. Sans note "Mémoire JARVIS" dans le vault, on retombe
 * sur un anneau unique centré, ce qui reste un graphe lisible pour un petit vault.
 *
 * Interaction : glisser un doigt pour déplacer la vue (pan), pincer pour zoomer, taper un nœud
 * pour l'ouvrir (callback [onNodeTap]).
 */
class VaultGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onNodeTap: ((String) -> Unit)? = null

    private var nodes: List<ObsidianController.NoteNode> = emptyList()
    private val positions = mutableMapOf<String, PointF>()
    private val degree = mutableMapOf<String, Int>()

    private var panX = 0f
    private var panY = 0f
    private var scale = 1f

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33FFFFFF")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var accentColor = Color.parseColor("#00E5FF")

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = findNodeAt(e.x, e.y)
            if (hit != null) onNodeTap?.invoke(hit)
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(0.4f, 3f)
            invalidate()
            return true
        }
    })

    /** Couleur d'accent (thème utilisateur, voir SettingsActivity) pour le nœud central. */
    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    fun setGraph(newNodes: List<ObsidianController.NoteNode>) {
        nodes = newNodes
        degree.clear()
        for (n in nodes) {
            degree[n.title] = (degree[n.title] ?: 0) + n.linkedTitles.size
            for (t in n.linkedTitles) degree[t] = (degree[t] ?: 0) + 1
        }
        computeLayout()
        // Recentre la vue au premier affichage (pan/scale par défaut) plutôt que de garder un
        // ancien pan qui viserait dans le vide sur un nouveau graphe.
        panX = 0f
        panY = 0f
        scale = 1f
        invalidate()
    }

    private fun computeLayout() {
        positions.clear()
        if (nodes.isEmpty()) return
        val hubTitle = nodes.firstOrNull { it.title.equals(ObsidianController.MEMORY_NOTE_TITLE, ignoreCase = true) }?.title

        val allTitles = nodes.map { it.title }.toMutableList()
        val hub = hubTitle ?: allTitles.maxByOrNull { degree[it] ?: 0 } ?: allTitles.first()
        positions[hub] = PointF(0f, 0f)
        allTitles.remove(hub)

        val hubNode = nodes.firstOrNull { it.title == hub }
        val innerSet = mutableSetOf<String>()
        hubNode?.linkedTitles?.forEach { innerSet.add(it) }
        nodes.forEach { if (it.linkedTitles.contains(hub)) innerSet.add(it.title) }
        innerSet.remove(hub)
        val inner = allTitles.filter { innerSet.contains(it) }.sorted()
        val outer = allTitles.filter { !innerSet.contains(it) }.sorted()

        val innerRadius = 320f
        val outerRadius = 620f
        inner.forEachIndexed { i, title ->
            val angle = 2.0 * Math.PI * i / inner.size.coerceAtLeast(1)
            positions[title] = PointF((innerRadius * cos(angle)).toFloat(), (innerRadius * sin(angle)).toFloat())
        }
        outer.forEachIndexed { i, title ->
            val angle = 2.0 * Math.PI * i / outer.size.coerceAtLeast(1) + 0.3
            positions[title] = PointF((outerRadius * cos(angle)).toFloat(), (outerRadius * sin(angle)).toFloat())
        }
    }

    private fun toScreen(p: PointF): PointF {
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        return PointF(cx + p.x * scale, cy + p.y * scale)
    }

    private fun nodeRadius(title: String): Float {
        val isHub = title.equals(ObsidianController.MEMORY_NOTE_TITLE, ignoreCase = true)
        val base = if (isHub) 34f else 22f + (degree[title]?.coerceAtMost(6) ?: 0) * 2f
        return base * scale
    }

    private fun findNodeAt(x: Float, y: Float): String? {
        for ((title, pos) in positions) {
            val screen = toScreen(pos)
            val r = nodeRadius(title)
            if (hypot((x - screen.x).toDouble(), (y - screen.y).toDouble()) <= r) return title
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        // Arêtes d'abord (sous les nœuds).
        for (node in nodes) {
            val from = positions[node.title] ?: continue
            for (linked in node.linkedTitles) {
                val to = positions[linked] ?: continue
                val a = toScreen(from)
                val b = toScreen(to)
                canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
            }
        }

        // Nœuds ensuite.
        textPaint.textSize = 12f * min(scale, 1.4f)
        for (node in nodes) {
            val pos = positions[node.title] ?: continue
            val screen = toScreen(pos)
            val r = nodeRadius(node.title)
            val isHub = node.title.equals(ObsidianController.MEMORY_NOTE_TITLE, ignoreCase = true)
            val paint = if (isHub) hubPaint.apply { color = accentColor } else nodePaint.apply {
                color = Color.parseColor("#332A3A55")
            }
            canvas.drawCircle(screen.x, screen.y, r, paint)
            canvas.drawCircle(screen.x, screen.y, r, nodeStrokePaint)
            val label = if (node.title.length > 14) node.title.take(13) + "…" else node.title
            canvas.drawText(label, screen.x, screen.y + r + textPaint.textSize + 4f, textPaint)
        }
    }
}
