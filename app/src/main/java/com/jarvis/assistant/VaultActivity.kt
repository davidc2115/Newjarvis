package com.jarvis.assistant

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
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
 * Volontairement simple (liste + lecture + navigation par wikilinks), pas un graphe visuel
 * (nœuds/arêtes) : c'est la version "on peut vraiment se balader dans son vault" en un
 * passage, le graphe visuel reste une amélioration possible ultérieure si demandée.
 */
class VaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultBinding

    // Pile de navigation "maison" (pas le back-stack Android normal, un seul Activity) : quand
    // on tape un wikilink depuis la note A on empile "A" avant d'afficher la note B, pour que
    // le bouton retour ramène à A plutôt que de fermer l'écran directement.
    private val noteBackStack = ArrayDeque<String>()
    private var currentNoteTitle: String? = null
    private var allNoteTitles: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        binding.vaultBackButton.setOnClickListener { onBackPressedFromVault() }

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
            val result = ObsidianController.listNotes(this@VaultActivity)
            val notes = result.getOrNull().orEmpty()
            // Titres SANS extension .md ni chemin de sous-dossier pour l'affichage/la recherche
            // de wikilinks (voir ObsidianController.collectAllNoteTitles, même convention).
            allNoteTitles = notes.map { path -> path.substringAfterLast('/').removeSuffix(".md") }
            if (notes.isEmpty()) {
                binding.vaultEmptyText.text = getString(R.string.vault_empty_message)
                binding.vaultEmptyText.visibility = View.VISIBLE
                binding.vaultListScroll.visibility = View.GONE
            } else {
                binding.vaultEmptyText.visibility = View.GONE
                renderList(notes)
            }
        }
    }

    private fun renderList(notePaths: List<String>) {
        binding.vaultListContainer.removeAllViews()
        notePaths.forEach { path ->
            val title = path.substringAfterLast('/').removeSuffix(".md")
            val row = TextView(this).apply {
                text = if (path.contains('/')) "$title\n$path" else title
                setTextColor(ContextCompat.getColor(this@VaultActivity, R.color.text_primary))
                textSize = 15f
                setBackgroundResource(R.drawable.bg_model_row)
                setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4)) }
                setOnClickListener {
                    noteBackStack.clear()
                    showNote(title)
                }
            }
            binding.vaultListContainer.addView(row)
        }
        showList()
    }

    private fun showList() {
        currentNoteTitle = null
        binding.vaultTitleText.text = getString(R.string.vault_title)
        binding.vaultListScroll.visibility = View.VISIBLE
        binding.vaultDetailScroll.visibility = View.GONE
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
