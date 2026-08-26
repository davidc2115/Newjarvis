package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebsiteGenController — génère un VRAI site web statique multi-pages (plusieurs fichiers
 * .html distincts reliés par une navigation commune, une feuille de style partagée, un script
 * commun pour les animations) avec de VRAIES images (photos envoyées par l'utilisateur dans le
 * chat + images générées par IA via le même moteur que generate_image — pas de placeholders
 * CSS). Réutilise le fournisseur IA déjà configuré dans JARVIS, aucune clé API supplémentaire.
 *
 * Remplace l'ancienne version (un seul fichier HTML autonome) suite à la demande explicite de
 * l'utilisateur : "plusieurs pages séparées, animation, image". Le site est maintenant un
 * DOSSIER dans Documents/JARVIS-Sites/<nom>/ contenant :
 *   index.html, <autres-pages>.html, styles.css, script.js, dossier images (une par photo), site.json (métadonnées
 *   utilisées par editWebsite pour savoir quelles pages/images existent déjà).
 *
 * Pipeline en plusieurs appels IA plutôt qu'un seul gros prompt monolithique :
 *   1) PLAN : liste des pages + slots d'images nécessaires (JSON)
 *   2) CSS partagé (une seule fois, cohérence garantie entre toutes les pages)
 *   3) Images (une par slot, via ImageGenController — le même moteur que generate_image)
 *   4) Contenu de CHAQUE page (juste le <main>, injecté dans un gabarit Kotlin commun qui
 *      fournit une nav/en-tête/pied de page 100% identiques sur toutes les pages — la nav
 *      n'est PAS régénérée par l'IA à chaque page, ce qui garantirait une incohérence sinon)
 *   5) script.js (statique, écrit une fois en Kotlin : menu mobile, révélation au scroll,
 *      ancre en douceur — logique générique, pas besoin d'IA, zéro risque d'erreur JS)
 */
object WebsiteGenController {

    data class Result(val success: Boolean, val message: String, val filePath: String? = null)

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    // ─────────────────────────────────────────────────────────────────────────
    // Prompts IA
    // ─────────────────────────────────────────────────────────────────────────

    private const val PLAN_INSTRUCTIONS =
        "Tu es un architecte de site web premium. On va te donner une description de site à " +
        "concevoir. Réponds UNIQUEMENT avec un objet JSON valide, AUCUN texte avant/après, AUCUN " +
        "bloc markdown, exactement dans ce format :\n" +
        "{\"siteName\":\"Nom du site\",\"pages\":[{\"file\":\"index\",\"navLabel\":\"Accueil\"}," +
        "{\"file\":\"a-propos\",\"navLabel\":\"À propos\"}],\"images\":[{\"key\":\"hero\"," +
        "\"prompt\":\"detailed english prompt for an AI image generator, photorealistic style\"}]}\n" +
        "RÈGLES : \"pages\" doit TOUJOURS commencer par {\"file\":\"index\",...} (page d'accueil). " +
        "Choisis entre 3 et 6 pages selon la complexité réelle demandée (un site simple type " +
        "vitrine = 3-4 pages, un site plus riche = 5-6), jamais plus. \"file\" en minuscules, " +
        "sans espace ni accent (utilise des tirets). \"images\" : entre 2 et 8 slots d'images " +
        "selon les besoins réels du site (une image héro, des illustrations de sections, une " +
        "galerie si pertinent) — chaque \"prompt\" doit être en ANGLAIS, détaillé (sujet, " +
        "composition, éclairage, ambiance), adapté au style du site (photorealistic pour un " +
        "commerce/restaurant/entreprise, illustration/flat design pour un site plus créatif, " +
        "selon ce qui convient le mieux à la description donnée). INCLUS TOUJOURS un slot " +
        "supplémentaire avec \"key\":\"logo\" en PREMIER dans \"images\" : un logo simple et " +
        "moderne pour le site (icône/emblème minimaliste adapté au nom et au thème, PAS une " +
        "photo, style vectoriel/flat, fond uni ou transparent, composition centrée carrée)."

