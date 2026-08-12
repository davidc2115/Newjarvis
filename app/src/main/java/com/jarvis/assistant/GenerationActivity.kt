package com.jarvis.assistant

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
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
    private lateinit var historyText: TextView
    private var lastWebsiteFile: File? = null

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
        historyText = findViewById(R.id.historyText)

        replicateTokenInput.setText(Prefs.getReplicateToken(this))

        // Les 3 générations tournent désormais dans GenerationService (arrière-plan) :
        // elles continuent même si on quitte cet écran ou ferme l'application, sont
        // enregistrées dans l'historique, et une notification prévient à la fin.

        findViewById<TextView>(R.id.btnGenImage).setOnClickListener {
            val prompt = imagePrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris l'image souhaitée.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            imageView.visibility = android.view.View.GONE
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
            try {
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

        findViewById<TextView>(R.id.btnRefreshHistory).setOnClickListener { refreshHistory() }

        findViewById<TextView>(R.id.btnViewLastImage).setOnClickListener {
            val path = lastSuccessfulRecordPath("image")
            if (path == null || !File(path).exists()) {
                Toast.makeText(this, "Aucune image générée pour l'instant.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                imageView.setImageBitmap(bitmap)
                imageView.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this, "Impossible de charger l'image : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.btnViewLastVideo).setOnClickListener {
            val path = lastSuccessfulRecordPath("video")
            if (path == null || !File(path).exists()) {
                Toast.makeText(this, "Aucune vidéo générée pour l'instant.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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

        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
    }

    private fun lastSuccessfulRecordPath(type: String): String? =
        Prefs.getGenerationHistory(this)
            .firstOrNull { it.type == type && it.status == "success" && !it.resultPath.isNullOrBlank() }
            ?.resultPath

    private fun refreshHistory() {
        val history = Prefs.getGenerationHistory(this)
        if (history.isEmpty()) {
            historyText.text = "Aucune génération pour l'instant."
            return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        historyText.text = history.take(30).joinToString("\n\n") { record ->
            val icon = when (record.status) {
                "success" -> "✅"
                "failed" -> "❌"
                else -> "⏳"
            }
            val typeLabel = when (record.type) {
                "image" -> "Image"
                "video" -> "Vidéo"
                "website" -> "Site web"
                else -> record.type
            }
            val date = fmt.format(Date(record.timestamp))
            val promptShort = record.prompt.take(60)
            val detail = if (record.status == "failed" && !record.errorMessage.isNullOrBlank()) {
                "\n   ${record.errorMessage.take(100)}"
            } else ""
            "$icon [$date] $typeLabel — $promptShort$detail"
        }
    }
}
