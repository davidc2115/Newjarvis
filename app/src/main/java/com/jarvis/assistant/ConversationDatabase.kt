package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class ConversationSummary(val id: Long, val title: String, val updatedAt: Long)

/**
 * Historique persistant des conversations (SQLite embarqué, zéro dépendance
 * externe). Chaque conversation est sauvegardée avec un titre dérivé du
 * premier message, pour être listée dans la barre latérale.
 */
class ConversationDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jarvis_conversations.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                messages_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS conversations")
        onCreate(db)
    }

    companion object {
        @Volatile private var instance: ConversationDatabase? = null

        fun get(context: Context): ConversationDatabase =
            instance ?: synchronized(this) {
                instance ?: ConversationDatabase(context).also { instance = it }
            }
    }
}

object ConversationHistoryManager {

    private fun attachmentToJson(a: Attachment): JSONObject = JSONObject()
        .put("path", a.path)
        .put("name", a.name)
        .put("mimeType", a.mimeType)
        .put("imageBase64", a.imageBase64 ?: JSONObject.NULL)
        .put("imageMime", a.imageMime ?: JSONObject.NULL)
        .put("extractedText", a.extractedText ?: JSONObject.NULL)

    private fun attachmentFromJson(o: JSONObject): Attachment = Attachment(
        path = o.optString("path", ""),
        name = o.optString("name", ""),
        mimeType = o.optString("mimeType", ""),
        imageBase64 = if (o.isNull("imageBase64")) null else o.optString("imageBase64"),
        imageMime = if (o.isNull("imageMime")) null else o.optString("imageMime"),
        extractedText = if (o.isNull("extractedText")) null else o.optString("extractedText")
    )

    private fun messagesToJson(messages: List<Message>): String {
        val arr = JSONArray()
        for (m in messages) {
            val attachmentsArr = JSONArray()
            m.attachments.forEach { attachmentsArr.put(attachmentToJson(it)) }
            arr.put(
                JSONObject()
                    .put("text", m.text)
                    .put("isUser", m.isUser)
                    .put("imageBase64", m.imageBase64 ?: JSONObject.NULL)
                    .put("imageMimeType", m.imageMimeType ?: JSONObject.NULL)
                    .put("attachmentPath", m.attachmentPath ?: JSONObject.NULL)
                    .put("attachmentName", m.attachmentName ?: JSONObject.NULL)
                    .put("attachments", attachmentsArr)
            )
        }
        return arr.toString()
    }

    private fun jsonToMessages(json: String): MutableList<Message> {
        val result = mutableListOf<Message>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // "attachments" et "attachmentPath"/"attachmentName" sont absents des messages
                // sauvegardés AVANT l'ajout du multi-pièces-jointes — optJSONArray/optString
                // gèrent nativement cette absence (liste vide / null), aucune migration requise.
                val attachmentsArr = o.optJSONArray("attachments")
                val attachments = if (attachmentsArr != null) {
                    (0 until attachmentsArr.length()).map { attachmentFromJson(attachmentsArr.getJSONObject(it)) }
                } else emptyList()
                result.add(
                    Message(
                        text = o.optString("text", ""),
                        isUser = o.optBoolean("isUser", false),
                        imageBase64 = if (o.isNull("imageBase64")) null else o.optString("imageBase64"),
                        imageMimeType = if (o.isNull("imageMimeType")) null else o.optString("imageMimeType"),
                        attachmentPath = if (o.isNull("attachmentPath")) null else o.optString("attachmentPath"),
                        attachmentName = if (o.isNull("attachmentName")) null else o.optString("attachmentName"),
                        attachments = attachments
                    )
                )
            }
        } catch (_: Exception) { }
        return result
    }

    private fun deriveTitle(messages: List<Message>): String {
        val firstUserMsg = messages.firstOrNull { it.isUser }?.text?.trim()
        if (firstUserMsg.isNullOrBlank()) return "Nouvelle conversation"
        return if (firstUserMsg.length > 40) firstUserMsg.take(40) + "…" else firstUserMsg
    }

    /** Sauvegarde (création ou mise à jour) la conversation courante. Renvoie son ID. */
    fun save(context: Context, conversationId: Long?, messages: List<Message>): Long? {
        if (messages.isEmpty()) return conversationId

        val db = ConversationDatabase.get(context).writableDatabase
        val values = ContentValues().apply {
            put("title", deriveTitle(messages))
            put("updated_at", System.currentTimeMillis())
            put("messages_json", messagesToJson(messages))
        }

        return if (conversationId != null) {
            db.update("conversations", values, "id = ?", arrayOf(conversationId.toString()))
            conversationId
        } else {
            db.insert("conversations", null, values)
        }
    }

    fun load(context: Context, conversationId: Long): MutableList<Message> {
        val db = ConversationDatabase.get(context).readableDatabase
        db.query(
            "conversations", arrayOf("messages_json"), "id = ?",
            arrayOf(conversationId.toString()), null, null, null
        ).use { c ->
            if (c.moveToFirst()) return jsonToMessages(c.getString(0))
        }
        return mutableListOf()
    }

    fun listAll(context: Context): List<ConversationSummary> {
        val db = ConversationDatabase.get(context).readableDatabase
        val result = mutableListOf<ConversationSummary>()
        db.query(
            "conversations", arrayOf("id", "title", "updated_at"),
            null, null, null, null, "updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                result.add(ConversationSummary(c.getLong(0), c.getString(1), c.getLong(2)))
            }
        }
        return result
    }

    fun delete(context: Context, conversationId: Long) {
        val db = ConversationDatabase.get(context).writableDatabase
        db.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
    }

    /** Supprime TOUTES les conversations enregistrées (table entière vidée). Irréversible. */
    fun deleteAll(context: Context) {
        val db = ConversationDatabase.get(context).writableDatabase
        db.delete("conversations", null, null)
    }
}
