package com.jarvis.assistant

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mlkit.genai.common.FeatureStatus
import com.jarvis.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Écran de chat : liste de messages + barre de saisie, sidebar rétractable pour changer de
 * conversation, bouton réglages en haut à droite (couleur d'accent). Backend IA : Gemini Nano
 * on-device via AICore (voir GeminiNanoController) — gratuit, sans clé, mais seulement
 * disponible sur les appareils compatibles AICore (Pixel 8/9, Galaxy S24...).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversations: MutableList<Conversation>
    private lateinit var activeConversation: Conversation
    private var accentColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BUG RÉEL CORRIGÉ (signalement utilisateur : titre/bouton réglages cachés sous la
        // barre de statut) : sans cet appel EXPLICITE, le comportement edge-to-edge (contenu
        // qui dessine sous les barres système, à charge pour l'appli de compenser via des
        // insets) varie selon la version d'Android/le fabricant au lieu d'être garanti --
        // sur certains appareils le système compense déjà tout seul (aucun souci), sur
        // d'autres non, et notre propre compensation manuelle (applyWindowInsets) ne suffit
        // alors plus puisqu'elle recevait des insets à zéro. Forcer explicitement le mode
        // edge-to-edge rend applyWindowInsets() fiable sur TOUS les appareils.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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

    /**
     * Compense le mode edge-to-edge (activé explicitement ci-dessus) : pousse la barre du
     * haut sous la barre de statut, ET pousse le bas du contenu (donc la barre de saisie,
     * tout en bas) au-dessus de la barre de navigation OU du clavier, selon lequel des deux
     * est le plus grand -- corrige aussi le signalement utilisateur "la barre de saisie doit
     * rester affichée au-dessus du clavier quand on tape" : en edge-to-edge, adjustResize
     * (AndroidManifest) seul ne suffit plus, il faut lire explicitement l'inset IME.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.topBar.setPadding(binding.topBar.paddingLeft, bars.top, binding.topBar.paddingRight, binding.topBar.paddingBottom)
            binding.mainContent.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
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
        Prefs.saveConversations(this, conversations)
        binding.messageInput.setText("")
        refreshChat()
        refreshSidebar()

        requestAiReply(text)
    }

    /**
     * Route la requête vers le backend IA choisi dans Réglages (voir Prefs.getSelectedModel) :
     * Gemini Nano via AICore, ou Gemma 3 1B en local via LiteRT-LM (voir GemmaController).
     * Les deux sont indépendants -- changer de modèle dans Réglages change le backend utilisé
     * dès le message suivant, sans redémarrer l'appli.
     */
    private fun requestAiReply(prompt: String) {
        when (Prefs.getSelectedModel(this)) {
            Prefs.MODEL_GEMMA -> requestGemmaReply(prompt)
            else -> requestGeminiNanoReply(prompt)
        }
    }

    /**
     * Backend IA : Gemini Nano on-device via AICore (voir GeminiNanoController). Aucune clé,
     * aucun réseau une fois le modèle téléchargé -- mais uniquement disponible sur les
     * appareils compatibles AICore. Si l'appareil ne l'est pas, on l'explique clairement au
     * lieu d'échouer silencieusement.
     */
    private fun requestGeminiNanoReply(prompt: String) {
        lifecycleScope.launch {
            try {
                when (GeminiNanoController.checkStatus()) {
                    FeatureStatus.AVAILABLE -> {
                        val reply = GeminiNanoController.generateReply(prompt)
                        appendAssistantMessage(reply)
                    }
                    FeatureStatus.DOWNLOADABLE -> {
                        appendAssistantMessage(getString(R.string.gemini_nano_downloading))
                        GeminiNanoController.downloadModel(
                            onFailed = { error -> appendAssistantMessage("❌ Échec du téléchargement de Gemini Nano : $error") },
                            onCompleted = {
                                lifecycleScope.launch {
                                    val reply = GeminiNanoController.generateReply(prompt)
                                    appendAssistantMessage(reply)
                                }
                            }
                        )
                    }
                    FeatureStatus.DOWNLOADING -> appendAssistantMessage(getString(R.string.gemini_nano_still_downloading))
                    else -> appendAssistantMessage(getString(R.string.gemini_nano_unavailable))
                }
            } catch (e: Exception) {
                appendAssistantMessage("❌ Erreur Gemini Nano : ${e.message}")
            }
        }
    }

    /**
     * Backend IA : Gemma 3 1B en local via LiteRT-LM (voir GemmaController). Ne passe pas par
     * AICore -- fonctionne sur tout appareil suffisamment puissant, mais le modèle doit avoir
     * été téléchargé au préalable dans Réglages (jeton Hugging Face requis, licence Gemma).
     */
    private fun requestGemmaReply(prompt: String) {
        if (!GemmaController.isDownloaded(this)) {
            appendAssistantMessage(getString(R.string.gemma_not_downloaded_chat))
            return
        }
        lifecycleScope.launch {
            try {
                val reply = GemmaController.generateReply(this@MainActivity, prompt)
                appendAssistantMessage(reply)
            } catch (e: Exception) {
                appendAssistantMessage("❌ Erreur Gemma : ${e.message}")
            }
        }
    }

    private fun appendAssistantMessage(text: String) {
        activeConversation.messages.add(Message(text, isUser = false))
        Prefs.saveConversations(this, conversations)
        refreshChat()
        refreshSidebar()
    }
}
