package com.jarvis.assistant

import android.content.Context
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage local minimal (SharedPreferences) : couleur d'accent choisie dans Réglages +
 * conversations du chat. Pas de base de données pour l'instant (projet reconstruit depuis
 * zéro, voir MainActivity) — un simple JSON suffit tant que le volume reste raisonnable ;
 * à remplacer par une vraie base si le nombre de conversations/messages grossit beaucoup.
 */
object Prefs {

    private const val PREFS_NAME = "jarvis_prefs"
    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_CONVERSATIONS = "conversations_json"
    private const val KEY_ACTIVE_CONVERSATION_ID = "active_conversation_id"
    private const val KEY_SELECTED_MODEL = "selected_ai_model"
    private const val KEY_HF_TOKEN = "huggingface_token"
    private const val KEY_GOOGLE_WEB_CLIENT_ID = "google_web_client_id"
    private const val KEY_GOOGLE_ACCOUNTS = "google_linked_accounts_json"

    /** Identifiants des backends IA supportés (voir GeminiNanoController / GemmaController). */
    const val MODEL_GEMINI_NANO = "gemini_nano"
    const val MODEL_GEMMA = "gemma"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedModel(context: Context): String =
        prefs(context).getString(KEY_SELECTED_MODEL, MODEL_GEMINI_NANO) ?: MODEL_GEMINI_NANO

    fun setSelectedModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    /** Jeton d'accès personnel Hugging Face (huggingface.co/settings/tokens), requis pour
     *  télécharger le modèle Gemma car son dépôt est soumis à l'acceptation de la licence
     *  Gemma. Jamais codé en dur dans le dépôt public -- saisi par l'utilisateur uniquement. */
    fun getHfToken(context: Context): String? = prefs(context).getString(KEY_HF_TOKEN, null)

    fun setHfToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getAccentColor(context: Context): Int {
        val stored = prefs(context).getInt(KEY_ACCENT_COLOR, Int.MIN_VALUE)
        return if (stored == Int.MIN_VALUE) ContextCompat.getColor(context, R.color.accent_default) else stored
    }

    fun setAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT_COLOR, color).apply()
    }

    fun getActiveConversationId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_CONVERSATION_ID, null)

    fun setActiveConversationId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_CONVERSATION_ID, id).apply()
    }

    fun loadConversations(context: Context): MutableList<Conversation> {
        val raw = prefs(context).getString(KEY_CONVERSATIONS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<Conversation>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val messages = mutableListOf<Message>()
                val msgArray = obj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgArray.length()) {
                    val m = msgArray.getJSONObject(j)
                    messages.add(Message(m.optString("text", ""), m.optBoolean("isUser", true), m.optLong("timestamp", 0L)))
                }
                result.add(Conversation(obj.getString("id"), obj.optString("title", "Conversation"), messages))
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveConversations(context: Context, conversations: List<Conversation>) {
        val array = JSONArray()
        conversations.forEach { conv ->
            val obj = JSONObject()
            obj.put("id", conv.id)
            obj.put("title", conv.title)
            val msgArray = JSONArray()
            conv.messages.forEach { msg ->
                val m = JSONObject()
                m.put("text", msg.text)
                m.put("isUser", msg.isUser)
                m.put("timestamp", msg.timestamp)
                msgArray.put(m)
            }
            obj.put("messages", msgArray)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_CONVERSATIONS, array.toString()).apply()
    }

    /** ID client OAuth "Web application" (Google Cloud Console -- voir GoogleAccountController),
     *  requis comme serverClientId par Credential Manager. Jamais codé en dur, saisi par
     *  l'utilisateur dans Réglages, comme le jeton Hugging Face ci-dessus. */
    fun getGoogleWebClientId(context: Context): String? = prefs(context).getString(KEY_GOOGLE_WEB_CLIENT_ID, null)

    fun setGoogleWebClientId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GOOGLE_WEB_CLIENT_ID, id).apply()
    }

    /** Comptes Google liés (email + nom affiché) -- voir GoogleAccountController.LinkedAccount. */
    fun loadGoogleAccounts(context: Context): MutableList<GoogleAccountController.LinkedAccount> {
        val raw = prefs(context).getString(KEY_GOOGLE_ACCOUNTS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<GoogleAccountController.LinkedAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    GoogleAccountController.LinkedAccount(
                        obj.getString("email"),
                        obj.optString("displayName", "")
                    )
                )
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveGoogleAccounts(context: Context, accounts: List<GoogleAccountController.LinkedAccount>) {
        val array = JSONArray()
        accounts.forEach { acc ->
            val obj = JSONObject()
            obj.put("email", acc.email)
            obj.put("displayName", acc.displayName)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_GOOGLE_ACCOUNTS, array.toString()).apply()
    }
}
