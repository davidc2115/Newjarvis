package com.jarvis.assistant

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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

    private lateinit var reposResultText: TextView
    private lateinit var reposListContainer: LinearLayout

    private lateinit var browseOwnerInput: EditText
    private lateinit var browseRepoInput: EditText
    private lateinit var browseBranchInput: EditText
    private lateinit var browsePathInput: EditText
    private lateinit var browseAccountInput: EditText
    private lateinit var browseResultText: TextView
    private lateinit var browseListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github)

        BottomNav.setup(this, NavDestination.GITHUB)
        EdgeToEdgeHelper.applyTopInset(findViewById(R.id.rootLayout))
        EdgeToEdgeHelper.applyBottomInset(findViewById(R.id.bottomNavRoot))

        val githubTokenInput = findViewById<EditText>(R.id.githubTokenInput)
        val saveGithubTokenButton = findViewById<TextView>(R.id.saveGithubTokenButton)
        val listReposButton = findViewById<TextView>(R.id.listReposButton)
        reposResultText = findViewById(R.id.reposResultText)
        reposListContainer = findViewById(R.id.reposListContainer)
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
        browseListContainer = findViewById(R.id.browseListContainer)
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

        // ─── Liste des dépôts (sélecteur cliquable) ──────────────────────────
        // Demandé explicitement : plus besoin de taper le propriétaire/nom du dépôt à la
        // main — on liste les dépôts réels du compte et taper l'un d'eux remplit les champs
        // ET ouvre directement sa racine.
        listReposButton.setOnClickListener {
            reposListContainer.removeAllViews()
            reposListContainer.visibility = View.GONE
            reposResultText.visibility = View.VISIBLE
            reposResultText.text = "Chargement…"
            val account = browseAccountInput.text.toString().trim()
            CoroutineScope(Dispatchers.Main).launch {
                val repos = GitHubController.listReposStructured(this@GitHubActivity, account)
                if (repos.isEmpty()) {
                    // Repli sur la version texte pour connaître la VRAIE cause (pas de
                    // compte, erreur réseau, ou simplement aucun dépôt) plutôt que
                    // d'afficher silencieusement une liste vide sans explication.
                    val message = withContext(Dispatchers.IO) {
                        GitHubController.listRepos(this@GitHubActivity, account)
                    }
                    reposResultText.text = message
                    return@launch
                }
                reposResultText.visibility = View.GONE
                reposListContainer.visibility = View.VISIBLE
                repos.forEach { repo ->
                    val visibility = if (repo.isPrivate) "privé" else "public"
                    addClickableRow(reposListContainer, "📦 ${repo.fullName} ($visibility)") {
                        browseOwnerInput.setText(repo.owner)
                        browseRepoInput.setText(repo.name)
                        browsePathInput.setText("")
                        browseCurrentPath()
                    }
                }
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

    /**
     * Navigateur de dossiers CLIQUABLE — demandé explicitement à la place de la saisie
     * manuelle du chemin. Un dossier tapé y entre (met à jour browsePathInput et reparcourt),
     * un fichier tapé remplit juste browsePathInput (utile pour ensuite le supprimer via
     * deleteFileButton) sans tenter de le "parcourir".
     */
    private fun browseCurrentPath() {
        val fields = readBrowseFields() ?: return
        browseListContainer.removeAllViews()
        browseListContainer.visibility = View.GONE
        browseResultText.visibility = View.VISIBLE
        browseResultText.text = "Chargement…"
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = GitHubController.listContentsStructured(this@GitHubActivity, fields.owner, fields.repo, fields.path, fields.branch, fields.account)) {
                is GitHubController.ContentsResult.Error -> {
                    browseResultText.text = result.message
                }
                is GitHubController.ContentsResult.NotADirectory -> {
                    browseResultText.text = "📄 « ${result.path} » est un fichier (${result.sizeBytes} octets), pas un dossier — utilise SUPPRIMER LE FICHIER ci-dessous si besoin, ou remonte d'un niveau."
                }
                is GitHubController.ContentsResult.Success -> {
                    if (result.folders.isEmpty() && result.files.isEmpty()) {
                        browseResultText.text = "📂 « ${fields.path.ifBlank { "/" }} » est vide."
                        return@launch
                    }
                    browseResultText.visibility = View.GONE
                    browseListContainer.visibility = View.VISIBLE

                    if (fields.path.isNotBlank()) {
                        addClickableRow(browseListContainer, "⬆ .. (remonter d'un niveau)") {
                            browsePathInput.setText(fields.path.trim('/').substringBeforeLast('/', ""))
                            browseCurrentPath()
                        }
                    }
                    result.folders.forEach { name ->
                        addClickableRow(browseListContainer, "📁 $name/") {
                            val newPath = if (fields.path.isBlank()) name else "${fields.path.trim('/')}/$name"
                            browsePathInput.setText(newPath)
                            browseCurrentPath()
                        }
                    }
                    result.files.forEach { (name, size) ->
                        addClickableRow(browseListContainer, "📄 $name ($size octets)") {
                            val newPath = if (fields.path.isBlank()) name else "${fields.path.trim('/')}/$name"
                            browsePathInput.setText(newPath)
                            Toast.makeText(this@GitHubActivity, "Chemin rempli : $newPath — utilise SUPPRIMER LE FICHIER si besoin.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /** Ajoute une ligne cliquable au style de l'écran (même fond que les boutons secondaires). */
    private fun addClickableRow(container: LinearLayout, text: String, onClick: () -> Unit) {
        val row = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setPadding(28, 24, 28, 24)
            background = getDrawable(R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 8 }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        container.addView(row)
    }
}
