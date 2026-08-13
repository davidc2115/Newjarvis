package com.jarvis.assistant

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GenerationActivity : AppCompatActivity() {

    private lateinit var imageOutput: TextView
    private lateinit var imageView: ImageView
    private lateinit var videoOutput: TextView
    private lateinit var websiteOutput: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyEmptyText: TextView
    private lateinit var activeGenCard: View
    private lateinit var activeGenLabel: TextView
    private var lastWebsiteFile: File? = null

    // Rafraîchit l'historique (et donc la barre de progression) toutes les 1,5s
    // pendant que cet écran est visible — indispensable pour voir en direct une
    // génération lancée depuis le chat (image/vidéo/site), pas seulement celles
    // lancées depuis ce bouton. S'arrête automatiquement quand l'écran n'est plus
    // affiché (onPause) pour ne pas tourner inutilement en arrière-plan.
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshHistory()
            pollHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generation)

        val imagePrompt = findViewById<EditText>(R.id.imagePromptInput)
        imageOutput = findViewById(R.id.imageOutputText)
        imageView = findViewById(R.id.generatedImageView)

        val replicateTokenInput = findViewById<EditText>(R.id.replicateTokenInput)
        val videoPrompt = findViewById<EditText>(R.id.videoPromptInput)
        videoOutput = findViewById(R.id.videoOutputText)

        val websitePrompt = findViewById<EditText>(R.id.websitePromptInput)
        websiteOutput = findViewById(R.id.websiteOutputText)
        historyContainer = findViewById(R.id.historyContainer)
        historyEmptyText = findViewById(R.id.historyEmptyText)
        activeGenCard = findViewById(R.id.activeGenCard)
        activeGenLabel = findViewById(R.id.activeGenLabel)

        replicateTokenInput.setText(Prefs.getReplicateToken(this))

        // Les 3 générations tournent désormais dans GenerationService (arrière-plan) :
        // elles continuent même si on quitte cet écran ou ferme l'application, sont
        // enregistrées dans l'historique, et une notification prévient à la fin.

        findViewById<TextView>(R.id.btnGenImage).setOnClickListener {
            val prompt = imagePrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris l'image souhaitée.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            imageView.visibility = View.GONE
            GenerationService.enqueue(this, "image", prompt)
            imageOutput.text = "🎨 Génération lancée en arrière-plan — une notification t'avertira dès que c'est prêt."
            refreshHistory()
        }

        findViewById<TextView>(R.id.btnGenVideo).setOnClickListener {
            val prompt = videoPrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris la vidéo souhaitée.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            Prefs.saveReplicateToken(this, replicateTokenInput.text.toString())
            GenerationService.enqueue(this, "video", prompt)
            videoOutput.text = "🎬 Génération lancée en arrière-plan (1 à 3 minutes) — une notification t'avertira dès que c'est prêt."
            refreshHistory()
        }

        findViewById<TextView>(R.id.btnGenWebsite).setOnClickListener {
            val prompt = websitePrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris le site souhaité.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            GenerationService.enqueue(this, "website", prompt)
            websiteOutput.text = "🌐 Génération lancée en arrière-plan — une notification t'avertira dès que c'est prêt."
            refreshHistory()
        }

        findViewById<TextView>(R.id.btnOpenWebsite).setOnClickListener {
            val file = lastWebsiteFile
                ?: lastSuccessfulRecordPath("website")?.let { File(it) }
                ?: WebsiteGenController.listGeneratedSites(this).firstOrNull()
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Aucun site généré pour l'instant.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openWebsiteFile(file)
        }

        findViewById<TextView>(R.id.btnRefreshHistory).setOnClickListener { refreshHistory() }

        findViewById<TextView>(R.id.btnViewLastImage).setOnClickListener {
            val path = lastSuccessfulRecordPath("image")
            if (path == null || !File(path).exists()) {
                Toast.makeText(this, "Aucune image générée pour l'instant.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewImage(path)
        }

        findViewById<TextView>(R.id.btnViewLastVideo).setOnClickListener {
            val path = lastSuccessfulRecordPath("video")
            if (path == null || !File(path).exists()) {
                Toast.makeText(this, "Aucune vidéo générée pour l'instant.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playVideo(path)
        }

        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    /**
     * Affiche/masque la carte de progression selon qu'il existe des générations
     * "pending" dans l'historique — que la génération ait été lancée depuis ce
     * bouton, ou depuis le chat/la voix via GenerationService.
     */
    private fun updateActiveGenCard(history: List<Prefs.GenerationRecord>) {
        val pending = history.filter { it.status == "pending" }
        if (pending.isEmpty()) {
            activeGenCard.visibility = View.GONE
            return
        }
        activeGenCard.visibility = View.VISIBLE
        val label = pending.joinToString("\n") { record ->
            val typeLabel = when (record.type) {
                "image" -> "🖼️ Image"
                "video" -> "🎬 Vidéo"
                "website" -> "🌐 Site web"
                "website_edit" -> "✏️ Modification de site"
                else -> record.type
            }
            "⏳ $typeLabel en cours — ${record.prompt.take(50)}"
        }
        activeGenLabel.text = label
    }

    private fun lastSuccessfulRecordPath(type: String): String? =
        Prefs.getGenerationHistory(this)
            .firstOrNull { it.type == type && it.status == "success" && !it.resultPath.isNullOrBlank() }
            ?.resultPath

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Reconstruit la galerie de générations : une carte cliquable par génération
     * (image/vidéo/site), la plus récente en premier. Toucher une carte affiche le
     * résultat (image/vidéo/site) et, pour un site, propose aussi de le modifier.
     */
    private fun refreshHistory() {
        // Corrige les générations restées bloquées sur "pending" parce que le service a
        // été tué par le système avant de pouvoir écrire un résultat (sinon elles restent
        // affichées "en cours" indéfiniment, sans jamais aboutir ni échouer explicitement).
        Prefs.reconcileStaleGenerations(this)
        val history = Prefs.getGenerationHistory(this)
        updateActiveGenCard(history)
        historyContainer.removeAllViews()

        if (history.isEmpty()) {
            historyEmptyText.visibility = View.VISIBLE
            return
        }
        historyEmptyText.visibility = View.GONE

        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        history.take(30).forEach { record ->
            val icon = when (record.status) {
                "success" -> "✅"
                "failed" -> "❌"
                else -> "⏳"
            }
            val typeLabel = when (record.type) {
                "image" -> "🖼️ Image"
                "video" -> "🎬 Vidéo"
                "website" -> "🌐 Site web"
                "website_edit" -> "✏️ Site modifié"
                else -> record.type
            }
            val date = fmt.format(Date(record.timestamp))
            val promptShort = record.prompt.take(60)
            val detail = when {
                record.status == "failed" && !record.errorMessage.isNullOrBlank() -> "\n${record.errorMessage.take(120)}"
                record.status == "success" -> "\n👉 Toucher pour afficher / modifier"
                record.status == "pending" -> "\n⏳ En cours..."
                else -> ""
            }

            val row = TextView(this).apply {
                text = "$icon [$date] $typeLabel\n$promptShort$detail"
                setTextColor(Color.parseColor("#E6EAF2"))
                textSize = 11f
                setLineSpacing(dp(2).toFloat(), 1f)
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = record.status != "pending"
                isFocusable = record.status != "pending"
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setOnClickListener { handleHistoryItemClick(record) }
            }
            historyContainer.addView(row)
        }
    }

    private fun handleHistoryItemClick(record: Prefs.GenerationRecord) {
        when (record.status) {
            "pending" -> Toast.makeText(this, "⏳ Génération en cours...", Toast.LENGTH_SHORT).show()
            "failed" -> AlertDialog.Builder(this)
                .setTitle("❌ Échec de la génération")
                .setMessage(record.errorMessage?.ifBlank { "Erreur inconnue." } ?: "Erreur inconnue.")
                .setPositiveButton("OK", null)
                .show()
            "success" -> {
                val path = record.resultPath
                if (path.isNullOrBlank() || !File(path).exists()) {
                    Toast.makeText(this, "❌ Fichier introuvable (peut-être supprimé ou déplacé).", Toast.LENGTH_LONG).show()
                    return
                }
                when (record.type) {
                    "image" -> viewImage(path)
                    "video" -> playVideo(path)
                    "website", "website_edit" -> showWebsiteOptions(record, File(path))
                    else -> Toast.makeText(this, "Type de génération inconnu.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun viewImage(path: String) {
        try {
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap == null) {
                Toast.makeText(this, "❌ Impossible de décoder l'image.", Toast.LENGTH_LONG).show()
                return
            }
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
            imageView.requestFocus()
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de charger l'image : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun playVideo(path: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir la vidéo : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWebsiteFile(file: File) {
        try {
            lastWebsiteFile = file
            val uri = WebsiteGenController.getShareableUri(this, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir le site : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Propose "Ouvrir" ou "Modifier" pour un site déjà généré (galerie ou bouton rapide). */
    private fun showWebsiteOptions(record: Prefs.GenerationRecord, file: File) {
        AlertDialog.Builder(this)
            .setTitle("🌐 ${record.prompt.take(60)}")
            .setItems(arrayOf("🌐 Ouvrir dans le navigateur", "✏️ Modifier ce site", "Annuler")) { dialog, which ->
                when (which) {
                    0 -> openWebsiteFile(file)
                    1 -> promptEditWebsite(file.absolutePath)
                }
                dialog.dismiss()
            }
            .show()
    }

    /** Demande les instructions de modification puis relance une génération d'édition en arrière-plan. */
    private fun promptEditWebsite(existingPath: String) {
        val input = EditText(this).apply {
            hint = "Ex : change la couleur principale en bleu, ajoute une section avis clients..."
            setTextColor(Color.parseColor("#E6EAF2"))
            setHintTextColor(Color.parseColor("#8A93A6"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Modifier le site")
            .setView(container)
            .setPositiveButton("Lancer la modification") { dialog, _ ->
                val instructions = input.text.toString()
                if (instructions.isBlank()) {
                    Toast.makeText(this, "Précise la modification souhaitée.", Toast.LENGTH_SHORT).show()
                } else {
                    GenerationService.enqueue(this, "website_edit", instructions, existingPath = existingPath)
                    Toast.makeText(this, "✏️ Modification lancée en arrière-plan.", Toast.LENGTH_SHORT).show()
                    refreshHistory()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Annuler") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
