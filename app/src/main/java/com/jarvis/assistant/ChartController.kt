package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * ChartController — génère de vrais graphiques (barres, courbes, camembert) sous
 * forme d'image PNG dessinée nativement (android.graphics.Canvas, aucune bibliothèque
 * externe) à partir de données fournies par l'IA. Réutilise EXACTEMENT le même circuit
 * d'affichage que la génération d'image (base64 → bulle du chat), donc un graphique
 * s'affiche directement dans la conversation comme une image générée.
 *
 * Format de données volontairement simple pour que l'IA le produise sans erreur :
 * une ligne par point, "étiquette;valeur" (ex: "Lundi;12\nMardi;18\nMercredi;9").
 */
object ChartController {

    data class Result(val success: Boolean, val message: String, val base64: String? = null, val mime: String? = null, val savedPath: String? = null)

    private data class Point(val label: String, val value: Double)

    private val PALETTE = listOf(
        Color.parseColor("#22D3EE"), Color.parseColor("#A78BFA"), Color.parseColor("#34D399"),
        Color.parseColor("#F472B6"), Color.parseColor("#FBBF24"), Color.parseColor("#60A5FA"),
        Color.parseColor("#F87171"), Color.parseColor("#4ADE80")
    )

    private const val BG_COLOR = "#12161F"
    private const val AXIS_COLOR = "#3A4152"
    private const val TEXT_COLOR = "#E6EAF2"

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    /**
     * [type] accepte : "bar"/"barres", "line"/"courbe"/"ligne", "pie"/"camembert"/"secteurs".
     * [dataCsv] : une ligne par point "étiquette;valeur".
     */
    fun generateChart(context: Context, type: String, title: String, dataCsv: String): Result {
        val points = dataCsv.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(";")
                if (parts.size < 2) return@mapNotNull null
                val value = parts[1].trim().replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
                Point(parts[0].trim(), value)
            }

        if (points.isEmpty()) {
            return Result(false, "❌ Aucune donnée exploitable. Format attendu : une ligne par point « étiquette;valeur » (ex: « Lundi;12 »).")
        }

        val normalizedType = when {
            type.contains("pie", true) || type.contains("camembert", true) || type.contains("secteur", true) -> "pie"
            type.contains("line", true) || type.contains("courbe", true) || type.contains("ligne", true) -> "line"
            else -> "bar"
        }

