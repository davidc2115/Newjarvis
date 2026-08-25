package com.jarvis.assistant

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Phase 1 du système Obsidian (voir tâche #211) : un VRAI dossier Obsidian sur le téléphone,
 * choisi par l'utilisateur (SAF -- Storage Access Framework), pas un dossier interne créé par
 * l'appli. C'est la demande explicite : "vault réel", pas une imitation.
 *
 * Toutes les opérations passent par DocumentFile.fromTreeUri() (androidx.documentfile), la
 * seule API qui fonctionne pour lire/écrire dans un dossier arbitraire choisi par
 * l'utilisateur sous scoped storage (Android 10+) -- java.io.File direct ne fonctionnerait pas
 * ici (permission refusée hors stockage privé de l'appli).
 *
 * Les notes sont des fichiers .md à la racine du vault OU dans des sous-dossiers (recherche
 * récursive pour lire/trouver une note par titre). Phase 1 = opérations de base seulement
 * (créer/lire/lister/compléter) ; liens automatiques, mémoire persistante et graphe visuel
 * sont des phases suivantes (voir tâches #225/#226), volontairement séparées pour livrer une
 * base solide et testée avant d'empiler dessus.
 */
object ObsidianController {

    /** true si l'utilisateur a choisi un vault (voir SettingsActivity) et que l'accès est
     *  toujours valide (permission persistante pas révoquée entre-temps). */
    fun hasVault(context: Context): Boolean = getVaultRoot(context) != null

    fun getVaultRoot(context: Context): DocumentFile? {
        val uriString = Prefs.getObsidianVaultUri(context) ?: return null
        val uri = Uri.parse(uriString)
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return null
        return if (doc.exists() && doc.isDirectory) doc else null
    }

    /** Nom d'un fichier .md à partir d'un titre de note libre (espaces/accents conservés --
     *  Obsidian gère très bien les noms de fichiers avec espaces/accents, pas de raison de les
     *  transformer et de perdre la correspondance titre <-> nom de fichier attendue par
     *  l'utilisateur). */
    private fun noteFileName(title: String): String {
        val clean = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-")
        return if (clean.endsWith(".md", ignoreCase = true)) clean else "$clean.md"
    }

    /** Recherche récursive d'une note par titre (insensible à la casse) dans tout le vault --
     *  pas seulement à la racine, les notes Obsidian réelles vivent souvent dans des
     *  sous-dossiers par catégorie. */
    private fun findNote(root: DocumentFile, title: String): DocumentFile? {
        val target = noteFileName(title).lowercase()
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.name?.lowercase() == target) {
                    return child
                }
            }
        }
        return null
    }

    /** Liste tous les fichiers .md du vault (chemin relatif inclus si dans un sous-dossier, ex.
     *  "Contacts/Julie.md") -- utile pour que JARVIS sache ce qui existe déjà avant de créer un
     *  doublon, et pour un futur écran de liste/graphe (phases suivantes). */
    suspend fun listNotes(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        val root = getVaultRoot(context)
            ?: return@withContext Result.failure(IllegalStateException("Aucun vault Obsidian choisi -- va dans Réglages pour en sélectionner un."))
        try {
            val result = mutableListOf<String>()
            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory) {
                        walk(child, if (prefix.isEmpty()) name else "$prefix/$name")
                    } else if (name.endsWith(".md", ignoreCase = true)) {
                        result.add(if (prefix.isEmpty()) name else "$prefix/$name")
                    }
                }
            }
            walk(root, "")
            Result.success(result.sorted())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createNote(context: Context, title: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        val root = getVaultRoot(context)
            ?: return@withContext Result.failure(IllegalStateException("Aucun vault Obsidian choisi -- va dans Réglages pour en sélectionner un."))
        try {
            val fileName = noteFileName(title)
            if (findNote(root, title) != null) {
                return@withContext Result.failure(IllegalStateException("Une note « $title » existe déjà -- utilise plutôt l'ajout/complément si tu veux la modifier."))
            }
            val file = root.createFile("text/markdown", fileName)
                ?: return@withContext Result.failure(IllegalStateException("Impossible de créer le fichier dans le vault (permission perdue ?)."))
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: return@withContext Result.failure(IllegalStateException("Impossible d'écrire dans le fichier créé."))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readNote(context: Context, title: String): Result<String> = withContext(Dispatchers.IO) {
        val root = getVaultRoot(context)
            ?: return@withContext Result.failure(IllegalStateException("Aucun vault Obsidian choisi -- va dans Réglages pour en sélectionner un."))
        try {
            val file = findNote(root, title)
                ?: return@withContext Result.failure(NoSuchElementException("Aucune note « $title » trouvée dans le vault."))
            val text = context.contentResolver.openInputStream(file.uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: return@withContext Result.failure(IllegalStateException("Impossible de lire le fichier."))
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Ajoute du contenu à la fin d'une note existante (créée si absente) -- pratique pour la
     *  future note "Mémoire JARVIS" (phase suivante) qui s'enrichit au fil des conversations. */
    suspend fun appendToNote(context: Context, title: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        val root = getVaultRoot(context)
            ?: return@withContext Result.failure(IllegalStateException("Aucun vault Obsidian choisi -- va dans Réglages pour en sélectionner un."))
        try {
            val existing = findNote(root, title)
            if (existing == null) {
                return@withContext createNote(context, title, content)
            }
            val previous = context.contentResolver.openInputStream(existing.uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: ""
            val updated = if (previous.isBlank()) content else "$previous\n\n$content"
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { it.write(updated.toByteArray(Charsets.UTF_8)) }
                ?: return@withContext Result.failure(IllegalStateException("Impossible d'écrire dans le fichier."))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
