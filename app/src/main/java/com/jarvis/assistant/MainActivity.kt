package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // Lot 2 "contrôle téléphone" (SMS/appels) : SEND_SMS et CALL_PHONE sont des permissions
    // "dangereuses", il faut les demander à l'exécution. On mémorise la commande en attente
    // pendant la durée du dialogue système, pour l'exécuter dès que l'utilisateur accepte
    // (ou expliquer clairement si il refuse).
    private var pendingCommand: CommandInterpreter.Command? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val command = pendingCommand
        pendingCommand = null
        if (command != null) {
            if (results.values.all { it }) {
                runDeviceCommand(command)
            } else {
                appendAssistantMessage("❌ Permission refusée -- impossible d'exécuter cette action sans elle.")
            }
        }
    }

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
        // Filet de sécurité pour le bug "titre/réglages caché sous la barre de statut" : si
        // requestApplyInsets() appelé dans onCreate() n'a rien fait parce que la vue n'était
        // pas encore attachée à la fenêtre à ce moment précis (cas documenté où l'appel est
        // silencieusement ignoré), on le retente ici -- onResume() garantit que la fenêtre
        // est bien attachée. Sans coût si les insets étaient déjà corrects.
        ViewCompat.requestApplyInsets(binding.root)

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
        // Signalement utilisateur persistant (bouton réglages/titre toujours caché sous la
        // barre de statut malgré le listener ci-dessus) : sur certains appareils/versions,
        // le tout premier passage d'insets a lieu AVANT que ce listener soit attaché (la
        // fenêtre a déjà reçu ses insets initiaux au moment où onCreate() s'exécute), donc
        // le callback ne se déclenche jamais tout seul. requestApplyInsets() force un nouveau
        // passage explicite juste après l'avoir attaché -- fix documenté officiellement pour
        // ce cas précis (voir developer.android.com/develop/ui/views/layout/edge-to-edge).
        ViewCompat.requestApplyInsets(binding.root)
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

        val command = CommandInterpreter.parse(text)
        if (command != null) {
            executeDeviceCommand(command)
        } else {
            requestAiReply(text)
        }
    }

    /**
     * Lot 1 "contrôle téléphone" (lampe/réveil/minuteur) : si le message tapé correspond à une
     * commande reconnue (voir CommandInterpreter), on exécute directement l'action système au
     * lieu d'appeler l'IA -- réponse immédiate, sans dépendre du modèle actif ni de sa capacité
     * (ou non) à faire du function-calling.
     */
    private fun executeDeviceCommand(command: CommandInterpreter.Command) {
        val requiredPermission = when (command) {
            is CommandInterpreter.Command.Sms -> Manifest.permission.SEND_SMS
            is CommandInterpreter.Command.Call -> Manifest.permission.CALL_PHONE
            is CommandInterpreter.Command.CreateContact -> Manifest.permission.WRITE_CONTACTS
            is CommandInterpreter.Command.FindContact -> Manifest.permission.READ_CONTACTS
            is CommandInterpreter.Command.GetLocation -> Manifest.permission.ACCESS_FINE_LOCATION
            else -> null
        }
        if (requiredPermission != null &&
            ContextCompat.checkSelfPermission(this, requiredPermission) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCommand = command
            permissionLauncher.launch(arrayOf(requiredPermission))
            return
        }

        // MANAGE_EXTERNAL_STORAGE (Android 10+) n'est PAS une permission runtime classique --
        // impossible à demander via permissionLauncher. Android impose de passer par un écran
        // Réglages système dédié que l'utilisateur doit approuver lui-même (voir StorageController).
        // Sur Android 9 et moins, WRITE_EXTERNAL_STORAGE (permission runtime classique) suffit.
        val needsAllFilesAccess = command is CommandInterpreter.Command.FindFile ||
            command is CommandInterpreter.Command.DeleteFile
        if (needsAllFilesAccess && !StorageController.hasAllFilesAccess(this)) {
            if (android.os.Build.VERSION.SDK_INT < 30) {
                pendingCommand = command
                permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                return
            }
            // Contrairement au cas ci-dessus, cet écran système ne renvoie pas de résultat
            // exploitable ici (pas de callback d'ActivityResultContracts branché dessus) --
            // on redemande donc simplement à l'utilisateur de retaper sa requête une fois
            // l'autorisation activée, plutôt que de mémoriser pendingCommand pour rien.
            appendAssistantMessage(
                "📂 J'ai besoin de l'autorisation \"Accès à tous les fichiers\" pour ça -- " +
                    "je t'ouvre l'écran Réglages, active le bouton puis retape ta demande."
            )
            startActivity(StorageController.allFilesAccessIntent(this))
            return
        }

        runDeviceCommand(command)
    }

    /** Exécute la commande une fois qu'on sait que les permissions nécessaires sont accordées. */
    private fun runDeviceCommand(command: CommandInterpreter.Command) {
        if (command is CommandInterpreter.Command.GetLocation) {
            LocationController.getCurrentLocation(
                this,
                onResult = { lat, lon ->
                    val mapsLink = "https://maps.google.com/?q=$lat,$lon"
                    appendAssistantMessage("📍 Position actuelle : %.6f, %.6f\n$mapsLink".format(lat, lon))
                },
                onError = { error -> appendAssistantMessage("❌ $error") }
            )
            return
        }
        val reply = when (command) {
            is CommandInterpreter.Command.Flashlight -> {
                val ok = DeviceController.setFlashlight(this, command.on)
                when {
                    !ok -> "❌ Impossible d'accéder au flash de cet appareil."
                    command.on -> "🔦 Lampe torche allumée."
                    else -> "🔦 Lampe torche éteinte."
                }
            }
            is CommandInterpreter.Command.Timer -> {
                val ok = DeviceController.setTimer(this, command.seconds, null)
                if (ok) "⏱️ Minuteur lancé." else "❌ Impossible de lancer le minuteur (aucune appli Horloge trouvée ?)."
            }
            is CommandInterpreter.Command.Alarm -> {
                val ok = DeviceController.setAlarm(this, command.hour, command.minute, null)
                if (ok) "⏰ Réveil réglé à %02d:%02d.".format(command.hour, command.minute)
                else "❌ Impossible de régler le réveil (aucune appli Horloge trouvée ?)."
            }
            is CommandInterpreter.Command.Sms -> {
                val ok = DeviceController.sendSms(this, command.phoneNumber, command.message)
                if (ok) "📩 SMS envoyé à ${command.phoneNumber}." else "❌ Échec de l'envoi du SMS."
            }
            is CommandInterpreter.Command.Call -> {
                val ok = DeviceController.makeCall(this, command.phoneNumber)
                if (ok) "📞 Appel en cours vers ${command.phoneNumber}." else "❌ Impossible de lancer l'appel."
            }
            is CommandInterpreter.Command.CreateContact -> {
                val ok = ContactsController.createContact(this, command.name, command.phoneNumber)
                if (ok) "👤 Contact « ${command.name} » créé (${command.phoneNumber})." else "❌ Échec de la création du contact."
            }
            is CommandInterpreter.Command.FindContact -> {
                val contact = ContactsController.findContact(this, command.name)
                when {
                    contact == null -> "🔍 Aucun contact trouvé pour « ${command.name} »."
                    contact.phoneNumbers.isEmpty() -> "👤 ${contact.name} -- aucun numéro enregistré."
                    else -> "👤 ${contact.name} : ${contact.phoneNumbers.joinToString(", ")}"
                }
            }
            CommandInterpreter.Command.GetLocation -> return // géré au-dessus (async)
            is CommandInterpreter.Command.FindFile -> {
                val files = StorageController.findFiles(command.query)
                if (files.isEmpty()) "🔍 Aucun fichier trouvé pour « ${command.query} »."
                else "📂 ${files.size} résultat(s) :\n" + files.joinToString("\n") { it.absolutePath }
            }
            is CommandInterpreter.Command.DeleteFile -> {
                val files = StorageController.findFiles(command.name)
                val exactMatch = files.firstOrNull { it.name.equals(command.name, ignoreCase = true) } ?: files.firstOrNull()
                when {
                    exactMatch == null -> "🔍 Aucun fichier trouvé pour « ${command.name} »."
                    StorageController.deleteFile(exactMatch.absolutePath) -> "🗑️ Fichier supprimé : ${exactMatch.absolutePath}"
                    else -> "❌ Échec de la suppression de ${exactMatch.absolutePath} (dossier non vide ou erreur)."
                }
            }
        }
        appendAssistantMessage(reply)
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
