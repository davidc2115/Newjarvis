package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vue plein-écran interactive pour explorer le graphe RÉEL du vault Obsidian — pendant/liaison
 * (pan tactile), zoom (pincement), tap sur un nœud pour voir son détail complet (titre entier +
 * contenu de la note si c'en est une). Contrairement à OrbView.drawObsidianWeb() (petit rond en
 * arrière-plan du chat, volontairement compact et peu de libellés pour rester lisible), cette
 * vue est l'écran DÉDIÉ demandé explicitement par l'utilisateur pour "voir chaque lien et point"
 * — même thème visuel "cosmos/univers" (étoiles, nébuleuse) pour rester cohérent avec l'orb.
 */
class VaultGraphExplorerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var accentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
        set(value) { field = value; invalidate() }

    /** Appelé quand l'utilisateur tape sur un nœud — l'activité affiche alors son détail. */
    var onNodeTapped: ((ObsidianController.VaultGraphNode) -> Unit)? = null

    private data class LaidOutNode(val node: ObsidianController.VaultGraphNode, val bx: Float, val by: Float, val isHub: Boolean, val isFolder: Boolean = false)

    private var graph: ObsidianController.VaultGraph? = null
    private var laidOut: List<LaidOutNode> = emptyList()

    // Position/écran de chaque nœud recalculée à chaque frame dans onDraw() et réutilisée
    // telle quelle par handleTap() -- évite de dupliquer la logique de projection pan/zoom.
    private var lastScreenPositions: List<Triple<Float, Float, Int>> = emptyList() // x, y, index dans laidOut

    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private var selectedLaidOutIndex = -1

    fun setGraph(g: ObsidianController.VaultGraph?) {
        graph = g
        layoutGraph()
        invalidate()
    }

    /** Même disposition hiérarchique que l'orb (voir OrbView.layoutFromGraph pour le
     * raisonnement complet) : "🧠 Cerveau" au centre, dossiers du vault en cercle autour, chaque
     * note membre en étoile autour du point de SON dossier, le reste (notes isolées, Mémoire,
     * Génération) en spirale de Fibonacci dans la bande extérieure. Recalculée uniquement quand
     * le graphe change. */
    private fun layoutGraph() {
        val g = graph
        if (g == null || g.nodes.isEmpty()) {
            laidOut = emptyList()
            return
        }
        val count = g.nodes.size
        val result = MutableList<LaidOutNode?>(count) { null }

        val hubIdx = g.nodes.indices.maxByOrNull { g.nodes[it].degree } ?: 0
        result[hubIdx] = LaidOutNode(g.nodes[hubIdx], 0f, 0f, isHub = true)

        val neighborsOf = HashMap<Int, MutableList<Int>>()
        g.edges.forEach { (a, b) ->
            neighborsOf.getOrPut(a) { mutableListOf() }.add(b)
            neighborsOf.getOrPut(b) { mutableListOf() }.add(a)
        }

        val placed = HashSet<Int>()
        placed.add(hubIdx)
        val folderIndices = g.nodes.indices.filter { it != hubIdx && g.nodes[it].isFolder }
        val folderRadius = 0.5f
        val memberRadius = 0.4f
        folderIndices.forEachIndexed { fi, folderIdx ->
            val theta = if (folderIndices.size <= 1) 0.0 else 2.0 * Math.PI * fi / folderIndices.size
            val fx = (cos(theta) * folderRadius).toFloat()
            val fy = (sin(theta) * folderRadius).toFloat()
            result[folderIdx] = LaidOutNode(g.nodes[folderIdx], fx, fy, isHub = false, isFolder = true)
            placed.add(folderIdx)

            val members = (neighborsOf[folderIdx] ?: emptyList()).filter { it != hubIdx && it !in folderIndices }
            members.forEachIndexed { mi, memberIdx ->
                val mTheta = if (members.size <= 1) theta else 2.0 * Math.PI * mi / members.size
                val mx = (fx + cos(mTheta) * memberRadius).toFloat()
                val my = (fy + sin(mTheta) * memberRadius).toFloat()
                result[memberIdx] = LaidOutNode(g.nodes[memberIdx], mx, my, isHub = false)
                placed.add(memberIdx)
            }
        }

        val leftover = g.nodes.indices.filter { it !in placed }
        val order = leftover.sortedByDescending { g.nodes[it].degree }
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        order.forEachIndexed { rank, originalIdx ->
            val r = 0.62f + 0.38f * sqrt(rank / (order.size - 1f).coerceAtLeast(1f))
            val theta = goldenAngle * rank
            val x = (cos(theta) * r).toFloat()
            val y = (sin(theta) * r).toFloat()
            result[originalIdx] = LaidOutNode(g.nodes[originalIdx], x, y, isHub = false)
        }

        laidOut = result.filterNotNull()
        selectedLaidOutIndex = -1
    }

    // ── Habillage cosmos (fond étoilé + nébuleuse), positions fixes indépendantes du graphe,
    // même logique que OrbView -- voir son commentaire pour le détail du raisonnement. ─────────
    private data class StarDot(val rx: Float, val ry: Float, val size: Float, val phase: Float, val baseAlpha: Int)
    private val starField: List<StarDot> by lazy {
        val rng = kotlin.random.Random(7)
        List(160) {
            StarDot(
                rx = rng.nextFloat() * 3.2f - 1.6f,
                ry = rng.nextFloat() * 3.2f - 1.6f,
                size = 1f + rng.nextFloat() * 2.6f,
                phase = rng.nextFloat() * 2f * Math.PI.toFloat(),
                baseAlpha = 30 + rng.nextInt(110)
            )
        }
    }

    private var twinklePhase = 0f
    private val twinkleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { twinklePhase = it.animatedValue as Float; invalidate() }
    }

    init {
        twinkleAnimator.start()
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val folderIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        val baseScale = minOf(width, height) / 2f * 0.85f
        val scale = baseScale * scaleFactor

        // Nébuleuse + fond étoilé, toujours en tout premier (arrière-plan).
        glowPaint.shader = RadialGradient(
            cx - scale * 0.25f, cy - scale * 0.2f, scale * 1.1f,
            intArrayOf(adjustAlpha(accentColor, 0.14f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx - scale * 0.25f, cy - scale * 0.2f, scale * 1.1f, glowPaint)
        glowPaint.shader = RadialGradient(
            cx + scale * 0.3f, cy + scale * 0.28f, scale * 0.85f,
            intArrayOf(adjustAlpha(Color.WHITE, 0.06f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx + scale * 0.3f, cy + scale * 0.28f, scale * 0.85f, glowPaint)
        glowPaint.shader = null

        val cosmicT = twinklePhase * 2f * Math.PI.toFloat()
        starField.forEach { s ->
            val twinkle = 0.5f + 0.5f * sin(cosmicT + s.phase)
            nodePaint.shader = null
            nodePaint.color = Color.WHITE
            nodePaint.alpha = (s.baseAlpha * (0.4f + 0.6f * twinkle)).toInt().coerceIn(10, 200)
            canvas.drawCircle(cx + s.rx * scale, cy + s.ry * scale, s.size * scaleFactor.coerceAtMost(2f), nodePaint)
        }

        val g = graph
        if (g == null || laidOut.isEmpty()) {
            lastScreenPositions = emptyList()
            return
        }

        val screenPositions = laidOut.mapIndexed { idx, n ->
            Triple(cx + n.bx * scale, cy + n.by * scale, idx)
        }
        lastScreenPositions = screenPositions

        // Liens réels du graphe (les indices de laidOut correspondent à ceux de graph.nodes,
        // reconstruits dans le même ordre par layoutGraph() ci-dessus).
        linePaint.color = accentColor
        g.edges.forEach { (i, j) ->
            if (i < screenPositions.size && j < screenPositions.size) {
                val (x1, y1, _) = screenPositions[i]
                val (x2, y2, _) = screenPositions[j]
                linePaint.alpha = 70
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }
        }

        // Libellés : tous visibles si on est suffisamment zoomé, sinon seulement les nœuds les
        // plus connectés (comme sur l'orb compact) pour rester lisible dézoomé.
        val labelCutoff = if (scaleFactor > 1.6f) laidOut.size else minOf(10, laidOut.size)
        val order = laidOut.indices.sortedByDescending { laidOut[it].node.degree }
        val rankOf = HashMap<Int, Int>()
        order.forEachIndexed { rank, idx -> rankOf[idx] = rank }

        screenPositions.forEach { (x, y, idx) ->
            val laid = laidOut[idx]
            val isSelected = idx == selectedLaidOutIndex
            if (laid.isHub) {
                nodePaint.shader = RadialGradient(
                    x, y, scale * 0.09f,
                    intArrayOf(Color.WHITE, accentColor, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(x, y, scale * 0.09f, nodePaint)
                nodePaint.shader = null
            } else if (laid.isFolder) {
                // Dossier du vault (ex. "Contacts") : icône 📁 visible au lieu du simple point,
                // demande utilisateur explicite ("les dossiers affichés avec leur icône").
                nodePaint.shader = RadialGradient(
                    x, y, scale * 0.07f,
                    intArrayOf(adjustAlpha(accentColor, 0.5f), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(x, y, scale * 0.07f, nodePaint)
                nodePaint.shader = null
                folderIconPaint.textSize = (26f + 12f * scaleFactor).coerceIn(26f, 56f)
                folderIconPaint.alpha = if (isSelected) 255 else 235
                canvas.drawText("📁", x, y + folderIconPaint.textSize * 0.32f, folderIconPaint)
            } else {
                nodePaint.color = accentColor
                nodePaint.alpha = if (isSelected) 255 else 170
                canvas.drawCircle(x, y, if (isSelected) 8f else 5f, nodePaint)
            }

            if (isSelected) {
                val selectionRadius = when {
                    laid.isHub -> scale * 0.09f
                    laid.isFolder -> scale * 0.07f
                    else -> 5f
                }
                selectionRingPaint.color = Color.WHITE
                selectionRingPaint.alpha = 220
                canvas.drawCircle(x, y, selectionRadius + 10f, selectionRingPaint)
            }

            val showLabel = laid.node.forceLabel || (rankOf[idx] ?: Int.MAX_VALUE) < labelCutoff || isSelected
            if (showLabel) {
                labelPaint.textSize = (16f + 6f * scaleFactor).coerceIn(16f, 34f)
                labelPaint.color = if (isSelected) Color.WHITE else accentColor
                labelPaint.alpha = if (laid.isHub || isSelected) 235 else 175
                val maxChars = if (scaleFactor > 1.6f) 40 else 16
                val label = if (laid.node.title.length > maxChars) laid.node.title.take(maxChars - 1) + "…" else laid.node.title
                // Décalage plus grand sous un dossier : son icône 📁 (plus grande qu'un simple
                // point) déborderait sinon sur le libellé.
                val extraOffset = if (laid.isFolder) folderIconPaint.textSize * 0.6f else 0f
                canvas.drawText(label, x, y + 16f + labelPaint.textSize * 0.9f + extraOffset, labelPaint)
            }
        }
    }

    // ── Gestes tactiles : pincement pour zoomer, glissement pour déplacer la vue, tap pour
    // sélectionner un nœud, double-tap pour recentrer/dézoomer instantanément. ─────────────────
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.4f, 6f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scaleFactor = 1f
            panX = 0f
            panY = 0f
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        if (lastScreenPositions.isEmpty()) return
        val touchRadiusPx = 48f
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        lastScreenPositions.forEach { (x, y, idx) ->
            val d = hypot((x - screenX).toDouble(), (y - screenY).toDouble()).toFloat()
            if (d < touchRadiusPx && d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        if (bestIdx >= 0) {
            selectedLaidOutIndex = bestIdx
            invalidate()
            onNodeTapped?.invoke(laidOut[bestIdx].node)
        }
    }

    override fun onDetachedFromWindow() {
        twinkleAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
