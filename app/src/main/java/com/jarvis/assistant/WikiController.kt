package com.jarvis.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * WikiController — implémentation du pattern "LLM Wiki" (voir gist Karpathy et son adaptation
 * kennyg/llm-wiki-obsidian-setup) côté JARVIS : au lieu de re-dériver la connaissance à chaque
 * question depuis des notes brutes (RAG classique), JARVIS construit et MAINTIENT un wiki
 * structuré et interlié dans le vault (dossier Wiki/) — la connaissance est compilée une fois
 * puis tenue à jour, pas re-dérivée à chaque fois.
 *
 * Différent de la mémoire durable (remember_fact -> note "Mémoire JARVIS", des faits courts sur
 * l'UTILISATEUR lui-même) et des fiches contact (save_contact_profile, des PERSONNES connues) :
 * ce contrôleur sert à structurer et recouper du savoir sur des SUJETS (sources approfondies,
 * entités récurrentes, concepts, synthèses de recherche) — voir la doc SYSTEM_PROMPT pour la
 * règle de choix exacte.
 *
 * Contrairement à un agent de code classique (qui suit la discipline du pattern uniquement via
 * ses instructions), index.md et log.md sont tenus à jour ICI, par du CODE déterministe — jamais
 * oubliés même si le modèle IA derrière JARVIS ne s'en souvient pas à chaque appel.
 *
 * Structure créée dans le vault :
 *   Wiki/
 *   ├── index.md      — catalogue de toutes les pages, régénéré par code à chaque wiki_page
 *   ├── log.md        — journal chronologique append-only ("## [date heure] opération | Titre")
 *   ├── overview.md   — synthèse générale, librement enrichie
 *   ├── sources/      — une page par source approfondie
 *   ├── entities/     — personnes, outils, organisations récurrents
 *   ├── concepts/     — idées, patterns, techniques
 *   └── synthesis/    — réponses substantielles archivées (compoundent au fil des questions)
 */
object WikiController {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun hasStorageAccess(): Boolean = PermissionsManager.hasManageStoragePermission()
    private fun missingStorageAccessMessage(): String = ObsidianController.missingStorageAccessMessagePublic()

    private data class PageType(
        val folder: String,
        val frontmatterType: String,
        val tag: String,
        val sectionTitle: String,
        val emoji: String,
        val logVerb: String
    )

    private val PAGE_TYPES = linkedMapOf(
        "source" to PageType("sources", "source-summary", "wiki/source", "Sources", "📚", "ingest"),
        "entity" to PageType("entities", "entity", "wiki/entity", "Entités", "👤", "entité"),
        "concept" to PageType("concepts", "concept", "wiki/concept", "Concepts", "💡", "concept"),
        "synthesis" to PageType("synthesis", "synthesis", "wiki/synthesis", "Synthèses", "🔎", "synthèse")
    )

    private fun wikiRoot(context: Context): File = File(ObsidianController.getVaultRoot(context), "Wiki")

    // ─────────────────────────────────────────────────────────────────────────
    // wiki_init — scaffold (idempotent, jamais destructif sur un wiki existant)
    // ─────────────────────────────────────────────────────────────────────────

    fun init(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        return try {
            val root = wikiRoot(context)
            val alreadyExisted = root.exists()
            PAGE_TYPES.values.forEach { File(root, it.folder).mkdirs() }

            val indexFile = File(root, "index.md")
            if (!indexFile.exists()) indexFile.writeText(buildEmptyIndex())

            val logFile = File(root, "log.md")
            if (!logFile.exists()) {
                logFile.writeText(logHeader())
                appendLog(context, "init", "Wiki initialisé")
            }

            val overviewFile = File(root, "overview.md")
            if (!overviewFile.exists()) {
                overviewFile.writeText("""
---
type: overview
tags: ["wiki/overview"]
date_updated: ${dateFormat.format(Date())}
---

# 🧭 Vue d'ensemble

Synthèse générale du wiki — à enrichir au fil des ingestions (wiki_page) et des réponses
substantielles archivées (type=synthesis). Vide pour l'instant.
""".trimIndent())
            }

            if (alreadyExisted) "📚 Wiki déjà initialisé : ${root.absolutePath}\n\n${status(context)}"
            else "✅ Wiki créé : ${root.absolutePath}\n📁 sources/ entities/ concepts/ synthesis/\n📄 index.md, log.md, overview.md"
        } catch (e: Exception) {
            "❌ Erreur initialisation du wiki : ${e.message}"
        }
    }

    private fun buildEmptyIndex(): String {
        val sb = StringBuilder()
        sb.append("""
---
type: index
tags: ["wiki/index"]
date_updated: ${dateFormat.format(Date())}
---

# 🗂️ Index — Wiki JARVIS

Catalogue de toutes les pages du wiki, tenu à jour automatiquement (wiki_page) — ne pas
éditer les tableaux à la main, la prochaine mise à jour les régénère.
""".trimIndent())
        sb.append("\n\n")
        PAGE_TYPES.values.forEach { pt ->
            sb.append("## ${pt.emoji} ${pt.sectionTitle} (0)\n\n")
            sb.append("| Page | Résumé | Maj |\n|---|---|---|\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun logHeader(): String = ("""
---
type: log
tags: ["wiki/log"]
---

# 📜 Journal du wiki

Journal chronologique append-only. Chaque entrée : `## [date heure] opération | Titre`.
""".trimIndent()) + "\n\n"

    private fun appendLog(context: Context, verb: String, title: String) {
        val root = wikiRoot(context)
        val logFile = File(root, "log.md")
        if (!logFile.exists()) logFile.writeText(logHeader())
        logFile.appendText("## [${dateTimeFormat.format(Date())}] $verb | $title\n")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // wiki_page — crée OU met à jour une page (source/entity/concept/synthesis) et répercute
    // automatiquement sur index.md + log.md — aucune étape manuelle supplémentaire côté IA.
    // ─────────────────────────────────────────────────────────────────────────

    fun page(
        context: Context,
        type: String,
        title: String,
        content: String,
        summary: String = "",
        tags: List<String> = emptyList()
    ): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val pt = PAGE_TYPES[type.lowercase().trim()]
            ?: return "❌ Type de page invalide : \"$type\" — utilise source, entity, concept ou synthesis."
        if (title.isBlank()) return "❌ Précise un titre de page."
        if (content.isBlank()) return "❌ Précise le contenu de la page."
        return try {
            val root = wikiRoot(context)
            // Auto-init si le wiki n'existe pas encore — jamais dire "impossible", même philosophie
            // que le reste du projet (obsidian_create_note crée le vault au besoin, etc.).
            if (!root.exists()) init(context)

            val dir = File(root, pt.folder).also { it.mkdirs() }
            val safeTitle = title.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val file = File(dir, "$safeTitle.md")
            val now = Date()
            val isUpdate = file.exists()

            val linkedContent = ObsidianController.autoLinkContentPublic(context, content, excludeTitle = safeTitle)
            val allTags = (listOf(pt.tag) + tags).distinct()
            val tagsStr = allTags.joinToString(", ") { "\"$it\"" }

            val body = """
---
type: ${pt.frontmatterType}
tags: [$tagsStr]
date_updated: ${dateFormat.format(now)}
source: JARVIS Assistant
---

# $title

$linkedContent
""".trimIndent()

            file.writeText(body)

            val effectiveSummary = summary.ifBlank {
                content.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.take(140) ?: ""
            }.replace("\n", " ").replace("|", "/").trim()

            updateIndex(context, pt, safeTitle, effectiveSummary, now)
            appendLog(context, pt.logVerb, safeTitle)

            val verbe = if (isUpdate) "mise à jour" else "créée"
            "${pt.emoji} Page $verbe : **$safeTitle** (${pt.sectionTitle}) — index.md et log.md mis à jour."
        } catch (e: Exception) {
            "❌ Erreur page wiki : ${e.message}"
        }
    }

    /**
     * Met à jour la section correspondante d'index.md : ajoute une ligne pour [safeTitle] si
     * elle n'existe pas encore, sinon met à jour sa ligne existante (résumé + date), et
     * recalcule le compteur affiché dans le titre de section. Traitement 100% par CODE,
     * jamais laissé à la discipline du modèle IA (c'est tout l'intérêt par rapport à un simple
     * prompt : index.md ne peut jamais dériver ou être oublié).
     */
    private fun updateIndex(context: Context, pt: PageType, safeTitle: String, summary: String, updatedDate: Date) {
        val root = wikiRoot(context)
        val indexFile = File(root, "index.md")
        if (!indexFile.exists()) indexFile.writeText(buildEmptyIndex())
        val lines = indexFile.readText().lines().toMutableList()

        val headingPrefix = "## ${pt.emoji} ${pt.sectionTitle} ("
        var headingIdx = -1
        for (i in lines.indices) {
            if (lines[i].startsWith(headingPrefix)) { headingIdx = i; break }
        }
        if (headingIdx < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            headingIdx = lines.size
            lines.add(headingPrefix + "0)")
            lines.add("")
            lines.add("| Page | Résumé | Maj |")
            lines.add("|---|---|---|")
        }

        var sectionEnd = lines.size
        for (i in (headingIdx + 1) until lines.size) {
            if (lines[i].startsWith("## ")) { sectionEnd = i; break }
        }

        var tableHeaderIdx = -1
        for (i in headingIdx until sectionEnd) {
            if (lines[i].trim().startsWith("| Page")) { tableHeaderIdx = i; break }
        }
        if (tableHeaderIdx < 0) {
            lines.add(headingIdx + 1, "| Page | Résumé | Maj |")
            lines.add(headingIdx + 2, "|---|---|---|")
            tableHeaderIdx = headingIdx + 1
            sectionEnd += 2
        }
        val dataStart = tableHeaderIdx + 2

        val rows = mutableListOf<String>()
        for (i in dataStart until sectionEnd) {
            val l = lines[i]
            if (l.trim().startsWith("|")) rows.add(l)
        }

        val newRow = "| [[$safeTitle]] | $summary | ${dateFormat.format(updatedDate)} |"
        val existingIdx = rows.indexOfFirst { it.contains("[[$safeTitle]]") }
        if (existingIdx >= 0) rows[existingIdx] = newRow else rows.add(newRow)

        val newSection = mutableListOf<String>()
        newSection.add(headingPrefix + "${rows.size})")
        newSection.add("")
        newSection.add("| Page | Résumé | Maj |")
        newSection.add("|---|---|---|")
        newSection.addAll(rows)
        newSection.add("")

        val newLines = lines.subList(0, headingIdx).toMutableList()
        newLines.addAll(newSection)
        newLines.addAll(lines.subList(sectionEnd, lines.size))

        indexFile.writeText(newLines.joinToString("\n").trimEnd() + "\n")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // wiki_status — vue d'ensemble : compteurs par section + dernières entrées du journal
    // ─────────────────────────────────────────────────────────────────────────

    fun status(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val root = wikiRoot(context)
        if (!root.exists()) {
            return "📚 Le wiki n'a pas encore été initialisé — utilise wiki_init pour créer le squelette " +
                "(sources/entities/concepts/synthesis + index.md + log.md)."
        }
        return try {
            val logFile = File(root, "log.md")
            val lastEntries = if (logFile.exists()) {
                logFile.readText().lines().filter { it.startsWith("## [") }.takeLast(5)
            } else emptyList()

            val sb = StringBuilder("📚 **État du wiki**\n\n")
            PAGE_TYPES.values.forEach { pt ->
                val count = File(root, pt.folder).listFiles { f -> f.isFile && f.extension == "md" }?.size ?: 0
                sb.append("${pt.emoji} ${pt.sectionTitle} : $count\n")
            }
            sb.append("\n📜 Dernières entrées du journal :\n")
            if (lastEntries.isEmpty()) sb.append("(aucune)\n") else lastEntries.forEach { sb.append("$it\n") }
            sb.toString().trim()
        } catch (e: Exception) {
            "❌ Erreur statut du wiki : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // wiki_lint — diagnostic : pages orphelines, entrées d'index périmées, liens cassés
    // ─────────────────────────────────────────────────────────────────────────

    fun lint(context: Context): String {
        if (!hasStorageAccess()) return missingStorageAccessMessage()
        val root = wikiRoot(context)
        if (!root.exists()) return "📚 Le wiki n'a pas encore été initialisé — utilise wiki_init d'abord."
        return try {
            val pages = PAGE_TYPES.values.flatMap { pt ->
                File(root, pt.folder).listFiles { f -> f.isFile && f.extension == "md" }?.toList() ?: emptyList()
            }
            val pageTitles = pages.map { it.nameWithoutExtension }.toSet()

            val indexFile = File(root, "index.md")
            val indexContent = if (indexFile.exists()) indexFile.readText() else ""
            val wikilinkRegex = Regex("\\[\\[([^\\]]+)\\]\\]")
            val indexedTitles = wikilinkRegex.findAll(indexContent).map { it.groupValues[1] }.toSet()

            val orphans = pageTitles - indexedTitles
            val staleIndexEntries = indexedTitles - pageTitles

            val vaultRoot = ObsidianController.getVaultRoot(context)
            val allVaultTitles = vaultRoot.walkTopDown()
                .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
                .map { it.nameWithoutExtension }
                .toSet()
            val brokenLinks = mutableListOf<String>()
            pages.forEach { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                wikilinkRegex.findAll(content).map { it.groupValues[1] }.distinct().forEach { linked ->
                    if (linked !in allVaultTitles) brokenLinks.add("${file.nameWithoutExtension} -> [[$linked]]")
                }
            }

            if (orphans.isEmpty() && staleIndexEntries.isEmpty() && brokenLinks.isEmpty()) {
                return "✅ Wiki propre : ${pages.size} page(s), aucune page orpheline, aucun lien cassé, index à jour."
            }

            val sb = StringBuilder("🔍 **Diagnostic du wiki** (${pages.size} page(s))\n\n")
            if (orphans.isNotEmpty()) {
                sb.append("⚠️ Pages absentes de l'index (${orphans.size}) :\n")
                orphans.forEach { sb.append("• $it\n") }
                sb.append("\n")
            }
            if (staleIndexEntries.isNotEmpty()) {
                sb.append("⚠️ Entrées d'index périmées, fichier introuvable (${staleIndexEntries.size}) :\n")
                staleIndexEntries.forEach { sb.append("• $it\n") }
                sb.append("\n")
            }
            if (brokenLinks.isNotEmpty()) {
                sb.append("⚠️ Liens cassés (${brokenLinks.size}) :\n")
                brokenLinks.take(20).forEach { sb.append("• $it\n") }
                if (brokenLinks.size > 20) sb.append("… et ${brokenLinks.size - 20} autre(s).\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "❌ Erreur diagnostic du wiki : ${e.message}"
        }
    }
}