    private const val CSS_INSTRUCTIONS =
        "Tu es un designer web premium. Génère UNIQUEMENT du CSS pur et valide (pas de balise " +
        "<style>, pas de bloc markdown, pas d'explication) formant une feuille de style complète " +
        "et cohérente pour le site décrit. Elle doit définir précisément ces classes/éléments " +
        "(utilisées telles quelles par toutes les pages du site, ne les renomme jamais) :\n" +
        "- :root avec des variables CSS pour la palette de couleurs (adaptée au sujet/ambiance " +
        "du site, PAS bleu/blanc par défaut) et la typographie (au moins une police Google Fonts " +
        "adaptée au ton, à importer via @import url(...) en haut du fichier)\n" +
        "- body, .container (largeur max centrée avec marges)\n" +
        "- .site-header (bandeau fixe/sticky en haut), .nav (barre de nav), .nav-brand (bloc logo+nom, " +
        "aligné en ligne avec un espacement entre les deux), .nav-logo (image logo dans l'en-tête, " +
        "hauteur fixe autour de 36-40px, largeur auto, border-radius léger), .nav-links (liste de " +
        "liens, cachée en mobile par défaut), .nav-toggle (bouton hamburger, visible seulement en " +
        "mobile), .nav-open (classe ajoutée en JS quand le menu mobile est ouvert — .nav-open " +
        ".nav-links doit alors s'afficher)\n" +
        "- .hero (section héro pleine largeur avec image de fond ou image à côté du texte, titre " +
        "impactant), .hero-image (image héro elle-même, object-fit: cover)\n" +
        "- .section (section de contenu générique avec espacement vertical généreux), .section-title\n" +
        "- .grid, .grid-2, .grid-3 (grilles responsives qui passent en 1 colonne sur mobile)\n" +
        "- .card (carte avec ombre légère, coins arrondis, effet hover subtil)\n" +
        "- .btn, .btn-primary (bouton d'action avec transition hover fluide)\n" +
        "- .site-footer (pied de page complet, couleur de fond distincte)\n" +
        "- .reveal et .reveal.visible (animation d'apparition au scroll : .reveal a opacity:0 et " +
        "une translation, .reveal.visible retombe à opacity:1/translation nulle, avec transition)\n" +
        "- img { max-width: 100%; height: auto; display: block; } et des media queries mobiles " +
        "(max-width: 768px) qui adaptent .nav-links/.grid-2/.grid-3/.hero en conséquence.\n" +
        "Design premium et spécifique au sujet, pas générique."

    private const val PAGE_INSTRUCTIONS =
        "Tu rédiges UNIQUEMENT le contenu HTML à placer entre <main> et </main> pour UNE page " +
        "précise d'un site déjà conçu (pas de <!DOCTYPE>, <html>, <head>, <nav> ni <footer> — ces " +
        "parties existent déjà ailleurs, tu écris SEULEMENT ce qui va dans <main>). Réponds " +
        "UNIQUEMENT avec ce fragment HTML, aucun texte avant/après, aucun bloc markdown. " +
        "EXIGENCES : contenu RÉALISTE et spécifique au sujet (jamais de Lorem ipsum ni de texte " +
        "générique \"Titre 1/Titre 2\" — invente des textes crédibles et pertinents), structure " +
        "en sections sémantiques <section class=\"section reveal\">, utilise les classes CSS déjà " +
        "définies (.grid/.grid-2/.grid-3, .card, .btn .btn-primary, .hero/.hero-image sur la page " +
        "d'accueil uniquement), place les images EXACTEMENT avec les chemins fournis (jamais un " +
        "autre chemin, jamais une image inventée), avec un attribut alt descriptif à chaque fois. " +
        "Si la page est un formulaire de contact, fais un vrai <form> visuellement complet (même " +
        "non fonctionnel côté serveur)."

