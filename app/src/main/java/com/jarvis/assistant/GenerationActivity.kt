package com.jarvis.assistant

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GenerationActivity : AppCompatActivity() {

    private var lastWebsiteFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generation)

        val imagePrompt = findViewById<EditText>(R.id.imagePromptInput)
        val imageOutput = findViewById<TextView>(R.id.imageOutputText)
        val imageView = findViewById<ImageView>(R.id.generatedImageView)

        val replicateTokenInput = findViewById<EditText>(R.id.replicateTokenInput)
        val videoPrompt = findViewById<EditText>(R.id.videoPromptInput)
        val videoOutput = findViewById<TextView>(R.id.videoOutputText)

        val websitePrompt = findViewById<EditText>(R.id.websitePromptInput)
        val websiteOutput = findViewById<TextView>(R.id.websiteOutputText)

        replicateTokenInput.setText(Prefs.getReplicateToken(this))

        findViewById<TextView>(R.id.btnGenImage).setOnClickListener {
            val prompt = imagePrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris l'image souhaitée.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            imageOutput.text = "⏳ Génération en cours..."
            imageView.visibility = android.view.View.GONE
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { ImageGenController.generateImage(this@GenerationActivity, prompt) }
                imageOutput.text = MarkdownUtils.toSpannable(result.message)
                if (result.base64 != null) {
                    try {
                        val bytes = Base64.decode(result.base64, Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = android.view.View.VISIBLE
                    } catch (_: Exception) { }
                }
            }
        }

        findViewById<TextView>(R.id.btnGenVideo).setOnClickListener {
            val prompt = videoPrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris la vidéo souhaitée.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            Prefs.saveReplicateToken(this, replicateTokenInput.text.toString())
            videoOutput.text = "⏳ Génération en cours (peut prendre 1 à 3 minutes)..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { VideoGenController.generateVideo(this@GenerationActivity, prompt) }
                videoOutput.text = MarkdownUtils.toSpannable(result.message)
            }
        }

        findViewById<TextView>(R.id.btnGenWebsite).setOnClickListener {
            val prompt = websitePrompt.text.toString()
            if (prompt.isBlank()) { Toast.makeText(this, "Décris le site souhaité.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            websiteOutput.text = "⏳ Génération en cours..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { WebsiteGenController.generateWebsite(this@GenerationActivity, prompt) }
                websiteOutput.text = MarkdownUtils.toSpannable(result.message)
                if (result.success && result.filePath != null) {
                    lastWebsiteFile = File(result.filePath)
                }
            }
        }

        findViewById<TextView>(R.id.btnOpenWebsite).setOnClickListener {
            val file = lastWebsiteFile ?: WebsiteGenController.listGeneratedSites(this).firstOrNull()
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
    }
}
