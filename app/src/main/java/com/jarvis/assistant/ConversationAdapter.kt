package com.jarvis.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Liste des conversations dans la sidebar rétractable. */
class ConversationAdapter(
    private val conversations: List<Conversation>,
    private val activeConversationId: String?,
    private val onClick: (Conversation) -> Unit,
    private val onDelete: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.conversationTitleText)
        val delete: ImageButton = view.findViewById(R.id.deleteConversationButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = conversations.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        holder.title.text = conversation.title
        holder.title.alpha = if (conversation.id == activeConversationId) 1f else 0.55f
        holder.itemView.setOnClickListener { onClick(conversation) }
        holder.delete.setOnClickListener { onDelete(conversation) }
    }
}
