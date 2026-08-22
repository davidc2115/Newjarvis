package com.jarvis.assistant

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactsController {

    /**
     * Recherche le premier numéro de téléphone pour le nom donné.
     * Utilise le CONTENT_FILTER_URI natif d'Android pour une recherche
     * insensible à la casse et tolérante aux fautes / prénoms / noms.
     */
    fun findPhoneNumber(context: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val cleanQuery = name.trim()
        if (cleanQuery.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        // 1. Recherche officielle native Android (CONTENT_FILTER_URI)
        val filterUri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(cleanQuery)
        )

        try {
            context.contentResolver.query(filterUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0)
                    if (!number.isNullOrBlank()) {
                        return number
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback : recherche large sur CONTENT_URI
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$cleanQuery%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Recherche les contacts correspondant à une requête et les retourne sous forme de texte.
     *
     * BUG RÉEL CORRIGÉ (signalement utilisateur : "quand je demande des contacts à JARVIS il
     * ne trouve aucun contact") : cette recherche interrogeait Phone.CONTENT_FILTER_URI, une
     * vue qui ne contient QUE les lignes ayant un numéro de téléphone enregistré -- un contact
     * sans aucun numéro (email seul, fiche professionnelle incomplète, contact ajouté juste
     * pour un libellé...) était donc invisible EN TOTALITÉ pour JARVIS, quel que soit le nom
     * recherché. Interroge maintenant Contacts.CONTENT_FILTER_URI (la table des contacts
     * eux-mêmes, tolérante à la casse/aux fautes comme avant) puis récupère le numéro
     * séparément SI il existe (voir getPrimaryPhoneNumber) -- un contact sans numéro apparaît
     * donc désormais, juste sans ligne de téléphone.
     */
    fun searchContacts(context: Context, query: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès aux contacts non accordée. Cliquez sur le bouton 'Demander Contacts' dans le dashboard."
        }

        val cleanQuery = query.trim()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )

        val filterUri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(cleanQuery)
        )

        return try {
            val cursor: Cursor? = context.contentResolver.query(filterUri, projection, null, null, "${ContactsContract.Contacts.DISPLAY_NAME} ASC")

            cursor?.use { c ->
                if (c.count == 0) return "👤 Aucun contact trouvé pour « $query »."

                val sb = StringBuilder("👤 **Résultats de la recherche pour « $query »** :\n\n")
                var count = 0
                val seenIds = mutableSetOf<String>()

                while (c.moveToNext() && count < 10) {
                    val contactId = c.getString(0) ?: ""
                    if (contactId.isBlank() || !seenIds.add(contactId)) continue
                    val displayName = c.getString(1) ?: "Inconnu"
                    val phone = getPrimaryPhoneNumber(context, contactId)
                    val labels = getContactLabels(context, contactId)
                    val labelsSuffix = if (labels.isNotEmpty()) " 🏷️ ${labels.joinToString(", ")}" else ""
                    val phoneSuffix = if (phone != null) " : $phone" else " (aucun numéro enregistré)"
                    sb.append("${count + 1}. **$displayName**$phoneSuffix$labelsSuffix\n")
                    count++
                }
                sb.toString()
            } ?: "❌ Impossible d'effectuer la recherche dans les contacts."
        } catch (e: Exception) {
            "❌ Erreur lors de la recherche des contacts : ${e.message}"
        }
    }

    /** Numéro principal du contact [contactId], ou null s'il n'en a aucun (voir searchContacts :
     *  ne doit JAMAIS faire disparaître un contact de la liste, juste laisser le champ vide). */
    private fun getPrimaryPhoneNumber(context: Context, contactId: String): String? {
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (_: Exception) {
            null
        }
    }

    fun addContact(context: Context, name: String, phone: String, email: String = ""): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification des contacts non accordée."
        }

        return try {
            val ops = ArrayList<ContentProviderOperation>()

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

            if (email.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                        .build()
                )
            }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            "✅ Contact **$name** ($phone) ajouté avec succès !"
        } catch (e: Exception) {
            "❌ Échec de l'ajout du contact : ${e.message}"
        }
    }

    /** BUG RÉEL CORRIGÉ (voir searchContacts ci-dessus, même cause) : listait via
     *  Phone.CONTENT_URI, donc omettait tout contact sans numéro de téléphone -- passe par
     *  Contacts.CONTENT_URI (tous les contacts) puis récupère le numéro séparément si présent. */
    fun getContactList(context: Context, count: Int = 20): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès aux contacts non accordée."
        }

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "👤 Aucun contact enregistré dans le téléphone."

                val sb = StringBuilder("👤 **Liste des contacts (${minOf(count, c.count)})** :\n\n")
                var idx = 0
                val seenIds = mutableSetOf<String>()

                while (c.moveToNext() && idx < count) {
                    val contactId = c.getString(0) ?: ""
                    if (contactId.isBlank() || !seenIds.add(contactId)) continue
                    val displayName = c.getString(1) ?: "Inconnu"
                    val phone = getPrimaryPhoneNumber(context, contactId)
                    val labels = getContactLabels(context, contactId)
                    val labelsSuffix = if (labels.isNotEmpty()) " 🏷️ ${labels.joinToString(", ")}" else ""
                    val phoneSuffix = phone ?: "aucun numéro"
                    sb.append("${idx + 1}. **$displayName** — $phoneSuffix$labelsSuffix\n")
                    idx++
                }
                sb.toString()
            } ?: "❌ Échec de la lecture de la liste des contacts."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture des contacts : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Libellés / groupes natifs (feature "Libellés" de l'app Contacts/Google
    // Contacts) — jusqu'ici jamais interrogés par JARVIS, qui ne connaissait donc
    // jamais les libellés créés manuellement par l'utilisateur alors qu'ils sont
    // bien présents dans le carnet d'adresses natif du téléphone (bug signalé).
    // ─────────────────────────────────────────────────────────────────────────

    /** Libellés/groupes auxquels appartient le contact [contactId] (peut être vide). */
    private fun getContactLabels(context: Context, contactId: String): List<String> {
        val labels = mutableListOf<String>()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val groupId = cursor.getLong(0)
                    context.contentResolver.query(
                        ContactsContract.Groups.CONTENT_URI,
                        arrayOf(ContactsContract.Groups.TITLE),
                        "${ContactsContract.Groups._ID} = ?",
                        arrayOf(groupId.toString()),
                        null
                    )?.use { groupCursor ->
                        if (groupCursor.moveToFirst()) {
                            val title = groupCursor.getString(0)
                            if (!title.isNullOrBlank()) labels.add(title)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return labels.distinct()
    }

    /** Liste tous les libellés/groupes de contacts existants dans le carnet d'adresses natif. */
    fun listAllLabels(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès aux contacts non accordée."
        }
        return try {
            val labels = mutableListOf<String>()
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE, ContactsContract.Groups.DELETED),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val deleted = c.getInt(1)
                    val title = c.getString(0)
                    if (deleted == 0 && !title.isNullOrBlank()) labels.add(title)
                }
            }
            val distinct = labels.distinct().sorted()
            if (distinct.isEmpty()) "🏷️ Aucun libellé/groupe de contact trouvé dans le carnet d'adresses du téléphone."
            else "🏷️ **Libellés de contacts trouvés (${distinct.size})** :\n\n" + distinct.joinToString("\n") { "• $it" }
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture des libellés : ${e.message}"
        }
    }

    /** Liste les contacts portant un libellé/groupe précis (recherche partielle, insensible à la casse).
     *
     *  BUG RÉEL CORRIGÉ (signalement utilisateur : "il ne lit pas les libellés") : cette
     *  fonction trouvait bien le bon groupe ET les bons contactIds via GroupMembership, mais la
     *  toute dernière étape (récupérer le nom à afficher) interrogeait Phone.CONTENT_URI --
     *  un contact du groupe SANS numéro de téléphone n'avait alors AUCUNE ligne dans cette
     *  table, donc `c.moveToFirst()` échouait et ce contact était silencieusement supprimé du
     *  résultat, malgré avoir bien le libellé demandé. Pire, si TOUS les contacts d'un libellé
     *  manquaient de numéro (cas plausible pour des contacts filés par catégorie sans être
     *  forcément joignables), le résultat final semblait dire "aucun contact n'a ce libellé" --
     *  une conclusion fausse. Le nom est maintenant lu directement sur Contacts.CONTENT_URI
     *  (garanti présent pour tout contact), le numéro reste une info optionnelle en plus. */
    fun listContactsByLabel(context: Context, label: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès aux contacts non accordée."
        }
        if (label.isBlank()) return "❌ Précise le libellé à rechercher."
        return try {
            val groupIds = mutableListOf<String>()
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups.TITLE} LIKE ?",
                arrayOf("%$label%"),
                null
            )?.use { c -> while (c.moveToNext()) groupIds.add(c.getString(0)) }

            if (groupIds.isEmpty()) {
                return "🔍 Aucun libellé de contact ne correspond à « $label » (vérifie l'orthographe exacte dans ton appli Contacts, ou demande list_contact_labels pour voir la liste complète)."
            }

            val contactIds = mutableSetOf<String>()
            for (gid in groupIds) {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.CONTACT_ID),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, gid),
                    null
                )?.use { c -> while (c.moveToNext()) contactIds.add(c.getString(0)) }
            }
            if (contactIds.isEmpty()) return "📋 Aucun contact n'a le libellé « $label »."

            val sb = StringBuilder("🏷️ **Contacts avec le libellé « $label »** :\n\n")
            var idx = 0
            for (cid in contactIds) {
                val name = context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                    "${ContactsContract.Contacts._ID} = ?",
                    arrayOf(cid),
                    null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: continue
                idx++
                val phone = getPrimaryPhoneNumber(context, cid)
                sb.append("$idx. **$name**${if (phone != null) " : $phone" else ""}\n")
            }
            if (idx == 0) "📋 Aucun contact n'a le libellé « $label »." else sb.toString()
        } catch (e: Exception) {
            "❌ Erreur lors de la recherche par libellé : ${e.message}"
        }
    }
}
