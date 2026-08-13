package com.jarvis.assistant

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GitHubActivity : AppCompatActivity() {

    private lateinit var accountsListText: TextView
    private lateinit var accountLabelInput: EditText
    private lateinit var accountTokenInput: EditText

    private lateinit var browseOwnerInput: EditText
    private lateinit var browseRepoInput: EditText
    private lateinit var browseBranchInput: EditText
    private lateinit var browsePathInput: EditText
    private lateinit var browseAccountInput: EditText
    private lateinit var browseResultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github)

        BottomNav.setup(this, NavDestination.GITHUB)
        EdgeToEdgeHelper.applyTopInset(findViewById(R.id.rootLayout))
        EdgeToEdgeHelper.applyBottomInset(findViewById(R.id.bottomNavRoot))

        val githubTokenInput = findViewById<EditText>(R.id.githubTokenInput)
        val saveGithubTokenButton = findViewById<TextView>(R.id.saveGithubTokenButton)
        val listReposButton = findViewById<TextView>(R.id.listReposButton)
        val reposResultText = findViewById<TextView>(R.id.reposResultText)
        val newRepoNameInput = findViewById<EditText>(R.id.newRepoNameInput)
        val newRepoDescInput = findViewById<EditText>(R.id.newRepoDescInput)
        val createRepoButton = findViewById<TextView>(R.id.createRepoButton)

        accountsListText = findViewById(R.id.accountsListText)
        accountLabelInput = findViewById(R.id.accountLabelInput)
        accountTokenInput = findViewById(R.id.accountTokenInput)
        val addAccountButton = findViewById<TextView>(R.id.addAccountButton)
        val setDefaultAccountButton = findViewById<TextView>(R.id.setDefaultAccountButton)
        val removeAccountButton = findViewById<TextView>(R.id.removeAccountButton)

        browseOwnerInput = findViewById(R.id.browseOwnerInput)
        browseRepoInput = findViewById(R.id.browseRepoInput)
        browseBranchInput = findViewById(R.id.browseBranchInput)
        browsePathInput = findViewById(R.id.browsePathInput)
        browseAccountInput = findViewById(R.id.browseAccountInput)
        val browseButton = findViewById<TextView>(R.id.browseButton)
        browseResultText = findViewById(R.id.browseResultText)
        val deleteFileButton = findViewById<TextView>(R.id.deleteFileButton)
        val deleteFolderButton = findViewById<TextView>(R.id.deleteFolderButton)
        val deleteRepoButton = findViewById<TextView>(R.id.deleteRepoButton)

        githubTokenInput.setText(Prefs.getGithubToken(this))

        saveGithubTokenButton.setOnClickListener {
            Prefs.saveGithubToken(this, githubTokenInput.text.toString().trim())
            Toast.makeText(this, "✅ Jeton GitHub enregistré", Toast.LENGTH_SHORT).show()
        }

        // ─── Multi-comptes ──────────────────────────────────────────────────
        refreshAccountsList()

        addAccountButton.setOnClickListener {
            val label = accountLabelInput.text.toString().trim()
            val token = accountTokenInput.text.toString().trim()
            if (label.isBlank() || token.isBlank()) {
                Toast.makeText(this, "Renseigne un nom ET un jeton pour ce compte", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.addGithubAccount(this, Prefs.GitHubAccount(label = label, token = token))
            accountLabelInput.text.clear()
            accountTokenInput.text.clear()
            Toast.makeText(this, "✅ Compte « $label » ajouté", Toast.LENGTH_SHORT).show()
            refreshAccountsList()
        }

        setDefaultAccountButton.setOnClickListener {
            val label = accountLabelInput.text.toString().trim()
            val account = Prefs.findGithubAccount(this, label)
            if (account == null) {
                Toast.makeText(this, "Aucun compte ne correspond à « $label » (champ nom du compte ci-dessus)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setDefaultGithubAccount(this, account.id)
            Toast.makeText(this, "⭐ « ${account.label} » est maintenant le compte par défaut", Toast.LENGTH_SHORT).show()
            refreshAccountsList()
        }

        removeAccountButton.setOnClickListener {
            val label = accountLabelInput.text.toString().trim()
            val account = Prefs.findGithubAccount(this, label)
            if (account == null) {
                Toast.makeText(this, "Aucun compte ne correspond à « $label » (champ nom du compte ci-dessus)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Retirer ce compte ?")
                .setMessage("« ${account.label} » sera retiré de JARVIS (le jeton n'est pas révoqué sur GitHub, juste oublié ici).")
                .setPositiveButton("Retirer") { _, _ ->
                    Prefs.removeGithubAccount(this, account.id)
                    accountLabelInput.text.clear()
                    Toast.makeText(this, "🗑 Compte « ${account.label} » retiré", Toast.LENGTH_SHORT).show()
                    refreshAccountsList()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // ─── Liste des dépôts ───────────────────────────────────────────────
        listReposButton.setOnClickListener {
            reposResultText.visibility = View.VISIBLE
            reposResultText.text = "Chargement…"
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    GitHubController.listRepos(this@GitHubActivity, browseAccountInput.text.toString().trim())
                }
                reposResultText.text = result
            }
        }

        // ─── Navigateur de dépôt ────────────────────────────────────────────
        browseButton.setOnClickListener { browseCurrentPath() }

        deleteFileButton.setOnClickListener {
            val (owner, repo, branch, path, account) = readBrowseFields() ?: return@setOnClickListener
            if (path.isBlank()) {
                Toast.makeText(this, "Précise le chemin du fichier à supprimer", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Supprimer ce fichier ?")
                .setMessage("« $path » sera supprimé de $owner/$repo (branche $branch). Irréversible.")
                .setPositiveButton("Supprimer") { _, _ ->
                    browseResultText.visibility = View.VISIBLE
                    browseResultText.text = "Suppression en cours…"
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = withContext(Dispatchers.IO) {
                            GitHubController.deleteFile(this@GitHubActivity, owner, repo, path, "Suppression depuis JARVIS", branch, account)
                        }
                        browseResultText.text = result
                        if (result.startsWith("✅")) browseCurrentPath()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        deleteFolderButton.setOnClickListener {
            val (owner, repo, branch, path, account) = readBrowseFields() ?: return@setOnClickListener
            if (path.isBlank()) {
                Toast.makeText(this, "Précise le chemin du dossier à supprimer (jamais la racine)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Supprimer ce dossier entier ?")
                .setMessage("Tous les fichiers sous « $path » seront supprimés de $owner/$repo (branche $branch), en un seul commit. Irréversible.")
                .setPositiveButton("Supprimer") { _, _ ->
                    browseResultText.visibility = View.VISIBLE
                    browseResultText.text = "Suppression en cours…"
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = withContext(Dispatchers.IO) {
                            GitHubController.deleteFolder(this@GitHubActivity, owner, repo, path, "Suppression de dossier depuis JARVIS", branch, account)
                        }
                        browseResultText.text = result
                        if (result.startsWith("✅")) browseCurrentPath()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        deleteRepoButton.setOnClickListener {
            val owner = browseOwnerInput.text.toString().trim()
            val repo = browseRepoInput.text.toString().trim()
            val account = browseAccountInput.text.toString().trim()
            if (owner.isBlank() || repo.isBlank()) {
                Toast.makeText(this, "Renseigne le propriétaire ET le nom du dépôt", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("⚠️ Supprimer TOUT le dépôt $owner/$repo ?")
                .setMessage("Cette action est DÉFINITIVE et IRRÉVERSIBLE : le dépôt entier (code, historique, issues…) sera supprimé de GitHub.")
                .setPositiveButton("Supprimer définitivement") { _, _ ->
                    browseResultText.visibility = View.VISIBLE
                    browseResultText.text = "Suppression en cours…"
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = withContext(Dispatchers.IO) {
                            GitHubController.deleteRepo(this@GitHubActivity, owner, repo, account)
                        }
                        browseResultText.text = result
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // ─── Création de dépôt ──────────────────────────────────────────────
        createRepoButton.setOnClickListener {
            val name = newRepoNameInput.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "Donne un nom au dépôt d'abord", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val description = newRepoDescInput.text.toString().trim()
            createRepoButton.text = "Création en cours…"

            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    GitHubController.createRepo(this@GitHubActivity, name, description, false, browseAccountInput.text.toString().trim())
                }
                createRepoButton.text = "＋ CRÉER LE DÉPÔT (PUBLIC)"
                Toast.makeText(this@GitHubActivity, result, Toast.LENGTH_LONG).show()
                if (result.startsWith("✅")) {
                    newRepoNameInput.text.clear()
                    newRepoDescInput.text.clear()
                }
            }
        }
    }

    private fun refreshAccountsList() {
        accountsListText.text = GitHubController.listAccounts(this)
    }

    private data class BrowseFields(val owner: String, val repo: String, val branch: String, val path: String, val account: String)

    private fun readBrowseFields(): BrowseFields? {
        val owner = browseOwnerInput.text.toString().trim()
        val repo = browseRepoInput.text.toString().trim()
        val branch = browseBranchInput.text.toString().trim().ifBlank { "main" }
        val path = browsePathInput.text.toString().trim()
        val account = browseAccountInput.text.toString().trim()
        if (owner.isBlank() || repo.isBlank()) {
            Toast.makeText(this, "Renseigne le propriétaire ET le nom du dépôt", Toast.LENGTH_SHORT).show()
            return null
        }
        return BrowseFields(owner, repo, branch, path, account)
    }

    private fun browseCurrentPath() {
        val fields = readBrowseFields() ?: return
        browseResultText.visibility = View.VISIBLE
        browseResultText.text = "Chargement…"
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                GitHubController.listContents(this@GitHubActivity, fields.owner, fields.repo, fields.path, fields.branch, fields.account)
            }
            browseResultText.text = result
        }
    }
}
