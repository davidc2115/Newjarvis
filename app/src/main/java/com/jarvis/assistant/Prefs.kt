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
    private const val KEY_LOCAL_LLM_MODEL_ID = "local_llm_model_id"
    private const val KEY_GOOGLE_WEB_CLIENT_ID = "google_web_client_id"
    private const val KEY_GOOGLE_ACCOUNTS = "google_linked_accounts_json"
    private const val KEY_EMAIL_ACCOUNTS = "email_accounts_json"
    private const val KEY_GOOGLE_ACCESS_TOKEN = "google_oauth_access_token"
    private const val KEY_GOOGLE_ACCESS_TOKEN_EXPIRY = "google_oauth_access_token_expiry_millis"
    private const val KEY_OBSIDIAN_VAULT_URI = "obsidian_vault_tree_uri"
    private const val KEY_TTS_ENABLED = "tts_enabled"
    private const val KEY_USER_NAME = "user_display_name"
    private const val KEY_GOOGLE_ACTIVE_ACCOUNT_EMAIL = "google_active_account_email"
    private const val KEY_GOOGLE_ACCOUNT_TOKENS = "google_account_tokens_json"

    /** Identifiants des backends IA supportés (voir GeminiNanoController / LocalLlmController).
     *  MODEL_LOCAL_LLM garde la valeur "gemma" (pas "local_llm") pour ne pas casser la
     *  préférence déjà enregistrée sur les téléphones où Gemma était sélectionné avant ce
     *  changement -- seul le NOM du backend a changé (Gemma -> registre Qwen), pas son rôle
     *  ni sa position dans l'UI (2e ligne, "IA locale"). */
    const val MODEL_GEMINI_NANO = "gemini_nano"
    const val MODEL_LOCAL_LLM = "gemma"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedModel(context: Context): String =
        prefs(context).getString(KEY_SELECTED_MODEL, MODEL_GEMINI_NANO) ?: MODEL_GEMINI_NANO

    fun setSelectedModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    /** Quel modèle du registre LocalLlmController.AVAILABLE_MODELS est actif pour le
     *  backend "IA locale" -- voir LocalLlmController.modelById (retombe sur QWEN3_0_6B si
     *  la valeur enregistrée ne correspond à aucun modèle connu, ex. après suppression d'un
     *  modèle du registre). */
    fun getLocalLlmModelId(context: Context): String =
        prefs(context).getString(KEY_LOCAL_LLM_MODEL_ID, null) ?: LocalLlmController.QWEN3_0_6B.id

    fun setLocalLlmModelId(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_LOCAL_LLM_MODEL_ID, modelId).apply()
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

    // Synthese vocale des reponses de JARVIS (tache voix/personnalite -- "vraie appli JARVIS").
    // Active par defaut : un assistant JARVIS qui ne parle pas ne ressemble pas a JARVIS.
    // L'utilisateur peut couper via le bouton haut-parleur dans la barre du haut.
    fun isTtsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS_ENABLED, true)

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    // Nom/prénom que l'utilisateur veut que JARVIS utilise pour s'adresser à lui (axe "vrai
    // dialogue" -- un assistant qui ne connaît pas le nom de son utilisateur ne peut pas
    // vraiment personnaliser la conversation). Optionnel : null/vide si jamais renseigné dans
    // Réglages, auquel cas MainActivity.buildConversationalPrompt reste neutre plutôt que
    // d'inventer un nom.
    fun getUserName(context: Context): String? =
        prefs(context).getString(KEY_USER_NAME, null)?.trim()?.ifBlank { null }

    fun setUserName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

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

    fun getGoogleWebClientId(context: Context): String? {
        // isNullOrBlank() et pas juste null : une ancienne version de l'appli a pu enregistrer
        // une chaîne vide (champ Réglages quitté sans saisie, avant l'ajout de cette valeur par
        // défaut) -- cette préférence survit à une simple réinstallation par-dessus (pas un
        // désinstall complet), donc un null-check seul ne suffisait pas à activer le défaut.
        val saved = prefs(context).getString(KEY_GOOGLE_WEB_CLIENT_ID, null)
        return if (saved.isNullOrBlank()) DEFAULT_GOOGLE_WEB_CLIENT_ID else saved
    }

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

    /**
     * URI de l'arborescence (SAF -- Storage Access Framework, DocumentsContract.Document tree)
     * choisie par l'utilisateur comme vault Obsidian réel sur son téléphone (voir
     * ObsidianController). Stockée en String (Uri.toString()) -- la permission d'accès
     * persistante est prise séparément via ContentResolver.takePersistableUriPermission() au
     * moment du choix (voir SettingsActivity), sans quoi elle ne survivrait pas à un redémarrage
     * de l'appli/du téléphone.
     */
    fun getObsidianVaultUri(context: Context): String? = prefs(context).getString(KEY_OBSIDIAN_VAULT_URI, null)

    fun setObsidianVaultUri(context: Context, uri: String?) {
        prefs(context).edit().putString(KEY_OBSIDIAN_VAULT_URI, uri).apply()
    }

    /**
     * Email du compte Google dont le jeton est actuellement en cache (voir
     * getGoogleAccessToken/setGoogleAccessToken -- UN SEUL jeton à la fois, pas un par compte
     * lié). Sert uniquement à AFFICHER clairement à l'utilisateur quel compte parmi ceux liés
     * est actif pour Agenda/Mail -- voir developer.android.com/identity/authorization
     * ("Authorization from a non-default account") : l'API Google elle-même n'autorise qu'un
     * seul "compte par défaut" à la fois pour AuthorizationClient, il faut re-choisir un compte
     * dans le sélecteur système pour en activer un autre, ce n'est pas une limite de JARVIS.
     */
    fun getActiveGoogleAccountEmail(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_ACTIVE_ACCOUNT_EMAIL, null)

    fun setActiveGoogleAccountEmail(context: Context, email: String) {
        prefs(context).edit().putString(KEY_GOOGLE_ACTIVE_ACCOUNT_EMAIL, email).apply()
    }

    // --- Jetons OAuth PAR compte (email -> {token, expiry}) --------------------------------
    // Ajoute a getGoogleAccessToken/setGoogleAccessToken (UN SEUL jeton "actif", toujours
    // conserve tel quel pour les operations d'ECRITURE -- creer/supprimer un evenement,
    // envoyer un mail -- qui doivent cibler un seul compte sans ambiguite). Ce deuxieme
    // stockage, lui, retient un jeton par compte AUTORISE (voir SettingsActivity.onLegacySignInResult,
    // qui ecrit dans les deux a chaque autorisation reussie) : il permet de LIRE (agenda, mails)
    // simultanement sur tous les comptes dont le jeton est encore valide, sans repasser par le
    // selecteur systeme a chaque fois -- contourne la limite "un seul compte par defaut a la
    // fois" de l'API Google (voir commentaire de getActiveGoogleAccountEmail) puisqu'on ne
    // redemande jamais un jeton pour un AUTRE compte que celui qui vient d'etre autorise : on se
    // contente de reutiliser ceux deja obtenus tant qu'ils n'ont pas expire (~55 min).
    fun setGoogleAccessTokenForAccount(context: Context, email: String, token: String, expiresInSeconds: Long = 3300) {
        if (email.isBlank()) return
        val map = loadGoogleAccountTokensRaw(context)
        val entry = JSONObject()
        entry.put("token", token)
        entry.put("expiry", System.currentTimeMillis() + expiresInSeconds * 1000)
        map.put(email, entry)
        prefs(context).edit().putString(KEY_GOOGLE_ACCOUNT_TOKENS, map.toString()).apply()
    }

    /** Tous les jetons de compte encore valides (non expires), email -> jeton. */
    fun getAllValidGoogleAccountTokens(context: Context): Map<String, String> {
        val map = loadGoogleAccountTokensRaw(context)
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, String>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val email = keys.next()
            val entry = map.optJSONObject(email) ?: continue
            val expiry = entry.optLong("expiry", 0L)
            val token = entry.optString("token", "")
            if (token.isNotBlank() && now < expiry) result[email] = token
        }
        return result
    }

    private fun loadGoogleAccountTokensRaw(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_GOOGLE_ACCOUNT_TOKENS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
