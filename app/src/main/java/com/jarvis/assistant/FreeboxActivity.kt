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

class FreeboxActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var destInput: EditText
    private lateinit var outputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freebox)

        hostInput = findViewById(R.id.fbxHostInput)
        pathInput = findViewById(R.id.fbxPathInput)
        nameInput = findViewById(R.id.fbxNameInput)
        destInput = findViewById(R.id.fbxDestInput)
        outputText = findViewById(R.id.fbxOutputText)

        hostInput.setText(Prefs.getFreeboxHost(this))

        findViewById<TextView>(R.id.btnFbxPair).setOnClickListener {
            Prefs.saveFreeboxHost(this, hostInput.text.toString())
            outputText.text = "⏳ Envoi de la demande d'appairage..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    FreeboxController.pairApp(this@FreeboxActivity) { status ->
                        runOnUiThread { outputText.text = status }
                    }
                }
                outputText.text = result.message
                if (result.success) Toast.makeText(this@FreeboxActivity, "Freebox appairée !", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.btnFbxWifiOn).setOnClickListener { setFbxWifi(true) }
        findViewById<TextView>(R.id.btnFbxWifiOff).setOnClickListener { setFbxWifi(false) }
        findViewById<TextView>(R.id.btnFbxWifiStatus).setOnClickListener {
            outputText.text = "⏳..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { FreeboxController.getWifiStatus(this@FreeboxActivity) }
                outputText.text = result.message
            }
        }

        findViewById<TextView>(R.id.btnFbxList).setOnClickListener { listCurrentPath() }

        findViewById<TextView>(R.id.btnFbxMkdir).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) { toastNeedName(); return@setOnClickListener }
            outputText.text = "⏳..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { FreeboxController.createFolder(this@FreeboxActivity, pathInput.text.toString(), name) }
                outputText.text = result.message
                listCurrentPath()
            }
        }

        findViewById<TextView>(R.id.btnFbxRename).setOnClickListener {
            val fullPath = nameInput.text.toString().trim()
            if (fullPath.isBlank() || !fullPath.contains("/")) {
                Toast.makeText(this, "Indique le CHEMIN COMPLET de l'élément à renommer dans « Nom », suivi du nouveau nom via une popup.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val input = EditText(this).apply { hint = "Nouveau nom" }
            AlertDialog.Builder(this)
                .setTitle("Renommer « ${fullPath.substringAfterLast("/")} »")
                .setView(input)
                .setPositiveButton("Renommer") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isBlank()) return@setPositiveButton
                    outputText.text = "⏳..."
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = withContext(Dispatchers.IO) { FreeboxController.renameEntry(this@FreeboxActivity, fullPath, newName) }
                        outputText.text = result.message
                        listCurrentPath()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        findViewById<TextView>(R.id.btnFbxDelete).setOnClickListener {
            val fullPath = nameInput.text.toString().trim()
            if (fullPath.isBlank()) { toastNeedName(); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setTitle("Supprimer ?")
                .setMessage("« $fullPath » sera supprimé de la Freebox. Irréversible.")
                .setPositiveButton("Supprimer") { _, _ ->
                    outputText.text = "⏳..."
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = withContext(Dispatchers.IO) { FreeboxController.deleteEntry(this@FreeboxActivity, fullPath) }
                        outputText.text = result.message
                        listCurrentPath()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        findViewById<TextView>(R.id.btnFbxMove).setOnClickListener {
            val fullPath = nameInput.text.toString().trim()
            val dest = destInput.text.toString().trim()
            if (fullPath.isBlank() || dest.isBlank()) {
                Toast.makeText(this, "Renseigne le chemin complet dans « Nom » et le dossier de destination.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            outputText.text = "⏳..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { FreeboxController.moveEntry(this@FreeboxActivity, fullPath, dest) }
                outputText.text = result.message
                listCurrentPath()
            }
        }

        if (FreeboxController.isConfigured(this)) listCurrentPath()
    }

    private fun setFbxWifi(enabled: Boolean) {
        outputText.text = "⏳..."
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { FreeboxController.setWifiState(this@FreeboxActivity, enabled) }
            outputText.text = result.message
        }
    }

    private fun listCurrentPath() {
        val path = pathInput.text.toString().ifBlank { "/" }
        outputText.text = "⏳ Chargement..."
        CoroutineScope(Dispatchers.Main).launch {
            val files = withContext(Dispatchers.IO) { FreeboxController.listDirectory(this@FreeboxActivity, path) }
            outputText.text = FreeboxController.formatDirectoryListing(path, files)
        }
    }

    private fun toastNeedName() {
        Toast.makeText(this, "Renseigne le champ « Nom » (chemin complet pour renommer/supprimer/déplacer).", Toast.LENGTH_LONG).show()
    }
}