        val width = 1000
        val height = 700
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor(BG_COLOR))

        drawTitle(canvas, title.ifBlank { "Graphique" }, width)

        try {
            when (normalizedType) {
                "pie" -> drawPie(canvas, points, width, height)
                "line" -> drawLine(canvas, points, width, height)
                else -> drawBars(canvas, points, width, height)
            }
        } catch (e: Exception) {
            return Result(false, "❌ Erreur lors du dessin du graphique : ${e.message}")
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val savedPath = try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "JARVIS-Generated").also { it.mkdirs() }
            val safeTitle = title.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim().ifBlank { "graphique" }
            val file = File(dir, "${fileDateFormat.format(Date())}_$safeTitle.png")
            file.writeBytes(bytes)
            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            } catch (e: Exception) { /* non bloquant */ }
            file.absolutePath
        } catch (e: Exception) {
            null
        }

        val savedNote = savedPath?.let { "\n📁 Enregistré dans : $it" } ?: ""
        return Result(true, "📊 Graphique généré pour « $title ».$savedNote", base64, "image/png", savedPath)
    }

    private fun drawTitle(canvas: Canvas, title: String, width: Int) {
        val paint = Paint().apply {
            color = Color.parseColor(TEXT_COLOR)
            textSize = 30f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title, width / 2f, 50f, paint)
    }

    // ─── Barres ─────────────────────────────────────────────────────────────

    private fun drawBars(canvas: Canvas, points: List<Point>, width: Int, height: Int) {
        val plotLeft = 70f
        val plotRight = width - 40f
        val plotTop = 100f
        val plotBottom = height - 90f
        val maxValue = max(points.maxOf { it.value }, 0.0001)

        val axisPaint = Paint().apply { color = Color.parseColor(AXIS_COLOR); strokeWidth = 2f }
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)

        val barGap = 20f
        val barWidth = ((plotRight - plotLeft) - barGap * (points.size + 1)) / points.size
        val labelPaint = Paint().apply { color = Color.parseColor(TEXT_COLOR); textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val valuePaint = Paint().apply { color = Color.parseColor(TEXT_COLOR); textSize = 16f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

        points.forEachIndexed { i, p ->
            val barHeight = ((p.value / maxValue) * (plotBottom - plotTop)).toFloat()
            val left = plotLeft + barGap + i * (barWidth + barGap)
            val top = plotBottom - barHeight
            val right = left + barWidth
            val barPaint = Paint().apply { color = PALETTE[i % PALETTE.size]; isAntiAlias = true }
            canvas.drawRoundRect(RectF(left, top, right, plotBottom), 8f, 8f, barPaint)
            canvas.drawText(formatValue(p.value), left + barWidth / 2, top - 10f, valuePaint)
            drawTruncatedLabel(canvas, p.label, left + barWidth / 2, plotBottom + 24f, labelPaint, barWidth + barGap)
        }
    }

    // ─── Courbe ─────────────────────────────────────────────────────────────

    private fun drawLine(canvas: Canvas, points: List<Point>, width: Int, height: Int) {
        val plotLeft = 70f
        val plotRight = width - 40f
        val plotTop = 100f
        val plotBottom = height - 90f
        val maxValue = max(points.maxOf { it.value }, 0.0001)
        val minValue = min(points.minOf { it.value }, 0.0)

        val axisPaint = Paint().apply { color = Color.parseColor(AXIS_COLOR); strokeWidth = 2f }
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)

        val linePaint = Paint().apply { color = PALETTE[0]; strokeWidth = 4f; isAntiAlias = true; style = Paint.Style.STROKE }
        val dotPaint = Paint().apply { color = PALETTE[0]; isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.parseColor(TEXT_COLOR); textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val valuePaint = Paint().apply { color = Color.parseColor(TEXT_COLOR); textSize = 16f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

        val stepX = if (points.size > 1) (plotRight - plotLeft) / (points.size - 1) else 0f
        val range = max(maxValue - minValue, 0.0001)

        val coords = points.mapIndexed { i, p ->
            val x = plotLeft + i * stepX
            val y = plotBottom - ((p.value - minValue) / range * (plotBottom - plotTop)).toFloat()
            x to y
        }

        for (i in 0 until coords.size - 1) {
            canvas.drawLine(coords[i].first, coords[i].second, coords[i + 1].first, coords[i + 1].second, linePaint)
        }
        coords.forEachIndexed { i, (x, y) ->
            canvas.drawCircle(x, y, 6f, dotPaint)
            canvas.drawText(formatValue(points[i].value), x, y - 16f, valuePaint)
            drawTruncatedLabel(canvas, points[i].label, x, plotBottom + 24f, labelPaint, stepX.takeIf { it > 0 } ?: 100f)
        }
    }

    // ─── Camembert ──────────────────────────────────────────────────────────

    private fun drawPie(canvas: Canvas, points: List<Point>, width: Int, height: Int) {
        val total = points.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
        val cx = width * 0.36f
        val cy = height / 2f + 20f
        val radius = min(width * 0.28f, (height - 140) / 2f)
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        var startAngle = -90f
        val slicePaint = Paint().apply { isAntiAlias = true }
        points.forEachIndexed { i, p ->
            val sweep = (p.value / total * 360f).toFloat()
            slicePaint.color = PALETTE[i % PALETTE.size]
            canvas.drawArc(rect, startAngle, sweep, true, slicePaint)
            startAngle += sweep
        }

        // Légende à droite
        val legendX = width * 0.62f
        var legendY = cy - radius + 10f
        val legendPaint = Paint().apply { textSize = 18f; isAntiAlias = true; color = Color.parseColor(TEXT_COLOR) }
        val swatchPaint = Paint().apply { isAntiAlias = true }
        points.forEachIndexed { i, p ->
            swatchPaint.color = PALETTE[i % PALETTE.size]
            canvas.drawRoundRect(RectF(legendX, legendY - 14f, legendX + 24f, legendY + 6f), 4f, 4f, swatchPaint)
            val pct = (p.value / total * 100).let { "%.1f".format(it) }
            canvas.drawText("${p.label} — ${formatValue(p.value)} ($pct%)", legendX + 34f, legendY, legendPaint)
            legendY += 30f
        }
    }

    // ─── Utilitaires ────────────────────────────────────────────────────────

    private fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

    private fun drawTruncatedLabel(canvas: Canvas, label: String, cx: Float, y: Float, paint: Paint, maxWidth: Float) {
        var text = label
        while (paint.measureText(text) > maxWidth && text.length > 1) {
            text = text.dropLast(1)
        }
        if (text != label && text.length > 1) text = text.dropLast(1) + "…"
        canvas.drawText(text, cx, y, paint)
    }
}
