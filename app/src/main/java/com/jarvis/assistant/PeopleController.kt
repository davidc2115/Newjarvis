package com.jarvis.assistant

import android.content.Context
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fiches contacts enrichies (catégorie, téléphone, email, adresse, GPS)
 * stockées directement comme notes Markdown dans le dossier "Contacts" du
 * vault Obsidian de l'utilisateur (voir ObsidianController.getVaultRoot).
 * Contrairement au carnet d'adresses natif du téléphone, ces fiches sont
 * visibles et éditables directement dans l'app Obsidian, pas cachées dans
 * une base de données.
 *
 * Format de chaque fiche (Contacts/Nom.md) :
 * ---
 * category: travail
 * phone: "0612345678"
 * email: pierre@exemple.com
 * address: "12 rue de Paris, 75001 Paris"
 * latitude: 48.8566
 * longitude: 2.3522
 * updated: 2026-08-12 14:30
 * tags: [jarvis, contact]
 * ---
 *
 * # Pierre Dupont
 *
 * Notes libres ici...
 */
object PeopleController {

    private val VALID_CATEGORIES = setOf("travail", "personnel", "famille", "client", "autre")
    private val updatedFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // BUG RÉEL CORRIGÉ : la catégorie était forcée à "autre" dès qu'elle ne correspondait pas
    // MOT POUR MOT à une des 5 valeurs de VALID_CATEGORIES (ex: "professionnel", "boulot",
    // "collègue", "ami" étaient tous silencieusement rangés dans "autre" à l'enregistrement).
    // Pire : la LECTURE (getContactsByCategory / listByCategory) comparait le paramètre
    // "category" reçu de l'IA à cette même liste stricte SANS repasser par cette normalisation
    // — donc même quand l'IA redemandait avec un mot légèrement différent de celui utilisé à
    // l'enregistrement (ex: sauvegardé sous "travail" mais recherché comme "professionnel"),
    // le filtre ne trouvait rien et JARVIS répondait "aucun contact dans cette catégorie",
    // alors que les fiches existaient bel et bien. En centralisant la normalisation ici et en
    // l'appliquant À LA FOIS à l'écriture et à la lecture, save et recherche restent toujours
    // cohérents entre eux, quel que soit le mot exact employé par l'utilisateur/l'IA.
    // BUG RÉEL CORRIGÉ : le test "pro" en sous-chaîne SUR LA CHAÎNE ENTIÈRE faisait matcher
    // n'importe quel mot contenant "pro" ailleurs qu'au début — "ami proche" (à cause de
    // "proche"), "propriétaire", "produit"... tombaient tous à tort dans "travail" au lieu
    // de "personnel"/"autre". Découpe désormais en MOTS et teste chaque mot individuellement
    // (via "profession" plutôt que le trop court "pro"), ce qui élimine ces faux positifs
    // tout en couvrant toujours "professionnel(le)(s)".
    private fun normalizeCategoryToken(raw: String): String? {
        val c = raw.lowercase().trim()
        if (c.isBlank()) return null
        if (c in VALID_CATEGORIES) return c
        val words = c.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        fun matches(vararg needles: String) = words.any { w -> needles.any { w.contains(it) } }
        // Catégories PERSONNALISÉES : demandé explicitement — un mot qui ne correspond à
        // aucun des 4 synonymes ci-dessous n'est plus forcé dans "autre", il devient sa
        // propre catégorie telle quelle (ex: "voisins", "sport"). "autre" reste réservé au
        // cas où aucune catégorie n'est fournie du tout (voir normalizeCategory ci-dessous),
        // pas un fourre-tout pour "je ne reconnais pas ce mot".
        return when {
            matches("trav", "boulot", "bureau", "collèg", "colleg", "business", "profession") -> "travail"
            matches("perso", "ami") -> "personnel"
            matches("famil") -> "famille"
            matches("client") -> "client"
            else -> c
        }
    }

    // Un contact peut appartenir à PLUSIEURS catégories à la fois (ex: "famille, travail") —
    // demandé explicitement pour ne pas devoir choisir une seule étiquette par personne.
    // Sépare sur virgule / slash / " et ", normalise chaque terme individuellement (sans
    // jamais insérer "autre" pour un simple espace superflu entre séparateurs), déduplique
    // en conservant l'ordre. Toujours au moins une catégorie en sortie ("autre" par défaut).
    private fun normalizeCategories(raw: String): List<String> {
        val tokens = raw.split(Regex("[,/]|\\bet\\b", RegexOption.IGNORE_CASE))
            .mapNotNull { normalizeCategoryToken(it) }
            .distinct()
        return tokens.ifEmpty { listOf("autre") }
    }

    private fun normalizeCategory(raw: String): String = normalizeCategoryToken(raw) ?: "autre"

    private fun contactsFolder(context: Context): File =
        File(ObsidianController.getVaultRoot(context), "Contacts").also { it.mkdirs() }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()

