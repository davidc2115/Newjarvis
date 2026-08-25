package com.jarvis.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.SubjectTerm

/**
 * Contrôleur Email IMAP/SMTP -- porté depuis l'ancienne version de ce projet (avant la remise
 * à zéro complète, voir git history commit 2abe7dd) à la demande explicite de l'utilisateur
 * ("regarde comme c'était sur l'ancienne appli") pour un accès mail SANS passer par Google
 * Cloud Console : lecture (IMAP) et envoi (SMTP) directement depuis l'APK, avec un simple "mot
 * de passe d'application" saisi par l'utilisateur dans Réglages > Email (jamais codé en dur).
 *
 * SIMPLIFIÉ par rapport à l'original : l'ancienne appli avait aussi une branche OAuth2 via
 * AccountManager (jamais aboutie, voir commit 30fca92) -- retirée ici, incompatible avec le
 * retrait explicite de la Console Cloud demandé par l'utilisateur (voir SettingsActivity.kt).
 *
 * Utilise android-mail (portage JavaMail pour Android, com.sun.mail:android-mail:1.6.7 -- le
 * javax.mail standard du JDK n'existe pas sur Android, voir app/build.gradle).
 *
 * Gmail/Yahoo/Outlook bloquent l'IMAP avec le mot de passe principal du compte : il faut un
 * "mot de passe d'application" (16 caractères), généré sur myaccount.google.com > Sécurité >
 * Mots de passe des applications -- nécessite que la validation en 2 étapes soit activée.
 */
object EmailController {

    private const val TAG = "EmailController"
    private const val FETCH_TIMEOUT_MS = 30_000

    data class EmailMessage(
        val from: String,
        val to: String,
        val subject: String,
        val body: String,
        val date: String,
        val isRead: Boolean = false
    )

    private data class FetchResult(val success: Boolean, val message: String, val emails: List<EmailMessage> = emptyList())

    /** Lit les N derniers emails de la boîte de réception -- réponse déjà formatée pour le chat. */
    suspend fun readInbox(context: Context, count: Int = 5): String = withContext(Dispatchers.IO) {
        val account = Prefs.getDefaultEmailAccount(context)
            ?: return@withContext "❌ Aucun compte email configuré -- va dans ⚙ Réglages > Email pour en ajouter un."

        val result = fetchEmails(account, "INBOX", count)
        if (!result.success) return@withContext "❌ Erreur lecture emails (${account.label}) : ${result.message}"
        if (result.emails.isEmpty()) return@withContext "📭 La boîte de réception de ${account.email} est vide."

        buildString {
            append("📬 ${result.emails.size} dernier(s) email(s) de ${account.email} :\n\n")
            result.emails.forEachIndexed { i, email ->
                append("${i + 1}. ${email.subject.ifBlank { "(sans sujet)" }}\n")
                append("   De : ${email.from}\n")
                append("   Le : ${email.date}\n")
                val preview = email.body.take(150).replace("\n", " ").trim()
                if (preview.isNotBlank()) append("   Aperçu : $preview…\n")
                append("\n")
            }
        }
    }

    /** Lit uniquement les emails non lus (20 derniers max, affiche les 10 premiers). */
    suspend fun readUnread(context: Context): String = withContext(Dispatchers.IO) {
        val account = Prefs.getDefaultEmailAccount(context)
            ?: return@withContext "❌ Aucun compte email configuré -- va dans ⚙ Réglages > Email pour en ajouter un."

        val result = fetchEmails(account, "INBOX", 20, onlyUnread = true)
        if (!result.success) return@withContext "❌ Erreur lecture emails : ${result.message}"
        if (result.emails.isEmpty()) return@withContext "✅ Aucun email non lu dans ${account.email}."

        buildString {
            append("📬 ${result.emails.size} email(s) non lu(s) dans ${account.email} :\n\n")
            result.emails.take(10).forEachIndexed { i, email ->
                append("${i + 1}. ${email.subject.ifBlank { "(sans sujet)" }} — ${email.from}\n")
                append("   ${email.date}\n\n")
            }
        }
    }

