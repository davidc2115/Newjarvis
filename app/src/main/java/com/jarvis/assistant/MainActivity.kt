package com.jarvis.assistant

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.databinding.ActivityMainBinding
import java.util.UUID

/**
 * Écran de chat : liste de messages + barre de saisie, sidebar rétractable pour changer de
 * conversation, bouton réglages en haut à droite (couleur d'accent). Pas encore de backend IA
 * reconstruit (voir README) — l'envoi d'un message ajoute une réponse d'attente, pour que
 * l'interface reste testable pendant qu'on reconstruit le reste fonctionnalité par
 * fonctionnalité.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversations: MutableList<Conversation>
    private lateinit var activeConversation: Conversation
    private var accentColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        loadState()
        setupChat()
        setupSidebar()
        setupTopBar()
        setupInputBar()
    }

    override fun onResume() {
        super.onResume()
        // La couleur a pu changer dans Réglages entre-temps (activité séparée).
        val current = Prefs.getAccentColor(this)
        if (current != accentColor) {
            accentColor = current
            applyAccentColor()
            refreshChat()
        }
    }

    /** Évite que le contenu passe sous la barre de statut/navigation (edge-to-edge, cible SDK 35). */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(binding.topBar.paddingLeft, bars.top, binding.topBar.paddingRight, binding.topBar.paddingBottom)
            binding.mainContent.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun loadState() {
        accentColor = Prefs.getAccentColor(this)
        conversations = Prefs.loadConversations(this)
        if (conversations.isEmpty()) {
            conversations.add(Conversation(UUID.randomUUID().toString(), "Nouvelle conversation"))
            Prefs.saveConversations(this, conversations)
        }
        val activeId = Prefs.getActiveConversationId(this)
        activeConversation = conversations.firstOrNull { it.id == activeId } ?: conversations.first()
        Prefs.setActiveConversationId(this, activeConversation.id)
    }

    private fun setupChat() {
        binding.messagesRecycler.layoutManager = LinearLayoutManager(this)
        refreshChat()
    }

    private fun refreshChat() {
        binding.conversationTitle.text = activeConversation.title
        binding.messagesRecycler.adapter = ChatAdapter(activeConversation.messages, accentColor)
        if (activeConversation.messages.isNotEmpty()) {
            binding.messagesRecycler.scrollToPosition(activeConversation.messages.size - 1)
        }
    }

    private fun setupSidebar() {
        binding.conversationsRecycler.layoutManager = LinearLayoutManager(this)
        refreshSidebar()

        binding.newConversationButton.setOnClickListener {
            val fresh = Conversation(UUID.randomUUID().toString(), "Nouvelle conversation")
            conversations.add(0, fresh)
            activeConversation = fresh
            Prefs.setActiveConversationId(this, fresh.id)
            Prefs.saveConversations(this, conversations)
            refreshChat()
            refreshSidebar()
            binding.root.closeDrawer(GravityCompat.START)
        }
    }

    private fun refreshSidebar() {
        binding.conversationsRecycler.adapter = ConversationAdapter(
            conversations,
            activeConversation.id,
            onClick = { conversation ->
                activeConversation = conversation
                Prefs.setActiveConversationId(this, conversation.id)
                refreshChat()
                refreshSidebar()
                binding.root.closeDrawer(GravityCompat.START)
            },
            onDelete = { conversation -> confirmDeleteConversation(conversation) }
        )
    }

    private fun confirmDeleteConversation(conversation: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer la conversation ?")
            .setMessage("« ${conversation.title} » sera définitivement supprimée.")
            .setPositiveButton("Supprimer") { _, _ ->
                conversations.remove(conversation)
                if (conversations.isEmpty()) {
                    conversations.add(Conversation(UUID.randomUUID().toString(), "Nouvelle conversation"))
                }
                if (activeConversation.id == conversation.id) {
                    activeConversation = conversations.first()
                    Prefs.setActiveConversationId(this, activeConversation.id)
                }
                Prefs.saveConversations(this, conversations)
                refreshChat()
                refreshSidebar()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun setupTopBar() {
        binding.menuButton.setOnClickListener { binding.root.openDrawer(GravityCompat.START) }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun setupInputBar() {
        applyAccentColor()
        binding.sendButton.setOnClickListener { sendMessage() }
    }

    private fun applyAccentColor() {
        val sendBg = binding.sendButton.background?.mutate()
        if (sendBg is GradientDrawable) sendBg.setColor(accentColor)

        val newConvBg = binding.newConversationButton.background?.mutate()
        if (newConvBg is GradientDrawable) newConvBg.setStroke(dpToPx(1), accentColor)
        binding.newConversationButton.setTextColor(accentColor)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun sendMessage() {
        val text = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        if (activeConversation.messages.isEmpty()) {
            activeConversation.title = text.take(30)
        }
        activeConversation.messages.add(Message(text, isUser = true))
        // Pas encore de backend IA reconstruit — réponse d'attente pour garder le chat testable.
        activeConversation.messages.add(Message(getString(R.string.placeholder_assistant_reply), isUser = false))

        Prefs.saveConversations(this, conversations)
        binding.messageInput.setText("")
        refreshChat()
        refreshSidebar()
    }
}