    /** Casse/accents/espaces normalisés pour détecter qu'un nom désigne le même contact. */
    private fun normalizeName(name: String): String =
        java.text.Normalizer.normalize(name.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    // BUG RÉEL CORRIGÉ : findExactNameMatch() (utilisée à l'ÉCRITURE par saveContact) était
    // volontairement stricte (égalité exacte normalisée uniquement), alors que findContact()
    // plus bas (utilisée à la LECTURE par get_contact_details/search_contact_profile/...)
    // acceptait déjà une correspondance en sous-chaîne. Résultat concret : demander de
    // compléter "Jean" après avoir enregistré "Jean Dupont" (catégorie famille) créait un
    // SECOND fichier "Jean.md" orphelin — catégorie "autre" par défaut puisqu'aucune fiche
    // existante n'était trouvée à l'écriture — pendant que relire "Jean" retrouvait toujours
    // l'original correct via la recherche tolérante. D'où une fiche "coincée sur autre" ou
    // "correcte" selon la formulation exacte employée pour la retrouver, alors qu'il
    // s'agissait en réalité de deux fichiers différents pour la même personne. Écriture et
    // lecture utilisent maintenant EXACTEMENT la même résolution (voir resolveContactFile).
    private fun findExactNameMatch(context: Context, name: String): File? = resolveContactFile(context, name)

    /**
     * Résout le fichier correspondant à [name] — logique UNIQUE partagée par la lecture
     * (findContact) et l'écriture (saveContact/addVisit/addAttachment), pour qu'un nom
     * formulé différemment d'un tour à l'autre résolve toujours vers LE MÊME fichier des
     * deux côtés (voir le commentaire de findExactNameMatch ci-dessus pour le bug que ça
     * corrige). Ordre : 1) nom de fichier littéral exact, 2) nom normalisé (accents/casse
     * ignorés) strictement égal, 3) repli tolérant en sous-chaîne dans un sens ou l'autre.
     */
    private fun resolveContactFile(context: Context, name: String): File? {
        val folder = contactsFolder(context)
        File(folder, "${safeFileName(name)}.md").let { if (it.exists()) return it }

        val target = normalizeName(name)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        files.firstOrNull { normalizeName(it.nameWithoutExtension) == target }?.let { return it }

        return files.firstOrNull { f ->
            val n = normalizeName(f.nameWithoutExtension)
            n.contains(target) || target.contains(n)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture / écriture d'une fiche
    // ─────────────────────────────────────────────────────────────────────────

    private const val VISITS_MARKER = "## Historique des rendez-vous"
    private const val ATTACHMENTS_MARKER = "## 📎 Pièces jointes"
    // BUG RÉEL CORRIGÉ : ouvert directement dans Obsidian, un fichier de fiche contact ne
    // montrait quasiment que le bloc YAML (frontmatter) suivi d'une note brute — toute la
    // mise en forme riche (emojis, gras, sections Personnel/Professionnel) n'existait QUE de
    // façon éphémère dans les réponses du chat (formatFullDetails), jamais enregistrée dans
    // le fichier réel. COORDS_MARKER délimite un bloc markdown enrichi, entièrement
    // RÉGÉNÉRÉ à partir des données du frontmatter à CHAQUE écriture (jamais lu comme du
    // texte libre modifiable), pour que la fiche soit un vrai document markdown agréable à
    // lire tel quel, en plus des infos structurées du frontmatter.
    private const val COORDS_MARKER = "## 📇 Coordonnées"

    private data class ContactNote(
        val name: String,
        val category: String,
        val nickname: String?,
        val phone: String?,
        val phonePro: String?,
        val email: String?,
        val address: String?,
        val addressPro: String?,
        val birthday: String?,
        val company: String?,
        val position: String?,
        val latitude: Double?,
        val longitude: Double?,
        val installDate: String?,
        val notes: String,
        val visits: List<String>,
        val attachments: List<String>,
        val file: File
    )

    /**
     * Extrait les lignes "- ..." de la section commençant par [marker] dans [text] (jusqu'au
     * prochain "## ..." ou la fin), et renvoie séparément le texte SANS aucune des sections
     * connues (notes pures). Générique pour ne pas dupliquer cette logique entre visites et
     * pièces jointes, et pour rester correct quel que soit l'ordre d'écriture des sections.
     */
    private fun extractMarkerSection(text: String, marker: String): List<String> {
        val idx = text.indexOf(marker)
        if (idx < 0) return emptyList()
        val after = text.substring(idx + marker.length)
        val nextMarkerIdx = after.indexOf("\n## ")
        val sectionText = if (nextMarkerIdx >= 0) after.substring(0, nextMarkerIdx) else after
        return sectionText.lines().map { it.trim() }.filter { it.startsWith("-") }.map { it.removePrefix("-").trim() }
    }

    private fun notesWithoutKnownSections(text: String): String {
        val cut = listOf(COORDS_MARKER, VISITS_MARKER, ATTACHMENTS_MARKER)
            .mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }
            .minOrNull() ?: text.length
        return text.substring(0, cut).trim()
    }

    /** Lignes de frontmatter YAML — centralisé pour que saveContact/addVisit/addAttachment
     * écrivent toujours exactement les mêmes champs, dans le même ordre. */
    private fun buildFrontmatterLines(
        cat: String, nickname: String?, phone: String?, phonePro: String?, email: String?,
        address: String?, addressPro: String?, birthday: String?, company: String?,
        position: String?, latitude: Double?, longitude: Double?, installDate: String?
    ): List<String> {
        val lines = mutableListOf("category: $cat")
        nickname?.let { lines.add("nickname: \"$it\"") }
        phone?.let { lines.add("phone: \"$it\"") }
        phonePro?.let { lines.add("phone_pro: \"$it\"") }
        email?.let { lines.add("email: \"$it\"") }
        address?.let { lines.add("address: \"$it\"") }
        addressPro?.let { lines.add("address_pro: \"$it\"") }
        birthday?.let { lines.add("birthday: \"$it\"") }
        company?.let { lines.add("company: \"$it\"") }
        position?.let { lines.add("position: \"$it\"") }
        latitude?.let { lines.add("latitude: $it") }
        longitude?.let { lines.add("longitude: $it") }
        installDate?.let { lines.add("install_date: \"$it\"") }
        lines.add("updated: ${updatedFormat.format(Date())}")
        lines.add("tags: [jarvis, contact]")
        return lines
    }

    /**
     * Bloc markdown enrichi reconstruit à partir des mêmes données que le frontmatter — voir
     * COORDS_MARKER ci-dessus pour le pourquoi (toujours régénéré, jamais du texte libre
     * round-trippé). Groupé PERSONNEL / PROFESSIONNEL avec sous-titres en gras et séparateur,
     * pour que le rendu ressemble aux notes riches que JARVIS sait déjà produire ailleurs
     * (obsidian_create_note) plutôt qu'à une simple liste plate d'étiquettes.
     */
    private fun buildCoordsSection(
        cat: String, nickname: String?, phone: String?, phonePro: String?, email: String?,
        address: String?, addressPro: String?, birthday: String?, company: String?,
        position: String?, latitude: Double?, longitude: Double?, installDate: String?
    ): String {
        // COORDS_MARKER doit rester le TOUT PREMIER caractère de cette chaîne (aucun préfixe,
        // même pas un simple saut de ligne supplémentaire) : notesWithoutKnownSections() coupe
        // le texte au premier indexOf(COORDS_MARKER) et traite tout ce qui précède comme les
        // notes libres de l'utilisateur. Un séparateur "---" ajouté AVANT le marqueur serait
        // donc silencieusement absorbé dans les notes et RÉAPPARAÎTRAIT dupliqué à chaque
        // sauvegarde suivante (c'est exactement le bug de duplication déjà corrigé ailleurs
        // dans ce fichier, voir findExactNameMatch). Tout ce qui suit le marqueur, en revanche,
        // est entièrement régénéré à chaque fois et jamais relu comme donnée — c'est là, et
        // seulement là, qu'on peut se permettre des séparateurs "---" et des sous-titres ### .
        val sb = StringBuilder("$COORDS_MARKER\n\n")
        sb.append("🏷️ **Catégorie** : ${categoryLabel(cat)}\n")

        val personalLines = mutableListOf<String>()
        nickname?.let { personalLines.add("💬 Surnom : $it") }
        birthday?.let { personalLines.add("🎂 Anniversaire : $it") }
        phone?.let { personalLines.add("📞 Téléphone : $it") }
        email?.let { personalLines.add("✉️ Email : $it") }
        address?.let { personalLines.add("🏠 Adresse : $it") }

        val proLines = mutableListOf<String>()
        company?.let { proLines.add("🏢 Entreprise : $it") }
        position?.let { proLines.add("🧑‍💼 Poste : $it") }
        phonePro?.let { proLines.add("📱 Téléphone pro : $it") }
        addressPro?.let { proLines.add("📍 Adresse pro : $it") }

        val hasGpsOrInstall = (latitude != null && longitude != null) || installDate != null

        // ### (vrai sous-titre Markdown, rendu en gras + taille agrandie par Obsidian) au lieu
        // d'un simple "🔹 **texte**" — se rapproche visuellement des vraies notes riches (ex:
        // obsidian_create_note) plutôt que de rester une liste plate avec du gras isolé.
        if (personalLines.isNotEmpty()) {
            sb.append("\n### 🔹 Personnel\n")
            personalLines.forEach { sb.append("$it\n") }
            if (proLines.isNotEmpty() || hasGpsOrInstall) sb.append("\n---\n")
        }
        if (proLines.isNotEmpty()) {
            sb.append("\n### 🔹 Professionnel\n")
            proLines.forEach { sb.append("$it\n") }
            if (hasGpsOrInstall) sb.append("\n---\n")
        }
        if (hasGpsOrInstall) {
            sb.append("\n")
            if (latitude != null && longitude != null) sb.append("🌐 GPS : $latitude, $longitude\n")
            installDate?.let { sb.append("📆 Date d'installation : $it\n") }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Contenu markdown COMPLET d'une fiche (frontmatter + titre + notes + coordonnées +
     * historique + pièces jointes) — UNIQUE point de construction, partagé par saveContact,
     * addVisit, addAttachment ET refreshAllContacts. Avant cette extraction, chacune de ces
     * 4 fonctions reconstruisait ce même buildString séparément : elles avaient déjà été
     * alignées à la main lors des derniers correctifs de richesse des fiches, mais rien
     * n'empêchait qu'elles redivergent silencieusement au prochain changement fait dans une
     * seule des 4 copies — cause plausible d'une partie de l'incohérence "les fiches ne se
     * ressemblent pas entre elles" signalée en observant le vault directement dans Obsidian.
     * Centraliser ici rend cette divergence structurellement impossible.
     */
    private fun renderContactMarkdown(
        canonicalName: String, cat: String, nickname: String?, phone: String?, phonePro: String?,
        email: String?, address: String?, addressPro: String?, birthday: String?, company: String?,
        position: String?, latitude: Double?, longitude: Double?, installDate: String?,
        notes: String, visits: List<String>, attachments: List<String>
    ): String {
        val frontmatterLines = buildFrontmatterLines(
            cat, nickname, phone, phonePro, email, address, addressPro, birthday, company,
            position, latitude, longitude, installDate
        )
        return buildString {
            append("---\n")
            append(frontmatterLines.joinToString("\n"))
            append("\n---\n\n")
            append("# 🪪 $canonicalName\n\n")
            if (notes.isNotBlank()) append(notes) else append("_Aucune note._")
            append("\n\n")
            append(buildCoordsSection(
                cat, nickname, phone, phonePro, email, address, addressPro, birthday, company,
                position, latitude, longitude, installDate
            ))
            if (visits.isNotEmpty()) {
                append("\n\n$VISITS_MARKER\n")
                visits.forEach { append("- $it\n") }
            }
            if (attachments.isNotEmpty()) {
                append("\n\n$ATTACHMENTS_MARKER\n")
                attachments.forEach { append("- $it\n") }
            }
        }
    }

    /** Version publique légère (pour l'export KML notamment), sans les détails internes de parsing. */
    data class ContactSummary(
        val name: String,
        val category: String,
        val address: String?,
        val latitude: Double?,
        val longitude: Double?
    )

    /** Parse une fiche existante (frontmatter simple clé: valeur + corps libre). */
    private fun parseContactFile(file: File): ContactNote? {
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: Exception) { return null }

        val parts = text.split("---").filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val frontmatter = parts[0]
        val body = parts.drop(1).joinToString("---").trim()

        // Une IA écrit parfois littéralement "null"/"N/A"/"aucun" dans un champ au lieu de
        // l'omettre — sans ce filtre, une fiche déjà enregistrée avec ce genre de valeur
        // continuerait à afficher "Téléphone perso : null" indéfiniment. Traité comme une
        // vraie absence, au même titre qu'un champ vide.
        val blankPlaceholders = setOf("null", "n/a", "na", "none", "aucun", "aucune", "non renseigné", "non renseigne", "inconnu", "-", "?")
        fun field(key: String): String? {
            val regex = Regex("^$key:\\s*\"?([^\"\\n]*)\"?\\s*$", RegexOption.MULTILINE)
            val value = regex.find(frontmatter)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: return null
            return if (value.lowercase() in blankPlaceholders) null else value
        }

        val bodyWithoutTitle = body.lines().dropWhile { it.startsWith("#") || it.isBlank() }.joinToString("\n").trim()

        val notesOnly = notesWithoutKnownSections(bodyWithoutTitle)
        val visits = extractMarkerSection(bodyWithoutTitle, VISITS_MARKER)
        val attachments = extractMarkerSection(bodyWithoutTitle, ATTACHMENTS_MARKER)

        return ContactNote(
            name = file.nameWithoutExtension,
            category = field("category") ?: "autre",
            nickname = field("nickname"),
            phone = field("phone"),
            phonePro = field("phone_pro"),
            email = field("email"),
            address = field("address"),
            addressPro = field("address_pro"),
            birthday = field("birthday"),
            company = field("company"),
            position = field("position"),
            latitude = field("latitude")?.toDoubleOrNull(),
            longitude = field("longitude")?.toDoubleOrNull(),
            installDate = field("install_date"),
            notes = notesOnly,
            visits = visits,
            attachments = attachments,
            file = file
        )
    }

    fun saveContact(
        context: Context,
        name: String,
        category: String? = null,
        nickname: String? = null,
        phone: String? = null,
        phonePro: String? = null,
        email: String? = null,
        address: String? = null,
        addressPro: String? = null,
        birthday: String? = null,
        company: String? = null,
        position: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        installDate: String? = null,
        notes: String? = null
    ): String {
        if (name.isBlank()) return "❌ Nom du contact manquant."
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()

        return try {
            val folder = contactsFolder(context)
            // Réutilise une fiche existante dont le nom correspond exactement (casse/accents
            // ignorés) plutôt que d'en créer une nouvelle sous une orthographe légèrement
            // différente — évite de fragmenter les infos d'un même contact dans plusieurs
            // fichiers séparés au fil des enregistrements successifs.
            val file = findExactNameMatch(context, name) ?: File(folder, "${safeFileName(name)}.md")
            val existing = parseContactFile(file)
            val isUpdate = existing != null

            // BUG RÉEL CORRIGÉ : quand save_contact_profile est rappelé pour compléter UNE
            // seule info (ex: juste le téléphone) sans repréciser "category", le paramètre
            // recevait par défaut la valeur "autre" côté appelant — qui était alors écrite
            // TELLE QUELLE, effaçant silencieusement une catégorie correcte déjà enregistrée
            // (ex: "famille" -> "autre" au prochain appel qui ne reparle pas de catégorie).
            // C'était une cause directe de "catégorie introuvable ensuite". Comme les autres
            // champs (nickname, phone, ...), la catégorie n'est désormais réécrite QUE si elle
            // est explicitement fournie ; sinon celle déjà enregistrée est conservée. Accepte
            // aussi plusieurs catégories à la fois (ex: "famille, travail").
            val cat = if (category.isNullOrBlank()) (existing?.category ?: "autre")
                else normalizeCategories(category).joinToString(", ")
            // Garde l'orthographe/casse d'origine si la fiche existait déjà, pour que le
            // titre de la note ne change pas d'un enregistrement à l'autre selon la façon
            // dont le nom a été prononcé/écrit cette fois-ci.
            val canonicalName = existing?.name ?: name

            val finalNickname = nickname ?: existing?.nickname
            val finalPhone = phone ?: existing?.phone
            val finalPhonePro = phonePro ?: existing?.phonePro
            val finalEmail = email ?: existing?.email
            val finalAddress = address ?: existing?.address
            val finalAddressPro = addressPro ?: existing?.addressPro
            val finalBirthday = birthday ?: existing?.birthday
            val finalCompany = company ?: existing?.company
            val finalPosition = position ?: existing?.position
            val finalLat = latitude ?: existing?.latitude
            val finalLng = longitude ?: existing?.longitude
            val finalInstallDate = installDate ?: existing?.installDate
            val finalNotes = notes ?: existing?.notes ?: ""
            val finalVisits = existing?.visits ?: emptyList()
            val finalAttachments = existing?.attachments ?: emptyList()

            val content = renderContactMarkdown(
                canonicalName, cat, finalNickname, finalPhone, finalPhonePro, finalEmail, finalAddress,
                finalAddressPro, finalBirthday, finalCompany, finalPosition, finalLat, finalLng, finalInstallDate,
                finalNotes, finalVisits, finalAttachments
            )

            file.writeText(content)

            if (isUpdate) "✅ Fiche de **$canonicalName** mise à jour (catégorie : $cat) dans le vault Obsidian."
            else "✅ **$canonicalName** ajouté(e) aux contacts $cat, dans Obsidian → Contacts/${file.name}"
        } catch (e: Exception) {
            "❌ Erreur lors de l'enregistrement dans Obsidian : ${e.message}"
        }
    }

    /** Ajoute une ligne à l'historique des rendez-vous d'un contact (créé si besoin). */
    fun addVisit(context: Context, name: String, visitLabel: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val contact = findContact(context, name)
        val category = contact?.category ?: "client"
        val updatedVisits = (contact?.visits ?: emptyList()) + visitLabel

        return try {
            val folder = contactsFolder(context)
            val targetName = contact?.name ?: name
            val file = File(folder, "${safeFileName(targetName)}.md")

            val content = renderContactMarkdown(
                targetName, category, contact?.nickname, contact?.phone, contact?.phonePro, contact?.email,
                contact?.address, contact?.addressPro, contact?.birthday, contact?.company,
                contact?.position, contact?.latitude, contact?.longitude, contact?.installDate,
                contact?.notes ?: "", updatedVisits, contact?.attachments ?: emptyList()
            )
            file.writeText(content)
            "✅ Rendez-vous ajouté à l'historique de **$targetName**."
        } catch (e: Exception) {
            "❌ Erreur lors de l'ajout du rendez-vous : ${e.message}"
        }
    }

    /**
     * Attache un fichier déjà présent sur le disque (photo OU document, ex: le dernier
     * fichier envoyé dans le chat) à la fiche d'un contact — le copie dans
     * Contacts/Pieces-jointes/<Nom>/ du vault, puis enregistre sa référence dans la fiche
     * pour qu'il réapparaisse à chaque consultation (voir formatFullDetails).
     */
    fun addAttachment(context: Context, name: String, sourcePath: String, originalFileName: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return "❌ Fichier introuvable : $sourcePath"

        val contact = findContact(context, name)
        val targetName = contact?.name ?: name
        val category = contact?.category ?: "autre"

        return try {
            val attachDir = File(contactsFolder(context), "Pieces-jointes/${safeFileName(targetName)}").also { it.mkdirs() }
            val safeOriginalName = originalFileName.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val destFile = File(attachDir, "${System.currentTimeMillis()}_$safeOriginalName")
            sourceFile.copyTo(destFile, overwrite = true)

            val file = File(contactsFolder(context), "${safeFileName(targetName)}.md")
            val updatedAttachments = (contact?.attachments ?: emptyList()) + destFile.absolutePath

            val content = renderContactMarkdown(
                targetName, category, contact?.nickname, contact?.phone, contact?.phonePro, contact?.email,
                contact?.address, contact?.addressPro, contact?.birthday, contact?.company,
                contact?.position, contact?.latitude, contact?.longitude, contact?.installDate,
                contact?.notes ?: "", contact?.visits ?: emptyList(), updatedAttachments
            )
            file.writeText(content)
            "📎 « $safeOriginalName » attaché à la fiche de **$targetName**."
        } catch (e: Exception) {
            "❌ Erreur lors de l'ajout de la pièce jointe : ${e.message}"
        }
    }

    /**
     * Renvoie (base64, mime) de la dernière pièce jointe IMAGE d'un contact, pour la
     * réafficher directement dans le chat quand sa fiche est consultée — sans ça, une photo
     * attachée resterait invisible tant qu'on n'irait pas fouiller le vault manuellement.
     */
    fun getLatestImageAttachment(context: Context, name: String): Pair<String, String>? {
        val contact = findContact(context, name) ?: return null
        val imagePath = contact.attachments.lastOrNull { path ->
            FileGenController.mimeTypeFor(path).startsWith("image/")
        } ?: return null
        val file = File(imagePath)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP) to FileGenController.mimeTypeFor(imagePath)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Crée ou complète une fiche client à partir d'un événement d'agenda :
     * titre → nom du client (sauf si nameOverride fourni), lieu → adresse,
     * description → notes, + ajoute ce rendez-vous à l'historique.
     */
    fun createClientFromEvent(context: Context, event: CalendarController.EventDetails, nameOverride: String? = null): String {
        val clientName = nameOverride?.ifBlank { null } ?: event.title
        if (clientName.isBlank()) return "❌ Impossible de déterminer le nom du client (titre d'événement vide)."

        val saveResult = saveContact(
            context, clientName, category = "client",
            address = event.location.ifBlank { null },
            notes = event.description.ifBlank { null }
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
        val visitLabel = "${sdf.format(Date(event.startMillis))} : ${event.title}" +
            (if (event.location.isNotBlank()) " (${event.location})" else "")
        addVisit(context, clientName, visitLabel)

        return "$saveResult\n📅 Rendez-vous du ${sdf.format(Date(event.startMillis))} ajouté à son historique."
    }

    /** Version structurée (sans le texte formaté) pour l'export KML. */
    fun getContactsByCategory(context: Context, category: String): List<ContactSummary> {
        if (!PermissionsManager.hasManageStoragePermission()) return emptyList()
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val raw = category.lowercase().trim()
        val all = raw.isBlank() || raw == "tous" || raw == "tout"
        val cat = normalizeCategory(raw)

        return files.mapNotNull { parseContactFile(it) }
            .filter { all || it.category.split(",").map { c -> c.trim() }.contains(cat) }
            .map { ContactSummary(it.name, it.category, it.address, it.latitude, it.longitude) }
            .sortedBy { it.name }
    }

    fun getContactDetails(context: Context, name: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val contact = findContact(context, name) ?: return "❌ Aucune fiche trouvée pour « $name »."
        return formatFullDetails(contact)
    }

    fun searchContacts(context: Context, query: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        // BUG RÉEL CORRIGÉ (signalement utilisateur : "ne recherche pas non plus dans les
        // fiches de mes contacts") : cette recherche comparait en .lowercase() BRUT, sans
        // la normalisation NFD (suppression des accents) utilisée partout ailleurs dans ce
        // fichier via normalizeName() (ex: resolveContactFile). Résultat concret : chercher
        // "ecole" ne matchait PAS une fiche dont l'adresse ou le nom contenait "École"/
        // "Écolé", et une recherche par nom échouait dès que la casse/les accents tapés par
        // l'utilisateur différaient de ceux enregistrés dans la fiche. On normalise
        // maintenant la requête ET chaque champ comparé avec normalizeName(), pour un
        // matching identique (accent/casse insensible) à celui déjà utilisé en écriture.
        val q = normalizeName(query)

        val matches = files.mapNotNull { parseContactFile(it) }.filter { c ->
            normalizeName(c.name).contains(q) ||
                (c.phone?.lowercase()?.contains(q) == true) ||
                (c.phonePro?.lowercase()?.contains(q) == true) ||
                (c.email?.lowercase()?.contains(q) == true) ||
                (c.address?.let { normalizeName(it) }?.contains(q) == true) ||
                (c.addressPro?.let { normalizeName(it) }?.contains(q) == true) ||
                normalizeName(c.notes).contains(q)
        }

        if (matches.isEmpty()) return "🔍 Aucun contact trouvé pour « $query »."
        if (matches.size == 1) return formatFullDetails(matches[0])

        // Auparavant, dès que 2+ fiches correspondaient (homonymes, ou fiches dupliquées
        // par incohérence de casse/accent), seul un résumé compact s'affichait — sans les
        // notes ni l'historique de rendez-vous — donnant l'impression que JARVIS "n'avait
        // pas d'autres infos" tant que l'utilisateur n'affinait pas la recherche jusqu'à
        // ne matcher qu'UNE seule fiche. Tant que le nombre de résultats reste raisonnable,
        // on affiche maintenant TOUT directement, sans qu'il faille insister.
        if (matches.size <= 4) {
            val sb = StringBuilder("🔍 **${matches.size} contacts correspondent à « $query »** :\n\n")
            matches.forEachIndexed { i, c ->
                sb.append(formatFullDetails(c))
                if (i < matches.size - 1) sb.append("\n\n───\n\n")
            }
            return sb.toString().trim()
        }

        val sb = StringBuilder("🔍 **${matches.size} résultats pour « $query »** (trop nombreux pour tout détailler — affine la recherche pour voir les infos complètes d'un contact précis) :\n\n")
        matches.forEach { appendSummary(sb, it) }
        return sb.toString().trim()
    }

    fun listByCategory(context: Context, category: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val raw = category.lowercase().trim()
        val all = raw.isBlank() || raw == "tous" || raw == "tout"
        val cat = normalizeCategory(raw)

        val contacts = files.mapNotNull { parseContactFile(it) }
            .filter { all || it.category.split(",").map { c -> c.trim() }.contains(cat) }
            .sortedBy { it.name }

        if (contacts.isEmpty()) return "Aucun contact${if (!all) " dans la catégorie « $cat »" else ""}."

        // Peu de contacts dans la catégorie -> autant tout montrer en détail directement
        // plutôt que d'obliger l'utilisateur à redemander chaque fiche une par une.
        if (contacts.size <= 3) {
            val sb = StringBuilder("📇 **Contacts${if (!all) " — $cat" else ""}** (${contacts.size}) :\n\n")
            contacts.forEachIndexed { i, c ->
                sb.append(formatFullDetails(c))
                if (i < contacts.size - 1) sb.append("\n\n───\n\n")
            }
            return sb.toString().trim()
        }

        val sb = StringBuilder("📇 **Contacts${if (!all) " — $cat" else ""}** :\n\n")
        contacts.forEach { appendSummary(sb, it) }
        return sb.toString().trim()
    }

    /**
     * Réécrit TOUTES les fiches contact existantes avec le gabarit actuel (frontmatter +
     * section ## 📇 Coordonnées enrichie), sans jamais changer les DONNÉES elles-mêmes —
     * corrige l'incohérence réelle signalée en parcourant le vault directement dans
     * l'application Obsidian : une fiche n'était réécrite au nouveau format QUE lorsqu'elle
     * était retouchée (save_contact_profile/add_client_visit/attach_contact_file) ; toute
     * fiche non retouchée depuis un précédent format restait figée dans son ancienne mise en
     * forme, donnant des fiches visiblement différentes les unes des autres. Aucune donnée
     * n'est perdue : tout texte présent AVANT le premier marqueur connu (## 📇 Coordonnées /
     * ## Historique des rendez-vous / ## 📎 Pièces jointes) est conservé tel quel comme note
     * libre (voir notesWithoutKnownSections) — y compris le corps entier d'une très vieille
     * fiche qui n'aurait aucun de ces marqueurs. Ne réécrit sur le disque QUE les fiches dont
     * la structure diffère réellement du gabarit actuel (la ligne "updated:" est ignorée dans
     * cette comparaison, sinon toutes les fiches seraient marquées "modifiées" à chaque fois).
     */
    fun refreshAllContacts(context: Context): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        if (files.isEmpty()) return "ℹ️ Aucune fiche contact dans le vault pour l'instant."

        fun stripUpdatedLine(s: String) = s.lines().filterNot { it.trim().startsWith("updated:") }.joinToString("\n")

        var updated = 0
        var unchanged = 0
        var failed = 0
        files.forEach { file ->
            try {
                val contact = parseContactFile(file)
                if (contact == null) {
                    failed++
                    return@forEach
                }
                val normalizedCat = normalizeCategories(contact.category).joinToString(", ")
                val newContent = renderContactMarkdown(
                    contact.name, normalizedCat, contact.nickname, contact.phone, contact.phonePro,
                    contact.email, contact.address, contact.addressPro, contact.birthday, contact.company,
                    contact.position, contact.latitude, contact.longitude, contact.installDate,
                    contact.notes, contact.visits, contact.attachments
                )
                val oldContent = file.readText()
                if (stripUpdatedLine(oldContent) == stripUpdatedLine(newContent)) {
                    unchanged++
                } else {
                    file.writeText(newContent)
                    updated++
                }
            } catch (e: Exception) {
                failed++
            }
        }

        val detail = if (failed > 0) " ($failed illisible(s), ignorée(s) sans y toucher)" else ""
        return "✅ Fiches contact harmonisées : $updated mise(s) à jour vers le format actuel, $unchanged déjà à jour$detail."
    }

    fun deleteContact(context: Context, name: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val contact = findContact(context, name) ?: return "❌ Aucune fiche trouvée pour « $name »."
        return if (contact.file.delete()) {
            "🗑️ Fiche de **${contact.name}** supprimée du vault Obsidian."
        } else {
            "❌ Échec de la suppression de la fiche."
        }
    }

    /** Trouve la fiche d'un contact et ouvre Maps sur son adresse (ou ses coordonnées GPS si connues). */
    fun navigateToContact(context: Context, name: String): String {
        if (!PermissionsManager.hasManageStoragePermission()) return ObsidianController.missingStorageAccessMessagePublic()
        val contact = findContact(context, name)
            ?: return "❌ Aucune fiche trouvée pour « $name ». Ajoute d'abord son adresse avec save_contact_profile."

        val destination = if (contact.latitude != null && contact.longitude != null) {
            "${contact.latitude},${contact.longitude}"
        } else {
            contact.address
        }

        if (destination.isNullOrBlank()) {
            return "❌ **${contact.name}** n'a pas d'adresse ni de coordonnées GPS enregistrées."
        }

        val mapsResult = LocationController.openMaps(context, destination)
        return "🧭 Direction ${contact.name} — $mapsResult"
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun findContact(context: Context, name: String): ContactNote? =
        resolveContactFile(context, name)?.let { parseContactFile(it) }

    /**
     * Affiche TOUT ce qui est enregistré sur ce contact, en liste propre avec emojis —
     * uniquement les champs réellement renseignés (pas de ligne vide/« non renseigné »
     * pour ne pas alourdir l'affichage), afin que l'utilisateur voie d'un coup d'œil
     * l'intégralité des informations réellement stockées dans le vault.
     */
    private fun formatFullDetails(c: ContactNote): String {
        return buildString {
            // Titre : "{surnom} {nom} ({categorie})" si un surnom affectueux est enregistré
            // (ex: "Ma Cherie"), sinon juste "{nom} ({categorie})" - format de base demande :
            // les infos sont regroupees en deux blocs "Personnel"/"Professionnel", chaque
            // champ n'apparaissant QUE s'il est reellement renseigne.
            val titleName = if (!c.nickname.isNullOrBlank()) "${c.nickname} ${c.name}" else c.name
            append("📇 $titleName (${categoryLabel(c.category)})\n\n")

            val hasPersonal = !c.birthday.isNullOrBlank() || !c.phone.isNullOrBlank() ||
                !c.email.isNullOrBlank() || !c.address.isNullOrBlank()
            if (hasPersonal) {
                append("**Personnel**\n")
                if (!c.birthday.isNullOrBlank()) append("🎂 ${c.birthday}\n")
                if (!c.phone.isNullOrBlank()) append("📞 ${c.phone}\n")
                if (!c.email.isNullOrBlank()) append("✉️ ${c.email}\n")
                if (!c.address.isNullOrBlank()) append("🏠 ${c.address}\n")
                append("\n")
            }

            val hasPro = !c.phonePro.isNullOrBlank() || !c.company.isNullOrBlank() ||
                !c.addressPro.isNullOrBlank() || !c.position.isNullOrBlank()
            if (hasPro) {
                append("**Professionnel**\n")
                if (!c.phonePro.isNullOrBlank()) append("📱 ${c.phonePro}\n")
                if (!c.company.isNullOrBlank()) append("🏢 ${c.company}\n")
                if (!c.addressPro.isNullOrBlank()) append("📍 ${c.addressPro}\n")
                if (!c.position.isNullOrBlank()) append("💼 Poste : ${c.position}\n")
                append("\n")
            }

            if (c.latitude != null && c.longitude != null) append("🌐 GPS : ${c.latitude}, ${c.longitude}\n")
            if (!c.installDate.isNullOrBlank()) append("📆 Date d'installation : ${c.installDate}\n")
            if (c.notes.isNotBlank()) append("\n📝 Notes : ${c.notes}\n")
            if (c.visits.isNotEmpty()) {
                append("\n🗓️ Historique des rendez-vous (${c.visits.size}) :\n")
                c.visits.takeLast(10).forEach { append("   • $it\n") }
            }
            if (c.attachments.isNotEmpty()) {
                append("\n📎 Pièces jointes (${c.attachments.size}) :\n")
                c.attachments.forEach { append("   • ${File(it).name}\n") }
            }
            if (!hasPersonal && !hasPro && c.installDate.isNullOrBlank()) {
                append("\nℹ️ Aucune coordonnée enregistrée pour l'instant (juste le nom et la catégorie).\n")
            }
        }.trim()
    }

    // Catégorie personnalisée : affichée telle quelle plutôt que forcée en "autre" — voir
    // normalizeCategoryToken pour le pourquoi.
    private fun singleCategoryLabel(cat: String): String = cat.ifBlank { "autre" }

    /** [cat] peut contenir plusieurs catégories séparées par des virgules (ex: "famille, travail"). */
    private fun categoryLabel(cat: String): String =
        cat.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(" / ") { singleCategoryLabel(it) }

    private fun appendSummary(sb: StringBuilder, c: ContactNote) {
        sb.append("• ${c.name} (${categoryLabel(c.category)})")
        if (!c.phone.isNullOrBlank()) sb.append(" — 📞 ${c.phone}")
        if (!c.phonePro.isNullOrBlank()) sb.append(" — 📱 ${c.phonePro}")
        if (!c.email.isNullOrBlank()) sb.append(" — ✉️ ${c.email}")
        sb.append("\n")
        if (!c.address.isNullOrBlank()) sb.append("   🏠 ${c.address}\n")
        if (!c.addressPro.isNullOrBlank()) sb.append("   🏗️ ${c.addressPro}\n")
        if (!c.installDate.isNullOrBlank()) sb.append("   📆 Installé le ${c.installDate}\n")
        sb.append("\n")
    }
}
