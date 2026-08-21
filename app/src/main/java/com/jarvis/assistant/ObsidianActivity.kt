package com.jarvis.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ObsidianActivity : AppCompatActivity() {

    private lateinit var vaultPathText: TextView
    private lateinit var resultText: TextView
    private lateinit var noteInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var folderInput: EditText
    private lateinit var searchInput: EditText

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = resolveTreeUriToPath(uri)
            if (path == null) {
                Toast.makeText(
                    this,
                    "❌ Impossible de déterminer le chemin réel de ce dossier (carte SD non standard ?). Vault inchangé.",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            val target = File(path)
            // Refuse la racine ENTIÈRE du stockage interne comme "dossier vault" : un
            // utilisateur qui confirme le sélecteur sans naviguer dans un sous-dossier
            // précis pointe par erreur vers TOUT le stockage du téléphone — JARVIS créerait
            // alors ses dossiers (Notes Rapides, Modèles...) directement à la racine visible,
            // mélangés avec le reste des fichiers de l'utilisateur (bug déjà signalé).
            val storageRootPath = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            if (path.trimEnd('/').equals(storageRootPath, ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "❌ Ce dossier est la racine ENTIÈRE du stockage du téléphone, pas un vault précis. " +
                        "Choisis (ou crée) un sous-dossier dédié, par exemple ton vault Obsidian existant " +
                        "ou un nouveau dossier « JARVIS-Vault ». Vault inchangé.",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            // Vérification concrète avant d'adopter ce chemin : on doit pouvoir au
            // moins créer/lister le dossier. Sans ce contrôle, un chemin mal calculé
            // (ex: carte SD) serait accepté silencieusement et JARVIS écrirait dans
            // le vide — exactement le genre de désynchronisation signalée.
            val usable = target.exists() || target.mkdirs()
            if (!usable) {
                Toast.makeText(
                    this,
                    "❌ Ce dossier n'est pas accessible en écriture par JARVIS (chemin calculé : $path). " +
                        "Choisis un dossier sur le stockage interne, ou dans Documents.",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            Prefs.saveObsidianVaultPath(this, path)
            vaultPathText.text = "📂 Vault : $path"
            Toast.makeText(this, "✅ Vault pointé vers : $path (vérifié accessible)", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Convertit une URI d'arbre de documents (SAF) en chemin filesystem réel.
     * L'ancienne implémentation faisait un simple replace("/tree/primary:", "/sdcard/")
     * qui ne fonctionnait QUE pour le stockage interne principal — pour toute carte SD
     * ou volume secondaire (id différent de "primary"), le chemin obtenu était un
     * fragment d'URI invalide, silencieusement accepté comme chemin de vault, ce qui
     * cassait complètement la correspondance avec le vrai vault Obsidian de l'utilisateur.
     */
    private fun resolveTreeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            val volumeId = parts.getOrNull(0) ?: return null
            val relativePath = parts.getOrNull(1) ?: ""
            val base = if (volumeId.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                // Volume secondaire (carte SD, stockage USB...) — chemin standard sur
                // la grande majorité des appareils Android, non garanti à 100% selon
                // le fabricant, d'où la vérification d'accessibilité juste après.
                "/storage/$volumeId"
            }
            if (relativePath.isBlank()) base else "$base/$relativePath"
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_obsidian)

        vaultPathText  = findViewById(R.id.obsidianVaultPathText)
        resultText     = findViewById(R.id.obsidianResultText)
        noteInput      = findViewById(R.id.obsidianNoteInput)
        contentInput   = findViewById(R.id.obsidianContentInput)
        folderInput    = findViewById(R.id.obsidianFolderInput)
        searchInput    = findViewById(R.id.obsidianSearchInput)

        // Afficher chemin du vault
        val root = ObsidianController.getVaultRoot(this)
        vaultPathText.text = "📂 Vault : ${root.absolutePath}"

        // Init automatique si vault n'existe pas
        if (!root.exists()) {
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { ObsidianController.initVault(this@ObsidianActivity) }
                resultText.text = result
                vaultPathText.text = "📂 Vault : ${ObsidianController.getVaultRoot(this@ObsidianActivity).absolutePath}"
            }
        } else {
            // Afficher stats au démarrage
            CoroutineScope(Dispatchers.Main).launch {
                resultText.text = withContext(Dispatchers.IO) { ObsidianController.getVaultStats(this@ObsidianActivity) }
            }
        }

        setupButtons()
    }

    private fun setupButtons() {

        // ── Créer une note ──────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnCreateNote).setOnClickListener {
            val title   = noteInput.text.toString().trim()
            val content = contentInput.text.toString().trim()
            val folder  = folderInput.text.toString().trim().ifBlank { "Notes Rapides" }

            if (title.isBlank()) {
                Toast.makeText(this, "Entrez un titre pour la note", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runAsync { ObsidianController.createNote(this, title, content, folder) }
            noteInput.text.clear()
            contentInput.text.clear()
        }

        // ── Note du jour ────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnDailyNote).setOnClickListener {
            val extra = contentInput.text.toString().trim()
            runAsync { ObsidianController.createDailyNote(this, extra) }
        }

        // ── Recherche ───────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnSearchNotes).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "Entrez un terme de recherche", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runAsync { ObsidianController.searchNotes(this, query) }
        }

        // ── Lister les notes ────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnListNotes).setOnClickListener {
            runAsync { ObsidianController.listNotes(this) }
        }

        // ── Exploration interactive de la Toile Obsidian (pan/zoom, tap sur un point) ───────
        findViewById<TextView>(R.id.btnVaultGraph).setOnClickListener {
            startActivity(Intent(this, VaultGraphActivity::class.java))
        }

        // ── Ouvrir dans Obsidian ────────────────────────────────────────────
        findViewById<TextView>(R.id.btnOpenObsidian).setOnClickListener {
            runAsync { ObsidianController.openInObsidian(this, "") }
        }

        // ── Init / Réparer Vault ────────────────────────────────────────────
        findViewById<TextView>(R.id.btnInitVault).setOnClickListener {
            runAsync {
                val result = ObsidianController.initVault(this)
                val path   = ObsidianController.getVaultRoot(this).absolutePath
                runOnUiThread { vaultPathText.text = "📂 Vault : $path" }
                result
            }
        }

        // ── Changer dossier vault ───────────────────────────────────────────
        findViewById<TextView>(R.id.btnChangeVaultPath).setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // ── Réinitialiser le chemin (garde le contenu de l'ancien dossier) ──
        findViewById<TextView>(R.id.btnResetVaultPath).setOnClickListener {
            runAsync { ObsidianController.resetVaultPath(this) }
            vaultPathText.text = "📂 Vault : ${ObsidianController.getVaultRoot(this).absolutePath}"
        }

        // ── Vider le vault actuel (destructif) ──────────────────────────────
        findViewById<TextView>(R.id.btnWipeVault).setOnClickListener {
            val root = ObsidianController.getVaultRoot(this).absolutePath
            android.app.AlertDialog.Builder(this)
                .setTitle("Vider le vault ?")
                .setMessage("Toutes les notes dans « $root » seront supprimées définitivement. Cette action est irréversible.")
                .setPositiveButton("Vider") { _, _ ->
                    runAsync { ObsidianController.wipeVault(this) }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // ── Effacer les surnoms de calendrier (stockés hors du vault) ───────
        // Ne fait volontairement PAS partie de "Vider le vault" : les surnoms
        // (name_calendar) vivent dans les préférences de l'app, pas dans les
        // fichiers Obsidian — vider le vault ne les touche jamais.
        findViewById<TextView>(R.id.btnResetCalendarNicknames).setOnClickListener {
            runAsync { CalendarController.resetCalendarNicknames(this) }
        }
    }

    /** Lance une coroutine IO et affiche le résultat dans resultText. */
    private fun runAsync(block: suspend () -> String) {
        resultText.text = "⏳ En cours…"
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { block() }
            resultText.text = result
        }
    }
}
