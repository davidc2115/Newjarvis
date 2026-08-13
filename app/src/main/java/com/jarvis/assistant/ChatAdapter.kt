package com.jarvis.assistant

import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.text.method.LinkMovementMethod
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<Message>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val bubbleContainer: LinearLayout = view.findViewById(R.id.bubbleContainer)
        val senderLabel: TextView = view.findViewById(R.id.senderLabel)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val messageImage: ImageView = view.findViewById(R.id.messageImage)
        val quickActionsScroll: android.widget.HorizontalScrollView = view.findViewById(R.id.quickActionsScroll)
        val quickActionsRow: LinearLayout = view.findViewById(R.id.quickActionsRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = MarkdownUtils.toSpannable(message.text)
        // Nécessaire pour que les liens cliquables (tel:/mailto:/adresse) posés par
        // MarkdownUtils réagissent réellement au tap — un simple Spannable avec
        // ClickableSpan ne suffit pas sans ce MovementMethod.
        holder.messageText.movementMethod = LinkMovementMethod.getInstance()

        if (message.imageBase64 != null) {
            try {
                val bytes = Base64.decode(message.imageBase64, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.messageImage.setImageBitmap(bitmap)
                holder.messageImage.visibility = View.VISIBLE
            } catch (e: Exception) {
                holder.messageImage.visibility = View.GONE
            }
        } else {
            holder.messageImage.visibility = View.GONE
        }

        val context = holder.root.context

        // Boutons d'action rapide (appeler/SMS/itinéraire/mail) déduits du texte du message —
        // complémentaires aux liens cliquables déjà posés par MarkdownUtils, mais plus visibles
        // pour un geste rapide sans devoir viser le bon mot dans la bulle.
        holder.quickActionsRow.removeAllViews()
        val quickActions = MarkdownUtils.extractQuickActions(message.text)
        if (quickActions.isEmpty()) {
            holder.quickActionsScroll.visibility = View.GONE
        } else {
            holder.quickActionsScroll.visibility = View.VISIBLE
            for (action in quickActions) {
                val chip = TextView(context).apply {
                    text = action.label
                    textSize = 12f
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(24, 12, 24, 12)
                    setBackgroundResource(R.drawable.bg_quick_action_chip)
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.marginEnd = 8
                    layoutParams = params
                    setOnClickListener { action.onClick(this) }
                }
                holder.quickActionsRow.addView(chip)
            }
        }

        if (message.isUser) {
            holder.senderLabel.text = "VOUS"
            holder.bubbleContainer.setBackgroundResource(R.drawable.bg_bubble_user)
            applyBubbleColorOverride(holder.bubbleContainer, Prefs.getChatBubbleUserColor(context))
            holder.root.gravity = Gravity.END
        } else {
            holder.senderLabel.text = "JARVIS"
            holder.bubbleContainer.setBackgroundResource(R.drawable.bg_bubble_ai)
            applyBubbleColorOverride(holder.bubbleContainer, Prefs.getChatBubbleAiColor(context))
            holder.root.gravity = Gravity.START
        }
    }

    /**
     * Applique une couleur de bulle personnalisée (via set_chat_theme depuis le chat/vocal)
     * par-dessus le dégradé par défaut du thème — 0 = pas de surcharge, on garde le dégradé
     * d'origine intact. setTint sur un GradientDrawable remplace le dégradé par une teinte
     * plate : c'est le compromis attendu d'une personnalisation de couleur simple.
     */
    private fun applyBubbleColorOverride(view: LinearLayout, color: Int) {
        if (color == 0) return
        view.background?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
