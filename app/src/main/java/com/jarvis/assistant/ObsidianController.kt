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

    /**
     * Supprime un dossier du vault et TOUT son contenu (récursif) — manquant jusqu'ici :
     * seule la création de dossier (createFolder) et la suppression d'une note individuelle
     * (deleteNote, fichiers .md uniquement) existaient, aucune action pour retirer un dossier
     * entier. IRRÉVERSIBLE, l'IA doit confirmer avec l'utilisateur avant d'appeler ceci (comme
     * pour obsidian_wipe/obsidian_delete_note — convention déjà en place dans le prompt).
     */
    fun deleteFolder(context: Context, path: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        if (path.isBlank()) return "❌ Précise le dossier à supprimer."
        return try {
            val root = getVaultRoot(context)
            val safePath = path.split("/", "\\").joinToString("/") { it.replace(Regex("[:*?\"<>|]"), "-").trim() }
            val dir = File(root, safePath)
            // Garde-fou : ne jamais permettre de supprimer la racine du vault elle-même via
            // cette action (obsidian_wipe existe déjà, explicitement, pour ce cas précis) —
            // sans ce contrôle, un chemin vide/"." effacerait tout le vault silencieusement.
            if (dir.canonicalPath == root.canonicalPath) {
                return "❌ Utilise obsidian_wipe pour vider tout le vault — cette action ne supprime qu'un sous-dossier précis."
            }
            if (!dir.exists() || !dir.isDirectory) return "❌ Dossier introuvable : $safePath"
            var deletedFiles = 0
            dir.walkBottomUp().forEach { f ->
                if (f.isFile) { if (f.delete()) deletedFiles++ } else if (f != dir) f.delete()
            }
            dir.delete()
            "🗑️ Dossier « $safePath » supprimé ($deletedFiles fichier(s) avec)."
        } catch (e: Exception) {
            "❌ Erreur suppression dossier : ${e.message}"
        }
    }

    /**
     * Renomme/déplace un dossier du vault (et tout son contenu) — ex: "Notes Rapides" ->
     * "Idées", ou déplacer un dossier entier ailleurs dans l'arborescence en changeant son
     * chemin. Contrairement à moveNote (qui déplace UNE note), ceci déplace le dossier en bloc.
     */
    fun renameFolder(context: Context, path: String, newPath: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        if (path.isBlank() || newPath.isBlank()) return "❌ Précise le dossier à renommer ET son nouveau nom/chemin."
        return try {
            val root = getVaultRoot(context)
            val safeOld = path.split("/", "\\").joinToString("/") { it.replace(Regex("[:*?\"<>|]"), "-").trim() }
            val safeNew = newPath.split("/", "\\").joinToString("/") { it.replace(Regex("[:*?\"<>|]"), "-").trim() }
            val src = File(root, safeOld)
            if (!src.exists() || !src.isDirectory) return "❌ Dossier introuvable : $safeOld"
            val dest = File(root, safeNew)
            if (dest.exists()) return "❌ « $safeNew » existe déjà — choisis un autre nom."
            dest.parentFile?.mkdirs()
            // renameTo() échoue silencieusement entre certains volumes (ex: stockage interne vs
            // carte SD) — repli copie+suppression comme pour moveNote, pour rester fiable.
            if (src.renameTo(dest)) {
                "✅ Dossier renommé : « $safeOld » -> « $safeNew »"
            } else {
                src.copyRecursively(dest, overwrite = false)
                src.deleteRecursively()
                "✅ Dossier renommé : « $safeOld » -> « $safeNew »"
            }
        } catch (e: Exception) {
            "❌ Erreur renommage dossier : ${e.message}"
        }
    }

    /**
     * Liste les VRAIS sous-dossiers du vault (arborescence réelle sur disque), pas seulement
     * ceux qui contiennent déjà une note — bug réel corrigé : jusqu'ici, aucune action ne
     * listait les dossiers eux-mêmes (obsidian_list liste des NOTES, getVaultStats ne comptait
     * que les dossiers CONTENANT au moins une note .md). Un dossier créé (obsidian_create_folder)
     * mais encore vide était donc invisible à toute action ultérieure — dans une nouvelle
     * conversation ou après un redémarrage, JARVIS n'avait aucun moyen de savoir qu'il existait
     * déjà et pouvait soit dire "introuvable", soit en recréer un autre avec un nom légèrement
     * différent (casse, accents, formulation) au lieu de réutiliser l'existant.
     */
    fun listFolders(context: Context, path: String = ""): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val root = getVaultRoot(context)
        if (!root.exists() || !root.isDirectory) {
            return "❌ Le dossier du vault n'existe pas ou n'est pas accessible : ${root.absolutePath}."
        }
        val baseDir = if (path.isBlank()) root else File(root, path)
        if (!baseDir.exists() || !baseDir.isDirectory) return "📁 Le dossier \"$path\" n'existe pas dans le vault."

        val folders = baseDir.walkTopDown()
            .filter { it.isDirectory && it != baseDir && it.name != ".obsidian" && !it.path.contains("${File.separator}.obsidian${File.separator}") }
            .sortedBy { it.relativeTo(root).path.lowercase() }
            .toList()

        if (folders.isEmpty()) {
            return "📁 Aucun sous-dossier ${if (path.isBlank()) "dans le vault" else "dans \"$path\""} (à part les notes directement dedans)."
        }
        val sb = StringBuilder("📁 **${folders.size} dossier(s)** ${if (path.isBlank()) "dans le vault" else "dans \"$path\""} :\n\n")
        folders.forEach { dir ->
            val rel = dir.relativeTo(root).path
            val noteCount = dir.listFiles { f -> f.isFile && f.extension == "md" }?.size ?: 0
            sb.append("📁 $rel ($noteCount note(s))\n")
        }
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create note
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Liens automatiques entre notes ("second brain" — voir aussi la mémoire durable
    // plus bas) : contrairement à Obsidian lui-même (qui ne crée AUCUN lien tout seul,
    // l'utilisateur tape manuellement [[Titre]]), JARVIS peut le faire automatiquement
    // puisque C'EST LUI qui écrit le contenu — si le texte d'une nouvelle note/ajout
    // mentionne le titre exact d'une autre note déjà existante, ce titre est entouré de
    // [[...]]. Ensuite, c'est l'app Obsidian ELLE-MÊME (pas JARVIS) qui affiche
    // automatiquement les backlinks et le graphe à partir de cette syntaxe standard —
    // pas besoin de maintenir un index de liens séparé côté JARVIS, juste écrire le bon
    // markdown. Volontairement PRUDENT : un seul lien par titre (pas de sur-liage d'un
    // nom répété 10 fois dans la même note), uniquement en bordure de mot (jamais au
    // milieu d'un autre mot), jamais si le lien existe déjà quelque part dans le texte.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wrapper public pour réutiliser la logique de liens [[wikilinks]] automatiques depuis
     * un autre contrôleur (WikiController) sans dupliquer le scan du vault — même pattern que
     * missingStorageAccessMessagePublic()/getVaultRoot() déjà exposés publiquement plus haut.
     */
    fun autoLinkContentPublic(context: Context, content: String, excludeTitle: String? = null): String =
        autoLinkContent(context, content, excludeTitle)

    private fun autoLinkContent(context: Context, content: String, excludeTitle: String? = null): String {
        if (content.isBlank() || !hasStorageAccess()) return content
        val root = getVaultRoot(context)
        if (!root.exists() || !root.isDirectory) return content
        val titles = try {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
                .map { it.nameWithoutExtension }
                .filter { it.length >= 3 && !it.equals(excludeTitle, ignoreCase = true) }
                .distinct()
                .sortedByDescending { it.length }
                .toList()
        } catch (_: Exception) {
            return content
        }

        var result = content
        for (title in titles) {
            if (result.contains("[[$title]]", ignoreCase = true)) continue
            val idx = result.indexOf(title, ignoreCase = true)
            if (idx < 0) continue
            val before = if (idx > 0) result[idx - 1] else ' '
            val afterIdx = idx + title.length
            val after = if (afterIdx < result.length) result[afterIdx] else ' '
            val isWordBoundary = !before.isLetterOrDigit() && !after.isLetterOrDigit()
            if (!isWordBoundary) continue
            result = result.substring(0, idx) + "[[" + result.substring(idx, afterIdx) + "]]" + result.substring(afterIdx)
        }
        return result
    }

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
            val linkedContent = autoLinkContent(context, content, excludeTitle = safeTitle)

            val body = """
---
date: ${dateFormat.format(now)}
tags: [$tagsStr]
source: JARVIS Assistant
---

# $title

$linkedContent

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
            val linkedContent = if (content.isNotBlank()) autoLinkContent(context, content, excludeTitle = today) else content
            if (file.exists()) {
                // Append to existing
                if (linkedContent.isNotBlank()) {
                    file.appendText("\n\n${timeFormat.format(Date())} — $linkedContent")
                    "📅 Ajouté à la note du jour ($todayDisp) :\n\"$linkedContent\""
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

${if (linkedContent.isNotBlank()) linkedContent else "— Notes du jour —"}

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
    // BUG RÉEL CORRIGÉ : ne se basait QUE sur le tout dernier message utilisateur, et
    // s'arrêtait au TOUT PREMIER mot-clé trouvé (score binaire, pas de classement) — une
    // relance mi-conversation ("et son adresse ?", "et pour l'autre ?") qui ne répète pas
    // le mot-clé initial ne retrouvait plus rien, donnant l'impression que JARVIS "oubliait"
    // en cours de route. Prend maintenant les derniers messages utilisateur (pas juste le
    // dernier) pour construire les mots-clés, et classe les notes par NOMBRE de mots-clés
    // trouvés (titre pondéré plus fort que contenu) au lieu de s'arrêter au premier match —
    // les notes les plus pertinentes remontent en premier même quand plusieurs notes
    // matchent partiellement.
    fun quickContextSearch(context: Context, recentUserMessages: List<String>, maxNotes: Int = 5): String? {
        if (!hasStorageAccess()) return null
        val root = getVaultRoot(context)
        if (!root.exists() || !root.isDirectory) return null

        val words = recentUserMessages.joinToString(" ")
            .lowercase()
            .replace(Regex("[^a-zà-ÿ0-9 ]"), " ")
            .split(" ")
            .filter { it.length >= 4 && it !in CONTEXT_STOPWORDS_FR }
            .distinct()
        // Message(s) trop génériques pour en tirer un mot-clé (ex: "yo", "ça va ?") : plutôt
        // que de ne rien injecter du tout, on signale quand même l'existence et les titres
        // des notes les plus récentes — un aperçu léger, sans le contenu complet — pour que
        // JARVIS ait conscience du vault dès le début d'une conversation même sans mot-clé.
        if (words.isEmpty()) {
            val recent = root.walkTopDown()
                .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
                .sortedByDescending { it.lastModified() }
                .take(maxNotes)
                .toList()
            if (recent.isEmpty()) return null
            return recent.joinToString("\n") { "### ${it.nameWithoutExtension} (récent)" }
        }

        data class Hit(val file: File, val score: Int, val excerpt: String)
        val hits = mutableListOf<Hit>()
        for (file in root.walkTopDown()) {
            if (!file.isFile || file.extension != "md" || file.path.contains(".obsidian")) continue
            try {
                val text = file.readText()
                val textLower = text.lowercase()
                val nameLower = file.nameWithoutExtension.lowercase()
                val titleMatches = words.count { nameLower.contains(it) }
                val contentMatches = words.count { textLower.contains(it) }
                val score = titleMatches * 3 + contentMatches
                if (score == 0) continue
                val hitWord = words.firstOrNull { textLower.contains(it) }
                val excerpt = if (hitWord != null) {
                    val idx = textLower.indexOf(hitWord)
                    val start = maxOf(0, idx - 80)
                    val end = minOf(text.length, idx + hitWord.length + 220)
                    text.substring(start, end).replace("\n", " ")
                } else {
                    text.take(220).replace("\n", " ")
                }
                hits.add(Hit(file, score, excerpt))
            } catch (_: Exception) {
                // note illisible — on l'ignore simplement, pas bloquant pour les autres
            }
        }

        if (hits.isEmpty()) return null
        val top = hits.sortedByDescending { it.score }.take(maxNotes)
        val sb = StringBuilder()
        top.forEach { hit ->
            sb.append("### ${hit.file.nameWithoutExtension}\n…${hit.excerpt.trim()}…\n\n")
        }
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mémoire durable ("second brain") : contrairement à quickContextSearch ci-dessus
    // (qui ne renvoie que des extraits liés au(x) dernier(s) message(s), différents à
    // chaque fois), cette note spéciale est relue EN ENTIER et injectée SYSTÉMATIQUEMENT
    // à CHAQUE message, sans condition de mot-clé (voir ApiClient.sendChat) — c'est ce
    // qui règle le symptôme "on repart de zéro à chaque conversation/redémarrage" : les
    // faits qui y sont notés (via remember_fact, ou directement demandés par
    // l'utilisateur : "retiens que...") restent connus de JARVIS en permanence, pas
    // seulement quand leurs mots-clés apparaissent par hasard dans le message en cours.
    // Bornée en taille (MAX_MEMORY_CHARS) : au-delà, les entrées les plus ANCIENNES sont
    // retirées en premier (FIFO), jamais les plus récentes — même logique que
    // MAX_GENERATION_HISTORY/MAX_HISTORY_MESSAGES ailleurs dans le projet, pour ne jamais
    // laisser grossir indéfiniment ce qui est envoyé à chaque appel IA.
    // ─────────────────────────────────────────────────────────────────────────

    private const val MEMORY_NOTE_TITLE = "Mémoire JARVIS"
    private const val MAX_MEMORY_CHARS = 4000

    private fun memoryNoteHeader(): String = """
---
tags: ["memoire", "jarvis"]
source: JARVIS Assistant
---

# 🧠 Mémoire JARVIS

Faits durables retenus par JARVIS au fil des conversations — relue EN ENTIER et prise en
compte à CHAQUE message, contrairement aux autres notes (qui ne remontent que si leurs
mots-clés correspondent à la conversation en cours). Modifiable librement ici même, ou en
disant à JARVIS « retiens que... » / « oublie que... ».
""".trimIndent()

    private fun trimMemoryIfNeeded(fullText: String): String {
        if (fullText.length <= MAX_MEMORY_CHARS) return fullText
        val lines = fullText.lines()
        val bulletStartIdx = lines.indexOfFirst { it.trim().startsWith("- [") }
        if (bulletStartIdx < 0) return fullText.takeLast(MAX_MEMORY_CHARS)
        val header = lines.subList(0, bulletStartIdx)
        var bullets = lines.subList(bulletStartIdx, lines.size).filter { it.isNotBlank() }
        while (bullets.isNotEmpty() &&
            (header.joinToString("\n").length + bullets.joinToString("\n").length) > MAX_MEMORY_CHARS
        ) {
            bullets = bullets.drop(1) // retire l'entrée la plus ANCIENNE en premier
        }
        return (header + bullets).joinToString("\n")
    }

    /** Ajoute un fait durable à la mémoire (crée la note si besoin). */
    fun rememberFact(context: Context, fact: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val trimmedFact = fact.trim()
        if (trimmedFact.isBlank()) return "❌ Rien à retenir : le fait est vide."
        return try {
            val root = getVaultRoot(context)
            val file = File(root, "$MEMORY_NOTE_TITLE.md")
            val line = "- [${displayFormat.format(Date())}] $trimmedFact"
            val updated = if (!file.exists()) {
                memoryNoteHeader() + "\n" + line
            } else {
                trimMemoryIfNeeded(file.readText().trimEnd() + "\n" + line)
            }
            file.writeText(updated)
            "✅ Retenu durablement : \"$trimmedFact\""
        } catch (e: Exception) {
            "❌ Erreur mémoire : ${e.message}"
        }
    }

    /** Retire les entrées de mémoire contenant [query] (recherche simple, insensible à la casse). */
    fun forgetFact(context: Context, query: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return "❌ Précise ce qu'il faut oublier."
        val root = getVaultRoot(context)
        val file = File(root, "$MEMORY_NOTE_TITLE.md")
        if (!file.exists()) return "ℹ️ Aucune mémoire enregistrée pour l'instant."
        return try {
            val queryLower = trimmedQuery.lowercase()
            val lines = file.readText().lines()
            val kept = lines.filter { !(it.trim().startsWith("- [") && it.lowercase().contains(queryLower)) }
            val removed = lines.size - kept.size
            if (removed == 0) return "ℹ️ Rien trouvé dans la mémoire correspondant à \"$trimmedQuery\"."
            file.writeText(kept.joinToString("\n"))
            "✅ $removed élément(s) oublié(s) correspondant à \"$trimmedQuery\"."
        } catch (e: Exception) {
            "❌ Erreur mémoire : ${e.message}"
        }
    }

    /** Contenu brut (uniquement les entrées, sans l'en-tête) pour injection systématique dans le system prompt. */
    fun getMemoryContext(context: Context): String? {
        if (!hasStorageAccess()) return null
        val root = getVaultRoot(context)
        val file = File(root, "$MEMORY_NOTE_TITLE.md")
        if (!file.exists()) return null
        return try {
            val bullets = file.readText().lines().filter { it.trim().startsWith("- [") }
            if (bullets.isEmpty()) null else bullets.joinToString("\n")
        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Append to note
    // ─────────────────────────────────────────────────────────────────────────

    fun appendToNote(context: Context, query: String, text: String): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val file = findNote(context, query)
            ?: return "❌ Note \"$query\" introuvable. Créez-la d'abord."
        return try {
            val linkedText = autoLinkContent(context, text, excludeTitle = file.nameWithoutExtension)
            file.appendText("\n\n${timeFormat.format(Date())} — $linkedText")
            "✅ Ajouté à **${file.nameWithoutExtension}** :\n\"$linkedText\""
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
            // Compte les VRAIS sous-dossiers (arborescence réelle), pas seulement ceux qui
            // contiennent déjà une note — un dossier créé mais encore vide comptait comme 0
            // avant ce correctif, ce qui cachait son existence même à l'utilisateur qui
            // demandait "combien de dossiers j'ai".
            val folders = root.walkTopDown()
                .filter { it.isDirectory && it != root && it.name != ".obsidian" && !it.path.contains("${File.separator}.obsidian${File.separator}") }
                .toList()
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
