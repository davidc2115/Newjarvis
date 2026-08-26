package com.jarvis.assistant

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer

/**
 * Lot 3 "contrôle téléphone" : lecture/création de contacts natifs (ContactsContract), avec
 * READ_CONTACTS/WRITE_CONTACTS demandées à l'exécution (voir MainActivity.executeDeviceCommand).
 */
object ContactsController {

    data class ContactInfo(val name: String, val phoneNumbers: List<String>, val address: String? = null)

    /**
     * Normalise pour la comparaison : enlève les accents (Normalizer NFD + suppression des
     * marques diacritiques) et met en minuscules. BUG SIGNALÉ PERSISTANT ("aucun contact
     * trouvé" malgré cleanName() qui retire déjà les mots parasites) : la clause SQL
     * "DISPLAY_NAME LIKE ?" utilisée avant ne fait un rapprochement insensible à la casse que
     * pour les caractères ASCII a-z (comportement par défaut de SQLite sur Android, pas
     * d'extension ICU chargée) -- "Eric" ne matchait donc jamais un contact enregistré "Éric",
     * "cecile" ne matchait pas "Cécile", etc., très courant avec des noms français. On récupère
     * maintenant TOUS les contacts (requête large, peu coûteuse : juste _ID+DISPLAY_NAME) et on
     * filtre nous-mêmes en Kotlin avec cette normalisation, qui gère correctement les accents.
     */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .trim()

    /**
     * Recherche par nom (correspondance partielle, insensible à la casse ET aux accents).
     * Renvoie Result pour remonter une vraie exception (SecurityException si la permission
     * READ_CONTACTS a été révoquée entre-temps, IllegalStateException du ContentProvider...)
     * au lieu de la confondre avec un simple "aucun contact ne correspond" -- même logique que
     * DeviceController.setTimer/setAlarm (voir leur commentaire).
     */
    fun findContact(context: Context, query: String): Result<ContactInfo?> {
        return try {
            val resolver = context.contentResolver
            val normalizedQuery = normalize(query)
            if (normalizedQuery.isBlank()) return Result.success(null)

            var contactId: Long? = null
            var name: String? = null
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val candidateName = cursor.getString(nameCol) ?: continue
                    if (normalize(candidateName).contains(normalizedQuery)) {
                        contactId = cursor.getLong(idCol)
                        name = candidateName
                        // Préfère une correspondance de nom complet (ex. recherche "julie
                        // martin" qui matche exactement) à la première trouvée : on continue
                        // seulement si ce n'est pas déjà une correspondance exacte.
                        if (normalize(candidateName) == normalizedQuery) return@use
                    }
                }
            }

            val id = contactId ?: return Result.success(null)
            val finalName = name ?: return Result.success(null)

            val phones = mutableListOf<String>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { phoneCursor ->
                while (phoneCursor.moveToNext()) {
                    phones.add(phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                }
            }

            // Adresse postale (StructuredPostal) -- demandée par l'utilisateur en plus du
            // numéro pour "affiche un contact" ; READ_CONTACTS couvre déjà cette table, pas
            // besoin d'une permission supplémentaire.
            var address: String? = null
            resolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
                "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { addressCursor ->
                if (addressCursor.moveToFirst()) {
                    address = addressCursor.getString(
                        addressCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                    )
                }
            }

            Result.success(ContactInfo(finalName, phones, address))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Tous les contacts natifs avec leurs numeros/adresse (tache #240, demande explicite
     * utilisateur -- "generer les fiches contact" dans le vault Obsidian, comme l'ancienne
     * appli). Requete large deja utilisee ailleurs (normalize()/findContact) : recupere
     * d'abord tous les (_ID, DISPLAY_NAME) puis, pour chaque contact, ses numeros et son
     * adresse -- meme logique que findContact, juste sans filtre de nom.
     */
    fun listAllContacts(context: Context): Result<List<ContactInfo>> {
        return try {
            val resolver = context.contentResolver
            val ids = mutableListOf<Pair<Long, String>>()
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.isBlank()) continue
                    ids.add(cursor.getLong(idCol) to name)
                }
            }
            val result = ids.map { (id, name) ->
                val phones = mutableListOf<String>()
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id.toString()), null
                )?.use { phoneCursor ->
                    while (phoneCursor.moveToNext()) {
                        phones.add(phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                    }
                }
                var address: String? = null
                resolver.query(
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
                    "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                    arrayOf(id.toString()), null
                )?.use { addressCursor ->
                    if (addressCursor.moveToFirst()) {
                        address = addressCursor.getString(
                            addressCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                        )
                    }
                }
                ContactInfo(name, phones, address)
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Crée un nouveau contact (nom + un numéro de téléphone). */
    fun createContact(context: Context, name: String, phoneNumber: String): Boolean {
        return try {
            val ops = arrayListOf<ContentProviderOperation>()

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
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }
}
