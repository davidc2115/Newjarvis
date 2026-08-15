package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestion des préférences JARVIS v3.
 *
 * Nouveautés v3 :
 *  - Multi-clés par provider (JSON array) avec rotation round-robin
 *  - Blacklist temporaire des clés en erreur (429/401)
 *  - Stratégie de rotation configurable par provider
 *  - Comptes email IMAP/SMTP multiples
 *  - Paramètres de contrôle téléphone (permissions accordées)
 */
object Prefs {
    private const val PREFS_NAME = "jarvis_prefs"

    // ─── Clés de stockage ─────────────────────────────────────────────────────
    private const val KEY_PROVIDER          = "provider"
    private const val KEY_BASE_URL          = "base_url"
    private const val KEY_MODEL             = "model"
    private const val KEY_API_KEY           = "api_key"            // rétrocompat
    private const val KEY_LOCAL_MODEL_PATH  = "local_model_path"
    private const val KEY_LOCAL_MODEL_FORMAT= "local_model_format"
    private const val KEY_ACCENT_COLOR      = "accent_color"
    private const val KEY_HF_TOKEN          = "hf_token"
    private const val KEY_ORB_STYLE         = "orb_style"
    private const val KEY_EMAIL_ACCOUNTS    = "email_accounts"     // JSON array
    private const val KEY_GITHUB_ACCOUNTS   = "github_accounts"    // JSON array
    private const val KEY_ROTATION_STRATEGY = "rotation_strategy"  // "ROUNDROBIN"|"FALLBACK"|"RANDOM"
    private const val KEY_OBSIDIAN_VAULT_PATH = "obsidian_vault_path"

    const val DEFAULT_ACCENT_COLOR = -1525685 // #FFE8B84B (or — thème Apex Studio)

    // ═════════════════════════════════════════════════════════════════════════
    // MULTI-CLÉS API PAR PROVIDER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Retourne toutes les clés configurées pour ce provider.
     * Migration douce : si aucune liste n'existe, cherche l'ancienne clé unique.
     */
    fun getApiKeysFor(context: Context, provider: Provider): List<String> {
        val json = prefs(context).getString("api_keys_${provider.name}", null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        }
        // Migration : ancienne clé unique
        val legacy = prefs(context).getString("api_key_${provider.name}", "")
            ?: prefs(context).getString(KEY_API_KEY, "") ?: ""
        return if (legacy.isNotBlank()) listOf(legacy) else emptyList()
    }

    fun saveApiKeysFor(context: Context, provider: Provider, keys: List<String>) {
        val arr = JSONArray()
        keys.filter { it.isNotBlank() }.forEach { arr.put(it) }
        prefs(context).edit()
            .putString("api_keys_${provider.name}", arr.toString())
            .remove("api_key_${provider.name}") // nettoie l'ancien format
            .apply()
    }

    fun addApiKeyFor(context: Context, provider: Provider, key: String) {
        val current = getApiKeysFor(context, provider).toMutableList()
        if (key.isNotBlank() && !current.contains(key)) {
            current.add(key)
            saveApiKeysFor(context, provider, current)
        }
    }

    fun removeApiKeyFor(context: Context, provider: Provider, key: String) {
        val current = getApiKeysFor(context, provider).toMutableList()
        current.remove(key)
        saveApiKeysFor(context, provider, current)
        // Réinitialise l'index si nécessaire
        val idx = getKeyIndex(context, provider)
        if (idx >= current.size) saveKeyIndex(context, provider, 0)
    }

    /** Rétrocompat : retourne la 1ère clé configurée ou "". */
    fun getApiKeyFor(context: Context, provider: Provider): String =
        getApiKeysFor(context, provider).firstOrNull() ?: ""

    /** Sauvegarde un Map<Provider, String> en découpant les clés multiples par virgule ou retour à la ligne. */
    fun saveApiKeys(context: Context, keys: Map<Provider, String>) {
        for ((provider, keyStr) in keys) {
            val keysList = keyStr.split(",", "\n", ";").map { it.trim() }.filter { it.isNotBlank() }
            saveApiKeysFor(context, provider, keysList)
        }
    }

    /** Sauvegarde un Map<Provider, List<String>> en batch (écran Clés API). */
    fun saveAllApiKeys(context: Context, keysMap: Map<Provider, List<String>>) {
        val editor = prefs(context).edit()
        for ((provider, keys) in keysMap) {
            val arr = JSONArray()
            keys.filter { it.isNotBlank() }.forEach { arr.put(it) }
            editor.putString("api_keys_${provider.name}", arr.toString())
        }
        editor.apply()
    }

    // ─── Rotation round-robin ─────────────────────────────────────────────────

