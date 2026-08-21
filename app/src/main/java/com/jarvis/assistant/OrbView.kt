package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }
    enum class VisualStyle { PULSE, NETWORK_SPHERE, OBSIDIAN_WEB, NEURAL_CORE }

    var accentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
        set(value) {
            field = value
            invalidate()
        }

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            updateAnimatorSpeed()
        }

    var visualStyle: VisualStyle = VisualStyle.PULSE
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Niveau sonore courant (0f = silence, 1f = fort), alimenté depuis l'extérieur par
     * VoiceModeActivity.onRmsChanged (le callback RMS déjà fourni par SpeechRecognizer pendant
     * l'écoute — PAS un second AudioRecord/MediaRecorder ouvert exprès pour l'orbe, ce qui
     * aurait un coût batterie/RAM inutile et pourrait entrer en conflit avec la reconnaissance
     * vocale déjà active sur le même micro). Lissé image par image vers smoothedAudioLevel
     * pour un rendu fluide même si le callback RMS n'arrive que toutes les ~100-300ms.
     */
    var audioLevel: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private var smoothedAudioLevel = 0f

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
    }

    private data class Point3D(val x: Float, val y: Float, val z: Float)

    private val spherePoints: List<Point3D> by lazy { generateSpherePoints(90) }

    private fun generateSpherePoints(count: Int): List<Point3D> {
        val points = mutableListOf<Point3D>()
        val golden = Math.PI * (3.0 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1f - (i / (count - 1f)) * 2f
            val radiusAtY = sqrt((1 - y * y).toDouble()).toFloat()
            val theta = golden * i
            val x = (cos(theta) * radiusAtY).toFloat()
            val z = (sin(theta) * radiusAtY).toFloat()
            points.add(Point3D(x, y, z))
        }
        return points
    }

    private var pulsePhase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            smoothedAudioLevel += (audioLevel - smoothedAudioLevel) * 0.15f
            invalidate()
        }
    }

    init {
        animator.start()
    }

    private fun updateAnimatorSpeed() {
        animator.duration = when (state) {
            OrbState.IDLE -> 3200L
            OrbState.LISTENING -> 1100L
            OrbState.THINKING -> 650L
            // Volontairement le plus LENT de tous les etats (avant : 900L, plus rapide
            // que IDLE) -- demande explicite : l'orb doit ralentir et devenir contemplative
            // pendant que JARVIS parle, pas s'agiter comme pendant l'ecoute/la reflexion.
            OrbState.SPEAKING -> 4200L
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (visualStyle) {
            VisualStyle.PULSE -> drawPulseOrb(canvas)
            VisualStyle.NETWORK_SPHERE -> drawNetworkSphere(canvas)
            VisualStyle.OBSIDIAN_WEB -> drawObsidianWeb(canvas)
            VisualStyle.NEURAL_CORE -> drawNeuralCore(canvas)
        }
    }

    private fun drawPulseOrb(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2f * 0.5f

        val ringCount = 3
        for (i in 0 until ringCount) {
            val progress = (pulsePhase + i / ringCount.toFloat()) % 1f
            val radius = baseRadius * (0.95f + progress * 0.7f)
            val alpha = ((1f - progress) * 90).toInt().coerceIn(0, 90)
            ringPaint.color = accentColor
            ringPaint.alpha = alpha
            ringPaint.strokeWidth = 3f + (1f - progress) * 4f
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        val coreRadius = baseRadius * (0.55f + 0.08f * sin(pulsePhase * 2 * Math.PI).toFloat())
        corePaint.shader = RadialGradient(
            cx, cy, coreRadius * 1.9f,
            intArrayOf(accentColor, adjustAlpha(accentColor, 0.35f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius * 1.9f, corePaint)

        corePaint.shader = null
        corePaint.color = Color.argb(
            230,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        canvas.drawCircle(cx, cy, coreRadius * 0.45f, corePaint)
    }

    // Angles/longueurs fixes des rayons du cœur lumineux (générés une fois, pas à
    // chaque frame) — évite un effet de scintillement aléatoire disgracieux.
    private val rayAngles: List<Float> by lazy { (0 until 16).map { (it / 16f) * 2f * Math.PI.toFloat() } }
    private val rayLengths: List<Float> by lazy { List(16) { 0.55f + (it % 5) * 0.09f } }

    private fun drawNetworkSphere(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) / 2f * 0.85f

        // vitesse de rotation liée à l'état (écoute/réflexion/parole = plus rapide)
        val speedFactor = when (state) {
            OrbState.IDLE -> 0.4f
            OrbState.LISTENING -> 1f
            OrbState.THINKING -> 1.8f
            OrbState.SPEAKING -> 1.3f
        }
        val angle = pulsePhase * 2f * Math.PI.toFloat() * speedFactor

        // Cœur lumineux avec rayons radiants (façon étoile/soleil) — c'est ce qui
        // donne l'aspect "sphère de particules lumineuse" de la référence visuelle,
        // en plus du maillage de points déjà existant ci-dessous.
        drawRadiantCore(canvas, cx, cy, scale, angle)

        val cosA = cos(angle)
        val sinA = sin(angle)
        val wobble = 0.25f * sin(angle * 0.5f)
        val cosW = cos(wobble)
        val sinW = sin(wobble)

        val screenPoints = spherePoints.map { p ->
            // rotation autour de l'axe Y
            val rx = p.x * cosA - p.z * sinA
            val rz = p.x * sinA + p.z * cosA
            // léger balancement autour de l'axe X
            val ry = p.y * cosW - rz * sinW
            val rz2 = p.y * sinW + rz * cosW
            Triple(cx + rx * scale, cy + ry * scale, rz2)
        }

        val threshold = scale * 0.42f
        for (i in screenPoints.indices) {
            val (x1, y1, z1) = screenPoints[i]
            for (j in i + 1 until screenPoints.size) {
                val (x2, y2, z2) = screenPoints[j]
                val dist = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
                if (dist < threshold) {
                    val avgZ = (z1 + z2) / 2f
                    val alpha = (((avgZ + 1f) / 2f) * 70).toInt().coerceIn(6, 70)
                    linePaint.color = accentColor
                    linePaint.alpha = alpha
                    canvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        }

        for ((x, y, z) in screenPoints) {
            val depth = (z + 1f) / 2f
            val alpha = (depth * 210 + 45).toInt().coerceIn(45, 255)
            dotPaint.color = accentColor
            dotPaint.alpha = alpha
            val r = 2f + depth * 2.6f
            canvas.drawCircle(x, y, r, dotPaint)
        }
    }

    /**
     * Dessine un cœur lumineux d'où partent de fins rayons vers l'extérieur, en
     * rotation lente et indépendante du maillage de points — c'est cet effet
     * "étoile/soleil au centre d'une toile" qui rapproche le rendu de la sphère
     * de particules demandée en référence, plutôt qu'un simple maillage plat.
     */
    private fun drawRadiantCore(canvas: Canvas, cx: Float, cy: Float, scale: Float, angle: Float) {
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f }
        val rotation = angle * 0.3f // rotation plus lente que le maillage pour un effet de profondeur
        rayAngles.forEachIndexed { i, baseAngle ->
            val a = baseAngle + rotation
            val length = scale * rayLengths[i]
            val endX = cx + cos(a) * length
            val endY = cy + sin(a) * length
            rayPaint.color = accentColor
            rayPaint.alpha = 55
            rayPaint.shader = null
            canvas.drawLine(cx, cy, endX, endY, rayPaint)
        }

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val glowRadius = scale * 0.22f
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(Color.WHITE, accentColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Style "Neural Core" (HUD sci-fi) : le nuage de points relié par des lignes de
    // proximité déjà présent dans NETWORK_SPHERE, mais REÉLLEMENT réactif au niveau
    // sonore (voir audioLevel/smoothedAudioLevel ci-dessus) — rotation, vibration des
    // points, luminosité du cœur et densité des liens augmentent avec le volume détecté
    // pendant l'écoute — plus deux anneaux réticule pointillés en rotation indépendante
    // et 4 crochets d'angle façon viseur, pour l'esthétique "HUD" demandée. Entièrement
    // en Canvas natif (pas de WebView/Three.js/Spline) : coût batterie/RAM identique aux
    // autres styles déjà existants.
    // ─────────────────────────────────────────────────────────────────────────

    // Décalage de phase individuel par point (généré une fois) pour que la vibration
    // audio-réactive de chaque point soit désynchronisée des autres — sinon tout
    // vibrerait à l'unisson, effet mécanique plutôt qu'organique.
    private val neuralJitterPhases: List<Float> by lazy {
        spherePoints.indices.map { (it * 0.6180339887f) % 1f * 2f * Math.PI.toFloat() }
    }

    private fun drawNeuralCore(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) / 2f * 0.85f
        val level = smoothedAudioLevel // 0f (silence) .. 1f (fort)

        val speedFactor = when (state) {
            OrbState.IDLE -> 0.35f
            OrbState.LISTENING -> 0.9f
            OrbState.THINKING -> 1.7f
            OrbState.SPEAKING -> 1.2f
        } * (1f + level * 0.8f) // plus rapide quand le micro capte du son
        val angle = pulsePhase * 2f * Math.PI.toFloat() * speedFactor

        drawPulsingCore(canvas, cx, cy, scale, level)
        drawRadiantRays(canvas, cx, cy, scale, angle, level)
        drawHudReticle(canvas, cx, cy, scale)

        val cosA = cos(angle)
        val sinA = sin(angle)
        val wobble = 0.25f * sin(angle * 0.5f)
        val cosW = cos(wobble)
        val sinW = sin(wobble)

        // Amplitude de la vibration audio-réactive des points, en fraction du rayon —
        // volontairement subtile (0 au repos, jusqu'à ~6% du rayon à plein volume).
        val jitterAmount = scale * 0.06f * level

        val screenPoints = spherePoints.mapIndexed { i, p ->
            val rx = p.x * cosA - p.z * sinA
            val rz = p.x * sinA + p.z * cosA
            val ry = p.y * cosW - rz * sinW
            val rz2 = p.y * sinW + rz * cosW
            val jitter = if (jitterAmount > 0.01f) {
                sin(pulsePhase * 14f * Math.PI.toFloat() + neuralJitterPhases[i]) * jitterAmount
            } else 0f
            Triple(cx + rx * scale + jitter, cy + ry * scale + jitter, rz2)
        }

        // Seuil de connexion des lignes légèrement élargi avec le volume : le maillage
        // se densifie visuellement quand JARVIS capte un son fort, plutôt que de rester
        // figé — c'est ce qui donne l'impression d'un "noyau neuronal" qui réagit.
        val threshold = scale * (0.40f + level * 0.10f)
        for (i in screenPoints.indices) {
            val (x1, y1, z1) = screenPoints[i]
            for (j in i + 1 until screenPoints.size) {
                val (x2, y2, z2) = screenPoints[j]
                val dist = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
                if (dist < threshold) {
                    val avgZ = (z1 + z2) / 2f
                    val alpha = (((avgZ + 1f) / 2f) * (70 + level * 60)).toInt().coerceIn(6, 130)
                    linePaint.color = accentColor
                    linePaint.alpha = alpha
                    canvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        }

        for ((x, y, z) in screenPoints) {
            val depth = (z + 1f) / 2f
            val alpha = (depth * 210 + 45 + level * 40).toInt().coerceIn(45, 255)
            dotPaint.color = accentColor
            dotPaint.alpha = alpha
            val r = 2f + depth * 2.6f + level * 1.5f
            canvas.drawCircle(x, y, r, dotPaint)
        }
    }

    /** Cœur central compact et vif (plus petit/plus intense que drawRadiantCore), dont la
     * taille et la luminosité pulsent avec le niveau sonore réel plutôt qu'un simple cycle. */
    private fun drawPulsingCore(canvas: Canvas, cx: Float, cy: Float, scale: Float, level: Float) {
        val basePulse = 0.5f + 0.06f * sin(pulsePhase * 2f * Math.PI).toFloat()
        val coreRadius = scale * (0.14f + basePulse * 0.05f + level * 0.09f)
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.shader = RadialGradient(
            cx, cy, coreRadius * 2.4f,
            intArrayOf(Color.WHITE, accentColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (200 + level * 55).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, coreRadius * 2.4f, glowPaint)
    }

    /**
     * Rayons lumineux qui partent du cœur et s'estompent vers leurs extrémités (dégradé par
     * rayon, comme le sursaut d'étoile de l'image de référence) — l'élément le plus visible du
     * rendu demandé. Réutilise rayAngles/rayLengths déjà définis pour NETWORK_SPHERE (mêmes
     * positions de base, pas de nouveau tableau), mais en plus longs/plus lumineux et
     * audio-réactifs (longueur et opacité augmentent avec le niveau sonore réel).
     */
    private fun drawRadiantRays(canvas: Canvas, cx: Float, cy: Float, scale: Float, angle: Float, level: Float) {
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val rotation = angle * 0.3f
        rayAngles.forEachIndexed { i, baseAngle ->
            val a = baseAngle + rotation
            val length = scale * rayLengths[i] * (1.35f + level * 0.5f)
            val endX = cx + cos(a) * length
            val endY = cy + sin(a) * length
            val alpha = (150 + level * 105).toInt().coerceIn(0, 255)
            rayPaint.strokeWidth = 1.1f + level * 1.2f
            rayPaint.shader = LinearGradient(
                cx, cy, endX, endY,
                Color.argb(alpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(cx, cy, endX, endY, rayPaint)
        }
    }

    /** Anneaux d'orbite fins et discrets autour du noyau — les arcs qui traversent l'image de
     * référence en arrière-plan. Cercles pleins (pas pointillés) : à cette taille et cette
     * opacité, un pointillé ne se distinguerait pas d'un trait plein, autant rester simple.
     * Un des trois est aplati (ellipse) pour évoquer un anneau vu de biais plutôt que 3 cercles
     * parfaitement concentriques, comme dans la référence. */
    private fun drawHudReticle(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        val ringPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
        ringPaint2.color = accentColor

        ringPaint2.alpha = 35
        canvas.drawCircle(cx, cy, scale * 1.15f, ringPaint2)
        ringPaint2.alpha = 22
        canvas.drawCircle(cx, cy, scale * 1.42f, ringPaint2)

        ringPaint2.alpha = 28
        canvas.save()
        canvas.rotate(-18f, cx, cy)
        canvas.scale(1f, 0.42f, cx, cy)
        canvas.drawCircle(cx, cy, scale * 1.65f, ringPaint2)
        canvas.restore()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Style "Toile Obsidian" : un graphe de nœuds à plat (comme la vue graphe
    // d'Obsidian), FIXE tant que JARVIS n'est pas actif, et qui prend vie
    // (nœuds qui dérivent doucement, liens qui pulsent) dès qu'il écoute,
    // réfléchit ou parle — demandé explicitement pour évoquer le vault de
    // notes plutôt qu'une sphère 3D générique.
    // ─────────────────────────────────────────────────────────────────────────

    private data class WebNode(val bx: Float, val by: Float, val phase: Float, val isHub: Boolean, val title: String? = null, val showLabel: Boolean = false)

    // Données RÉELLES du vault (notes = nœuds, [[wikilinks]] = liens) — voir
    // ObsidianController.buildVaultGraph(). Signalement utilisateur : l'ancien rendu était
    // purement décoratif (nœuds/liens procéduraux sans rapport avec le vault). Fournie par
    // l'activité hôte (VoiceModeActivity) après un scan en arrière-plan ; tant qu'elle n'est
    // pas encore chargée (ou vault vide/inaccessible), on retombe sur la disposition
    // procédurale d'origine plutôt que d'afficher un cercle vide.
    var graphData: ObsidianController.VaultGraph? = null
        set(value) {
            field = value
            cachedWebNodes = null
            invalidate()
        }

    private var cachedWebNodes: List<WebNode>? = null

    // Disposition en spirale de Fibonacci (phyllotaxie) : répartition organique et
    // homogène dans un disque. Recalculée uniquement quand graphData change (voir le setter
    // ci-dessus) — c'est ce qui garantit un rendu identique à chaque frame tant que les
    // données ne changent pas.
    private fun webNodes(): List<WebNode> {
        cachedWebNodes?.let { return it }
        val graph = graphData
        val computed = if (graph != null && graph.nodes.isNotEmpty()) {
            layoutFromGraph(graph)
        } else {
            generateWebNodes(22)
        }
        cachedWebNodes = computed
        return computed
    }

    /** Disposition procédurale de secours (tant que le vrai graphe n'est pas encore chargé). */
    private fun generateWebNodes(count: Int): List<WebNode> {
        val nodes = mutableListOf<WebNode>()
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        nodes.add(WebNode(0f, 0f, 0f, isHub = true))
        for (i in 1 until count) {
            val r = sqrt(i / (count - 1f))
            val theta = goldenAngle * i
            val x = (cos(theta) * r).toFloat()
            val y = (sin(theta) * r).toFloat()
            // phase individuelle (dérivée de l'index) pour que chaque nœud oscille de
            // façon désynchronisée des autres une fois animé — sinon tout bougerait à
            // l'unisson, effet mécanique plutôt qu'organique/vivant.
            val phase = (i * 0.6180339887f) % 1f * 2f * Math.PI.toFloat()
            nodes.add(WebNode(x, y, phase, isHub = false))
        }
        return nodes
    }

    /**
     * Même disposition en spirale de Fibonacci que generateWebNodes(), mais l'ORDRE dans le
     * disque suit le degré réel de chaque note (les plus connectées près du centre, comme le
     * fait naturellement la vue graphe d'Obsidian) au lieu d'un index arbitraire — la note la
     * plus reliée devient le nœud central mis en avant (isHub). Les indices du résultat
     * correspondent exactement à ceux de [graph.nodes]/[graph.edges] (pas de ré-indexation),
     * pour que drawObsidianWeb puisse tracer les vrais liens directement par index.
     */
    private fun layoutFromGraph(graph: ObsidianController.VaultGraph): List<WebNode> {
        val count = graph.nodes.size
        val order = graph.nodes.indices.sortedByDescending { graph.nodes[it].degree }
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        val result = MutableList(count) { WebNode(0f, 0f, 0f, false) }
        val labelCutoff = minOf(8, count)
        order.forEachIndexed { rank, originalIdx ->
            val title = graph.nodes[originalIdx].title
            // forceLabel : faits de Mémoire JARVIS / entrées de Génération -- leur libellé EST
            // l'information utile, donc toujours affiché même si leur "degré" (nombre de liens)
            // les place hors du top habituel (voir VaultGraphNode.forceLabel).
            val showLabel = rank < labelCutoff || graph.nodes[originalIdx].forceLabel
            val phase = (rank * 0.6180339887f) % 1f * 2f * Math.PI.toFloat()
            result[originalIdx] = if (rank == 0) {
                WebNode(0f, 0f, phase, isHub = true, title = title, showLabel = showLabel)
            } else {
                val r = sqrt(rank / (count - 1f).coerceAtLeast(1f))
                val theta = goldenAngle * rank
                val x = (cos(theta) * r).toFloat()
                val y = (sin(theta) * r).toFloat()
                WebNode(x, y, phase, isHub = false, title = title, showLabel = showLabel)
            }
        }
        return result
    }

    // Paint pour les libellés de titre (vrai graphe uniquement — voir graphData) ; taille et
    // couleur ajustées à la volée dans drawObsidianWeb() selon la couleur d'accent courante.
    private val webLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f
    }

    // ── Habillage "cosmos / univers" du style Toile Obsidian ────────────────────────────
    // Champ d'etoiles decoratif : positions FIXES (seed deterministe, pas de nouveau tirage
    // a chaque frame ni a chaque recomposition -- couteux et inutile), totalement independant
    // du graphe reel du vault. Pur habillage visuel demande explicitement ("plus facon
    // univers, cosmos") pour donner une impression de fond spatial/nebuleuse derriere les
    // noeuds/liens qui restent, eux, les vraies donnees du vault.
    private data class StarDot(val rx: Float, val ry: Float, val size: Float, val phase: Float, val baseAlpha: Int)

    private val starField: List<StarDot> by lazy { generateStarField(90) }

    private fun generateStarField(count: Int): List<StarDot> {
        val rng = kotlin.random.Random(42)
        return List(count) {
            StarDot(
                rx = rng.nextFloat() * 2.6f - 1.3f,
                ry = rng.nextFloat() * 2.6f - 1.3f,
                size = 1f + rng.nextFloat() * 2.3f,
                phase = rng.nextFloat() * 2f * Math.PI.toFloat(),
                baseAlpha = 35 + rng.nextInt(90)
            )
        }
    }

    /** Scintillement lent et desynchronise, volontairement independant de l'etat (IDLE/
     * LISTENING/THINKING/SPEAKING) -- le fond etoile doit rester un decor discret et stable,
     * ce sont les noeuds/liens du vault qui portent l'animation liee a l'activite de JARVIS. */
    private fun drawStarfield(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        val cosmicT = pulsePhase * 2f * Math.PI.toFloat() * 0.3f
        starField.forEach { s ->
            val twinkle = 0.5f + 0.5f * sin(cosmicT + s.phase)
            dotPaint.shader = null
            dotPaint.color = Color.WHITE
            dotPaint.alpha = (s.baseAlpha * (0.4f + 0.6f * twinkle)).toInt().coerceIn(12, 200)
            canvas.drawCircle(cx + s.rx * scale, cy + s.ry * scale, s.size, dotPaint)
        }
    }

    /** Deux nappes de "poussiere cosmique" en tres faible opacite (nebuleuse) -- assez pour
     * suggerer un fond spatial sans jamais nuire a la lisibilite du chat au-dessus (l'orb
     * entiere est deja affichee en alpha reduit via android:alpha dans activity_main.xml). */
    private fun drawNebulaGlow(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        dotPaint.shader = RadialGradient(
            cx - scale * 0.3f, cy - scale * 0.25f, scale * 0.95f,
            intArrayOf(adjustAlpha(accentColor, 0.16f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx - scale * 0.3f, cy - scale * 0.25f, scale * 0.95f, dotPaint)
        dotPaint.shader = RadialGradient(
            cx + scale * 0.35f, cy + scale * 0.3f, scale * 0.75f,
            intArrayOf(adjustAlpha(Color.WHITE, 0.07f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx + scale * 0.35f, cy + scale * 0.3f, scale * 0.75f, dotPaint)
        dotPaint.shader = null
    }

    /** Anneaux orbitaux discrets autour du hub (note la plus connectee), effet
     * "systeme solaire" cense renforcer le theme cosmos sans surcharger le graphe reel. */
    private fun drawOrbitRings(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        ringPaint.style = Paint.Style.STROKE
        ringPaint.color = accentColor
        ringPaint.strokeWidth = 1f
        listOf(0.32f, 0.58f, 0.85f).forEach { r ->
            ringPaint.alpha = 20
            canvas.drawCircle(cx, cy, scale * r, ringPaint)
        }
    }

    private fun drawObsidianWeb(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) / 2f * 0.9f

        // Habillage cosmos : fond etoile + nebuleuse + anneaux orbitaux, dessines AVANT le
        // graphe reel pour rester en arriere-plan (le graphe et ses libelles restent lisibles
        // au-dessus). Voir drawStarfield/drawNebulaGlow/drawOrbitRings ci-dessus.
        drawNebulaGlow(canvas, cx, cy, scale)
        drawStarfield(canvas, cx, cy, scale)
        drawOrbitRings(canvas, cx, cy, scale)

        // Amplitude de dérive des nœuds : nulle en IDLE (positions strictement fixes,
        // comme demandé), puis croissante selon l'activité — réflexion = le plus vif.
        val driftAmplitude = when (state) {
            OrbState.IDLE -> 0f
            OrbState.LISTENING -> 0.05f
            OrbState.THINKING -> 0.11f
            // Ralenti nettement (etait 0.08f, quasi au niveau de THINKING) : demande
            // explicite pour un effet plus lent/contemplatif pendant que JARVIS parle.
            OrbState.SPEAKING -> 0.03f
        }
        val pulseSpeed = when (state) {
            OrbState.IDLE -> 0f
            OrbState.LISTENING -> 1f
            OrbState.THINKING -> 1.8f
            // Idem (etait 1.3f) : scintillement des noeuds/liens ralenti pendant la parole.
            OrbState.SPEAKING -> 0.4f
        }
        val t = pulsePhase * 2f * Math.PI.toFloat() * pulseSpeed

        val nodes = webNodes()
        val screenPoints = nodes.map { n ->
            val dx = if (driftAmplitude > 0f) sin(t + n.phase) * driftAmplitude else 0f
            val dy = if (driftAmplitude > 0f) cos(t * 0.8f + n.phase) * driftAmplitude else 0f
            Triple(cx + (n.bx + dx) * scale, cy + (n.by + dy) * scale, n.isHub)
        }

        val realEdges = graphData?.edges
        if (!realEdges.isNullOrEmpty()) {
            // Vrai graphe : on ne trace QUE les liens [[wikilink]] réellement présents entre
            // deux notes, pas une heuristique de distance — c'est tout l'intérêt par rapport à
            // l'ancien rendu procédural.
            realEdges.forEach { (i, j) ->
                if (i < screenPoints.size && j < screenPoints.size) {
                    val (x1, y1, _) = screenPoints[i]
                    val (x2, y2, _) = screenPoints[j]
                    val shimmer = if (pulseSpeed > 0f) (sin(t + i * 0.4f + j * 0.7f) * 0.5f + 0.5f) else 0.5f
                    val alpha = (35 + shimmer * 60).toInt().coerceIn(25, 100)
                    linePaint.color = accentColor
                    linePaint.alpha = alpha
                    canvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        } else {
            // Pas encore de données réelles (chargement en cours, vault vide/inaccessible) :
            // repli sur l'ancienne heuristique de distance, pour ne jamais afficher un cercle vide.
            val threshold = scale * 0.55f
            for (i in screenPoints.indices) {
                val (x1, y1, _) = screenPoints[i]
                for (j in i + 1 until screenPoints.size) {
                    val (x2, y2, _) = screenPoints[j]
                    val dist = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
                    if (dist < threshold) {
                        val shimmer = if (pulseSpeed > 0f) (sin(t + i * 0.4f + j * 0.7f) * 0.5f + 0.5f) else 0.5f
                        val alpha = (28 + shimmer * 55).toInt().coerceIn(20, 90)
                        linePaint.color = accentColor
                        linePaint.alpha = alpha
                        canvas.drawLine(x1, y1, x2, y2, linePaint)
                    }
                }
            }
        }

        screenPoints.forEachIndexed { idx, (x, y, isHub) ->
            val pulse = if (pulseSpeed > 0f) 0.7f + 0.3f * sin(t * 1.3f + x + y) else 1f
            if (isHub) {
                dotPaint.shader = RadialGradient(
                    x, y, scale * 0.16f,
                    intArrayOf(Color.WHITE, accentColor, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(x, y, scale * 0.16f * pulse, dotPaint)
                dotPaint.shader = null
            } else {
                dotPaint.color = accentColor
                dotPaint.alpha = (150 * pulse).toInt().coerceIn(80, 220)
                canvas.drawCircle(x, y, (3f + 1.5f * pulse), dotPaint)
            }

            // Titre de la note sous le nœud — uniquement pour les notes les plus connectées
            // (voir showLabel dans layoutFromGraph), sinon un vault de plusieurs dizaines de
            // notes deviendrait illisible sur un petit orbe.
            val node = nodes.getOrNull(idx)
            if (node?.showLabel == true && node.title != null) {
                webLabelPaint.color = accentColor
                webLabelPaint.alpha = if (node.isHub) 230 else 160
                val label = if (node.title.length > 14) node.title.take(13) + "…" else node.title
                canvas.drawText(label, x, y + scale * 0.11f + 22f, webLabelPaint)
            }
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
