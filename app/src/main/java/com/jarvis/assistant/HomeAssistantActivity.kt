package com.jarvis.assistant

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeAssistantActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var remoteUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var renameInput: EditText
    private lateinit var outputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_assistant)

        urlInput = findViewById(R.id.haUrlInput)
        remoteUrlInput = findViewById(R.id.haRemoteUrlInput)
        tokenInput = findViewById(R.id.haTokenInput)
        searchInput = findViewById(R.id.haSearchInput)
        renameInput = findViewById(R.id.haRenameInput)
        outputText = findViewById(R.id.haOutputText)

        urlInput.setText(Prefs.getHaUrl(this))
        remoteUrlInput.setText(Prefs.getHaRemoteUrl(this))
        tokenInput.setText(Prefs.getHaToken(this))

        findViewById<TextView>(R.id.btnHaSave).setOnClickListener {
            Prefs.saveHaUrl(this, urlInput.text.toString())
            Prefs.saveHaRemoteUrl(this, remoteUrlInput.text.toString())
            Prefs.saveHaToken(this, tokenInput.text.toString())
            outputText.text = "⏳ Test de connexion..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { HomeAssistantController.testConnection(this@HomeAssistantActivity) }
                outputText.text = MarkdownUtils.toSpannable(result.message)
                if (result.success) {
                    Toast.makeText(this@HomeAssistantActivity, "Connecté à Home Assistant !", Toast.LENGTH_SHORT).show()
                    refreshEntities()
                }
            }
        }

        findViewById<TextView>(R.id.btnHaRefresh).setOnClickListener { refreshEntities() }

        findViewById<TextView>(R.id.btnHaTurnOn).setOnClickListener { runEntityAction { HomeAssistantController.turnOn(this, it) } }
        findViewById<TextView>(R.id.btnHaTurnOff).setOnClickListener { runEntityAction { HomeAssistantController.turnOff(this, it) } }
        findViewById<TextView>(R.id.btnHaToggle).setOnClickListener { runEntityAction { HomeAssistantController.toggle(this, it) } }

        findViewById<TextView>(R.id.btnHaRename).setOnClickListener {
            val newName = renameInput.text.toString().trim()
            if (newName.isBlank()) {
                Toast.makeText(this, "Indique le nouveau nom dans le champ prévu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runEntityAction { entityId -> HomeAssistantController.renameEntity(this, entityId, newName) }
        }

        findViewById<TextView>(R.id.btnHaDelete).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "Indique le nom de l'appareil dans le champ de recherche.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Supprimer cet appareil ?")
                .setMessage("« $query » sera supprimé du registre Home Assistant. Cette action est irréversible et peut être annulée uniquement en réintégrant l'appareil depuis Home Assistant.")
                .setPositiveButton("Supprimer") { _, _ ->
                    runEntityAction { entityId -> HomeAssistantController.deleteEntity(this, entityId) }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        if (HomeAssistantController.isConfigured(this)) refreshEntities()
    }

    private fun refreshEntities() {
        outputText.text = "⏳ Chargement des appareils..."
        CoroutineScope(Dispatchers.Main).launch {
            val filter = searchInput.text.toString()
            val summary = withContext(Dispatchers.IO) { HomeAssistantController.summarize(this@HomeAssistantActivity, filter) }
            outputText.text = MarkdownUtils.toSpannable(summary)
        }
    }

    private fun runEntityAction(action: suspend (String) -> HomeAssistantController.ActionResult) {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Indique le nom de l'appareil dans le champ de recherche.", Toast.LENGTH_SHORT).show()
            return
        }
        outputText.text = "⏳ Recherche de « $query »..."
        CoroutineScope(Dispatchers.Main).launch {
            val entity = withContext(Dispatchers.IO) { HomeAssistantController.findEntity(this@HomeAssistantActivity, query) }
            if (entity == null) {
                outputText.text = "❌ Aucun appareil trouvé pour « $query »."
                return@launch
            }
            val result = withContext(Dispatchers.IO) { action(entity.entityId) }
            outputText.text = "${entity.friendlyName} : ${result.message}"
            refreshEntities()
        }
    }
}