    /** Retourne la prochaine clé valide selon la stratégie configurée. */
    fun getNextApiKey(context: Context, provider: Provider): String {
        val keys = getApiKeysFor(context, provider)
        if (keys.isEmpty()) return ""
        val validKeys = keys.filter { !isKeyBlacklisted(context, provider, it) }
        if (validKeys.isEmpty()) {
            clearBlacklist(context, provider) // toutes blacklistées → reset
            return keys.first()
        }
        return when (getRotationStrategy(context)) {
            RotationStrategy.RANDOM -> validKeys.random()
            RotationStrategy.ROUNDROBIN -> {
                val idx = getKeyIndex(context, provider) % validKeys.size
                saveKeyIndex(context, provider, (idx + 1) % validKeys.size)
                validKeys[idx]
            }
            RotationStrategy.FALLBACK -> validKeys.first() // premier valide
        }
    }

    /** Signale une clé comme défaillante (blacklist temporaire 1h). */
    fun markKeyFailed(context: Context, provider: Provider, key: String) {
        val mapJson = prefs(context).getString("api_keys_failed_${provider.name}", "{}") ?: "{}"
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        map.put(key, System.currentTimeMillis())
        prefs(context).edit().putString("api_keys_failed_${provider.name}", map.toString()).apply()
    }

    private fun isKeyBlacklisted(context: Context, provider: Provider, key: String): Boolean {
        val mapJson = prefs(context).getString("api_keys_failed_${provider.name}", "{}") ?: "{}"
        return try {
            val map = JSONObject(mapJson)
            if (!map.has(key)) return false
            val ts = map.getLong(key)
            System.currentTimeMillis() - ts < 60 * 60 * 1000L // 1 heure
        } catch (_: Exception) { false }
    }

    private fun clearBlacklist(context: Context, provider: Provider) {
        prefs(context).edit().remove("api_keys_failed_${provider.name}").apply()
    }

    private fun getKeyIndex(context: Context, provider: Provider): Int =
        prefs(context).getInt("api_key_idx_${provider.name}", 0)

    private fun saveKeyIndex(context: Context, provider: Provider, idx: Int) {
        prefs(context).edit().putInt("api_key_idx_${provider.name}", idx).apply()
    }

    // ─── Stratégie de rotation ────────────────────────────────────────────────

    enum class RotationStrategy { ROUNDROBIN, FALLBACK, RANDOM }

    fun getRotationStrategy(context: Context): RotationStrategy =
        try {
            RotationStrategy.valueOf(
                prefs(context).getString(KEY_ROTATION_STRATEGY, "ROUNDROBIN") ?: "ROUNDROBIN"
            )
        } catch (_: Exception) { RotationStrategy.ROUNDROBIN }

