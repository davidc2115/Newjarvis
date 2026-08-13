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

    private const val GEN_INSTRUCTIONS = "Tu es un générateur de sites web haut de gamme, niveau agence web premium. " +
        "On va te donner une description de site. Réponds UNIQUEMENT avec le code source complet d'une seule page " +
        "HTML autonome (CSS dans une balise <style>, JS dans une balise <script>, tout inclus dans le fichier — " +
        "seules dépendances externes autorisées : polices Google Fonts, icônes via CDN comme Font Awesome ou " +
        "Lucide, et éventuellement une librairie d'animation légère comme AOS via CDN). " +
        "EXIGENCES DE QUALITÉ (obligatoires, pas optionnelles) : " +
        "1) Structure complète et cohérente avec le type de site demandé — pense comme un vrai site professionnel : " +
        "barre de navigation fixe/sticky avec menu responsive (hamburger sur mobile), section héro percutante avec " +
        "accroche claire et bouton d'action, sections de contenu pertinentes selon le sujet (services/produits, " +
        "à propos, points forts/avantages, galerie ou portfolio si pertinent, témoignages si pertinent, tarifs si " +
        "pertinent), section contact avec formulaire (même non fonctionnel côté serveur, visuellement complet) et " +
        "coordonnées, pied de page complet (liens, réseaux sociaux, mentions). " +
        "2) Contenu RÉALISTE et spécifique au sujet demandé — jamais de \"Lorem ipsum\" ni de texte générique type " +
        "\"Titre 1 / Titre 2\", invente des textes crédibles et pertinents (accroches, descriptions, témoignages) " +
        "qui correspondent vraiment à la description donnée. " +
        "3) Design premium : palette de couleurs cohérente et réfléchie (pas juste bleu/blanc par défaut — adapte " +
        "au secteur/ambiance demandée), typographie soignée avec au moins une police Google Fonts adaptée au ton, " +
        "espacements généreux et hiérarchie visuelle claire, ombres/dégradés subtils, icônes pour illustrer les " +
        "points clés, images en CSS (gradients, formes) ou placeholders visuels soignés si pas d'images réelles " +
        "disponibles. " +
        "4) Interactivité : animations d'apparition au scroll, transitions/hover fluides sur les boutons et cartes, " +
        "menu mobile fonctionnel en JS, défilement fluide (smooth scroll) vers les ancres. " +
        "5) Responsive réel : testé mentalement en largeur mobile (375px), tablette et desktop, pas juste une " +
        "media query alibi. " +
        "6) Accessibilité de base : contrastes suffisants, attributs alt sur les éléments visuels, structure " +
        "sémantique HTML5 (header/nav/main/section/footer). " +
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
            val html = cleanHtmlResponse(chatResult.text)

            if (!html.contains("<html", ignoreCase = true)) {
                return Result(false, "❌ La réponse de l'IA ne ressemble pas à une page HTML valide. Réessaie avec une description plus précise, ou vérifie qu'un provider IA est bien configuré (⚙ → Config).")
            }

            val savedPath = saveHtml(context, html, description)
            Result(true, "🌐 Site généré pour « $description ».\n📁 Enregistré dans : $savedPath\n\nOuvre-le depuis ⚙ → Génération → Sites web.", savedPath)
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de la génération du site : ${e.message}")
        }
    }

    /**
     * Modifie un site déjà généré : renvoie le HTML existant à l'IA avec une instruction
     * de modification précise, et écrase le fichier avec le résultat mis à jour (édition
     * en place — pas une nouvelle génération, pour garder la même URL/fichier partagé).
     */
    suspend fun editWebsite(context: Context, existingPath: String, instructions: String): Result {
        if (instructions.isBlank()) return Result(false, "❌ Aucune instruction de modification fournie.")
        val file = File(existingPath)
        if (!file.exists()) return Result(false, "❌ Fichier introuvable : $existingPath (le site a peut-être été supprimé ou déplacé).")

        val currentHtml = try { file.readText() } catch (e: Exception) {
            return Result(false, "❌ Impossible de lire le site existant : ${e.message}")
        }

        return try {
            val editPrompt = "$GEN_INSTRUCTIONS\n\n" +
                "Voici le code HTML COMPLET d'un site déjà généré, à modifier :\n\n$currentHtml\n\n" +
                "Applique UNIQUEMENT cette modification demandée, en conservant tout le reste du site à " +
                "l'identique (structure, contenu, style) sauf ce qui doit changer : $instructions\n\n" +
                "Renvoie le code source HTML COMPLET et autonome du site mis à jour (pas juste la partie modifiée, " +
                "pas de diff — le fichier entier, prêt à remplacer l'ancien)."
            val history = listOf(HistoryEntry(role = "user", text = editPrompt))
            val chatResult = ApiClient.sendChat(context, history)
            val html = cleanHtmlResponse(chatResult.text)

            if (!html.contains("<html", ignoreCase = true)) {
                return Result(false, "❌ La réponse de l'IA ne ressemble pas à une page HTML valide pour cette modification. Réessaie en précisant davantage.")
            }

            file.writeText(html)
            Result(true, "✏️ Site modifié (« $instructions ») dans : $existingPath\n\nRecharge la page dans le navigateur pour voir le changement.", existingPath)
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de la modification du site : ${e.message}")
        }
    }

    /** Nettoie un éventuel bloc markdown ```html si l'IA en a quand même mis un. */
    private fun cleanHtmlResponse(raw: String): String =
        raw.trim()
            .removePrefix("```html").removePrefix("```HTML").removePrefix("```")
            .removeSuffix("```")
            .trim()

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
