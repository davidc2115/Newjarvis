package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Sélecteur défilant (carrousel horizontal) pour la couleur du mode vocal —
 * remplace l'ancienne rangée fixe de 6 pastilles par une liste qui défile,
 * avec la couleur sélectionnée mise en évidence (agrandie, contour blanc).
 */
class ColorCarouselAdapter(
    private val context: Context,
    private val colors: List<Int>,
    private var selected: Int,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<ColorCarouselAdapter.VH>() {

    class VH(val swatch: View) : RecyclerView.ViewHolder(swatch)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val size = dp(52)
        val view = View(context).apply {
            layoutParams = RecyclerView.LayoutParams(size, size).apply {
                setMargins(dp(8), dp(10), dp(8), dp(10))
            }
        }
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val color = colors[position]
        val isSelected = color == selected
        holder.swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isSelected) setStroke(dp(3), Color.WHITE)
        }
        holder.swatch.scaleX = if (isSelected) 1.2f else 0.88f
        holder.swatch.scaleY = if (isSelected) 1.2f else 0.88f
        holder.swatch.alpha = if (isSelected) 1f else 0.7f
        holder.swatch.setOnClickListener {
            if (selected != color) {
                selected = color
                onSelect(color)
                notifyDataSetChanged()
            }
        }
    }

    override fun getItemCount(): Int = colors.size

    fun currentSelection(): Int = selected
}