    fun saveRotationStrategy(context: Context, strategy: RotationStrategy) {
        prefs(context).edit().putString(KEY_ROTATION_STRATEGY, strategy.name).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMPTES EMAIL IMAP / SMTP
    // ═════════════════════════════════════════════════════════════════════════

    data class EmailAccount(
        val id: String = System.currentTimeMillis().toString(),
        val label: String = "",          // ex: "Gmail perso"
        val email: String = "",          // adresse email complète
        val password: String = "",       // mot de passe ou app-password
        // IMAP (lecture)
        val imapHost: String = "",
        val imapPort: Int = 993,
        val imapSsl: Boolean = true,
        // SMTP (envoi)
        val smtpHost: String = "",
        val smtpPort: Int = 587,
        val smtpSsl: Boolean = false,
        val smtpStartTls: Boolean = true,
        val isDefault: Boolean = false,
        // OAuth2 Google (AccountManager)
        val oauthToken: String = "",
        val isOAuth: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("label", label); put("email", email)
            put("password", password)
            put("imapHost", imapHost); put("imapPort", imapPort); put("imapSsl", imapSsl)
            put("smtpHost", smtpHost); put("smtpPort", smtpPort); put("smtpSsl", smtpSsl)
            put("smtpStartTls", smtpStartTls); put("isDefault", isDefault)
            put("oauthToken", oauthToken); put("isOAuth", isOAuth)
        }

        companion object {
            fun fromJson(j: JSONObject) = EmailAccount(
                id          = j.optString("id", System.currentTimeMillis().toString()),
                label       = j.optString("label", ""),
                email       = j.optString("email", ""),
                password    = j.optString("password", ""),
                imapHost    = j.optString("imapHost", ""),
                imapPort    = j.optInt("imapPort", 993),
                imapSsl     = j.optBoolean("imapSsl", true),
                smtpHost    = j.optString("smtpHost", ""),
                smtpPort    = j.optInt("smtpPort", 587),
                smtpSsl     = j.optBoolean("smtpSsl", false),
                smtpStartTls= j.optBoolean("smtpStartTls", true),
                isDefault   = j.optBoolean("isDefault", false),
                oauthToken  = j.optString("oauthToken", ""),
                isOAuth     = j.optBoolean("isOAuth", false)
            )

            /** Configs pré-remplies pour les grands services. */
            fun preset(service: String, email: String, password: String): EmailAccount? = when (service.lowercase()) {
                "gmail" -> EmailAccount(
                    label = "Gmail", email = email, password = password,
                    imapHost = "imap.gmail.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.gmail.com", smtpPort = 587, smtpStartTls = true
                )
                "outlook", "hotmail", "live" -> EmailAccount(
                    label = "Outlook", email = email, password = password,
                    imapHost = "outlook.office365.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.office365.com", smtpPort = 587, smtpStartTls = true
                )
                "yahoo" -> EmailAccount(
                    label = "Yahoo", email = email, password = password,
                    imapHost = "imap.mail.yahoo.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.mail.yahoo.com", smtpPort = 587, smtpStartTls = true
                )
                "icloud" -> EmailAccount(
                    label = "iCloud", email = email, password = password,
                    imapHost = "imap.mail.me.com", imapPort = 993, imapSsl = true,
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
        } catch (_: Exception) { emptyList() }
    }

    fun getDefaultEmailAccount(context: Context): EmailAccount? =
        getEmailAccounts(context).firstOrNull { it.isDefault }
            ?: getEmailAccounts(context).firstOrNull()

    fun saveEmailAccounts(context: Context, accounts: List<EmailAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_EMAIL_ACCOUNTS, arr.toString()).apply()
    }

    fun addEmailAccount(context: Context, account: EmailAccount) {
        val list = getEmailAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        list.add(account)
        saveEmailAccounts(context, list)
    }

    fun removeEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).filter { it.id != id }
        saveEmailAccounts(context, list)
    }

    fun setDefaultEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).map { it.copy(isDefault = it.id == id) }
        saveEmailAccounts(context, list)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROVIDER ACTIF, URL, MODÈLE
    // ═════════════════════════════════════════════════════════════════════════

    fun getProvider(context: Context): Provider =
        Provider.fromName(
            prefs(context).getString(KEY_PROVIDER, Provider.GROQ.name) ?: Provider.GROQ.name
        )

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, Provider.GROQ.defaultBaseUrl)
            ?: Provider.GROQ.defaultBaseUrl

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, Provider.GROQ.defaultModel)
            ?: Provider.GROQ.defaultModel

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    // ─── Modèle local ─────────────────────────────────────────────────────────

    fun getLocalModelPath(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_PATH, "") ?: ""

    fun saveLocalModelPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH, path).apply()
    }

    // ─── Modèle Stable Diffusion local (génération d'image embarquée) ──────────

    fun getLocalSdModelPath(context: Context): String =
        prefs(context).getString("local_sd_model_path", "") ?: ""

    fun saveLocalSdModelPath(context: Context, path: String) {
        prefs(context).edit().putString("local_sd_model_path", path).apply()
    }

    fun getLocalModelFormat(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_FORMAT, "TASK") ?: "TASK"

    fun saveLocalModelFormat(context: Context, format: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_FORMAT, format).apply()
    }

    // ─── UI / Style ───────────────────────────────────────────────────────────

    fun getAccentColor(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)

    fun saveAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT_COLOR, color).apply()
    }

    // ─── Thème du chat (fond + bulles), personnalisable depuis le chat/vocal ──
    // 0 = pas de surcharge, on garde les couleurs par défaut du thème Apex Studio.
    private const val KEY_CHAT_BG_COLOR = "chat_bg_color"
    private const val KEY_CHAT_BUBBLE_USER_COLOR = "chat_bubble_user_color"
    private const val KEY_CHAT_BUBBLE_AI_COLOR = "chat_bubble_ai_color"

    fun getChatBackgroundColor(context: Context): Int = prefs(context).getInt(KEY_CHAT_BG_COLOR, 0)
    fun saveChatBackgroundColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CHAT_BG_COLOR, color).apply()
    }

    fun getChatBubbleUserColor(context: Context): Int = prefs(context).getInt(KEY_CHAT_BUBBLE_USER_COLOR, 0)
    fun saveChatBubbleUserColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CHAT_BUBBLE_USER_COLOR, color).apply()
    }

    fun getChatBubbleAiColor(context: Context): Int = prefs(context).getInt(KEY_CHAT_BUBBLE_AI_COLOR, 0)
    fun saveChatBubbleAiColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CHAT_BUBBLE_AI_COLOR, color).apply()
    }

    fun resetChatTheme(context: Context) {
        prefs(context).edit()
            .remove(KEY_CHAT_BG_COLOR)
            .remove(KEY_CHAT_BUBBLE_USER_COLOR)
            .remove(KEY_CHAT_BUBBLE_AI_COLOR)
            .apply()
    }

    // ─── Style de présentation préféré des fiches contact ─────────────────────
    // Cause réelle du bug "je demande un type de présentation et il ne le reprend pas
    // automatiquement" : le format d'une fiche contact était toujours généré tel quel par
    // PeopleController.formatFullDetails (fixe, en dur), et la seule façon de "changer" la
    // présentation était que l'IA reformule elle-même le texte dans SA réponse pour CE tour
    // précis — sans aucune mémoire persistante au-delà de la fenêtre de contexte de la
    // conversation en cours. Dès que la conversation redémarre ou que le contexte s'allonge,
    // la consigne de mise en forme est oubliée et JARVIS revient au format brut par défaut.
    private const val KEY_CONTACT_PRESENTATION_STYLE = "contact_presentation_style"

    fun getContactPresentationStyle(context: Context): String =
        prefs(context).getString(KEY_CONTACT_PRESENTATION_STYLE, "") ?: ""

    fun saveContactPresentationStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_CONTACT_PRESENTATION_STYLE, style.trim()).apply()
    }

    fun resetContactPresentationStyle(context: Context) {
        prefs(context).edit().remove(KEY_CONTACT_PRESENTATION_STYLE).apply()
    }

    // Même principe que ci-dessus mais pour la présentation de la localisation d'une personne
    // (ha_status{domain:"person"}) — demandé par l'utilisateur en même temps que la persistance
    // du style des fiches contact.
    private const val KEY_LOCATION_PRESENTATION_STYLE = "location_presentation_style"

    fun getLocationPresentationStyle(context: Context): String =
        prefs(context).getString(KEY_LOCATION_PRESENTATION_STYLE, "") ?: ""

    fun saveLocationPresentationStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_LOCATION_PRESENTATION_STYLE, style.trim()).apply()
    }

    fun resetLocationPresentationStyle(context: Context) {
        prefs(context).edit().remove(KEY_LOCATION_PRESENTATION_STYLE).apply()
    }

    // ─── Liens cliquables (tel/mail/itinéraire) dans le texte des fiches ──────
    // Désactivé par défaut (false) : à la demande explicite de l'utilisateur, ces liens ne
    // doivent plus apparaître automatiquement dès qu'un numéro/email/adresse est détecté —
    // seulement quand il le demande, via enable_contact_links.
    private const val KEY_CONTACT_LINKS_ENABLED = "contact_links_enabled"

    fun isContactLinksEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONTACT_LINKS_ENABLED, false)

    fun setContactLinksEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTACT_LINKS_ENABLED, enabled).apply()
    }

    fun getHfToken(context: Context): String =
        prefs(context).getString(KEY_HF_TOKEN, "") ?: ""

    fun saveHfToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getGithubToken(context: Context): String =
        prefs(context).getString("github_token", "") ?: ""

    // ═════════════════════════════════════════════════════════════════════════
    // MULTI-COMPTES GITHUB
    // ═════════════════════════════════════════════════════════════════════════

    data class GitHubAccount(
        val id: String = System.currentTimeMillis().toString(),
        val label: String = "",   // ex: "Perso", "Pro", "Client X"
        val token: String = "",
        val isDefault: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("label", label); put("token", token); put("isDefault", isDefault)
        }

        companion object {
            fun fromJson(j: JSONObject) = GitHubAccount(
                id        = j.optString("id", System.currentTimeMillis().toString()),
                label     = j.optString("label", ""),
                token     = j.optString("token", ""),
                isDefault = j.optBoolean("isDefault", false)
            )
        }
    }

    /**
     * Migration douce : si aucun compte n'a encore été ajouté à la nouvelle liste
     * multi-comptes mais qu'un ancien jeton unique (github_token, ⚙ → Clés API) existe,
     * il est exposé comme un compte "Principal" par défaut — la configuration existante
     * de l'utilisateur continue de fonctionner sans qu'il ait besoin de tout reconfigurer.
     */
    fun getGithubAccounts(context: Context): List<GitHubAccount> {
        val json = prefs(context).getString(KEY_GITHUB_ACCOUNTS, "[]") ?: "[]"
        val stored = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { GitHubAccount.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        if (stored.isNotEmpty()) return stored
        val legacyToken = getGithubToken(context)
        return if (legacyToken.isNotBlank()) {
            listOf(GitHubAccount(id = "legacy", label = "Principal", token = legacyToken, isDefault = true))
        } else {
            emptyList()
        }
    }

    fun getDefaultGithubAccount(context: Context): GitHubAccount? =
        getGithubAccounts(context).firstOrNull { it.isDefault }
            ?: getGithubAccounts(context).firstOrNull()

    /** Recherche floue d'un compte GitHub par son libellé (ex: "perso", "pro", "client X"). */
    fun findGithubAccount(context: Context, label: String): GitHubAccount? {
        if (label.isBlank()) return null
        val q = label.trim().lowercase()
        val all = getGithubAccounts(context)
        return all.firstOrNull { it.label.lowercase() == q }
            ?: all.firstOrNull { it.label.lowercase().contains(q) }
    }

    fun saveGithubAccounts(context: Context, accounts: List<GitHubAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_GITHUB_ACCOUNTS, arr.toString()).apply()
    }

    fun addGithubAccount(context: Context, account: GitHubAccount) {
        val list = getGithubAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        // Le tout premier compte ajouté devient automatiquement celui par défaut.
        val withDefault = if (list.isEmpty()) account.copy(isDefault = true) else account
        list.add(withDefault)
        saveGithubAccounts(context, list)
    }

    fun removeGithubAccount(context: Context, id: String) {
        val list = getGithubAccounts(context).filter { it.id != id }
        saveGithubAccounts(context, list)
    }

    fun setDefaultGithubAccount(context: Context, id: String) {
        val list = getGithubAccounts(context).map { it.copy(isDefault = it.id == id) }
        saveGithubAccounts(context, list)
    }

    // ─── Écoute permanente (mot-clé d'activation) ───────────────────────────────

    fun getWakeWord(context: Context): String =
        prefs(context).getString("wake_word", "jarvis") ?: "jarvis"

    fun saveWakeWord(context: Context, word: String) {
        prefs(context).edit().putString("wake_word", word.ifBlank { "jarvis" }).apply()
    }

    fun isWakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean("wake_word_enabled", false)

    fun saveWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wake_word_enabled", enabled).apply()
    }

    /** Clé d'accès gratuite Picovoice (console.picovoice.ai) pour le moteur de détection dédié. */
    fun getPicovoiceKey(context: Context): String =
        prefs(context).getString("picovoice_key", "") ?: ""

    fun savePicovoiceKey(context: Context, key: String) {
        prefs(context).edit().putString("picovoice_key", key.trim()).apply()
    }

    // ─── Surnoms de calendriers (pour distinguer plusieurs agendas similaires) ──

    fun getCalendarNickname(context: Context, calendarId: Long): String =
        prefs(context).getString("calendar_nickname_$calendarId", "") ?: ""

    fun saveCalendarNickname(context: Context, calendarId: Long, nickname: String) {
        prefs(context).edit().putString("calendar_nickname_$calendarId", nickname).apply()
    }

    /** Retrouve l'ID d'un calendrier à partir d'un surnom déjà enregistré. */
    fun findCalendarIdByNickname(context: Context, nickname: String): Long? {
        val all = prefs(context).all
        for ((key, value) in all) {
            if (key.startsWith("calendar_nickname_") && value is String &&
                value.equals(nickname, ignoreCase = true)
            ) {
                return key.removePrefix("calendar_nickname_").toLongOrNull()
            }
        }
        return null
    }

    /**
     * Efface TOUS les surnoms de calendrier enregistrés. Ces surnoms vivent dans les
     * SharedPreferences de l'app (pas dans le vault Obsidian) — "réinitialiser Obsidian"
     * ne les touche donc jamais, ce qui explique qu'ils survivent à un vidage du vault.
     * Cette fonction est le vrai bouton de réinitialisation pour eux spécifiquement.
     */
    fun clearAllCalendarNicknames(context: Context): Int {
        val p = prefs(context)
        val keysToRemove = p.all.keys.filter { it.startsWith("calendar_nickname_") }
        val editor = p.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
        return keysToRemove.size
    }


    fun saveGithubToken(context: Context, token: String) {
        prefs(context).edit().putString("github_token", token).apply()
    }

    fun getOrbStyle(context: Context): String =
        prefs(context).getString(KEY_ORB_STYLE, "NETWORK_SPHERE") ?: "NETWORK_SPHERE"

    fun saveOrbStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_ORB_STYLE, style).apply()
    }

    // ─── Sauvegarde groupée ───────────────────────────────────────────────────

    fun save(context: Context, provider: Provider, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_BASE_URL, baseUrl.ifBlank { provider.defaultBaseUrl })
            .putString(KEY_MODEL, model.ifBlank { provider.defaultModel })
            .putString(KEY_API_KEY, apiKey)
            .apply()
        if (!provider.isLocal && !provider.isAuto && apiKey.isNotBlank()) {
            // Ajoute à la liste multi-clés si pas déjà présente
            addApiKeyFor(context, provider, apiKey)
        }
    }

    // ─── Obsidian Second Brain ────────────────────────────────────────────────

    fun getObsidianVaultPath(context: Context): String =
        prefs(context).getString(KEY_OBSIDIAN_VAULT_PATH, "") ?: ""

    fun saveObsidianVaultPath(context: Context, path: String) =
        prefs(context).edit().putString(KEY_OBSIDIAN_VAULT_PATH, path).apply()

    // ═════════════════════════════════════════════════════════════════════════
    // HOME ASSISTANT (domotique)
    // ═════════════════════════════════════════════════════════════════════════

    fun getHaUrl(context: Context): String =
        prefs(context).getString("ha_url", "") ?: ""

    fun saveHaUrl(context: Context, url: String) {
        prefs(context).edit().putString("ha_url", url.trim().trimEnd('/')).apply()
    }

    fun getHaToken(context: Context): String =
        prefs(context).getString("ha_token", "") ?: ""

    fun saveHaToken(context: Context, token: String) {
        prefs(context).edit().putString("ha_token", token.trim()).apply()
    }

    /**
     * URL distante de secours pour Home Assistant (ex: URL Nabu Casa
     * "https://xxxx.ui.nabu.casa", ou une URL externe/DDNS + reverse-proxy configurée
     * par l'utilisateur) — utilisée UNIQUEMENT quand l'URL locale (ha_url) est
     * injoignable, pour piloter la maison même hors du réseau Wi-Fi local.
     */
    fun getHaRemoteUrl(context: Context): String =
        prefs(context).getString("ha_remote_url", "") ?: ""

    fun saveHaRemoteUrl(context: Context, url: String) {
        prefs(context).edit().putString("ha_remote_url", url.trim().trimEnd('/')).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RÉSEAU LOCAL — appareils enregistrés (pour Wake-on-LAN rapide)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * [remoteHost] = adresse publique/DDNS (optionnellement suivie de ":port") vers laquelle
     * basculer quand l'appareil n'est plus joignable en local (donc hors du Wi-Fi domestique).
     * JARVIS ne peut PAS créer cet accès lui-même : l'utilisateur doit avoir configuré une
     * redirection de port (port forwarding) sur sa box/routeur vers cet appareil, idéalement
     * avec une IP fixe ou un nom DDNS (No-IP, DuckDNS...) puisque l'IP publique change souvent.
     * Une fois ce champ renseigné, network_ping/network_open_web/wake_on_lan/print_file
     * basculent automatiquement dessus si l'accès local échoue.
     */
    data class SavedDevice(val name: String, val mac: String, val ip: String = "", val remoteHost: String = "")

    fun getSavedNetworkDevices(context: Context): List<SavedDevice> {
        val json = prefs(context).getString("network_saved_devices", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedDevice(o.optString("name"), o.optString("mac"), o.optString("ip", ""), o.optString("remoteHost", ""))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveNetworkDevice(context: Context, device: SavedDevice) {
        val list = getSavedNetworkDevices(context).filter { it.name != device.name }.toMutableList()
        list.add(device)
        writeSavedNetworkDevices(context, list)
    }

    fun removeNetworkDevice(context: Context, name: String) {
        writeSavedNetworkDevices(context, getSavedNetworkDevices(context).filter { it.name != name })
    }

    /** Enregistre/actualise l'adresse distante (publique/DDNS) d'un appareil déjà connu (ou nouveau). */
    fun setDeviceRemoteHost(context: Context, name: String, remoteHost: String) {
        val list = getSavedNetworkDevices(context).toMutableList()
        val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx >= 0) {
            list[idx] = list[idx].copy(remoteHost = remoteHost.trim())
        } else {
            list.add(SavedDevice(name = name, mac = "", ip = "", remoteHost = remoteHost.trim()))
        }
        writeSavedNetworkDevices(context, list)
    }

    /**
     * Enregistre automatiquement les appareils trouvés lors d'un scan réseau
     * (📡 Réseau local, ou commande vocale/chat "scanne le réseau") dans la
     * liste des appareils connus, pour qu'ils apparaissent dans l'écran
     * Réseau local sans action manuelle. Un appareil déjà connu (même IP) est
     * mis à jour (nom rafraîchi) sans écraser une adresse MAC déjà renseignée
     * manuellement pour le Wake-on-LAN.
     */
    fun saveScannedDevices(context: Context, devices: List<NetworkController.Device>) {
        if (devices.isEmpty()) return
        val existing = getSavedNetworkDevices(context).toMutableList()

        devices.forEach { scanned ->
            val label = scanned.label
            val scannedMac = scanned.mac?.takeIf { it.isNotBlank() } ?: ""
            val existingIdx = existing.indexOfFirst { it.ip.isNotBlank() && it.ip == scanned.ip }
            if (existingIdx >= 0) {
                val current = existing[existingIdx]
                existing[existingIdx] = current.copy(
                    // Ne renomme que si l'utilisateur n'a pas déjà donné un nom manuel (mac vide = probablement auto-détecté).
                    name = if (current.mac.isBlank() && current.name != label) label else current.name,
                    // Garde une MAC déjà connue ; ne la remplace que si elle était vide.
                    mac = current.mac.ifBlank { scannedMac },
                    ip = scanned.ip
                )
            } else {
                val nameTaken = existing.any { it.name == label }
                val finalName = if (nameTaken) "$label (${scanned.ip})" else label
                existing.add(SavedDevice(name = finalName, mac = scannedMac, ip = scanned.ip))
            }
        }

        writeSavedNetworkDevices(context, existing)
    }

    private fun writeSavedNetworkDevices(context: Context, list: List<SavedDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().put("name", d.name).put("mac", d.mac).put("ip", d.ip).put("remoteHost", d.remoteHost))
        }
        prefs(context).edit().putString("network_saved_devices", arr.toString()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GÉNÉRATION VIDÉO (Replicate) & SITES WEB
    // ═════════════════════════════════════════════════════════════════════════

    /** Jeton API Replicate (replicate.com/account/api-tokens) pour la génération vidéo IA. */
    fun getReplicateToken(context: Context): String =
        prefs(context).getString("replicate_token", "") ?: ""

    fun saveReplicateToken(context: Context, token: String) {
        prefs(context).edit().putString("replicate_token", token.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // IMPRIMANTE RÉSEAU PAR DÉFAUT (IPP)
    // ═════════════════════════════════════════════════════════════════════════

    /** Adresse IP de l'imprimante réseau à utiliser par défaut quand aucune n'est précisée dans la commande. */
    fun getDefaultPrinterIp(context: Context): String =
        prefs(context).getString("default_printer_ip", "") ?: ""

    fun saveDefaultPrinterIp(context: Context, ip: String) {
        prefs(context).edit().putString("default_printer_ip", ip.trim()).apply()
    }

    /**
     * Adresse distante (publique/DDNS, ex: "monreseau.ddns.net:6310") de l'imprimante par
     * défaut, utilisée en repli si l'IP locale est injoignable (donc aussi hors Wi-Fi
     * domestique) — nécessite que l'utilisateur ait redirigé le port 631 (ou un port de son
     * choix) de sa box/routeur vers l'imprimante.
     */
    fun getDefaultPrinterRemoteHost(context: Context): String =
        prefs(context).getString("default_printer_remote_host", "") ?: ""

    fun saveDefaultPrinterRemoteHost(context: Context, host: String) {
        prefs(context).edit().putString("default_printer_remote_host", host.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ACCÈS SMB/CIFS GÉNÉRIQUE (voir SmbController) — remplace la réintégration
    // complète de l'API Freebox par un accès standard au partage réseau
    // (fonctionne aussi avec un NAS, un PC Windows partagé, etc.), à la
    // demande explicite de l'utilisateur suite au retrait de FreeboxController.
    // ═════════════════════════════════════════════════════════════════════════

    fun getSmbHost(context: Context): String =
        prefs(context).getString("smb_host", "") ?: ""

    fun saveSmbHost(context: Context, host: String) {
        prefs(context).edit().putString("smb_host", host.trim()).apply()
    }

    fun getSmbUsername(context: Context): String =
        prefs(context).getString("smb_username", "") ?: ""

    fun saveSmbUsername(context: Context, username: String) {
        prefs(context).edit().putString("smb_username", username.trim()).apply()
    }

    fun getSmbPassword(context: Context): String =
        prefs(context).getString("smb_password", "") ?: ""

    fun saveSmbPassword(context: Context, password: String) {
        prefs(context).edit().putString("smb_password", password).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FREEBOX OS API (contrôle complet : LAN, Wi-Fi, domotique Freebox Home)
    // Ré-ajoutée à la demande explicite de l'utilisateur (accès total lecture/écriture
    // depuis JARVIS). app_token = jeton obtenu lors de l'appairage de l'app avec la
    // Freebox (Freebox OS -> Paramètres -> Gestion des accès -> Applications), JAMAIS
    // codé en dur dans le code source (dépôt public) : uniquement saisi ici via ⚙.
    // ═════════════════════════════════════════════════════════════════════════

    fun getFreeboxHost(context: Context): String {
        val raw = prefs(context).getString("freebox_host", "") ?: ""
        return raw.ifBlank { "http://mafreebox.freebox.fr" }
    }

    fun saveFreeboxHost(context: Context, host: String) {
        prefs(context).edit().putString("freebox_host", host.trim()).apply()
    }

    fun getFreeboxAppId(context: Context): String =
        prefs(context).getString("freebox_app_id", "") ?: ""

    fun saveFreeboxAppId(context: Context, appId: String) {
        prefs(context).edit().putString("freebox_app_id", appId.trim()).apply()
    }

    fun getFreeboxAppToken(context: Context): String =
        prefs(context).getString("freebox_app_token", "") ?: ""

    fun saveFreeboxAppToken(context: Context, token: String) {
        prefs(context).edit().putString("freebox_app_token", token.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DUCKDNS (nom de domaine gratuit + mise a jour d'IP, pour heberger un site
    // genere par JARVIS directement depuis le telephone). Le jeton DuckDNS n'est
    // JAMAIS code en dur dans le code source (depot public) : uniquement saisi
    // ici via l'ecran Parametres, comme pour les autres cles/jetons de l'app.
    // ═════════════════════════════════════════════════════════════════════════

    fun getDuckDnsDomain(context: Context): String =
        prefs(context).getString("duckdns_domain", "") ?: ""

    fun saveDuckDnsDomain(context: Context, domain: String) {
        prefs(context).edit().putString("duckdns_domain", domain.trim().lowercase().removeSuffix(".duckdns.org")).apply()
    }

    fun getDuckDnsToken(context: Context): String =
        prefs(context).getString("duckdns_token", "") ?: ""

    fun saveDuckDnsToken(context: Context, token: String) {
        prefs(context).edit().putString("duckdns_token", token.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HISTORIQUE DES GÉNÉRATIONS (image / vidéo / site web)
    // ═════════════════════════════════════════════════════════════════════════

    data class GenerationRecord(
        val id: String,
        val type: String,          // "image" | "video" | "website"
        val prompt: String,
        val status: String,        // "pending" | "success" | "failed"
        val timestamp: Long,
        val resultPath: String? = null,
        val errorMessage: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("type", type); put("prompt", prompt)
            put("status", status); put("timestamp", timestamp)
            put("resultPath", resultPath ?: ""); put("errorMessage", errorMessage ?: "")
        }
        companion object {
            fun fromJson(j: JSONObject) = GenerationRecord(
                id = j.optString("id"),
                type = j.optString("type"),
                prompt = j.optString("prompt"),
                status = j.optString("status", "pending"),
                timestamp = j.optLong("timestamp", System.currentTimeMillis()),
                resultPath = j.optString("resultPath", "").ifBlank { null },
                errorMessage = j.optString("errorMessage", "").ifBlank { null }
            )
        }
    }

    private const val MAX_GENERATION_HISTORY = 100

    /** Les plus récentes en premier. */
    fun getGenerationHistory(context: Context): List<GenerationRecord> {
        val json = prefs(context).getString("generation_history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { GenerationRecord.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun addGenerationRecord(context: Context, record: GenerationRecord) {
        val list = getGenerationHistory(context).toMutableList()
        list.add(0, record)
        writeGenerationHistory(context, list.take(MAX_GENERATION_HISTORY))
        if (record.status == "success") logGenerationToVault(context, record)
    }

    /** Met à jour l'enregistrement identifié par [id] (ex: passage pending → success/failed). */
    fun updateGenerationRecord(context: Context, id: String, transform: (GenerationRecord) -> GenerationRecord) {
        var updated: GenerationRecord? = null
        val list = getGenerationHistory(context).map {
            if (it.id == id) {
                val was = it.status
                val t = transform(it)
                if (was != "success" && t.status == "success") updated = t
                t
            } else it
        }
        writeGenerationHistory(context, list)
        updated?.let { logGenerationToVault(context, it) }
    }

    /**
     * Obsidian en premier plan : toute génération réussie (image, vidéo, site web, graphique,
     * PDF/Word/Excel/ZIP) est aussi tracée sous forme de note dans le vault (dossier « Générations »),
     * en plus de rester listée par list_generations — ainsi obsidian_search/obsidian_list retrouvent
     * aussi ces actions, et l'historique survit même si le cache generation_history est un jour purgé.
     * Best-effort : une erreur d'écriture vault (permission stockage manquante, etc.) ne doit jamais
     * faire échouer la génération elle-même, d'où le try/catch silencieux (déjà loggé côté ObsidianController).
     */
    private fun logGenerationToVault(context: Context, record: GenerationRecord) {
        try {
            val typeLabel = when (record.type) {
                "image" -> "🎨 Image générée"
                "video" -> "🎬 Vidéo générée"
                "website", "website_edit" -> "🌐 Site web généré"
                "chart" -> "📊 Graphique généré"
                "file_pdf" -> "📄 PDF créé"
                "file_docx" -> "📝 Document Word créé"
                "file_xlsx" -> "📊 Tableur Excel créé"
                "file_zip" -> "🗜️ Archive ZIP créée"
                else -> "✨ Génération (${record.type})"
            }
            val safePrompt = record.prompt.ifBlank { "(sans description)" }
            val title = "$typeLabel — ${safePrompt.take(60)}"
            val content = buildString {
                append("Type : ${record.type}\n")
                append("Description : $safePrompt\n")
                if (!record.resultPath.isNullOrBlank()) append("Fichier : ${record.resultPath}\n")
            }
            ObsidianController.createNote(context, title, content, folder = "Générations", tags = listOf("jarvis", "generation", record.type))
        } catch (_: Exception) {
            // best-effort, ne jamais faire échouer la génération pour ça
        }
    }

    fun removeGenerationRecord(context: Context, id: String) {
        writeGenerationHistory(context, getGenerationHistory(context).filter { it.id != id })
    }

    // Au-delà de ce délai, aucune génération ne travaille légitimement encore : la vidéo
    // est plafonnée à ~3min20 de sondage (MAX_POLL_ATTEMPTS côté VideoGenController), l'image
    // à 90s de délai réseau max, le site à la durée d'un simple appel de chat. Un enregistrement
    // encore "pending" après ce délai signifie que GenerationService a été tué par le système
    // (optimisation de batterie, manque de mémoire, app fermée de force) avant d'avoir pu écrire
    // un résultat — pas qu'il travaille toujours. Sans cette réconciliation, ces enregistrements
    // restaient bloqués sur "en cours" indéfiniment, ce qui donnait l'impression d'une boucle
    // infinie côté utilisateur.
    private const val STALE_GENERATION_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * Marque comme "failed" (avec une cause honnête) tout enregistrement resté "pending"
     * plus longtemps que ce qu'une génération met légitimement à se terminer. À appeler à
     * chaque ouverture/rafraîchissement de l'écran Génération. Retourne true si au moins un
     * enregistrement a été corrigé.
     */
    fun reconcileStaleGenerations(context: Context): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        val list = getGenerationHistory(context).map { record ->
            if (record.status == "pending" && now - record.timestamp > STALE_GENERATION_TIMEOUT_MS) {
                changed = true
                record.copy(
                    status = "failed",
                    errorMessage = "Interrompue : le service a été arrêté par le système avant la fin " +
                        "(optimisation de batterie, manque de mémoire, ou application fermée de force), " +
                        "sans message d'erreur explicite du moteur de génération. Réessaie ; si ça se " +
                        "reproduit souvent, désactive l'optimisation de batterie pour JARVIS dans les " +
                        "réglages système Android."
                )
            } else record
        }
        if (changed) writeGenerationHistory(context, list)
        return changed
    }

    private fun writeGenerationHistory(context: Context, list: List<GenerationRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString("generation_history", arr.toString()).apply()
    }

    // ─── Interne ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
