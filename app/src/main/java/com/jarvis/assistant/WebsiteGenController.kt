package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebsiteGenController — génère un site web statique complet (un seul
 * fichier .html avec CSS/JS intégrés) à partir d'une description en
 * langage naturel, en réutilisant le fournisseur IA déjà configuré dans
 * JARVIS (⚙ → Configuration) — aucune clé API supplémentaire nécessaire.
 *
 * Le fichier est enregistré dans Documents/JARVIS-Sites/ et peut être
 * ouvert directement dans le navigateur du téléphone.
 */
object WebsiteGenController {

    data class Result(val success: Boolean, val message: String, val filePath: String? = null)

    private const val GEN_INSTRUCTIONS = "Tu es un générateur de sites web. On va te donner une description " +
        "de site. Réponds UNIQUEMENT avec le code source complet d'une seule page HTML autonome " +
        "(CSS dans une balise <style> et JS dans une balise <script>, tout inclus dans le fichier, " +
        "aucune dépendance externe sauf polices Google Fonts ou icônes si nécessaire via CDN). " +
        "Le design doit être moderne, responsive (mobile + desktop) et soigné. " +
        "Ne mets AUCUN texte avant ou après le code, AUCUNE explication, AUCUN bloc markdown ```html — " +
        "uniquement le HTML brut commençant par <!DOCTYPE html> et finissant par </html>."

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    suspend fun generateWebsite(context: Context, description: String): Result {
        if (description.isBlank()) return Result(false, "❌ Aucune description de site fournie.")

        return try {
            val history = listOf(
                HistoryEntry(role = "user", text = "$GEN_INSTRUCTIONS\n\nDescription du site demandé : $description")
            )
            val chatResult = ApiClient.sendChat(context, history)
            var html = chatResult.text.trim()

            // Nettoie un éventuel bloc markdown si l'IA en a quand même mis un.
            html = html
                .removePrefix("```html").removePrefix("```HTML").removePrefix("```")
                .removeSuffix("```")
                .trim()

            if (!html.contains("<html", ignoreCase = true)) {
                return Result(false, "❌ La réponse de l'IA ne ressemble pas à une page HTML valide. Réessaie avec une description plus précise, ou vérifie qu'un provider IA est bien configuré (⚙ → Config).")
            }

            val savedPath = saveHtml(context, html, description)
            Result(true, "🌐 Site généré pour « $description ».\n📁 Enregistré dans : $savedPath\n\nOuvre-le depuis ⚙ → Génération → Sites web.", savedPath)
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de la génération du site : ${e.message}")
        }
    }

    private fun saveHtml(context: Context, html: String, description: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "JARVIS-Sites"
        ).also { it.mkdirs() }

        val safeName = description.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim().ifBlank { "site" }
        val fileName = "${fileDateFormat.format(Date())}_$safeName.html"
        val file = File(dir, fileName)
        file.writeText(html)
        return file.absolutePath
    }

    /** Liste les sites déjà générés (les plus récents en premier). */
    fun listGeneratedSites(context: Context): List<File> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "JARVIS-Sites"
        )
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "html" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** URI content:// (via FileProvider) pour ouvrir/partager un site généré. */
    fun getShareableUri(context: Context, file: File): android.net.Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
