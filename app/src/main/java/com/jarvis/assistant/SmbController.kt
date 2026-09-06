package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SmbController — accès générique à un partage réseau SMB/CIFS via jcifs-ng (bibliothèque
 * Java pure, aucune dépendance native, aucune clé/API requise). C'est le protocole standard
 * utilisé par les NAS, les partages de fichiers Windows, ET le disque dur de la Freebox
 * Server une fois son partage SMB activé (Freebox OS → Paramètres → Mode disque dur →
 * activer le partage réseau, puis créer un utilisateur avec un mot de passe dans
 * Paramètres → Utilisateurs).
 *
 * Choix assumé et honnête, suite à la demande explicite de l'utilisateur : ceci est un
 * client SMB GÉNÉRIQUE, PAS une réintégration de l'API propriétaire Freebox OS (jetons
 * d'application, contrôle du Wi-Fi de la box, etc. — retirée précédemment du projet). Ça
 * fonctionne pour PARCOURIR ET TÉLÉCHARGER des fichiers depuis n'importe quel partage SMB
 * (Freebox comme NAS/PC), mais ne donne PAS accès aux réglages internes de la Freebox.
 *
 * Identifiants requis : le compte SMB configuré côté serveur (Utilisateurs → mot de passe
 * dédié au partage réseau pour la Freebox), PAS l'identifiant Freebox Connect ni le code
 * d'accès à distance de la box. Sans identifiants renseignés, une tentative d'accès anonyme
 * est faite — fonctionne rarement sur une Freebox (partage protégé par défaut), mais peut
 * marcher sur un NAS/partage ouvert en invité.
 */
object SmbController {

    data class Result(val success: Boolean, val message: String, val localPath: String? = null)

    /**
     * Corrige le bug remonte par l'utilisateur : "impossible d'acceder au disque dur USB de
     * la Freebox... alors que tout est bien active". Avant ce correctif, TOUTE exception (nom
     * d'hote introuvable, mauvais identifiants, timeout reseau, acces refuse...) tombait dans
     * le meme message generique "${e.message}" — qui vaut souvent "null" pour les exceptions
     * jcifs-ng (SmbAuthException notamment), donc litteralement aucune info exploitable pour
     * l'utilisateur ni pour diagnostiquer a distance. Distingue maintenant les causes les plus
     * frequentes avec un conseil PRECIS pour chacune, au lieu d'un unique "verifie X/Y/Z" flou
     * qui ne dit pas LEQUEL des trois est en cause.
     */
    private fun diagnoseSmbError(e: Exception, host: String, share: String): String {
        val causeMsg = e.message ?: e.cause?.message
        return when {
            e is java.net.UnknownHostException -> "❌ Impossible de joindre « $host » : nom introuvable sur le réseau. " +
                "Un nom comme \"Freebox_Server\" nécessite la résolution NetBIOS, pas toujours disponible sur Android — " +
                "utilise plutôt l'ADRESSE IP LOCALE de la Freebox (ex: 192.168.1.254, visible dans Freebox OS → Réseau, " +
                "ou via network_scan) avec smb_configure{host}."
            e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException ->
                "❌ Impossible de joindre « $host » sur le réseau (connexion refusée ou expirée). Vérifie que le téléphone " +
                "est bien sur le MÊME réseau Wi-Fi que la Freebox (pas en 4G/5G, pas sur un Wi-Fi invité isolé), et que " +
                "l'adresse « $host » est correcte."
            e.javaClass.simpleName == "SmbAuthException" || (causeMsg?.contains("Logon failure", ignoreCase = true) == true) ||
                (causeMsg?.contains("Access is denied", ignoreCase = true) == true) ->
                "❌ Identifiants refusés pour « $share » sur $host. Le compte SMB doit être créé dans Freebox OS → " +
                "Paramètres → Utilisateurs (PAS l'identifiant Freebox Connect), avec la case « Autoriser l'accès au " +
                "stockage » cochée pour ce compte, puis reconfigure avec smb_configure{host,username,password}."
            causeMsg?.contains("STATUS_BAD_NETWORK_NAME", ignoreCase = true) == true ||
                causeMsg?.contains("does not specify a valid share", ignoreCase = true) == true ->
                "❌ Le partage « $share » n'existe pas sur $host tel quel. Vérifie le nom EXACT du partage dans Freebox OS " +
                "→ Mode disque dur (souvent le nom du disque, pas \"disque dur\" littéralement)."
            else -> "❌ Impossible d'accéder à « $share » sur $host : ${causeMsg ?: e.javaClass.simpleName}. Vérifie que le " +
                "partage réseau est activé (Freebox OS → Paramètres → Mode disque dur), que les identifiants sont corrects " +
                "(smb_configure), et que le téléphone est sur le même réseau local que la Freebox."
        }
    }

    private fun authContext(context: Context): CIFSContext {
        val username = Prefs.getSmbUsername(context)
        val password = Prefs.getSmbPassword(context)
        val base = SingletonContext.getInstance()
        val auth = if (username.isBlank()) {
            NtlmPasswordAuthenticator() // accès anonyme
        } else {
            NtlmPasswordAuthenticator("", username, password)
        }
        return base.withCredentials(auth)
    }

