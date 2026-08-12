package com.jarvis.assistant

import android.content.Context
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

    private fun contactsFolder(context: Context): File =
        File(ObsidianController.getVaultRoot(context), "Contacts").also { it.mkdirs() }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()

    /** Casse/accents/espaces normalisés pour détecter qu'un nom désigne le même contact. */
    private fun normalizeName(name: String): String =
        java.text.Normalizer.normalize(name.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    /**
     * Cherche une fiche existante dont le nom correspond EXACTEMENT (une fois casse et
     * accents ignorés) au nom donné — pour réutiliser cette même fiche au lieu d'en créer
     * une nouvelle sous une orthographe légèrement différente ("Jean Dupont" vs "jean
     * DUPONT" vs "Jéan Dupont"). Volontairement strict (égalité exacte normalisée, pas de
     * correspondance partielle) pour ne jamais fusionner deux personnes différentes par
     * erreur — ça fragmentait les fiches d'un même contact en plusieurs fichiers séparés,
     * chacun avec seulement une partie des infos enregistrées au fil du temps.
     */
    private fun findExactNameMatch(context: Context, name: String): File? {
        val target = normalizeName(name)
        val files = contactsFolder(context).listFiles { f -> f.extension == "md" } ?: emptyArray()
        return files.firstOrNull { normalizeName(it.nameWithoutExtension) == target }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture / écriture d'une fiche
    // ─────────────────────────────────────────────────────────────────────────

    private const val VISITS_MARKER = "## Historique des rendez-vous"

    private data class ContactNote(
        val name: String,
        val category: String,
        val phone: String?,
        val phonePro: String?,
        val email: String?,
        val address: String?,
        val addressPro: String?,
        val latitude: Double?,
        val longitude: Double?,
        val installDate: String?,
        val notes: String,
        val visits: List<String>,
        val file: File
    )

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

        fun field(key: String): String? {
            val regex = Regex("^$key:\\s*\"?([^\"\\n]*)\"?\\s*$", RegexOption.MULTILINE)
            return regex.find(frontmatter)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }

        val bodyWithoutTitle = body.lines().dropWhile { it.startsWith("#") || it.isBlank() }.joinToString("\n").trim()

        val markerIdx = bodyWithoutTitle.indexOf(VISITS_MARKER)
        val notesOnly: String
        val visits: List<String>
        if (markerIdx >= 0) {
            notesOnly = bodyWithoutTitle.substring(0, markerIdx).trim()
            visits = bodyWithoutTitle.substring(markerIdx + VISITS_MARKER.length)
                .lines()
                .map { it.trim() }
                .filter { it.startsWith("-") }
                .map { it.removePrefix("-").trim() }
        } else {
            notesOnly = bodyWithoutTitle
            visits = emptyList()
        }

        return ContactNote(
            name = file.nameWithoutExtension,
            category = field("category") ?: "autre",
            phone = field("phone"),
            phonePro = field("phone_pro"),
            email = field("email"),
            address = field("address"),
            addressPro = field("address_pro"),
            latitude = field("latitude")?.toDoubleOrNull(),
            longitude = field("longitude")?.toDoubleOrNull(),
            installDate = field("install_date"),
            notes = notesOnly,
            visits = visits,
            file = file
        )
    }

    fun saveContact(
        context: Context,
        name: String,
        category: String = "autre",
        phone: String? = null,
        phonePro: String? = null,
        email: String? = null,
        address: String? = null,
        addressPro: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        installDate: String? = null,
        notes: String? = null
    ): String {
        if (name.isBlank()) return "❌ Nom du contact manquant."
        val cat = category.lowercase().trim().let { if (it in VALID_CATEGORIES) it else "autre" }

        return try {
            val folder = contactsFolder(context)
            // Réutilise une fiche existante dont le nom correspond exactement (casse/accents
            // ignorés) plutôt que d'en créer une nouvelle sous une orthographe légèrement
            // différente — évite de fragmenter les infos d'un même contact dans plusieurs
            // fichiers séparés au fil des enregistrements successifs.
            val file = findExactNameMatch(context, name) ?: File(folder, "${safeFileName(name)}.md")
            val existing = parseContactFile(file)
            val isUpdate = existing != null
            // Garde l'orthographe/casse d'origine si la fiche existait déjà, pour que le
            // titre de la note ne change pas d'un enregistrement à l'autre selon la façon
            // dont le nom a été prononcé/écrit cette fois-ci.
            val canonicalName = existing?.name ?: name

            val finalPhone = phone ?: existing?.phone
            val finalPhonePro = phonePro ?: existing?.phonePro
            val finalEmail = email ?: existing?.email
            val finalAddress = address ?: existing?.address
            val finalAddressPro = addressPro ?: existing?.addressPro
            val finalLat = latitude ?: existing?.latitude
            val finalLng = longitude ?: existing?.longitude
            val finalInstallDate = installDate ?: existing?.installDate
            val finalNotes = notes ?: existing?.notes ?: ""
            val finalVisits = existing?.visits ?: emptyList()

            val frontmatterLines = mutableListOf("category: $cat")
            finalPhone?.let { frontmatterLines.add("phone: \"$it\"") }
            finalPhonePro?.let { frontmatterLines.add("phone_pro: \"$it\"") }
            finalEmail?.let { frontmatterLines.add("email: \"$it\"") }
            finalAddress?.let { frontmatterLines.add("address: \"$it\"") }
            finalAddressPro?.let { frontmatterLines.add("address_pro: \"$it\"") }
            finalLat?.let { frontmatterLines.add("latitude: $it") }
            finalLng?.let { frontmatterLines.add("longitude: $it") }
            finalInstallDate?.let { frontmatterLines.add("install_date: \"$it\"") }
            frontmatterLines.add("updated: ${updatedFormat.format(Date())}")
            frontmatterLines.add("tags: [jarvis, contact]")

            val content = buildString {
                append("---\n")
                append(frontmatterLines.joinToString("\n"))
                append("\n---\n\n")
                append("# $canonicalName\n\n")
                if (finalNotes.isNotBlank()) append(finalNotes) else append("_Aucune note._")
                if (finalVisits.isNotEmpty()) {
                    append("\n\n$VISITS_MARKER\n")
                    finalVisits.forEach { append("- $it\n") }
                }
            }

            file.writeText(content)

            if (isUpdate) "✅ Fiche de **$canonicalName** mise à jour (catégorie : $cat) dans le vault Obsidian."
            else "✅ **$canonicalName** ajouté(e) aux contacts $cat, dans Obsidian → Contacts/${file.name}"
        } catch (e: Exception) {
            "❌ Erreur lors de l'enregistrement dans Obsidian : ${e.message}"
        }
    }

    /** Ajoute une ligne à l'historique des rendez-vous d'un contact (créé si besoin). */
    fun addVisit(context: Context, name: String, visitLabel: String): String {
        val contact = findContact(context, name)
        val category = contact?.category ?: "client"
        val updatedVisits = (contact?.visits ?: emptyList()) + visitLabel

        return try {
            val folder = contactsFolder(context)
            val targetName = contact?.name ?: name
            val file = File(folder, "${safeFileName(targetName)}.md")

            val frontmatterLines = mutableListOf("category: $category")
            contact?.phone?.let { frontmatterLines.add("phone: \"$it\"") }
            contact?.phonePro?.let { frontmatterLines.add("phone_pro: \"$it\"") }
            contact?.email?.let { frontmatterLines.add("email: \"$it\"") }
            contact?.address?.let { frontmatterLines.add("address: \"$it\"") }
            contact?.addressPro?.let { frontmatterLines.add("address_pro: \"$it\"") }
            contact?.latitude?.let { frontmatterLines.add("latitude: $it") }
            contact?.longitude?.let { frontmatterLines.add("longitude: $it") }
            contact?.installDate?.let { frontmatterLines.add("install_date: \"$it\"") }
            frontmatterLines.add("updated: ${updatedFormat.format(Date())}")
            frontmatterLines.add("tags: [jarvis, contact]")

            val content = buildString {
                append("---\n")
                append(frontmatterLines.joinToString("\n"))
                append("\n---\n\n")
                append("# $targetName\n\n")
                val notesText = contact?.notes?.takeIf { it.isNotBlank() } ?: "_Aucune note._"
                append(notesText)
                append("\n\n$VISITS_MARKER\n")
                updatedVisits.forEach { append("- $it\n") }
            }
            file.writeText(content)
            "✅ Rendez-vous ajouté à l'historique de **$targetName**."
        } catch (e: Exception) {
            "❌ Erreur lors de l'ajout du rendez-vous : ${e.message}"
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
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val cat = category.lowercase().trim()
        val all = cat.isBlank() || cat == "tous" || cat == "tout"

        return files.mapNotNull { parseContactFile(it) }
            .filter { all || it.category == cat }
            .map { ContactSummary(it.name, it.category, it.address, it.latitude, it.longitude) }
            .sortedBy { it.name }
    }

    fun getContactDetails(context: Context, name: String): String {
        val contact = findContact(context, name) ?: return "❌ Aucune fiche trouvée pour « $name »."
        return formatFullDetails(contact)
    }

    fun searchContacts(context: Context, query: String): String {
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val q = query.lowercase()

        val matches = files.mapNotNull { parseContactFile(it) }.filter { c ->
            c.name.lowercase().contains(q) ||
                (c.phone?.lowercase()?.contains(q) == true) ||
                (c.phonePro?.lowercase()?.contains(q) == true) ||
                (c.email?.lowercase()?.contains(q) == true) ||
                (c.address?.lowercase()?.contains(q) == true) ||
                (c.addressPro?.lowercase()?.contains(q) == true) ||
                c.notes.lowercase().contains(q)
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
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val cat = category.lowercase().trim()
        val all = cat.isBlank() || cat == "tous" || cat == "tout"

        val contacts = files.mapNotNull { parseContactFile(it) }
            .filter { all || it.category == cat }
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

    fun deleteContact(context: Context, name: String): String {
        val contact = findContact(context, name) ?: return "❌ Aucune fiche trouvée pour « $name »."
        return if (contact.file.delete()) {
            "🗑️ Fiche de **${contact.name}** supprimée du vault Obsidian."
        } else {
            "❌ Échec de la suppression de la fiche."
        }
    }

    /** Trouve la fiche d'un contact et ouvre Maps sur son adresse (ou ses coordonnées GPS si connues). */
    fun navigateToContact(context: Context, name: String): String {
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

    private fun findContact(context: Context, name: String): ContactNote? {
        val folder = contactsFolder(context)
        val exact = File(folder, "${safeFileName(name)}.md")
        parseContactFile(exact)?.let { return it }

        // Recherche approximative si le nom exact ne correspond à aucun fichier
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val q = name.lowercase()
        return files.mapNotNull { parseContactFile(it) }
            .firstOrNull { it.name.lowercase().contains(q) }
    }

    /**
     * Affiche TOUT ce qui est enregistré sur ce contact, en liste propre avec emojis —
     * uniquement les champs réellement renseignés (pas de ligne vide/« non renseigné »
     * pour ne pas alourdir l'affichage), afin que l'utilisateur voie d'un coup d'œil
     * l'intégralité des informations réellement stockées dans le vault.
     */
    private fun formatFullDetails(c: ContactNote): String {
        return buildString {
            append("📇 ${c.name} (${categoryLabel(c.category)})\n\n")
            if (!c.phone.isNullOrBlank()) append("📞 Téléphone perso : ${c.phone}\n")
            if (!c.phonePro.isNullOrBlank()) append("📱 Téléphone pro : ${c.phonePro}\n")
            if (!c.email.isNullOrBlank()) append("✉️ Email : ${c.email}\n")
            if (!c.address.isNullOrBlank()) append("🏠 Adresse perso : ${c.address}\n")
            if (!c.addressPro.isNullOrBlank()) append("🏗️ Adresse pro / chantier : ${c.addressPro}\n")
            if (c.latitude != null && c.longitude != null) append("🌐 GPS : ${c.latitude}, ${c.longitude}\n")
            if (!c.installDate.isNullOrBlank()) append("📆 Date d'installation : ${c.installDate}\n")
            if (c.notes.isNotBlank()) append("\n📝 Notes : ${c.notes}\n")
            if (c.visits.isNotEmpty()) {
                append("\n🗓️ Historique des rendez-vous (${c.visits.size}) :\n")
                c.visits.takeLast(10).forEach { append("   • $it\n") }
            }
            val hasAnyDetail = !c.phone.isNullOrBlank() || !c.phonePro.isNullOrBlank() || !c.email.isNullOrBlank() ||
                !c.address.isNullOrBlank() || !c.addressPro.isNullOrBlank() || !c.installDate.isNullOrBlank()
            if (!hasAnyDetail) append("\nℹ️ Aucune coordonnée enregistrée pour l'instant (juste le nom et la catégorie).\n")
        }.trim()
    }

    private fun categoryLabel(cat: String): String = when (cat) {
        "travail" -> "travail"
        "personnel" -> "personnel"
        "famille" -> "famille"
        "client" -> "client"
        else -> "autre"
    }

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
