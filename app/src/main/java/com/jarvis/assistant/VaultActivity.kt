package com.jarvis.assistant

import android.app.AlertDialog
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityVaultBinding
import kotlinx.coroutines.launch

/**
 * Écran "vrai système Obsidian" navigable -- demande explicite de l'utilisateur : jusqu'ici
 * le vault (voir ObsidianController) n'existait que via des commandes texte dans le chat
 * (créer/lire/lister une note), sans aucun moyen de PARCOURIR le vault visuellement comme un
 * vrai second cerveau. Cet écran liste toutes les notes, et affiche le contenu d'une note au
 * tap avec ses [[wikilinks]] cliquables pour naviguer de note en note -- la façon dont on
 * utilise réellement Obsidian, pas juste du CRUD en ligne de commande.
 *
 * Liste + lecture + navigation par wikilinks -- le complément visuel en nœuds/arêtes ("vue de
 * la toile") est dans VaultGraphActivity (tâche #226), accessible via [vaultGraphButton] dans
 * la barre du haut ; taper un nœud du graphe revient ici en mode détail directement (voir
 * [EXTRA_OPEN_NOTE_TITLE]).
 *
 * Organisation par dossiers (tâche #239, demande explicite : "les dossier[s]" comme l'ancienne
 * appli) : la liste n'est plus un flux plat de "titre\nchemin", mais un vrai navigateur --
 * dossiers et notes du niveau courant seulement (voir [currentFolderPath]), on descend en
 * tapant un dossier, on remonte avec la ligne ".." ou le bouton retour. [vaultNewFolderButton]
 * crée un dossier DANS le dossier actuellement affiché (pas toujours à la racine).
 */
class VaultActivity : AppCompatActivity() {

    companion object {
        /** Ouvre directement une note precise au lancement (utilise par VaultGraphActivity
         *  quand on tape un noeud du graphe) plutot que de passer par la liste d'abord. */
        const val EXTRA_OPEN_NOTE_TITLE = "extra_open_note_title"
    }

    private lateinit var binding: ActivityVaultBinding

    // Pile de navigation "maison" (pas le back-stack Android normal, un seul Activity) : quand
    // on tape un wikilink depuis la note A on empile "A" avant d'afficher la note B, pour que
    // le bouton retour ramène à A plutôt que de fermer l'écran directement.
    private val noteBackStack = ArrayDeque<String>()
    private var currentNoteTitle: String? = null
    private var allNoteTitles: List<String> = emptyList()

    // Snapshot complet chargé une fois (voir loadVault) -- la navigation entre dossiers filtre
    // ensuite ces listes en mémoire, sans refaire d'I/O SAF à chaque tap (rapide, cohérent
    // pendant toute la session de navigation).
    private var allNotePaths: List<String> = emptyList()
    private var allFolderPaths: List<String> = emptyList()
    // "" = racine du vault ; sinon chemin relatif ("Contacts" ou "Projets/Alpha").
    private var currentFolderPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        binding.vaultBackButton.setOnClickListener { onBackPressedFromVault() }
        binding.vaultGraphButton.setOnClickListener {
            startActivity(android.content.Intent(this, VaultGraphActivity::class.java))
        }
        binding.vaultNewFolderButton.setOnClickListener { promptCreateFolder() }

