package com.jarvis.assistant

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

/**
 * Lot 3 "contrôle téléphone" : lecture/création de contacts natifs (ContactsContract), avec
 * READ_CONTACTS/WRITE_CONTACTS demandées à l'exécution (voir MainActivity.executeDeviceCommand).
 */
object ContactsController {

    data class ContactInfo(val name: String, val phoneNumbers: List<String>)

    /** Recherche par nom (correspondance partielle, insensible à la casse). */
    fun findContact(context: Context, query: String): ContactInfo? {
        val resolver = context.contentResolver
        val contactsCursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        ) ?: return null

        contactsCursor.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))

            val phones = mutableListOf<String>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { phoneCursor ->
                while (phoneCursor.moveToNext()) {
                    phones.add(phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                }
            }
            return ContactInfo(name, phones)
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
