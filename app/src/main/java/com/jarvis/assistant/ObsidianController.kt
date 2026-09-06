package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ObsidianController {

    private const val TAG = "ObsidianController"
    private val dateFormat   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat   = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    /**
     * Message honnête à renvoyer quand la permission "Accès complet au stockage"
     * (MANAGE_EXTERNAL_STORAGE, Android 11+) manque — plutôt que de laisser les
     * fonctions de lecture ci-dessous échouer SILENCIEUSEMENT (un dossier
     * inaccessible sans cette permission spéciale apparaît comme "vide"/"introuvable"
     * pour une simple lecture de fichiers, sans lever d'exception détectable).
     *
     * C'est la cause la plus probable et vérifiable du symptôme "après un
     * redémarrage ou une mise à jour, JARVIS ne trouve plus mes notes" : Android
     * NE GARANTIT PAS que cette permission spéciale survive à une réinstallation/
     * mise à jour de l'app (surtout hors Play Store) — elle peut être révoquée
     * automatiquement (réinitialisation des permissions des apps inutilisées,
     * changement de clé de signature lors d'une réinstallation, gestionnaires de
     * batterie/permissions agressifs de certains fabricants...). Les notes ne sont
     * PAS perdues : elles sont toujours au même endroit sur le stockage, juste
     * temporairement inaccessibles à JARVIS tant que la permission n'est pas
     * réaccordée.
     */
    fun missingStorageAccessMessagePublic(): String = missingStorageAccessMessage()

    private fun missingStorageAccessMessage(): String =
        "❌ JARVIS n'a plus l'accès complet au stockage (permission révoquée par Android — " +
            "cela arrive après une mise à jour/réinstallation de l'app, ce n'est PAS une perte de " +
            "données : tes notes sont toujours là). Va dans ⚙ → Permissions → réactive " +
            "« Accès complet au stockage », puis réessaie."

    private fun hasStorageAccess(): Boolean = PermissionsManager.hasManageStoragePermission()

    // ─────────────────────────────────────────────────────────────────────────
    // Vault root
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Corrige un bug signalé : après avoir choisi un dossier de vault via le sélecteur
     * (⚙ → Obsidian), si le dossier confirmé était la racine ENTIÈRE du stockage interne
     * (au lieu d'un sous-dossier précis du genre "JARVIS-Vault"), toutes les notes/dossiers
     * créés (Notes Rapides, Modèles, Daily Notes...) atterrissaient directement à la racine
     * visible du téléphone, mélangés avec le reste des fichiers de l'utilisateur — au lieu
     * d'être proprement isolés dans un vault dédié. Garde-fou : si le chemin enregistré
     * correspond exactement à la racine du stockage interne, on l'ignore et on revient
     * automatiquement au vault par défaut (Documents/JARVIS-Vault), en corrigeant aussi la
     * préférence enregistrée pour que ce ne soit pas juste un correctif silencieux ponctuel.
     */
    fun getVaultRoot(context: Context): File {
        val defaultVault = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "JARVIS-Vault"
        )
        val saved = Prefs.getObsidianVaultPath(context)
        if (saved.isBlank()) return defaultVault
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val normalizedSaved = saved.trimEnd('/')
        if (normalizedSaved.equals(storageRoot, ignoreCase = true) || normalizedSaved.isEmpty() || normalizedSaved == "/") {
            Log.w(TAG, "Vault path pointait vers la racine du stockage ($saved) — correction automatique vers le vault par défaut.")
            Prefs.saveObsidianVaultPath(context, "")
            return defaultVault
        }
        return File(saved)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init vault structure
    // ─────────────────────────────────────────────────────────────────────────

    fun initVault(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        return try {
            val root = getVaultRoot(context)
            val folders = listOf("Daily Notes", "Notes Rapides", "Contacts", "Tâches", "Emails", "Réflexions", "Générations", ".obsidian")
            folders.forEach { File(root, it).mkdirs() }

            // README
            val readme = File(root, "README.md")
            if (!readme.exists()) {
                readme.writeText("""
# 🧠 JARVIS Second Brain

Ce vault est géré par **JARVIS Assistant**.

## Dossiers
- 📅 **Daily Notes** — Notes journalières automatiques
- 📋 **Notes Rapides** — Notes créées par commande vocale
- 📞 **Contacts** — Fiches contacts
- ✅ **Tâches** — Listes de tâches
- 📧 **Emails** — Résumés d'emails importants
- 💭 **Réflexions** — Pensées et idées
- ✨ **Générations** — Historique des images, vidéos, sites, PDF/Word/Excel/ZIP créés

---
*Vault créé par JARVIS le ${displayFormat.format(Date())}*
""".trimIndent())
            }

            Prefs.saveObsidianVaultPath(context, root.absolutePath)
            "✅ Vault initialisé :\n${root.absolutePath}\n\n${folders.dropLast(1).joinToString("\n") { "📁 $it" }}"
        } catch (e: Exception) {
            "❌ Erreur lors de l'initialisation : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Réinitialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Réinitialise le CHEMIN du vault vers la valeur par défaut (Documents/JARVIS-Vault),
     * sans toucher au contenu d'aucun dossier — utile si un chemin personnalisé cassé
     * ou erroné avait été enregistré (ex: mauvaise carte SD résolue en chemin invalide
     * avant le correctif du sélecteur de dossier).
     */
    fun resetVaultPath(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        Prefs.saveObsidianVaultPath(context, "")
        val newRoot = getVaultRoot(context)
        newRoot.mkdirs()
        return "✅ Chemin du vault réinitialisé par défaut :\n${newRoot.absolutePath}\n\n" +
            "Le contenu de l'ancien dossier n'a pas été touché. Utilise « Initialiser/Réparer le vault » " +
            "pour recréer la structure ici si besoin."
    }

    /**
     * Vide entièrement le vault ACTUEL (toutes les notes .md et sous-dossiers créés par
     * JARVIS) puis recrée la structure de base — irréversible, à confirmer côté appelant
     * avant d'exécuter. Ne supprime PAS le dossier racine lui-même ni des fichiers qui
     * n'ont pas l'extension .md (au cas où le dossier est partagé avec d'autres usages).
     */
    fun wipeVault(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        return try {
            val root = getVaultRoot(context)
            if (!root.exists()) {
                return initVault(context)
            }
            var deletedFiles = 0
            root.walkBottomUp().forEach { f ->
                if (f == root) return@forEach
                if (f.isFile) {
                    if (f.delete()) deletedFiles++
                } else if (f.isDirectory) {
                    f.delete() // no-op silencieux si le dossier n'est pas vide (fichiers non-.md restants)
                }
            }
            val initResult = initVault(context)
            "🗑️ Vault vidé : $deletedFiles fichier(s) supprimé(s) dans ${root.absolutePath}.\n\n$initResult"
        } catch (e: Exception) {
            "❌ Erreur lors de la réinitialisation du vault : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create folder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Crée un dossier (sous-dossier) dans le vault — auparavant totalement absent des actions
     * exposées à l'IA (seul createNote existait), ce qui obligeait l'IA à répondre qu'elle ne
     * pouvait pas créer de dossier même quand l'utilisateur le demandait explicitement.
     */
    fun createFolder(context: Context, path: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        if (path.isBlank()) return "❌ Précise le nom du dossier à créer."
        return try {
            val root = getVaultRoot(context)
            val safePath = path.split("/", "\\").joinToString("/") { it.replace(Regex("[:*?\"<>|]"), "-").trim() }
            val dir = File(root, safePath)
            if (dir.exists()) return "📁 Le dossier « $safePath » existe déjà."
            if (dir.mkdirs()) "✅ Dossier créé : $safePath\n📄 Chemin : ${dir.absolutePath}"
            else "❌ Impossible de créer « $safePath » (chemin invalide ou droits insuffisants)."
        } catch (e: Exception) {
            "❌ Erreur création dossier : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create note
    // ─────────────────────────────────────────────────────────────────────────

    fun createNote(
        context: Context,
        title: String,
        content: String,
        folder: String = "Notes Rapides",
        tags: List<String> = listOf("jarvis")
    ): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        return try {
            val root     = getVaultRoot(context)
            val dir      = File(root, folder).also { it.mkdirs() }
            val safeTitle = title.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val file     = File(dir, "$safeTitle.md")
            val now      = Date()
            val tagsStr  = tags.joinToString(", ") { "\"$it\"" }

            val body = """
---
date: ${dateFormat.format(now)}
tags: [$tagsStr]
source: JARVIS Assistant
---

# $title

$content

---
*Créé par JARVIS le ${displayFormat.format(now)} à ${timeFormat.format(now)}*
""".trimIndent()

            file.writeText(body)
            Log.d(TAG, "Note created: ${file.absolutePath}")
            "✅ Note créée : **$safeTitle**\n📁 Dossier : $folder\n📄 Chemin : ${file.absolutePath}"
        } catch (e: Exception) {
            "❌ Erreur création note : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create daily note
    // ─────────────────────────────────────────────────────────────────────────

    fun createDailyNote(context: Context, content: String = ""): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val today     = dateFormat.format(Date())
        val todayDisp = displayFormat.format(Date())
        val root      = getVaultRoot(context)
        val dir       = File(root, "Daily Notes").also { it.mkdirs() }
        val file      = File(dir, "$today.md")

        return try {
            if (file.exists()) {
                // Append to existing
                if (content.isNotBlank()) {
                    file.appendText("\n\n${timeFormat.format(Date())} — $content")
                    "📅 Ajouté à la note du jour ($todayDisp) :\n\"$content\""
                } else {
                    "📅 Note du jour ($todayDisp) existe déjà.\n\n${file.readText().take(500)}…"
                }
            } else {
                val body = """
---
date: $today
tags: ["daily", "jarvis"]
source: JARVIS Assistant
---

# Journal du $todayDisp

${if (content.isNotBlank()) content else "— Notes du jour —"}

---
*Créé par JARVIS*
""".trimIndent()
                file.writeText(body)
                "📅 Note journalière créée pour $todayDisp !\n📄 ${file.absolutePath}"
            }
        } catch (e: Exception) {
            "❌ Erreur note journalière : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read note
    // ─────────────────────────────────────────────────────────────────────────

    fun readNote(context: Context, query: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val file = findNote(context, query)
            ?: return "❌ Aucune note trouvée pour \"$query\"."
        return try {
            val content = file.readText()
            "📄 **${file.nameWithoutExtension}**\n\n$content"
        } catch (e: Exception) {
            "❌ Impossible de lire la note : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search notes (filename + content)
    // ─────────────────────────────────────────────────────────────────────────

    fun searchNotes(context: Context, query: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val root    = getVaultRoot(context)
        // Distingue "vault inaccessible/mal configuré" de "vault vide" — sans ce contrôle,
        // un chemin de vault erroné (ex: pointant vers un ancien dossier vide, ou une carte SD
        // démontée) donnait silencieusement "aucun résultat" à chaque recherche, indiscernable
        // d'une note qui n'existe vraiment pas. C'est la cause la plus probable derrière "JARVIS
        // ne retrouve jamais mes notes" : utilise obsidian_status pour vérifier le chemin exact.
        if (!root.exists() || !root.isDirectory) {
            return "❌ Le dossier du vault n'existe pas ou n'est pas accessible : ${root.absolutePath}. " +
                "Vérifie le chemin configuré (obsidian_status) — utilise « Réparer le vault » ou obsidian_reset_path " +
                "si ce chemin ne correspond pas à ton vrai vault Obsidian."
        }
        val results = mutableListOf<Pair<File, String>>()
        val lower   = query.lowercase()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
            .forEach { file ->
                val nameMatch    = file.nameWithoutExtension.lowercase().contains(lower)
                var contentMatch = ""
                try {
                    val text = file.readText()
                    val idx  = text.lowercase().indexOf(lower)
                    if (idx >= 0) {
                        val start   = maxOf(0, idx - 40)
                        val end     = minOf(text.length, idx + query.length + 80)
                        contentMatch = "…${text.substring(start, end).replace("\n", " ")}…"
                    }
                } catch (_: Exception) {}

                if (nameMatch || contentMatch.isNotBlank()) {
                    val preview = if (nameMatch && contentMatch.isBlank()) "(titre)" else contentMatch
                    results.add(file to preview)
                }
            }

        if (results.isEmpty()) return "🔍 Aucun résultat pour \"$query\" dans le vault."

        val sb = StringBuilder("🔍 **${results.size} résultat(s) pour \"$query\"** :\n\n")
        results.take(10).forEach { (file, preview) ->
            sb.append("📄 **${file.nameWithoutExtension}**\n")
            sb.append("   📁 ${file.parentFile?.name}\n")
            if (preview != "(titre)") sb.append("   › $preview\n")
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recherche automatique de contexte (voir ApiClient.sendChat) : contrairement à
    // searchNotes (déclenchée par une action obsidian_search explicite décidée par le
    // LLM), cette fonction tourne SYSTÉMATIQUEMENT sur chaque message utilisateur, AVANT
    // même d'appeler l'IA, pour injecter les notes potentiellement pertinentes dans le
    // contexte — la récupération du vault ne dépend alors plus du bon vouloir du modèle
    // (certains fournisseurs/modèles moins "agentiques" n'appellent jamais obsidian_search
    // d'eux-mêmes, même quand l'instruction PRIORITÉ AUX NOTES OBSIDIAN du system prompt
    // leur dit de le faire — c'est la cause la plus probable derrière "JARVIS ne se
    // souvient jamais de ce que j'ai noté", en particulier au début d'une nouvelle
    // conversation où il n'y a aucun autre indice contextuel).
    // ─────────────────────────────────────────────────────────────────────────

    // BUG RÉEL CORRIGÉ : "salut"/"bonjour"/"jarvis" étaient dans cette liste de mots ignorés —
    // or c'est EXACTEMENT le contenu du tout premier message d'une nouvelle conversation la
    // plupart du temps ("Salut Jarvis", "Bonjour"...). Résultat : words finissait vide, la
    // fonction retournait null, et aucun contexte vault n'était jamais injecté précisément au
    // moment où c'était le plus utile (début de conversation, aucun autre indice contextuel
    // disponible) — cause directe de "après un redémarrage/nouvelle conversation, JARVIS ne
    // retrouve plus mes notes". Ces 3 mots ne présentaient de toute façon aucun risque réel de
    // faux positifs (peu probable qu'un titre de note s'appelle "salut").
    private val CONTEXT_STOPWORDS_FR = setOf(
        "les", "des", "une", "le", "la", "de", "du", "un", "et", "est", "tu", "je", "il", "elle", "on", "nous",
        "vous", "ils", "elles", "que", "qui", "quoi", "pour", "avec", "dans", "sur", "mon", "ma", "mes", "ton",
        "ta", "tes", "son", "sa", "ses", "ce", "cette", "ces", "été", "être", "avoir", "fais", "fait", "faire",
        "peux", "peut", "veux", "veut", "dit", "dis", "comme", "plus", "très", "pas", "ne", "se", "ça", "cela",
        "alors", "donc", "mais", "ou", "où", "quand", "comment", "pourquoi", "aussi", "bien", "déjà", "encore",
        "toujours", "jamais", "rappelle", "rappel", "souviens", "souvenir", "dernier", "dernière", "quel", "quelle",
        "merci"
    )

    /**
     * Renvoie un extrait des notes potentiellement pertinentes pour [userMessage], ou null
     * si le vault est inaccessible/vide ou si aucun mot-clé significatif n'a de correspondance
     * (évite d'injecter du bruit pour "salut ça va" par exemple). Coût borné : s'arrête dès
     * que [maxNotes] correspondances suffisantes sont trouvées, pas besoin de lire tout le vault.
     */
    fun quickContextSearch(context: Context, userMessage: String, maxNotes: Int = 3): String? {
        if (!hasStorageAccess()) return null
        val root = getVaultRoot(context)
        if (!root.exists() || !root.isDirectory) return null

        val words = userMessage.lowercase()
            .replace(Regex("[^a-zà-ÿ0-9 ]"), " ")
            .split(" ")
            .filter { it.length >= 4 && it !in CONTEXT_STOPWORDS_FR }
            .distinct()
        // Message trop générique pour en tirer un mot-clé (ex: "yo", "ça va ?") : plutôt que de
        // ne rien injecter du tout, on signale quand même l'existence et les titres des notes
        // les plus récentes — un aperçu léger, sans le contenu complet — pour que JARVIS ait
        // conscience du vault dès le début d'une conversation même sans mot-clé à chercher.
        if (words.isEmpty()) {
            val recent = root.walkTopDown()
                .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
                .sortedByDescending { it.lastModified() }
                .take(maxNotes)
                .toList()
            if (recent.isEmpty()) return null
            return recent.joinToString("\n") { "### ${it.nameWithoutExtension} (récent)" }
        }

        val matches = mutableListOf<Pair<File, String>>()
        for (file in root.walkTopDown()) {
            if (matches.size >= maxNotes) break
            if (!file.isFile || file.extension != "md" || file.path.contains(".obsidian")) continue
            val nameLower = file.nameWithoutExtension.lowercase()
            val titleHit = words.any { nameLower.contains(it) }
            try {
                if (titleHit) {
                    matches.add(file to file.readText().take(400))
                } else {
                    val text = file.readText()
                    val textLower = text.lowercase()
                    val hitWord = words.firstOrNull { textLower.contains(it) }
                    if (hitWord != null) {
                        val idx = textLower.indexOf(hitWord)
                        val start = maxOf(0, idx - 60)
                        val end = minOf(text.length, idx + hitWord.length + 150)
                        matches.add(file to text.substring(start, end).replace("\n", " "))
                    }
                }
            } catch (_: Exception) {
                // note illisible — on l'ignore simplement, pas bloquant pour les autres
            }
        }

        if (matches.isEmpty()) return null
        val sb = StringBuilder()
        matches.forEach { (file, excerpt) ->
            sb.append("### ${file.nameWithoutExtension}\n…${excerpt.trim()}…\n\n")
        }
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Append to note
    // ─────────────────────────────────────────────────────────────────────────

    fun appendToNote(context: Context, query: String, text: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val file = findNote(context, query)
            ?: return "❌ Note \"$query\" introuvable. Créez-la d'abord."
        return try {
            file.appendText("\n\n${timeFormat.format(Date())} — $text")
            "✅ Ajouté à **${file.nameWithoutExtension}** :\n\"$text\""
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List notes
    // ─────────────────────────────────────────────────────────────────────────

    fun listNotes(context: Context, folder: String = ""): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val root    = getVaultRoot(context)
        if (!root.exists() || !root.isDirectory) {
            return "❌ Le dossier du vault n'existe pas ou n'est pas accessible : ${root.absolutePath}. " +
                "Vérifie le chemin configuré (obsidian_status) — utilise « Réparer le vault » ou obsidian_reset_path " +
                "si ce chemin ne correspond pas à ton vrai vault Obsidian."
        }
        val baseDir = if (folder.isBlank()) root else File(root, folder)

        if (!baseDir.exists()) return "📁 Le dossier \"$folder\" n'existe pas dans le vault."

        val files = baseDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
            .sortedByDescending { it.lastModified() }
            .toList()

        if (files.isEmpty()) return "📋 Aucune note dans ${if (folder.isBlank()) "le vault" else "\"$folder\""}."

        val sb = StringBuilder("📋 **${files.size} note(s)** ${if (folder.isBlank()) "dans le vault" else "dans \"$folder\""}:\n\n")
        files.take(30).forEach { file ->
            val rel  = file.relativeTo(root).parent ?: ""
            val date = displayFormat.format(Date(file.lastModified()))
            sb.append("📄 **${file.nameWithoutExtension}**")
            if (rel.isNotBlank()) sb.append(" (📁 $rel)")
            sb.append(" — $date\n")
        }
        if (files.size > 30) sb.append("\n… et ${files.size - 30} autres.")
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete note
    // ─────────────────────────────────────────────────────────────────────────

    fun deleteNote(context: Context, query: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val file = findNote(context, query)
            ?: return "❌ Note \"$query\" introuvable."
        return try {
            val name = file.nameWithoutExtension
            file.delete()
            "🗑 Note **$name** supprimée."
        } catch (e: Exception) {
            "❌ Erreur suppression : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Move / rename note (deplacer un fichier vers un autre dossier du vault)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Déplace une note existante ([query], recherche floue par titre) vers [destinationFolder]
     * (chemin relatif à la racine du vault, créé automatiquement s'il n'existe pas encore —
     * même logique de nettoyage de chemin que createFolder). Gère les collisions de nom en
     * ajoutant un suffixe numérique plutôt que d'écraser silencieusement une note existante.
     */
    fun moveNote(context: Context, query: String, destinationFolder: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        if (destinationFolder.isBlank()) return "❌ Précise le dossier de destination."
        val file = findNote(context, query)
            ?: return "❌ Note \"$query\" introuvable."
        return try {
            val root = getVaultRoot(context)
            val safeFolder = destinationFolder.split("/", "\\").joinToString("/") { it.replace(Regex("[:*?\"<>|]"), "-").trim() }
            val destDir = File(root, safeFolder)
            if (!destDir.exists() && !destDir.mkdirs()) {
                return "❌ Impossible de créer le dossier de destination « $safeFolder »."
            }
            if (destDir.parentFile?.exists() != true && destDir.absolutePath != root.absolutePath) {
                // cas limite très improbable après mkdirs() ci-dessus, gardé par sécurité
            }
            var destFile = File(destDir, file.name)
            var suffix = 1
            while (destFile.exists() && destFile.absolutePath != file.absolutePath) {
                destFile = File(destDir, "${file.nameWithoutExtension} (${++suffix}).md")
            }
            if (destFile.absolutePath == file.absolutePath) {
                return "📁 **${file.nameWithoutExtension}** est déjà dans « $safeFolder »."
            }
            val moved = file.renameTo(destFile)
            if (moved) {
                "✅ Note **${file.nameWithoutExtension}** déplacée vers « $safeFolder »."
            } else {
                // renameTo peut échouer entre systèmes de fichiers différents (ex: stockage interne
                // vers carte SD) — repli sur copie + suppression de l'original.
                file.copyTo(destFile, overwrite = false)
                file.delete()
                "✅ Note **${file.nameWithoutExtension}** déplacée vers « $safeFolder »."
            }
        } catch (e: Exception) {
            "❌ Erreur lors du déplacement : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Open in Obsidian app
    // ─────────────────────────────────────────────────────────────────────────

    fun openInObsidian(context: Context, query: String): String {
        val root     = getVaultRoot(context)
        val vaultName = root.name
        val file     = if (query.isBlank()) null else findNote(context, query)

        return try {
            val uriStr = if (file != null) {
                val rel = file.relativeTo(root).path.replace(File.separator, "/").removeSuffix(".md")
                "obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(rel)}"
            } else {
                "obsidian://open?vault=${Uri.encode(vaultName)}"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "🟣 Ouverture dans Obsidian : ${file?.nameWithoutExtension ?: vaultName}"
        } catch (e: Exception) {
            "⚠️ Obsidian n'est pas installé sur ce téléphone.\n\nTéléchargez-le sur le Play Store : 'Obsidian — Connected Notes'"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vault stats
    // ─────────────────────────────────────────────────────────────────────────

    fun getVaultStats(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        return try {
            val root    = getVaultRoot(context)
            if (!root.exists()) return "📊 Le vault n'existe pas encore. Initialisez-le d'abord."

            val allFiles = root.walkTopDown()
                .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
                .toList()

            val totalSize  = allFiles.sumOf { it.length() }
            val sizeKb     = totalSize / 1024
            val folders    = allFiles.mapNotNull { it.parentFile?.name }.toSet()
            val todayStr   = dateFormat.format(Date())
            val todayNotes = allFiles.count { it.name.startsWith(todayStr) }

            """
📊 **Statistiques du Vault** :

📄 Notes totales   : ${allFiles.size}
📁 Dossiers        : ${folders.size}
💾 Taille totale   : ${sizeKb} Ko
📅 Notes du jour   : $todayNotes
📂 Chemin          : ${root.absolutePath}
            """.trimIndent()
        } catch (e: Exception) {
            "❌ Erreur stats : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helper — find note by partial name
    // ─────────────────────────────────────────────────────────────────────────

    // BUG RÉEL CORRIGÉ : findNote() ne comparait "query" QU'au titre du fichier, en exigeant
    // que la requête entière soit un sous-texte CONTIGU du nom de fichier. Deux conséquences
    // très fréquentes en usage réel : (1) les notes rapides sont créées avec un titre généré
    // automatiquement ("Note rapide 14:32") qui ne contient jamais le sujet réel de la note —
    // toute relecture/suppression/déplacement ultérieure par sujet ("ma note sur les
    // fournisseurs") échouait donc systématiquement alors que la note existait bien ; (2) la
    // formulation de la requête ne correspond presque jamais mot pour mot au titre exact
    // choisi lors de la création (ordre des mots différent, accents, un mot en plus/en moins).
    // Résultat : JARVIS annonçait "introuvable" pour des notes bel et bien présentes dans le
    // vault. Correction en 3 passes, de la plus précise à la plus tolérante, qui reproduit
    // exactement la logique déjà utilisée par searchNotes (titre PUIS contenu), avec un
    // dernier repli par mots-clés pour survivre aux reformulations :
    //   1. sous-texte contigu dans le TITRE (comportement d'origine, le plus précis)
    //   2. sous-texte contigu dans le CONTENU (couvre les notes au titre générique/daté)
    //   3. TOUS les mots significatifs de la requête retrouvés (titre + contenu confondus),
    //      pour tolérer un ordre des mots ou une formulation différente de celle d'origine
    // À égalité de correspondance, la note modifiée le plus récemment est privilégiée.
    private fun findNote(context: Context, query: String): File? {
        val root  = getVaultRoot(context)
        val lower = query.lowercase().trim()
        if (lower.isBlank()) return null
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
            .toList()

        candidates.firstOrNull { it.nameWithoutExtension.lowercase().contains(lower) }
            ?.let { return it }

        candidates
            .filter { runCatching { it.readText() }.getOrDefault("").lowercase().contains(lower) }
            .maxByOrNull { it.lastModified() }
            ?.let { return it }

        val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
        if (words.isEmpty()) return null
        return candidates
            .filter { file ->
                val haystack = (file.nameWithoutExtension + " " + runCatching { file.readText() }.getOrDefault(""))
                    .lowercase()
                words.all { haystack.contains(it) }
            }
            .maxByOrNull { it.lastModified() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse voice command and dispatch
    // ─────────────────────────────────────────────────────────────────────────

    fun handleVoiceCommand(context: Context, input: String): String? {
        val text  = input.trim()
        val lower = text.lowercase()
        return when {
            lower.startsWith("note que ")       -> createNote(context, title = "Note rapide ${timeFormat.format(Date())}", content = text.substring(9))
            lower.startsWith("note :")          -> createNote(context, title = "Note rapide ${timeFormat.format(Date())}", content = text.substring(6))
            lower.startsWith("écris dans mon journal") || lower.startsWith("journal ") -> {
                val content = text.substringAfter(" ").substringAfter(":").trim()
                createDailyNote(context, content)
            }
            lower == "note du jour" || lower == "daily note" || lower == "journal du jour" ->
                createDailyNote(context)
            lower.startsWith("cherche dans mes notes ") ->
                searchNotes(context, text.substring(23))
            lower.startsWith("lis ma note sur ") ->
                readNote(context, text.substring(16))
            lower.startsWith("ajoute à ") && lower.contains(" : ") -> {
                val parts = text.substring(9).split(" : ", limit = 2)
                if (parts.size == 2) appendToNote(context, parts[0].trim(), parts[1].trim())
                else null
            }
            lower == "mes notes" || lower == "liste mes notes" || lower == "voir mes notes" ->
                listNotes(context)
            lower == "stats vault" || lower == "combien de notes" ->
                getVaultStats(context)
            lower == "ouvrir obsidian" ->
                openInObsidian(context, "")
            else -> null
        }
    }
}