        loadVault()
    }

    override fun onBackPressed() {
        onBackPressedFromVault()
    }

    private fun onBackPressedFromVault() {
        if (currentNoteTitle != null) {
            val previous = noteBackStack.removeLastOrNull()
            if (previous != null) {
                showNote(previous)
            } else {
                showList()
            }
        } else if (currentFolderPath.isNotEmpty()) {
            currentFolderPath = parentPath(currentFolderPath)
            renderFolderView()
        } else {
            finish()
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.vaultTopBar.setPadding(
                binding.vaultTopBar.paddingLeft, bars.top,
                binding.vaultTopBar.paddingRight, binding.vaultTopBar.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun loadVault() {
        if (!ObsidianController.hasVault(this)) {
            binding.vaultEmptyText.text = getString(R.string.vault_no_vault_message)
            binding.vaultEmptyText.visibility = View.VISIBLE
            binding.vaultListScroll.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val notes = ObsidianController.listNotes(this@VaultActivity).getOrNull().orEmpty()
            val folders = ObsidianController.listFolders(this@VaultActivity).getOrNull().orEmpty()
            allNotePaths = notes
            allFolderPaths = folders
            // Titres SANS extension .md ni chemin de sous-dossier pour l'affichage/la recherche
            // de wikilinks (voir ObsidianController.collectAllNoteTitles, même convention).
            allNoteTitles = notes.map { path -> path.substringAfterLast('/').removeSuffix(".md") }
            if (notes.isEmpty() && folders.isEmpty()) {
                binding.vaultEmptyText.text = getString(R.string.vault_empty_message)
                binding.vaultEmptyText.visibility = View.VISIBLE
                binding.vaultListScroll.visibility = View.GONE
            } else {
                binding.vaultEmptyText.visibility = View.GONE
                currentFolderPath = ""
                renderFolderView()
                // Ouverture directe demandee par VaultGraphActivity (tap sur un noeud du
                // graphe) -- verifiee APRES renderFolderView/showList pour repasser en mode
                // detail par-dessus l'etat "liste" par defaut.
                val openTitle = intent.getStringExtra(EXTRA_OPEN_NOTE_TITLE)
                if (openTitle != null && allNoteTitles.any { it.equals(openTitle, ignoreCase = true) }) {
                    noteBackStack.clear()
                    showNote(openTitle)
                }
            }
        }
    }

    /** Chemin du dossier parent de [path] ("Projets/Alpha" -> "Projets", "Projets" -> ""). */
    private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

    /** Reconstruit la liste affichée pour [currentFolderPath] : uniquement les dossiers et
     *  notes DIRECTEMENT dedans (pas les descendants plus profonds -- ceux-là apparaissent
     *  quand on descend dans leur propre dossier), plus une ligne ".." si on n'est pas déjà à
     *  la racine. Filtre en mémoire sur les snapshots chargés par [loadVault], aucun I/O SAF
     *  supplémentaire ici. */
    private fun renderFolderView() {
        binding.vaultListContainer.removeAllViews()

        if (currentFolderPath.isNotEmpty()) {
            addRow("⬆️ ..") {
                currentFolderPath = parentPath(currentFolderPath)
                renderFolderView()
            }
        }

        val childFolders = allFolderPaths
            .filter { parentPath(it) == currentFolderPath }
            .sortedBy { it.substringAfterLast('/') }
        for (folderPath in childFolders) {
            val name = folderPath.substringAfterLast('/')
            addRow("📁 $name") {
                currentFolderPath = folderPath
                renderFolderView()
            }
        }

        val childNotes = allNotePaths
            .filter { parentPath(it) == currentFolderPath }
            .sortedBy { it.substringAfterLast('/') }
        for (notePath in childNotes) {
            val title = notePath.substringAfterLast('/').removeSuffix(".md")
            addRow(title) {
                noteBackStack.clear()
                showNote(title)
            }
        }

        if (currentFolderPath.isEmpty() && childFolders.isEmpty() && childNotes.isEmpty()) {
            // Vault non-vide globalement (sinon on ne serait pas arrivé ici, voir loadVault)
            // mais rien à la racine -- arrive si tout est range dans des sous-dossiers des le
            // depart, cas rare mais possible : evite un ecran blanc silencieux.
        }

        showList()
    }

    private fun addRow(label: String, onClick: () -> Unit) {
        val row = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@VaultActivity, R.color.text_primary))
            textSize = 15f
            setBackgroundResource(R.drawable.bg_model_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4)) }
            setOnClickListener { onClick() }
        }
        binding.vaultListContainer.addView(row)
    }

    private fun showList() {
        currentNoteTitle = null
        binding.vaultTitleText.text = if (currentFolderPath.isEmpty()) {
            getString(R.string.vault_title)
        } else {
            currentFolderPath.substringAfterLast('/')
        }
        binding.vaultListScroll.visibility = View.VISIBLE
        binding.vaultDetailScroll.visibility = View.GONE
    }

    /** Demande un nom de dossier (AlertDialog + EditText, pas de nouvel écran pour un geste
     *  aussi simple) et le crée DANS [currentFolderPath] -- reproduit le bouton "+" de l'écran
     *  graphe de l'ancienne appli (tâche #164), ici sur l'écran liste qui est l'entrée
     *  principale du vault. */
    private fun promptCreateFolder() {
        val input = EditText(this).apply {
            hint = getString(R.string.vault_new_folder_hint)
            setTextColor(ContextCompat.getColor(this@VaultActivity, R.color.text_primary))
        }
        val padding = dpToPx(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_new_folder_title)
            .setView(container)
            .setPositiveButton(R.string.vault_new_folder_confirm) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) createFolderHere(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createFolderHere(name: String) {
        val fullPath = if (currentFolderPath.isEmpty()) name else "$currentFolderPath/$name"
        lifecycleScope.launch {
            val result = ObsidianController.createFolder(this@VaultActivity, fullPath)
            result.fold(
                onSuccess = {
                    allFolderPaths = allFolderPaths + fullPath
                    renderFolderView()
                },
                onFailure = { e ->
                    android.widget.Toast.makeText(this@VaultActivity, e.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun showNote(title: String) {
        lifecycleScope.launch {
            val result = ObsidianController.readNote(this@VaultActivity, title)
            val content = result.getOrNull()
            if (content == null) {
                showNoteNotFoundToast(title)
                return@launch
            }
            currentNoteTitle = title
            binding.vaultTitleText.text = title
            binding.vaultListScroll.visibility = View.GONE
            binding.vaultDetailScroll.visibility = View.VISIBLE
            binding.vaultDetailContent.text = buildLinkedContent(content)
            binding.vaultDetailContent.movementMethod = LinkMovementMethod.getInstance()
            binding.vaultDetailScroll.scrollTo(0, 0)
        }
    }

    private fun showNoteNotFoundToast(title: String) {
        android.widget.Toast.makeText(this, getString(R.string.vault_note_not_found, title), android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Repère chaque [[Titre]] dans le contenu brut et le rend cliquable (navigation vers la
     * note visée si elle existe dans le vault -- voir [allNoteTitles], sinon un simple toast).
     * Les crochets eux-mêmes sont retirés de l'affichage (seul le titre reste visible),
     * conforme au rendu Obsidian standard.
     */
    private fun buildLinkedContent(raw: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val regex = Regex("\\[\\[([^\\[\\]]+)\\]\\]")
        var lastEnd = 0
        for (match in regex.findAll(raw)) {
            builder.append(raw.substring(lastEnd, match.range.first))
            val linkTitle = match.groupValues[1].trim()
            val start = builder.length
            builder.append(linkTitle)
            val end = builder.length
            val exists = allNoteTitles.any { it.equals(linkTitle, ignoreCase = true) }
            builder.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (exists) {
                        currentNoteTitle?.let { noteBackStack.addLast(it) }
                        showNote(linkTitle)
                    } else {
                        showNoteNotFoundToast(linkTitle)
                    }
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = ContextCompat.getColor(this@VaultActivity, R.color.accent_default)
                    ds.isUnderlineText = true
                }
            }, start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            lastEnd = match.range.last + 1
        }
        builder.append(raw.substring(lastEnd))
        return builder
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