    /** Recherche des emails par sujet. */
    suspend fun searchEmails(context: Context, query: String): String = withContext(Dispatchers.IO) {
        val account = Prefs.getDefaultEmailAccount(context)
            ?: return@withContext "❌ Aucun compte email configuré -- va dans ⚙ Réglages > Email pour en ajouter un."

        try {
            val session = buildImapSession(account)
            val store = session.getStore("imaps")
            store.connect(account.imapHost, account.imapPort, account.email, account.password)
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)

            val messages = folder.search(SubjectTerm(query))
            folder.close(false)
            store.close()

            if (messages.isEmpty()) return@withContext "🔍 Aucun email trouvé pour « $query »."

            buildString {
                append("🔍 ${messages.size} résultat(s) pour « $query » :\n\n")
                messages.take(5).forEachIndexed { i, msg ->
                    append("${i + 1}. ${msg.subject ?: "(sans sujet)"}\n")
                    append("   De : ${msg.from?.firstOrNull()}\n")
                    append("   ${formatDate(msg.sentDate)}\n\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search error", e)
            "❌ Erreur recherche email : ${e.javaClass.simpleName} : ${e.message}"
        }
    }

    /** Envoie un email via SMTP. */
    suspend fun sendEmail(context: Context, to: String, subject: String, body: String): String =
        withContext(Dispatchers.IO) {
            val account = Prefs.getDefaultEmailAccount(context)
                ?: return@withContext "❌ Aucun compte email configuré -- va dans ⚙ Réglages > Email pour en ajouter un."

            try {
                val props = buildSmtpProperties(account)
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(account.email, account.password)
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(account.email))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    this.subject = subject
                    setText(body, "UTF-8")
                }

                Transport.send(message)
                "✅ Email envoyé à $to depuis ${account.email}.\n\nSujet : $subject"
            } catch (e: MessagingException) {
                Log.e(TAG, "SMTP error", e)
                "❌ Erreur d'envoi email : ${e.message}\n\n" +
                    "💡 Pour Gmail : utilise un « mot de passe d'application » (myaccount.google.com > Sécurité > Mots de passe des applications), pas ton mot de passe principal."
            } catch (e: Exception) {
                "❌ Erreur inattendue : ${e.javaClass.simpleName} : ${e.message}"
            }
        }

    /** Teste la connexion IMAP d'un compte (utilisé par EmailConfigActivity avant sauvegarde). */
    suspend fun testConnection(account: Prefs.EmailAccount): String = withContext(Dispatchers.IO) {
        try {
            val session = buildImapSession(account)
            val store = session.getStore("imaps")
            store.connect(account.imapHost, account.imapPort, account.email, account.password)
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            val count = folder.messageCount
            folder.close(false)
            store.close()
            "✅ Connexion réussie ! ${account.email} — $count message(s) dans INBOX."
        } catch (e: Exception) {
            Log.e(TAG, "test connection error", e)
            "❌ Échec de connexion : ${e.javaClass.simpleName} : ${e.message}\n\n" +
                "💡 Gmail/Yahoo/Outlook bloquent le mot de passe ordinaire.\n" +
                "→ Active la validation en 2 étapes sur myaccount.google.com\n" +
                "→ Sécurité > Mots de passe des applications\n" +
                "→ Génère un code 16 lettres et colle-le ici (pas ton mot de passe normal)."
        }
    }

    private fun fetchEmails(account: Prefs.EmailAccount, folderName: String, count: Int, onlyUnread: Boolean = false): FetchResult {
        return try {
            val session = buildImapSession(account)
            val store = session.getStore("imaps")
            store.connect(account.imapHost, account.imapPort, account.email, account.password)

            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)

            val totalMessages = folder.messageCount
            if (totalMessages == 0) {
                folder.close(false)
                store.close()
                return FetchResult(true, "OK", emptyList())
            }

            val start = maxOf(1, totalMessages - count + 1)
            val messages = folder.getMessages(start, totalMessages).reversed()

            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH)
            val emailList = messages.mapNotNull { msg ->
                try {
                    val isRead = msg.flags.contains(javax.mail.Flags.Flag.SEEN)
                    if (onlyUnread && isRead) return@mapNotNull null

                    EmailMessage(
                        from = msg.from?.firstOrNull()?.toString() ?: "Inconnu",
                        to = msg.getRecipients(Message.RecipientType.TO)?.firstOrNull()?.toString() ?: "",
                        subject = msg.subject ?: "",
                        body = extractTextBody(msg),
                        date = msg.sentDate?.let { sdf.format(it) } ?: "",
                        isRead = isRead
                    )
                } catch (e: Exception) {
                    null
                }
            }

            folder.close(false)
            store.close()
            FetchResult(true, "OK", emailList)
        } catch (e: Exception) {
            Log.e(TAG, "IMAP fetch error", e)
            FetchResult(false, "${e.javaClass.simpleName} : ${e.message}")
        }
    }

    private fun buildImapSession(account: Prefs.EmailAccount): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", account.imapHost)
            put("mail.imaps.port", account.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.trust", "*")
            put("mail.imaps.connectiontimeout", FETCH_TIMEOUT_MS.toString())
            put("mail.imaps.timeout", FETCH_TIMEOUT_MS.toString())
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(account.email, account.password)
        })
    }

    private fun buildSmtpProperties(account: Prefs.EmailAccount): Properties = Properties().apply {
        put("mail.smtp.host", account.smtpHost)
        put("mail.smtp.port", account.smtpPort.toString())
        put("mail.smtp.auth", "true")
        if (account.smtpStartTls) {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
        put("mail.smtp.ssl.trust", "*")
        put("mail.smtp.connectiontimeout", FETCH_TIMEOUT_MS.toString())
        put("mail.smtp.timeout", FETCH_TIMEOUT_MS.toString())
    }

    private fun extractTextBody(part: javax.mail.Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> part.content as? String ?: ""
                part.isMimeType("text/html") -> {
                    val html = part.content as? String ?: ""
                    html.replace(Regex("<[^>]+>"), " ")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? javax.mail.Multipart ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until mp.count) {
                        val text = extractTextBody(mp.getBodyPart(i))
                        if (text.isNotBlank()) { sb.append(text); break }
                    }
                    sb.toString()
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatDate(date: java.util.Date?): String {
        if (date == null) return ""
        return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH).format(date)
    }
}
