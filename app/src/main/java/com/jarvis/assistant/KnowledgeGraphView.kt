package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Graphe de connaissance style « orbe JARVIS / Tech en Clair » :
 * chaque note du vault = un nœud, chaque [[wikilink]] = un lien lumineux.
 * Disposition en sphère 3D projetée, rotation lente, effet « astre ».
 */
class KnowledgeGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class GraphNode(val id: String, val label: String, val folder: String, var degree: Int = 0)
    data class GraphEdge(val from: String, val to: String)

    private data class Node3D(
        val node: GraphNode,
        var x: Float, var y: Float, var z: Float,
        var sx: Float = 0f, var sy: Float = 0f, var depth: Float = 0f
    )

    var accentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
        set(value) { field = value; invalidate() }

    /** Plein écran : rotation plus rapide, plus de liens visibles, labels plus nombreux. */
    var explodeMode: Boolean = false
        set(value) {
            field = value
            animator.duration = if (value) 22_000L else 40_000L
            invalidate()
        }

    private var explodeProgress = 1f // 0 = centre, 1 = sphère déployée
    private var explodeAnimator: ValueAnimator? = null

    private var nodes3d = listOf<Node3D>()
    private var edges = listOf<GraphEdge>()
    private var selectedId: String? = null
    private var rotationY = 0f
    private var rotationX = 0.25f
    private var emptyMessage = "Aucune note — initialise le vault ou crée des notes."

    var onNodeTap: ((GraphNode) -> Unit)? = null

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
        color = Color.parseColor("#E8ECF4")
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 36f
        color = Color.parseColor("#88A0C0")
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 40_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationY += if (explodeMode) 0.014f else 0.008f
            project()
            invalidate()
        }
    }

    fun setGraph(nodes: List<GraphNode>, links: List<GraphEdge>) {
        edges = links
        val degree = mutableMapOf<String, Int>()
        links.forEach { e ->
            degree[e.from] = (degree[e.from] ?: 0) + 1
            degree[e.to] = (degree[e.to] ?: 0) + 1
        }
        val enriched = nodes.map { it.copy(degree = degree[it.id] ?: 0) }
        val n = enriched.size.coerceAtLeast(1)
        val golden = Math.PI * (3.0 - sqrt(5.0))
        nodes3d = enriched.mapIndexed { i, node ->
            val y = if (n == 1) 0f else 1f - (i / (n - 1f)) * 2f
            val r = sqrt((1 - y * y).toDouble()).toFloat()
            val theta = (golden * i).toFloat()
            Node3D(node, cos(theta) * r, y, sin(theta) * r)
        }
        selectedId = null
        explodeProgress = if (explodeMode) 0f else 1f
        project()
        invalidate()
        if (!animator.isStarted) animator.start()
    }

    /** Animation d'explosion : les nœuds partent du centre et se déploient en sphère. */
    fun triggerExplosion() {
        explodeAnimator?.cancel()
        explodeProgress = 0f
        explodeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            interpolator = android.view.animation.OvershootInterpolator(1.1f)
            addUpdateListener {
                explodeProgress = it.animatedValue as Float
                project()
                invalidate()
            }
            start()
        }
    }

    fun setEmptyMessage(msg: String) {
        emptyMessage = msg
        invalidate()
    }

    private fun project() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val baseScale = if (explodeMode) 0.44f else 0.38f
        val scale = (minOf(width, height) * baseScale * explodeProgress.coerceAtLeast(0.02f)).coerceAtLeast(40f)
        val cosY = cos(rotationY)
        val sinY = sin(rotationY)
        val cosX = cos(rotationX)
        val sinX = sin(rotationX)
        nodes3d.forEach { p ->
            var x = p.x * cosY - p.z * sinY
            var z = p.x * sinY + p.z * cosY
            var y = p.y * cosX - z * sinX
            z = p.y * sinX + z * cosX
            p.depth = z
            p.sx = cx + x * scale
            p.sy = cy + y * scale
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (nodes3d.isNotEmpty()) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.42f

        // Fond orbe / halo central
        corePaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                Color.argb(40, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(12, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, corePaint)
        corePaint.shader = null

        if (nodes3d.isEmpty()) {
            canvas.drawText(emptyMessage, cx, cy, hintPaint)
            return
        }

        val byId = nodes3d.associateBy { it.node.id }
        val sorted = nodes3d.sortedBy { it.depth }

        // Liens (du plus lointain au plus proche)
        edgePaint.color = Color.argb(90, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        edges.forEach { e ->
            val a = byId[e.from] ?: return@forEach
            val b = byId[e.to] ?: return@forEach
            val alpha = ((a.depth + b.depth + 2f) / 4f * 120 + 40).toInt().coerceIn(30, 140)
            edgePaint.alpha = alpha
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, edgePaint)
        }

        // Nœuds
        sorted.forEach { p ->
            val depthNorm = ((p.depth + 1f) / 2f).coerceIn(0f, 1f)
            val baseR = 6f + p.node.degree * 2.2f + depthNorm * 4f
            val selected = p.node.id == selectedId
            val radius = if (selected) baseR * 1.6f else baseR

            // Glow
            glowPaint.shader = RadialGradient(
                p.sx, p.sy, radius * 3.5f,
                intArrayOf(
                    Color.argb(if (selected) 180 else 100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(p.sx, p.sy, radius * 3.5f, glowPaint)
            glowPaint.shader = null

            nodePaint.color = if (selected) Color.WHITE else accentColor
            nodePaint.alpha = (140 + depthNorm * 115).toInt().coerceIn(120, 255)
            canvas.drawCircle(p.sx, p.sy, radius, nodePaint)

            if (selected || p.node.degree >= 2 || nodes3d.size <= 24 || (explodeMode && depthNorm > 0.45f)) {
                labelPaint.alpha = if (selected) 255 else (100 + depthNorm * 100).toInt()
                labelPaint.textSize = if (selected) 32f else 22f
                val label = p.node.label.take(22)
                canvas.drawText(label, p.sx, p.sy - radius - 10f, labelPaint)
            }
        }

        // Compteur
        hintPaint.textSize = 28f
        hintPaint.alpha = 160
        canvas.drawText(
            "${nodes3d.size} notes · ${edges.size} liens",
            cx, height - 28f,
            hintPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && nodes3d.isNotEmpty()) {
            val hit = nodes3d.minByOrNull { hypot2(it.sx - event.x, it.sy - event.y) }
            if (hit != null && hypot2(hit.sx - event.x, hit.sy - event.y) < 50f * 50f) {
                selectedId = hit.node.id
                onNodeTap?.invoke(hit.node)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hypot2(a: Float, b: Float) = a * a + b * b
}
