package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Sélecteur défilant (carrousel horizontal) pour le style de l'orbe vocale —
 * chaque carte affiche un VRAI aperçu animé (une mini instance d'OrbView dans
 * le style correspondant), pas juste un libellé, pour voir concrètement le
 * rendu avant de choisir.
 */
class OrbStyleCarouselAdapter(
    private val context: Context,
    private val styles: List<Pair<String, String>>, // (id interne, libellé affiché)
    private var selected: String,
    private var accentColor: Int,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<OrbStyleCarouselAdapter.VH>() {

    class VH(val card: LinearLayout, val orb: OrbView, val label: TextView) : RecyclerView.ViewHolder(card)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val orb = OrbView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        val label = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#E6EAF2"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(orb)
            addView(label)
            layoutParams = RecyclerView.LayoutParams(dp(104), RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
        }
        return VH(card, orb, label)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (id, label) = styles[position]
        holder.label.text = label
        holder.orb.accentColor = accentColor
        holder.orb.visualStyle = if (id == "PULSE") OrbView.VisualStyle.PULSE else OrbView.VisualStyle.NETWORK_SPHERE
        holder.orb.state = OrbView.OrbState.IDLE

        val isSelected = id == selected
        holder.card.background = if (isSelected) {
            GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#1A2233"))
                setStroke(dp(2), accentColor)
            }
        } else null
        holder.card.alpha = if (isSelected) 1f else 0.55f

        holder.card.setOnClickListener {
            if (selected != id) {
                selected = id
                onSelect(id)
                notifyDataSetChanged()
            }
        }
    }

    override fun getItemCount(): Int = styles.size

    /** Appelé quand l'utilisateur change la couleur du carrousel voisin, pour que les aperçus restent cohérents. */
    fun updateAccentColor(color: Int) {
        accentColor = color
        notifyDataSetChanged()
    }
}
