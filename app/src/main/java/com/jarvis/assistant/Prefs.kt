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
    private const val KEY_EMAIL_ACCOUNTS = "email_accounts_json"
    private const val KEY_GOOGLE_ACCESS_TOKEN = "google_oauth_access_token"
    private const val KEY_GOOGLE_ACCESS_TOKEN_EXPIRY = "google_oauth_access_token_expiry_millis"

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
     *  requis comme serverClientId par Credential Manager. Ce n'est PAS un secret (contrairement
     *  au client secret, jamais utilisé ici) -- Google le documente explicitement comme un
     *  identifiant public sans risque à embarquer dans une appli, voir
     *  developer.android.com/identity/sign-in/credential-manager-siwg -- donc une valeur par
     *  défaut est acceptable ici, demande explicite de l'utilisateur. Reste modifiable dans
     *  Réglages si l'utilisateur crée son propre projet Cloud Console. */
    private const val DEFAULT_GOOGLE_WEB_CLIENT_ID =
        "253880913410-74a517f8fdmouu01hkojh01durm80236.apps.googleusercontent.com"

    fun getGoogleWebClientId(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_WEB_CLIENT_ID, null) ?: DEFAULT_GOOGLE_WEB_CLIENT_ID

    fun setGoogleWebClientId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GOOGLE_WEB_CLIENT_ID, id).apply()
    }

    /** Jeton d'accès OAuth Google (Gmail/Agenda), voir GoogleAccountController.requestAuthorization
     *  -- de courte durée de vie (~1h), on retient l'échéance pour savoir quand le redemander en
     *  silencieux plutôt que de le réutiliser expiré (l'API Google renverrait alors 401). */
    fun getGoogleAccessToken(context: Context): String? {
        val expiry = prefs(context).getLong(KEY_GOOGLE_ACCESS_TOKEN_EXPIRY, 0L)
        if (System.currentTimeMillis() >= expiry) return null
        return prefs(context).getString(KEY_GOOGLE_ACCESS_TOKEN, null)
    }

    fun setGoogleAccessToken(context: Context, token: String, expiresInSeconds: Long = 3300) {
        prefs(context).edit()
            .putString(KEY_GOOGLE_ACCESS_TOKEN, token)
            .putLong(KEY_GOOGLE_ACCESS_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
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

    // --- Comptes Email (IMAP/SMTP, mot de passe d'application) ------------------------------
    // Demande explicite utilisateur ("regarde comme c'était sur l'ancienne appli") : porté
    // depuis EmailController.kt/Prefs.kt de l'ancienne version du projet (avant la remise à
    // zéro), SIMPLIFIÉ pour ne garder que le chemin mot de passe d'application -- l'ancienne
    // appli avait aussi tenté une variante OAuth2 via AccountManager, jamais aboutie et
    // incompatible avec le retrait explicite de la Console Cloud (voir SettingsActivity.kt).

    data class EmailAccount(
        val id: String = System.currentTimeMillis().toString(),
        val label: String = "",
        val email: String = "",
        val password: String = "",
        val imapHost: String = "",
        val imapPort: Int = 993,
        val smtpHost: String = "",
        val smtpPort: Int = 587,
        val smtpStartTls: Boolean = true,
        val isDefault: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("label", label); put("email", email); put("password", password)
            put("imapHost", imapHost); put("imapPort", imapPort)
            put("smtpHost", smtpHost); put("smtpPort", smtpPort)
            put("smtpStartTls", smtpStartTls); put("isDefault", isDefault)
        }

        companion object {
            fun fromJson(j: JSONObject) = EmailAccount(
                id = j.optString("id", System.currentTimeMillis().toString()),
                label = j.optString("label", ""),
                email = j.optString("email", ""),
                password = j.optString("password", ""),
                imapHost = j.optString("imapHost", ""),
                imapPort = j.optInt("imapPort", 993),
                smtpHost = j.optString("smtpHost", ""),
                smtpPort = j.optInt("smtpPort", 587),
                smtpStartTls = j.optBoolean("smtpStartTls", true),
                isDefault = j.optBoolean("isDefault", false)
            )

            /** Configs pré-remplies pour les grands services -- l'utilisateur n'a plus qu'à
             *  saisir son adresse et son mot de passe d'application. */
            fun preset(service: String, email: String, password: String): EmailAccount? = when (service.lowercase()) {
                "gmail" -> EmailAccount(
                    label = "Gmail", email = email, password = password,
                    imapHost = "imap.gmail.com", imapPort = 993,
                    smtpHost = "smtp.gmail.com", smtpPort = 587, smtpStartTls = true
                )
                "outlook" -> EmailAccount(
                    label = "Outlook", email = email, password = password,
                    imapHost = "outlook.office365.com", imapPort = 993,
                    smtpHost = "smtp.office365.com", smtpPort = 587, smtpStartTls = true
                )
                "yahoo" -> EmailAccount(
                    label = "Yahoo", email = email, password = password,
                    imapHost = "imap.mail.yahoo.com", imapPort = 993,
                    smtpHost = "smtp.mail.yahoo.com", smtpPort = 587, smtpStartTls = true
                )
                "icloud" -> EmailAccount(
                    label = "iCloud", email = email, password = password,
                    imapHost = "imap.mail.me.com", imapPort = 993,
                    smtpHost = "smtp.mail.me.com", smtpPort = 587, smtpStartTls = true
                )
                else -> null
            }
        }
    }

    fun getEmailAccounts(context: Context): List<EmailAccount> {
        val json = prefs(context).getString(KEY_EMAIL_ACCOUNTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { EmailAccount.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDefaultEmailAccount(context: Context): EmailAccount? =
        getEmailAccounts(context).firstOrNull { it.isDefault } ?: getEmailAccounts(context).firstOrNull()

    fun saveEmailAccounts(context: Context, accounts: List<EmailAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_EMAIL_ACCOUNTS, arr.toString()).apply()
    }

    fun addEmailAccount(context: Context, account: EmailAccount) {
        val list = getEmailAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        // Le premier compte ajouté devient automatiquement le compte par défaut.
        val finalAccount = if (list.isEmpty()) account.copy(isDefault = true) else account
        list.add(finalAccount)
        saveEmailAccounts(context, list)
    }

    fun removeEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).filter { it.id != id }
        saveEmailAccounts(context, list)
    }
}
