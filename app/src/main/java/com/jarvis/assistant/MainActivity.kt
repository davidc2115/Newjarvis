package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pendingImageBar: View
    private lateinit var pendingImageThumbnail: ImageView
    private var removePendingImageButtonRef: TextView? = null
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var conversationListContainer: LinearLayout

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var pendingImageBase64: String? = null
    private var pendingImageMime: String? = null
    // Chemin disque + nom d'un fichier joint (photo OU document) en attente d'envoi — distinct
    // de pendingImageBase64 (qui ne sert qu'à la "vision" IA). Permet à attach_contact_file de
    // retrouver le fichier original plus tard, même une fois le message envoyé. Ce sont toujours
    // ceux de la PREMIÈRE pièce jointe (compatibilité) — voir pendingExtraAttachments pour le reste.
    private var pendingAttachmentPath: String? = null
    private var pendingAttachmentName: String? = null

    // Objet complet de la PREMIÈRE pièce jointe (y compris son extractedText éventuel, ex: un
    // .docx en premier — les scalaires pendingImageBase64/pendingAttachmentPath ci-dessus n'en
    // sont qu'un miroir partiel pour l'affichage/la compatibilité, la vraie source de vérité
    // envoyée à l'IA est cet objet).
    private var pendingPrimaryAttachment: Attachment? = null

    // Pièces jointes au-delà de la première (plusieurs fichiers, ou contenu d'un dossier/PDF
    // multi-pages) — voir Attachment.kt/AttachmentController.kt.
    private val pendingExtraAttachments = mutableListOf<Attachment>()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openVoiceMode() else {
            Toast.makeText(this, "Permission micro refusée", Toast.LENGTH_SHORT).show()
        }
    }

    // Sélection de PLUSIEURS fichiers en une fois (images, vidéos, documents, zip, pdf...) —
    // GetMultipleContents renvoie une liste d'URI même si l'utilisateur n'en choisit qu'un seul.
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<@JvmSuppressWildcards Uri> ->
        if (uris.isNotEmpty()) attachFiles(uris)
    }

    // Sélection d'un DOSSIER entier (ACTION_OPEN_DOCUMENT_TREE) — tous les fichiers directement
    // à l'intérieur sont joints pour analyse (voir AttachmentController.listFolderChildren).
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            val children = AttachmentController.listFolderChildren(this, treeUri)
            if (children.isEmpty()) {
                Toast.makeText(this, "Dossier vide ou illisible", Toast.LENGTH_SHORT).show()
            } else {
                attachFiles(children)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showCrashReportIfAny()
        BottomNav.setup(this, NavDestination.CHAT)
        EdgeToEdgeHelper.applyTopInset(findViewById(R.id.headerRow))
        EdgeToEdgeHelper.applyBottomInset(findViewById(R.id.bottomNavRoot))

        recyclerView = findViewById(R.id.recyclerView)
        messageInput = findViewById(R.id.messageInput)
        statusText = findViewById(R.id.statusText)
        pendingImageBar = findViewById(R.id.pendingImageBar)
        pendingImageThumbnail = findViewById(R.id.pendingImageThumbnail)
        drawerLayout = findViewById(R.id.drawerLayout)
        conversationListContainer = findViewById(R.id.conversationListContainer)
        val menuButton = findViewById<TextView>(R.id.menuButton)
        val newConversationButton = findViewById<TextView>(R.id.newConversationButton)
        val micButton = findViewById<TextView>(R.id.micButton)
        val sendButton = findViewById<TextView>(R.id.sendButton)
        val settingsButton = findViewById<TextView>(R.id.settingsButton)
        val hubButton = findViewById<TextView>(R.id.hubButton)
        val photoButton = findViewById<TextView>(R.id.photoButton)
        val removePendingImageButton = findViewById<TextView>(R.id.removePendingImageButton)
        removePendingImageButtonRef = removePendingImageButton

        adapter = ChatAdapter(ConversationStore.messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        // Fond du chat personnalisable via set_chat_theme{target:"fond",...} — 0 = pas de
        // surcharge, on garde le fond par défaut défini dans le layout/thème.
        Prefs.getChatBackgroundColor(this).let { bg -> if (bg != 0) recyclerView.setBackgroundColor(bg) }

        tts = TextToSpeech(this, this)

        if (ConversationStore.messages.isEmpty()) {
            addMessage(
                "Bonjour Monsieur. Je suis JARVIS, votre assistant personnel avec contrôle complet du smartphone. " +
                    "Je peux passer des appels, envoyer des SMS, lire vos emails, gérer vos médias, votre agenda et vos fichiers. " +
                    "Que souhaitez-vous faire ?",
                isUser = false,
                speak = false
            )
        }

        // Demande des permissions runtime principales au démarrage
        PermissionsManager.requestMissingPermissions(this, PermissionsManager.REQUEST_ALL)

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            val hasAttachment = pendingImageBase64 != null || pendingAttachmentPath != null || pendingExtraAttachments.isNotEmpty()
            if (text.isNotEmpty() || hasAttachment) {
                val totalCount = (if (pendingAttachmentPath != null) 1 else 0) + pendingExtraAttachments.size
                val defaultText = when {
                    totalCount > 1 -> "Analyse ces $totalCount fichiers joints."
                    pendingImageBase64 != null -> "Décris cette image."
                    else -> "Analyse ce fichier joint."
                }
                sendMessage(text.ifBlank { defaultText })
                messageInput.text.clear()
            }
        }

        micButton.setOnClickListener { checkPermissionAndOpenVoiceMode() }

        // Appui simple = choisir un ou plusieurs fichiers (image/vidéo/document/zip/pdf...).
        // Appui long = choisir un DOSSIER entier dont tous les fichiers seront joints.
        photoButton.setOnClickListener { pickFilesLauncher.launch("*/*") }
        photoButton.setOnLongClickListener {
            Toast.makeText(this, "📂 Choisis un dossier — tous ses fichiers seront joints", Toast.LENGTH_SHORT).show()
            pickFolderLauncher.launch(null)
            true
        }

        removePendingImageButton.setOnClickListener { clearPendingImage() }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Un seul bouton vers le menu complet — domotique, création IA, Second Brain
        // Obsidian, contrôle téléphone, GitHub, réglages... tout est regroupé et
        // organisé par sections dans SmartHomeActivity (voir son layout dédié).
        hubButton.setOnClickListener {
            startActivity(Intent(this, SmartHomeActivity::class.java))
        }

        menuButton.setOnClickListener {
            refreshConversationList()
            drawerLayout.openDrawer(Gravity.START)
        }

        newConversationButton.setOnClickListener {
            ConversationStore.startNew(this)
            adapter.notifyDataSetChanged()
            drawerLayout.closeDrawer(Gravity.START)
            addMessage("Nouvelle conversation démarrée. Que puis-je faire pour vous ?", isUser = false, speak = false)
        }
    }

    /** Reconstruit la liste des conversations passées dans le tiroir latéral. */
    private fun refreshConversationList() {
        conversationListContainer.removeAllViews()
        val conversations = ConversationHistoryManager.listAll(this)
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)

        if (conversations.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Aucune conversation enregistrée."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }
            conversationListContainer.addView(empty)
            return
        }

        for (conv in conversations) {
            val isActive = conv.id == ConversationStore.currentConversationId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundResource(if (isActive) R.drawable.bg_bubble_ai else android.R.color.transparent)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 8
                layoutParams = params
            }

            val titleView = TextView(this).apply {
                text = conv.title
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                maxLines = 2
            }
            val dateView = TextView(this).apply {
                text = sdf.format(java.util.Date(conv.updatedAt))
                setTextColor(getColor(R.color.text_secondary))
                textSize = 10f
            }

            row.addView(titleView)
            row.addView(dateView)

            row.setOnClickListener {
                ConversationStore.loadConversation(this, conv.id)
                adapter.notifyDataSetChanged()
                if (ConversationStore.messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(ConversationStore.messages.size - 1)
                }
                drawerLayout.closeDrawer(Gravity.START)
            }

            row.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Supprimer cette conversation ?")
                    .setMessage(conv.title)
                    .setPositiveButton("Supprimer") { _, _ ->
                        ConversationHistoryManager.delete(this, conv.id)
                        if (conv.id == ConversationStore.currentConversationId) {
                            ConversationStore.startNew(this)
                            adapter.notifyDataSetChanged()
                        }
                        refreshConversationList()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
                true
            }

            conversationListContainer.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.getChatBackgroundColor(this).let { bg ->
            recyclerView.setBackgroundColor(if (bg != 0) bg else android.graphics.Color.TRANSPARENT)
        }
        adapter.notifyDataSetChanged()
        if (ConversationStore.messages.isNotEmpty()) {
            recyclerView.scrollToPosition(ConversationStore.messages.size - 1)
        }
    }

    private fun checkPermissionAndOpenVoiceMode() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) openVoiceMode() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openVoiceMode() {
        startActivity(Intent(this, VoiceModeActivity::class.java))
    }

    /**
     * Traite une ou plusieurs pièces jointes d'un coup (choix multiple, ou contenu d'un
     * dossier entier) — chaque fichier est copié en local, PUIS analysé par
     * AttachmentController (extraction de texte, rendu de pages PDF en images, aperçu vidéo...).
     * La toute première pièce jointe traitée alimente les champs legacy (pendingImageBase64/
     * pendingAttachmentPath) pour rester compatible avec attach_contact_file ; tout le reste
     * (y compris les pages supplémentaires d'un même PDF) va dans pendingExtraAttachments.
     */
    private fun attachFiles(uris: List<Uri>) {
        CoroutineScope(Dispatchers.IO).launch {
            var anyFailed = false
            val newAttachments = mutableListOf<Attachment>()
            for (uri in uris) {
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val persisted = persistAttachmentCopy(uri, mimeType)
                if (persisted == null) {
                    anyFailed = true
                    continue
                }
                val (path, name) = persisted
                newAttachments.addAll(AttachmentController.process(this@MainActivity, path, name, mimeType))
            }

            withContext(Dispatchers.Main) {
                if (newAttachments.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Impossible de lire ce(s) fichier(s)", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (anyFailed) {
                    Toast.makeText(this@MainActivity, "⚠️ Certains fichiers n'ont pas pu être lus", Toast.LENGTH_SHORT).show()
                }

                for (a in newAttachments) {
                    if (pendingPrimaryAttachment == null) {
                        // Première pièce jointe de ce lot — objet complet conservé tel quel
                        // (extractedText inclus), + miroir dans les champs legacy pour
                        // l'affichage/attach_contact_file.
                        pendingPrimaryAttachment = a
                        pendingAttachmentPath = a.path
                        pendingAttachmentName = a.name
                        if (a.imageBase64 != null) {
                            pendingImageBase64 = a.imageBase64
                            pendingImageMime = a.imageMime
                        }
                    } else {
                        pendingExtraAttachments.add(a)
                    }
                }

                if (pendingImageBase64 != null) {
                    val bytes = Base64.decode(pendingImageBase64, Base64.NO_WRAP)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    pendingImageThumbnail.setImageBitmap(bmp)
                    pendingImageThumbnail.visibility = View.VISIBLE
                } else {
                    pendingImageThumbnail.visibility = View.GONE
                }

                val totalCount = 1 + pendingExtraAttachments.size
                removePendingImageButtonRef?.text = if (totalCount > 1) {
                    "📎 $totalCount fichiers joints — ✕ tout retirer"
                } else if (pendingImageBase64 != null) {
                    "✕ retirer la photo"
                } else {
                    "📎 ${pendingAttachmentName ?: "fichier"} — ✕ retirer"
                }
                pendingImageBar.visibility = View.VISIBLE
            }
        }
    }

    // Dossiers déjà gérés par JARVIS lui-même : si le fichier qu'on rejoint vit déjà dans l'un
    // d'eux (ex: une image générée précédemment, rejointe depuis la galerie pour analyse), on ne
    // doit PAS en faire une seconde copie dans Pieces-jointes-chat — le fichier original suffit.
    private val jarvisManagedDirNames = listOf("JARVIS-Generated", "JARVIS-Generations", "JARVIS-Fichiers")

    /**
     * Si [uri] pointe déjà vers un fichier physiquement présent dans un dossier géré par JARVIS
     * (voir jarvisManagedDirNames), renvoie directement son chemin réel sans copie — évite le
     * doublon signalé ("cela ne les enregistre pas une seconde fois sur le smartphone").
     * La colonne MediaStore "_data" est dépréciée mais reste renseignée pour les fichiers locaux
     * issus de la galerie/du stockage partagé ; si absente ou hors dossier JARVIS, on retombe sur
     * la copie classique.
     */
    private fun resolveExistingJarvisPath(uri: Uri): Pair<String, String>? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val dataIdx = cursor.getColumnIndex("_data")
                val dataPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                if (dataPath != null && jarvisManagedDirNames.any { dataPath.contains(it) } && java.io.File(dataPath).exists()) {
                    dataPath to (name ?: java.io.File(dataPath).name)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copie le fichier pointé par [uri] dans le cache PRIVÉ de l'appli (jamais dans les
     * Documents publics) : une pièce jointe envoyée dans le chat sert à l'ANALYSER, ce n'est
     * pas une demande explicite de sauvegarde — avant ce correctif, chaque photo/document
     * envoyé pour une simple question ("qu'y a-t-il sur cette photo ?") finissait quand même
     * dupliqué en permanence dans Documents/JARVIS-Fichiers, visible dans le gestionnaire de
     * fichiers, ce que l'utilisateur n'avait jamais demandé. Le cache reste lisible tout le
     * temps de la conversation en cours (l'analyse IA, l'aperçu, et attach_contact_file en ont
     * besoin), mais n'est ni visible dans les Documents ni sauvegardé, et Android peut le vider
     * automatiquement — cohérent avec la limite déjà documentée d'attach_contact_file ("ne
     * fonctionne que dans la même conversation, pas après un redémarrage de l'appli"). Si
     * l'utilisateur veut vraiment garder le fichier, attach_contact_file en fait une copie
     * PERMANENTE et délibérée dans la fiche du contact (PeopleController.addAttachment) —
     * c'est le seul cas où une pièce jointe de chat doit survivre durablement.
     */
    private fun persistAttachmentCopy(uri: Uri, mimeType: String): Pair<String, String>? {
        resolveExistingJarvisPath(uri)?.let { return it }
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val originalName = queryDisplayName(uri) ?: "piece_jointe_${System.currentTimeMillis()}"
            val safeName = originalName.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val dir = java.io.File(cacheDir, "Pieces-jointes-chat").also { it.mkdirs() }
            val destFile = java.io.File(dir, "${System.currentTimeMillis()}_$safeName")
            input.use { inStream -> destFile.outputStream().use { outStream -> inStream.copyTo(outStream) } }
            destFile.absolutePath to originalName
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun clearPendingImage() {
        pendingImageBase64 = null
        pendingImageMime = null
        pendingAttachmentPath = null
        pendingAttachmentName = null
        pendingPrimaryAttachment = null
        pendingExtraAttachments.clear()
        pendingImageThumbnail.visibility = View.VISIBLE
        pendingImageBar.visibility = View.GONE
    }

    private fun sendMessage(text: String) {
        // Liste complète des pièces jointes (première + reste) réellement envoyée à l'IA — les
        // champs legacy imageBase64/attachmentPath restent en plus pour compatibilité (aperçu UI,
        // attach_contact_file).
        val allAttachments = mutableListOf<Attachment>()
        pendingPrimaryAttachment?.let { allAttachments.add(it) }
        allAttachments.addAll(pendingExtraAttachments)

        addMessage(
            text, isUser = true, speak = false,
            imageBase64 = pendingImageBase64, imageMime = pendingImageMime,
            attachmentPath = pendingAttachmentPath, attachmentName = pendingAttachmentName,
            attachments = allAttachments
        )
        clearPendingImage()
        statusText.text = "● JARVIS réfléchit…"

        // — Interception Obsidian Second Brain —
        val obsidianReply = ObsidianController.handleVoiceCommand(this, text)
        if (obsidianReply != null) {
            addMessage(obsidianReply, isUser = false, speak = false)
            statusText.text = "● en veille"
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val result = ApiClient.sendChat(this@MainActivity, ConversationStore.history)
            addMessage(
                result.text, isUser = false, speak = false,
                imageBase64 = result.imageBase64, imageMime = result.imageMime
            )
            statusText.text = "● en veille"
        }
    }

    private fun addMessage(
        text: String,
        isUser: Boolean,
        speak: Boolean,
        imageBase64: String? = null,
        imageMime: String? = null,
        attachmentPath: String? = null,
        attachmentName: String? = null,
        attachments: List<Attachment> = emptyList()
    ) {
        if (isUser) {
            ConversationStore.addUser(text, imageBase64, imageMime, attachmentPath, attachmentName, attachments)
        } else {
            ConversationStore.addAssistant(text)
        }
        adapter.notifyItemInserted(ConversationStore.messages.size - 1)
        recyclerView.scrollToPosition(ConversationStore.messages.size - 1)
        ConversationStore.persist(this)
        if (speak && ttsReady) {
            tts?.speak(MarkdownUtils.stripForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    /**
     * Si l'app a planté au lancement précédent, affiche le rapport dans une
     * fenêtre copiable — plus besoin d'ADB pour diagnostiquer un crash.
     */
    private fun showCrashReportIfAny() {
        val crashFile = java.io.File(filesDir, "crash_log.txt")
        if (!crashFile.exists()) return

        val report = try {
            crashFile.readText()
        } catch (e: Exception) {
            crashFile.delete()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ JARVIS a planté au dernier lancement")
            .setMessage(report)
            .setPositiveButton("Copier") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash JARVIS", report))
                Toast.makeText(this, "Rapport copié dans le presse-papier", Toast.LENGTH_SHORT).show()
                crashFile.delete()
            }
            .setNegativeButton("Fermer") { _, _ -> crashFile.delete() }
            .setCancelable(false)
            .show()
    }
}
