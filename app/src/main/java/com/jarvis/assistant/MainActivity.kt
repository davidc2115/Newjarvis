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
import java.io.ByteArrayOutputStream
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
    // retrouver le fichier original plus tard, même une fois le message envoyé.
    private var pendingAttachmentPath: String? = null
    private var pendingAttachmentName: String? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openVoiceMode() else {
            Toast.makeText(this, "Permission micro refusée", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) attachImage(uri)
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
            if (text.isNotEmpty() || pendingImageBase64 != null || pendingAttachmentPath != null) {
                val defaultText = if (pendingImageBase64 != null) "Décris cette image." else "Voici un fichier joint."
                sendMessage(text.ifBlank { defaultText })
                messageInput.text.clear()
            }
        }

        micButton.setOnClickListener { checkPermissionAndOpenVoiceMode() }

        photoButton.setOnClickListener { pickImageLauncher.launch("*/*") }

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

    private fun attachImage(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: ""
        val isImage = mimeType.startsWith("image/")

        CoroutineScope(Dispatchers.IO).launch {
            // Toute pièce jointe (photo OU document) est copiée sur le disque, pas seulement
            // encodée en mémoire — c'est ce qui permet à attach_contact_file de la retrouver
            // plus tard (JARVIS n'a aucun autre moyen d'accéder au fichier original une fois
            // le sélecteur fermé, l'URI content:// n'étant pas garanti réutilisable ensuite).
            val persisted = persistAttachmentCopy(uri, mimeType)
            val visionResult = if (isImage) encodeImage(uri) else null

            withContext(Dispatchers.Main) {
                if (persisted == null && visionResult == null) {
                    Toast.makeText(this@MainActivity, "Impossible de lire ce fichier", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                pendingAttachmentPath = persisted?.first
                pendingAttachmentName = persisted?.second

                if (isImage && visionResult != null) {
                    pendingImageBase64 = visionResult.first
                    pendingImageMime = visionResult.second
                    val bytes = Base64.decode(visionResult.first, Base64.NO_WRAP)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    pendingImageThumbnail.setImageBitmap(bmp)
                    pendingImageThumbnail.visibility = View.VISIBLE
                    removePendingImageButtonRef?.text = "✕ retirer la photo"
                } else {
                    pendingImageBase64 = null
                    pendingImageMime = null
                    pendingImageThumbnail.visibility = View.GONE
                    removePendingImageButtonRef?.text = "📎 ${pendingAttachmentName ?: "fichier"} — ✕ retirer"
                }
                pendingImageBar.visibility = View.VISIBLE
            }
        }
    }

    /** Copie le fichier pointé par [uri] dans Documents/JARVIS-Fichiers/Pieces-jointes-chat/, retourne (chemin, nom). */
    private fun persistAttachmentCopy(uri: Uri, mimeType: String): Pair<String, String>? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val originalName = queryDisplayName(uri) ?: "piece_jointe_${System.currentTimeMillis()}"
            val safeName = originalName.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                "JARVIS-Fichiers/Pieces-jointes-chat"
            ).also { it.mkdirs() }
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

    private fun encodeImage(uri: Uri): Pair<String, String>? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            val maxDim = 1024
            val scale = minOf(1f, maxDim.toFloat() / maxOf(original.width, original.height))
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else original

            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
        } catch (e: Exception) {
            null
        }
    }

    private fun clearPendingImage() {
        pendingImageBase64 = null
        pendingImageMime = null
        pendingAttachmentPath = null
        pendingAttachmentName = null
        pendingImageThumbnail.visibility = View.VISIBLE
        pendingImageBar.visibility = View.GONE
    }

    private fun sendMessage(text: String) {
        addMessage(
            text, isUser = true, speak = false,
            imageBase64 = pendingImageBase64, imageMime = pendingImageMime,
            attachmentPath = pendingAttachmentPath, attachmentName = pendingAttachmentName
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
        attachmentName: String? = null
    ) {
        if (isUser) {
            ConversationStore.addUser(text, imageBase64, imageMime, attachmentPath, attachmentName)
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