    private fun buildUrl(host: String, share: String, path: String, isDirectory: Boolean): String {
        val cleanShare = share.trim().trim('/')
        val cleanPath = path.trim().trim('/')
        val sb = StringBuilder("smb://").append(host.trim()).append("/").append(cleanShare).append("/")
        if (cleanPath.isNotBlank()) sb.append(cleanPath).append("/")
        var url = sb.toString()
        // Les dossiers doivent se terminer par "/" en URL SMB ; pour un FICHIER on retire ce
        // "/" final (sinon jcifs le traite comme un chemin de dossier).
        if (!isDirectory && cleanPath.isNotBlank()) {
            url = url.trimEnd('/')
        }
        return url
    }

    fun configure(context: Context, host: String, username: String, password: String): String {
        if (host.isBlank()) return "❌ Adresse du serveur SMB manquante (ex: \"Freebox_Server\" ou son IP locale)."
        Prefs.saveSmbHost(context, host)
        Prefs.saveSmbUsername(context, username)
        Prefs.saveSmbPassword(context, password)
        return "✅ Accès SMB configuré pour « ${host.trim()} »" +
            (if (username.isNotBlank()) " (utilisateur : $username)." else " (accès anonyme, aucun identifiant fourni — fonctionne rarement sur une Freebox, précise un utilisateur/mot de passe SMB si ça échoue).")
    }

    /** Parcourt un dossier d'un partage SMB (racine du partage si [path] est vide). */
    fun listFiles(context: Context, share: String, path: String): String {
        val host = Prefs.getSmbHost(context)
        if (host.isBlank()) return "❌ Aucun serveur SMB configuré. Utilise smb_configure{host,username?,password?} d'abord (ex: host=\"Freebox_Server\")."
        if (share.isBlank()) return "❌ Précise le nom du partage à parcourir (activé et nommé côté Freebox OS → Paramètres → Mode disque dur)."
        return try {
            val url = buildUrl(host, share, path, isDirectory = true)
            val dir = SmbFile(url, authContext(context))
            val cleanPath = path.trim().trim('/')
            if (!dir.exists()) return "❌ « $share${if (cleanPath.isNotBlank()) "/$cleanPath" else ""} » introuvable sur $host. Vérifie le nom du partage et le chemin."
            if (!dir.isDirectory) return "📄 « ${dir.name.trimEnd('/')} » est un fichier, pas un dossier. Utilise smb_download_file pour le récupérer."
            val entries = dir.listFiles()
            if (entries.isNullOrEmpty()) return "📂 « $share${if (cleanPath.isNotBlank()) "/$cleanPath" else ""} » est vide."
            val folders = entries.filter { it.isDirectory }.map { it.name.trimEnd('/') }
            val files = entries.filter { !it.isDirectory }.map { it.name to it.length() }
            val sb = StringBuilder("📂 **$host/$share${if (cleanPath.isNotBlank()) "/$cleanPath" else ""}** :\n\n")
            folders.sorted().forEach { sb.append("📁 $it/\n") }
            files.sortedBy { it.first }.forEach { (n, size) -> sb.append("📄 $n (${size} octets)\n") }
            sb.toString().trim()
        } catch (e: Exception) {
            diagnoseSmbError(e, host, share)
        }
    }

    /** Télécharge un fichier d'un partage SMB vers Documents/JARVIS-Fichiers/Freebox/. */
    suspend fun downloadFile(context: Context, share: String, path: String): Result = withContext(Dispatchers.IO) {
        val host = Prefs.getSmbHost(context)
        if (host.isBlank()) return@withContext Result(false, "❌ Aucun serveur SMB configuré. Utilise smb_configure{host,username?,password?} d'abord.")
        if (share.isBlank() || path.isBlank()) return@withContext Result(false, "❌ Précise le partage ET le chemin exact du fichier à récupérer (voir smb_list_files pour les chemins disponibles).")
        try {
            val url = buildUrl(host, share, path, isDirectory = false)
            val remote = SmbFile(url, authContext(context))
            if (!remote.exists()) return@withContext Result(false, "❌ « $share/${path.trim('/')} » introuvable sur $host.")
            if (remote.isDirectory) return@withContext Result(false, "❌ « $share/${path.trim('/')} » est un dossier, pas un fichier. Utilise smb_list_files pour le parcourir.")
            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JARVIS-Fichiers/Freebox").also { it.mkdirs() }
            val fileName = remote.name.trimEnd('/').ifBlank { "fichier" }
            val destFile = File(outDir, "${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())}_$fileName")
            remote.inputStream.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
            Result(true, "✅ « $fileName » téléchargé depuis $host/$share.\n📁 Enregistré dans : ${destFile.absolutePath}", destFile.absolutePath)
        } catch (e: Exception) {
            Result(false, diagnoseSmbError(e, host, share))
        }
    }
}