    // ─────────────────────────────────────────────────────────────────────────
    // Génération complète
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [userImagePaths] : chemins EXACTS de photos déjà envoyées par l'utilisateur dans le chat
     * (ou trouvées via list_files/search_files) à intégrer réellement dans le site, en plus des
     * images générées par IA pour compléter les slots restants du plan.
     */
    suspend fun generateWebsite(context: Context, description: String, userImagePaths: List<String> = emptyList()): Result {
        if (description.isBlank()) return Result(false, "❌ Aucune description de site fournie.")

        return try {
            // 1) Plan du site (pages + slots d'images)
            val planJson = askAi(context, "$PLAN_INSTRUCTIONS\n\nDescription du site demandé : $description")
            val plan = parseJsonLenient(planJson)
                ?: return Result(false, "❌ L'IA n'a pas renvoyé un plan de site exploitable. Réessaie avec une description plus précise.")

            val siteName = plan.optString("siteName", description.take(40)).ifBlank { "Site" }
            val pagesArr = plan.optJSONArray("pages")
            if (pagesArr == null || pagesArr.length() == 0) {
                return Result(false, "❌ L'IA n'a proposé aucune page pour ce site. Réessaie.")
            }
            val pages = mutableListOf<Pair<String, String>>() // (file sans extension, navLabel)
            for (i in 0 until pagesArr.length()) {
                val p = pagesArr.optJSONObject(i) ?: continue
                val file = p.optString("file", "").trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
                val label = p.optString("navLabel", file).ifBlank { file }
                if (file.isNotBlank()) pages.add(file to label)
            }
            if (pages.none { it.first == "index" }) pages.add(0, "index" to "Accueil")
            if (pages.size > 6) pages.subList(6, pages.size).clear()

            // 2) Dossier du site
            val siteDir = createSiteDir(context, siteName)
            val imagesDir = File(siteDir, "images").also { it.mkdirs() }

            // 3) Images : d'abord celles fournies par l'utilisateur (copiées telles quelles),
            // puis génération IA pour les slots restants du plan (même moteur que generate_image
            // — cascade Gemini/OpenAI/HuggingFace/SD local/AI Horde, voir ImageGenController).
            val availableImages = mutableListOf<String>() // noms de fichiers dans images/, pour le prompt des pages
            userImagePaths.forEach { srcPath ->
                try {
                    val src = File(srcPath)
                    if (src.exists()) {
                        val ext = src.extension.ifBlank { "jpg" }
                        val destName = "user-${availableImages.size + 1}.$ext"
                        src.copyTo(File(imagesDir, destName), overwrite = true)
                        availableImages.add(destName)
                    }
                } catch (_: Exception) {}
            }

            val imageSlots = plan.optJSONArray("images")
            val imageFailures = mutableListOf<String>()
            if (imageSlots != null) {
                for (i in 0 until minOf(imageSlots.length(), 8)) {
                    val slot = imageSlots.optJSONObject(i) ?: continue
                    val key = slot.optString("key", "image${i + 1}").ifBlank { "image${i + 1}" }
                    val imgPrompt = slot.optString("prompt", "")
                    if (imgPrompt.isBlank()) continue
                    val genResult = try { ImageGenController.generateImage(context, imgPrompt) } catch (e: Exception) { null }
                    val generatedPath = genResult?.savedPath
                    if (generatedPath != null && File(generatedPath).exists()) {
                        val ext = File(generatedPath).extension.ifBlank { "jpg" }
                        val destName = "$key.$ext"
                        try {
                            File(generatedPath).copyTo(File(imagesDir, destName), overwrite = true)
                            availableImages.add(destName)
                        } catch (_: Exception) { imageFailures.add(key) }
                    } else {
                        imageFailures.add(key)
                    }
                }
            }

            // 4) CSS partagé (une seule fois pour tout le site)
            val cssRaw = askAi(context, "$CSS_INSTRUCTIONS\n\nDescription du site : $description")
            val css = cleanCodeResponse(cssRaw, "css")
            File(siteDir, "styles.css").writeText(css)

            // 5) script.js commun (statique, écrit directement — pas besoin d'IA)
            File(siteDir, "script.js").writeText(SHARED_SCRIPT_JS)

            // 6) Contenu de chaque page, injecté dans le gabarit commun (nav/head/footer garantis
            // identiques sur toutes les pages, générés une seule fois en Kotlin ci-dessous)
            // Le slot "logo" (voir PLAN_INSTRUCTIONS) est traité comme une image de marque
            // pour l'en-tête, pas comme une illustration de contenu — exclu de la liste
            // présentée à l'IA pour le corps des pages, utilisé séparément dans le gabarit.
            val logoFile = availableImages.firstOrNull { it.substringBeforeLast('.') == "logo" }
            val logoPath = logoFile?.let { "images/$it" }
            val contentImages = availableImages.filterNot { it == logoFile }
            val imagesListForPromptFiltered = if (contentImages.isEmpty()) "(aucune image disponible — n'insère aucune balise <img>)"
                else contentImages.joinToString(", ") { "images/$it" }

            for ((file, _) in pages) {
                val pagePrompt = "$PAGE_INSTRUCTIONS\n\n" +
                    "Description globale du site : $description\n" +
                    "Nom du site : $siteName\n" +
                    "Page à rédiger : « $file »" + (if (file == "index") " (page d'accueil — utilise .hero en haut)" else "") + "\n" +
                    "Pages du site (pour référence, ne régénère pas la nav) : ${pages.joinToString(", ") { it.second }}\n" +
                    "Images disponibles (chemins EXACTS à utiliser, n'en invente aucune autre) : $imagesListForPromptFiltered"
                val bodyRaw = askAi(context, pagePrompt)
                val bodyHtml = cleanCodeResponse(bodyRaw, "html")
                val fullPage = buildPageShell(siteName, pages, file, bodyHtml, logoPath)
                File(siteDir, "$file.html").writeText(fullPage)
            }

            // 7) Métadonnées pour editWebsite
            writeSiteManifest(siteDir, siteName, description, pages, availableImages)

            val indexPath = File(siteDir, "index.html").absolutePath
            val imageNote = when {
                imageFailures.isEmpty() && availableImages.isNotEmpty() -> " avec ${availableImages.size} image(s) réelle(s) intégrée(s)"
                imageFailures.isNotEmpty() -> " (⚠️ ${imageFailures.size} image(s) n'ont pas pu être générées, le reste du site est complet)"
                else -> ""
            }
            Result(
                true,
                "🌐 Site généré pour « $description » (${pages.size} page(s)$imageNote).\n📁 Enregistré dans : $indexPath\n\nOuvre-le depuis ⚙ → Génération → Sites web.",
                indexPath
            )
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de la génération du site : ${e.message}")
        }
    }

