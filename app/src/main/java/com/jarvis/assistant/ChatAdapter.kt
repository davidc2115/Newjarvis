package com.jarvis.assistant

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Affiche les messages de la conversation active : bulle à droite (couleur d'accent choisie
 * dans Réglages) pour l'utilisateur, bulle translucide à gauche pour l'assistant.
 */
class ChatAdapter(
    private val messages: List<Message>,
    private val accentColor: Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == TYPE_USER) R.layout.item_message_user else R.layout.item_message_assistant
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val vh = holder as MessageViewHolder
        vh.text.text = message.text
        if (message.isUser) {
            // .mutate() : une bulle par ViewHolder, sinon la teinte se répercuterait sur
            // TOUTES les bulles (le drawable XML est partagé/mis en cache par défaut).
            val bubble = vh.text.background?.mutate()
            if (bubble is GradientDrawable) bubble.setColor(accentColor)
        }
    }
}
