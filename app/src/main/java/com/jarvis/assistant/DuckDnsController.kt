package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DuckDnsController — nom de domaine gratuit (sous-domaine *.duckdns.org) qui pointe
 * toujours vers l'IP publique actuelle du téléphone, mis à jour périodiquement via
 * l'API HTTP ultra simple de DuckDNS (https://www.duckdns.org/spec.jsp). Gratuit,
 * sans carte bancaire, un seul jeton à coller dans ⚙ Paramètres (jamais codé en dur
 * ici, dépôt public — voir Prefs.getDuckDnsDomain/getDuckDnsToken).
 *
 * Utilisé pour donner une adresse stable à un site généré par JARVIS et hébergé
 * directement depuis le téléphone (voir LocalWebServerController), combiné à une
 * redirection de port sur la Freebox (voir FreeboxController.configurePortForward)
 * pour être accessible depuis l'extérieur du réseau local.
 */
object DuckDnsController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Vrai si un domaine ET un jeton DuckDNS sont enregistrés dans les Paramètres. */
    fun isConfigured(context: Context): Boolean =
        Prefs.getDuckDnsDomain(context).isNotBlank() && Prefs.getDuckDnsToken(context).isNotBlank()

    private const val NOT_CONFIGURED =
        "❌ DuckDNS n'est pas configuré. Va sur https://www.duckdns.org (connexion gratuite via GitHub/Google/Twitter), " +
            "crée un sous-domaine, puis colle ton sous-domaine et ton jeton (token) dans ⚙ Paramètres → section DuckDNS. " +
            "Gratuit, sans carte bancaire, aucune limite de durée."

    /** Nom de domaine complet configuré (ex: monjarvis.duckdns.org), ou chaîne vide si non configuré. */
    fun fullDomain(context: Context): String {
        val domain = Prefs.getDuckDnsDomain(context)
        return if (domain.isBlank()) "" else "$domain.duckdns.org"
    }

    /**
     * Met à jour l'enregistrement DuckDNS avec l'IP publique actuelle du téléphone
     * (paramètre ip vide dans la requête = DuckDNS détecte lui-même l'IP appelante,
     * le cas d'usage le plus simple et le plus fiable pour un réseau mobile/domestique
     * dont l'IP change régulièrement).
     */
    suspend fun updateIp(context: Context): String = withContext(Dispatchers.IO) {
        val domain = Prefs.getDuckDnsDomain(context)
        val token = Prefs.getDuckDnsToken(context)
        if (domain.isBlank() || token.isBlank()) return@withContext NOT_CONFIGURED

        val url = "https://www.duckdns.org/update?domains=$domain&token=$token&ip="
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                when {
                    body.startsWith("OK") -> {
                        val ip = body.removePrefix("OK").trim().trimStart(',').trim()
                        "✅ DuckDNS mis à jour : $domain.duckdns.org" + if (ip.isNotBlank()) " → $ip" else " (IP détectée automatiquement)"
                    }
                    body.startsWith("KO") -> "❌ DuckDNS a refusé la mise à jour : sous-domaine ou jeton incorrect. Vérifie-les dans ⚙ Paramètres → DuckDNS."
                    !resp.isSuccessful -> "❌ Erreur DuckDNS (${resp.code}) : $body"
                    else -> "⚠️ Réponse DuckDNS inattendue : $body"
                }
            }
        } catch (e: Exception) {
            "❌ Erreur réseau lors de la mise à jour DuckDNS : ${e.message}"
        }
    }

    /** État actuel (domaine configuré ou non), sans appel réseau. */
    fun status(context: Context): String {
        if (!isConfigured(context)) return NOT_CONFIGURED
        return "🦆 DuckDNS configuré : ${fullDomain(context)}\n\n" +
            "Utilise duckdns_update pour rafraîchir l'IP maintenant, ou active la mise à jour automatique " +
            "en arrière-plan (⚙ Paramètres → DuckDNS → mise à jour auto)."
    }
}
