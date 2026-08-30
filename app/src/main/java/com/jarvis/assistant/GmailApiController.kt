package com.jarvis.assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gmail via la vraie API REST officielle (Gmail API v1), authentifiée par OAuth -- demande
 * explicite de l'utilisateur (remplace EmailController/IMAP-SMTP, qui ne nécessitait qu'un mot
 * de passe d'application mais que l'utilisateur ne veut plus utiliser).
 *
 * Comme GoogleCalendarApiController : chaque fonction reçoit un jeton d'accès déjà valide (voir
 * MainActivity.ensureGoogleToken), aucune dépendance à une Activity ici -- appels REST purs.
 *
 * users.messages.send attend un message MIME RFC 2822 complet encodé en base64url dans le champ
 * "raw" (voir developers.google.com/workspace/gmail/api/guides/sending) -- pas de bibliothèque
 * javax.mail nécessaire pour ça, un simple message texte suffit à construire l'en-tête minimal.
 */
object GmailApiController {

    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    private const val TIMEOUT_MS = 20000

    private fun request(url: String, token: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        return code to text
    }

    private fun errorMessage(code: Int, body: String): String {
        val (detail, reason) = try {
            val err = JSONObject(body).optJSONObject("error")
            val firstReason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
            err?.optString("message") to firstReason
        } catch (e: Exception) {
            null to null
        }
        // 403 recouvre DEUX causes bien distinctes côté Google, avec des remèdes opposés --
        // signalement utilisateur : "il ne me demande pas d'autorisation pour l'agenda", cause
        // probable : le jeton en cache n'a jamais eu le scope Agenda/Mail (compte lié avant
        // l'ajout de ce scope, ou consentement implicite silencieux côté Play Services -- voir
        // GoogleAccountController.requestAuthorization) et l'ancien message générique
        // ("active l'API dans Cloud Console") était FAUX/trompeur dans ce cas précis, puisque
        // l'API est bien activée -- c'est le CONSENTEMENT qui manque. reason="insufficientPermissions"
        // (ou message contenant "insufficient" / "Insufficient Permission") = scope manquant,
        // à corriger avec le nouveau bouton "Réautoriser" (Réglages > Compte(s) Google) plutôt
        // qu'en touchant Cloud Console.
        val insufficientScope = reason == "insufficientPermissions" ||
            (detail?.contains("insufficient", ignoreCase = true) == true)
        return when (code) {
            401 -> "❌ Session Google expirée ou révoquée -- reconnecte-toi dans Réglages > Compte(s) Google. (${detail ?: "401 Unauthorized"})"
            403 -> if (insufficientScope) {
                "❌ Autorisation Agenda/Mail manquante ou incomplète pour ce compte -- utilise le bouton " +
                    "« 🔄 Réautoriser » à côté du compte dans Réglages > Compte(s) Google pour redonner " +
                    "l'accès à cette donnée. (${detail ?: "403 insufficient scope"})"
            } else {
                "❌ Accès refusé par Google -- vérifie que l'API \"Gmail API\" est bien activée dans ton projet Cloud Console. (${detail ?: "403 Forbidden"})"
            }
            else -> "❌ Erreur Gmail ($code) : ${detail ?: body.take(200)}"
        }
    }

    /** En-tête de base d'un message -- décode le header MIME "From: Nom <email>" façon RFC 2047
     *  si besoin n'est pas nécessaire ici, on ne lit que Subject/From, texte brut suffit. */
    private fun headerValue(payload: JSONObject, name: String): String {
        val headers = payload.optJSONArray("headers") ?: return ""
        for (i in 0 until headers.length()) {
            val h = headers.getJSONObject(i)
            if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
        }
        return ""
    }

    private fun formatMessageList(json: String, ids: List<String>, token: String, emptyMessage: String, title: String): String {
        if (ids.isEmpty()) return emptyMessage
        return buildString {
            append("$title\n\n")
            ids.forEachIndexed { i, id ->
                val (code, body) = request(
                    "$BASE/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date",
                    token
                )
                if (code !in 200..299) return@forEachIndexed
                val payload = JSONObject(body).optJSONObject("payload") ?: return@forEachIndexed
                val subject = headerValue(payload, "Subject").ifBlank { "(sans sujet)" }
                val from = headerValue(payload, "From")
                append("${i + 1}. $subject\n   De : $from\n\n")
            }
        }
    }

    suspend fun readInbox(token: String, count: Int = 5): String = withContext(Dispatchers.IO) {
        val (code, body) = request("$BASE/messages?maxResults=$count&labelIds=INBOX", token)
        if (code !in 200..299) return@withContext errorMessage(code, body)
        val ids = JSONObject(body).optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } ?: emptyList()
        formatMessageList(body, ids, token, "📭 Ta boîte de réception Gmail est vide.", "📬 ${ids.size} dernier(s) email(s)")
    }

    suspend fun readUnread(token: String): String = withContext(Dispatchers.IO) {
        val (code, body) = request("$BASE/messages?maxResults=20&labelIds=INBOX&labelIds=UNREAD", token)
        if (code !in 200..299) return@withContext errorMessage(code, body)
        val ids = JSONObject(body).optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } ?: emptyList()
        formatMessageList(body, ids, token, "✅ Aucun email non lu.", "📬 ${ids.size} email(s) non lu(s)")
    }

    suspend fun searchEmails(token: String, query: String): String = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("subject:$query", "UTF-8")
        val (code, body) = request("$BASE/messages?maxResults=5&q=$q", token)
        if (code !in 200..299) return@withContext errorMessage(code, body)
        val ids = JSONObject(body).optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } ?: emptyList()
        formatMessageList(body, ids, token, "🔍 Aucun email trouvé pour « $query ».", "🔍 ${ids.size} résultat(s) pour « $query »")
    }

    suspend fun sendEmail(token: String, to: String, subject: String, body: String): String = withContext(Dispatchers.IO) {
        // Message MIME minimal en RFC 2822 -- pas de From explicite : Gmail l'assigne
        // automatiquement au compte authentifié par le jeton (comportement documenté, évite
        // toute usurpation d'expéditeur).
        val mime = "To: $to\r\n" +
            "Subject: =?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?=\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n\r\n" +
            body
        val raw = Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject().put("raw", raw)
        val (code, respBody) = request("$BASE/messages/send", token, "POST", payload.toString())
        if (code !in 200..299) return@withContext errorMessage(code, respBody)
        "✅ Email envoyé à $to.\n\nSujet : $subject"
    }
}