    /**
     * Modifie une page précise d'un site déjà généré (par défaut index.html) : ne renvoie à
     * l'IA QUE le contenu de <main> de cette page (pas tout le fichier), pour rester cohérent
     * avec le gabarit commun (nav/head/footer/CSS partagés, jamais régénérés ici).
     */
    suspend fun editWebsite(context: Context, existingPath: String, instructions: String): Result {
        if (instructions.isBlank()) return Result(false, "❌ Aucune instruction de modification fournie.")
        val file = File(existingPath)
        if (!file.exists()) return Result(false, "❌ Fichier introuvable : $existingPath (le site a peut-être été supprimé ou déplacé).")

        val siteDir = file.parentFile ?: return Result(false, "❌ Impossible de déterminer le dossier du site.")
        val manifest = readSiteManifest(siteDir)

        val currentHtml = try { file.readText() } catch (e: Exception) {
            return Result(false, "❌ Impossible de lire la page existante : ${e.message}")
        }
        val currentBody = extractMain(currentHtml) ?: currentHtml

        return try {
            val pageFile = file.nameWithoutExtension
            val pagesForPrompt = manifest?.pages?.joinToString(", ") { it.second } ?: pageFile
            val imagesListForPrompt = manifest?.images?.takeIf { it.isNotEmpty() }?.joinToString(", ") { "images/$it" }
                ?: "(utilise uniquement les images déjà présentes dans la page ci-dessous, n'en invente aucune autre)"

            val editPrompt = "$PAGE_INSTRUCTIONS\n\n" +
                "Voici le contenu HTML ACTUEL de cette page (entre <main> et </main>), à modifier :\n\n$currentBody\n\n" +
                "Applique UNIQUEMENT cette modification, en conservant tout le reste à l'identique sauf ce qui doit " +
                "changer : $instructions\n\n" +
                "Pages du site (pour référence) : $pagesForPrompt\n" +
                "Images disponibles : $imagesListForPrompt\n" +
                "Renvoie le contenu HTML COMPLET et mis à jour de <main> (pas juste la partie modifiée)."
            val bodyRaw = askAi(context, editPrompt)
            val newBody = cleanCodeResponse(bodyRaw, "html")

            val siteName = manifest?.siteName ?: pageFile
            val pages = manifest?.pages ?: listOf(pageFile to pageFile)
            val fullPage = buildPageShell(siteName, pages, pageFile, newBody)
            file.writeText(fullPage)
            Result(true, "✏️ Page « $pageFile » modifiée (« $instructions »).\n📁 $existingPath\n\nRecharge la page dans le navigateur pour voir le changement.", existingPath)
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de la modification du site : ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Publication en ligne — GitHub Pages (gratuit, reutilise le jeton GitHub deja configure
    // par l'utilisateur dans les Parametres, aucune cle API supplementaire)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Publie un site déjà généré ([siteDir], le dossier contenant index.html) sur GitHub
     * Pages : crée un dépôt (ou réutilise s'il existe déjà), envoie tous les fichiers du site
     * (HTML/CSS/JS en texte, images en binaire), puis active GitHub Pages sur la branche main.
     * Gratuit et sans clé API additionnelle — utilise le compte GitHub déjà configuré par
     * l'utilisateur (⚙ Paramètres → Clés API → Codage GitHub).
     */
    suspend fun publishToGitHub(
        context: Context,
        siteDir: File,
        repoName: String = "",
        accountLabel: String = "",
        makePrivate: Boolean = false
    ): Result {
        if (!siteDir.exists() || !siteDir.isDirectory) {
            return Result(false, "❌ Dossier du site introuvable : ${siteDir.absolutePath}")
        }
        if (!File(siteDir, "index.html").exists()) {
            return Result(false, "❌ Ce dossier ne contient pas de site généré (index.html manquant).")
        }

        val manifest = readSiteManifest(siteDir)
        val safeRepoName = repoName.ifBlank { manifest?.siteName ?: siteDir.name }
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .trim('-')
            .ifBlank { "jarvis-site" }
            .take(90)

        val login = GitHubController.getLogin(context, accountLabel)
            ?: return Result(false, "❌ Impossible de récupérer le compte GitHub (jeton absent ou invalide). " +
                "Configure un jeton dans ⚙ Paramètres → Clés API → Codage GitHub, ou précise le bon compte.")

        // Crée le dépôt — si le nom existe déjà sur ce compte (422), on réutilise simplement
        // le dépôt existant plutôt que d'échouer (permet de republier un site déjà publié).
        val createMsg = GitHubController.createRepo(context, safeRepoName, "Site web généré par JARVIS", makePrivate, accountLabel)
        if (createMsg.startsWith("❌") && !createMsg.contains("already exists", ignoreCase = true) && !createMsg.contains("422")) {
            return Result(false, "❌ Impossible de créer le dépôt « $safeRepoName » : $createMsg")
        }

        val textExtensions = setOf("html", "css", "js", "json", "txt", "md")
        var uploaded = 0
        var failed = 0
        siteDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relPath = file.relativeTo(siteDir).path.replace(File.separator, "/")
            val commitMsg = "Site JARVIS : $relPath"
            val result = if (file.extension.lowercase() in textExtensions) {
                GitHubController.createOrUpdateFile(context, login, safeRepoName, relPath, file.readText(), commitMsg, "main", accountLabel)
            } else {
                GitHubController.createOrUpdateBinaryFile(context, login, safeRepoName, relPath, file.readBytes(), commitMsg, "main", accountLabel)
            }
            if (result.startsWith("✅")) uploaded++ else failed++
        }

        if (uploaded == 0) {
            return Result(false, "❌ Aucun fichier n'a pu être envoyé sur GitHub (dépôt « $safeRepoName »). " +
                "Vérifie les droits d'écriture du jeton avec github_test_access.")
        }

        val pagesMsg = GitHubController.enablePages(context, login, safeRepoName, "main", "/", accountLabel)
        val pageUrl = Regex("https://\\S+").find(pagesMsg)?.value?.trimEnd('.', ')', '»', ':')

        val failNote = if (failed > 0) " (⚠️ $failed fichier(s) sur ${uploaded + failed} n'ont pas pu être envoyés)" else ""
        return if (pageUrl != null) {
            Result(
                true,
                "🌐 Site publié en ligne via GitHub Pages$failNote !\n🔗 $pageUrl\n\n" +
                    "📦 Dépôt : https://github.com/$login/$safeRepoName\n\n" +
                    "⏳ GitHub Pages peut prendre 1 à 2 minutes avant d'être accessible la toute première fois.",
                pageUrl
            )
        } else {
            Result(
                false,
                "⚠️ Fichiers envoyés sur GitHub$failNote, mais l'activation de GitHub Pages a échoué : $pagesMsg\n" +
                    "📦 Dépôt : https://github.com/$login/$safeRepoName"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gabarit HTML commun (nav/head/footer identiques sur toutes les pages)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPageShell(siteName: String, pages: List<Pair<String, String>>, currentFile: String, bodyHtml: String, logoPath: String? = null): String {
        val navLinks = pages.joinToString("\n            ") { (file, label) ->
            val href = "$file.html"
            val active = if (file == currentFile) " class=\"active\"" else ""
            "<a href=\"$href\"$active>${escapeHtml(label)}</a>"
        }
        return """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(siteName)}</title>
<link rel="stylesheet" href="styles.css">
</head>
<body>
<header class="site-header">
    <div class="container nav">
        <div class="nav-brand">${if (logoPath != null) "<img src=\"$logoPath\" alt=\"${escapeHtml(siteName)}\" class=\"nav-logo\"> " else ""}<span>${escapeHtml(siteName)}</span></div>
        <button class="nav-toggle" aria-label="Menu">☰</button>
        <nav class="nav-links">
            $navLinks
        </nav>
    </div>
</header>
$bodyHtml
<footer class="site-footer">
    <div class="container">
        <p>&copy; ${SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())} ${escapeHtml(siteName)}. Tous droits réservés.</p>
        <p>Site généré par JARVIS.</p>
    </div>
</footer>
<script src="script.js"></script>
</body>
</html>"""
    }

    private const val SHARED_SCRIPT_JS = """
// Menu mobile
document.addEventListener('DOMContentLoaded', function () {
    var toggle = document.querySelector('.nav-toggle');
    var header = document.querySelector('.site-header');
    if (toggle && header) {
        toggle.addEventListener('click', function () {
            header.classList.toggle('nav-open');
        });
    }

    // Ancres en douceur
    document.querySelectorAll('a[href^="#"]').forEach(function (link) {
        link.addEventListener('click', function (e) {
            var target = document.querySelector(link.getAttribute('href'));
            if (target) {
                e.preventDefault();
                target.scrollIntoView({ behavior: 'smooth' });
            }
        });
    });

    // Révélation au scroll
    var revealEls = document.querySelectorAll('.reveal');
    if ('IntersectionObserver' in window && revealEls.length) {
        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (entry.isIntersecting) {
                    entry.target.classList.add('visible');
                    observer.unobserve(entry.target);
                }
            });
        }, { threshold: 0.15 });
        revealEls.forEach(function (el) { observer.observe(el); });
    } else {
        revealEls.forEach(function (el) { el.classList.add('visible'); });
    }
});
"""

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitaires
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun askAi(context: Context, prompt: String): String {
        val history = listOf(HistoryEntry(role = "user", text = prompt))
        return ApiClient.sendChat(context, history).text
    }

    /** Nettoie un bloc markdown ```lang / ```html / ```css / ```json éventuel. */
    private fun cleanCodeResponse(raw: String, lang: String): String {
        var text = raw.trim()
        val fenceStart = Regex("^```(?:$lang|html|css|json)?\\s*", RegexOption.IGNORE_CASE)
        text = fenceStart.replace(text, "")
        text = text.removeSuffix("```").trim()
        return text
    }

    private fun parseJsonLenient(raw: String): JSONObject? {
        val cleaned = cleanCodeResponse(raw, "json")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return try { JSONObject(cleaned.substring(start, end + 1)) } catch (e: Exception) { null }
    }

    private fun extractMain(html: String): String? {
        val start = html.indexOf("<main")
        if (start < 0) return null
        val startTagEnd = html.indexOf('>', start)
        if (startTagEnd < 0) return null
        val end = html.lastIndexOf("</main>")
        if (end < 0 || end <= startTagEnd) return null
        return html.substring(startTagEnd + 1, end).trim()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sitesRootDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JARVIS-Sites")

    private fun createSiteDir(context: Context, siteName: String): File {
        val root = sitesRootDir().also { it.mkdirs() }
        val safeName = siteName.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim().ifBlank { "site" }
        val dirName = "${fileDateFormat.format(Date())}_$safeName"
        return File(root, dirName).also { it.mkdirs() }
    }

    private fun writeSiteManifest(siteDir: File, siteName: String, description: String, pages: List<Pair<String, String>>, images: List<String>) {
        val json = JSONObject()
        json.put("siteName", siteName)
        json.put("description", description)
        val pagesArr = JSONArray()
        pages.forEach { (file, label) -> pagesArr.put(JSONObject().put("file", file).put("navLabel", label)) }
        json.put("pages", pagesArr)
        val imagesArr = JSONArray()
        images.forEach { imagesArr.put(it) }
        json.put("images", imagesArr)
        try { File(siteDir, "site.json").writeText(json.toString(2)) } catch (_: Exception) {}
    }

    private data class SiteManifest(val siteName: String, val pages: List<Pair<String, String>>, val images: List<String>)

    private fun readSiteManifest(siteDir: File): SiteManifest? {
        val f = File(siteDir, "site.json")
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val siteName = json.optString("siteName", siteDir.name)
            val pages = mutableListOf<Pair<String, String>>()
            json.optJSONArray("pages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    pages.add(p.optString("file", "") to p.optString("navLabel", ""))
                }
            }
            val images = mutableListOf<String>()
            json.optJSONArray("images")?.let { arr -> for (i in 0 until arr.length()) images.add(arr.optString(i)) }
            SiteManifest(siteName, pages, images)
        } catch (e: Exception) { null }
    }

    /**
     * Liste les pages d'accueil (index.html) des sites déjà générés, les plus récents en
     * premier — un fichier ouvrable directement (pas le dossier), pour rester compatible avec
     * l'ouverture via FileProvider/ACTION_VIEW côté UI.
     */
    fun listGeneratedSites(context: Context): List<File> {
        val dir = sitesRootDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isDirectory && File(f, "index.html").exists() }
            ?.sortedByDescending { it.lastModified() }
            ?.map { File(it, "index.html") }
            ?: emptyList()
    }

    /** URI content:// (via FileProvider) pour ouvrir/partager la page d'accueil d'un site généré. */
    fun getShareableUri(context: Context, file: File): android.net.Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // BUG RÉEL CORRIGÉ : ouvrir index.html seul via FileProvider/ACTION_VIEW ne donne au
    // navigateur une permission de lecture QUE sur ce fichier précis — styles.css, script.js,
    // les images/ et les autres pages (liens relatifs entre pages) restent inaccessibles
    // (chaque ressource relative nécessiterait sa propre permission content://, jamais
    // accordée). Résultat observé : "le site créé n'a pas d'image, pas de logo, une seule
    // page" alors que le site généré sur le disque est bien complet et multi-pages — c'est
    // uniquement la façon de l'OUVRIR qui cassait tout. On sert donc le dossier entier via le
    // même serveur web local que start_local_web_server (java.net.ServerSocket, déjà présent
    // et fonctionnel), puis on ouvre une vraie URL http://127.0.0.1 dessus — les chemins
    // relatifs se résolvent alors normalement, exactement comme un site vraiment hébergé.
    fun openInBrowserViaLocalServer(context: Context, siteDir: File): String {
        if (!siteDir.exists() || !File(siteDir, "index.html").exists()) {
            return "❌ Site introuvable ou incomplet : ${siteDir.absolutePath}"
        }
        val startMsg = LocalWebServerController.start(context, siteDir)
        if (startMsg.startsWith("❌")) return startMsg
        // Le serveur démarre sur un thread séparé (voir LocalWebServerService.startServer) —
        // quasi instantané mais pas garanti terminé au retour de start(), d'où cette courte
        // marge avant d'ouvrir le navigateur dessus.
        Thread.sleep(400)
        val port = LocalWebServerService.currentPort.takeIf { it != 0 } ?: 8080
        val url = "http://127.0.0.1:$port/"
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "🌐 Site ouvert dans le navigateur : $url\nLe serveur local reste actif pour que tout (styles, images, navigation entre pages) s'affiche correctement — utilise stop_local_web_server une fois fini, sinon il continue de tourner en arrière-plan (consomme de la batterie)."
        } catch (e: Exception) {
            "❌ Impossible d'ouvrir le navigateur : ${e.message}. Le site reste accessible sur $url tant que le serveur local tourne."
        }
    }
}
