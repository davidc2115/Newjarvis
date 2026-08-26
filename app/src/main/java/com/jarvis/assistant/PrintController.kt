package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * PrintController — impression directe sur une imprimante réseau via le
 * protocole IPP (Internet Printing Protocol, RFC 8010/2911), sans passer
 * par l'interface web de l'imprimante ni par une autre application.
 *
 * IPP est le protocole standard utilisé par la quasi-totalité des
 * imprimantes réseau modernes (y compris toutes les imprimantes compatibles
 * AirPrint/IPP Everywhere — la grande majorité du parc domestique et
 * bureautique depuis plusieurs années). Le document (PDF, image, etc.) est
 * envoyé directement en HTTP sur le port 631 de l'imprimante, sans jamais
 * ouvrir de navigateur ni d'appli tierce — JARVIS parle directement au
 * protocole d'impression.
 *
 * Limite honnête : les très anciennes imprimantes qui n'exposent QUE le
 * port 9100 (JetDirect/RAW, sans IPP) ou un pilote propriétaire Windows/USB
 * ne sont pas couvertes ici — il n'existe alors aucune façon standard de les
 * piloter depuis Android sans logiciel du fabricant.
 */
object PrintController {

    data class Result(val success: Boolean, val message: String)

    private const val IPP_PORT = 631
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 20000

    /**
     * Imprime un fichier (PDF, PNG, JPEG...) sur l'imprimante réseau désignée par
     * [printerRef] (adresse IP, ou vide pour utiliser l'imprimante par défaut
     * enregistrée dans les préférences). Retourne un message honnête en cas
     * d'échec (imprimante injoignable, format refusé, etc.) plutôt qu'un succès
     * silencieux non garanti.
     */
    suspend fun printFile(context: Context, filePath: String, printerRef: String? = null): Result =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext Result(false, "❌ Fichier introuvable : $filePath")
            }

            val printerIp = printerRef?.trim()?.takeIf { it.isNotBlank() }
                ?: Prefs.getDefaultPrinterIp(context).takeIf { it.isNotBlank() }
                ?: return@withContext Result(
                    false,
                    "❌ Aucune imprimante configurée. Précise l'adresse IP de l'imprimante réseau " +
                        "(utilise list_printers pour la découvrir automatiquement), ou enregistre-en une par défaut."
                )

            val mimeType = FileGenController.mimeTypeFor(file.name)
            if (mimeType == "*/*") {
                return@withContext Result(
                    false,
                    "❌ Format de fichier « ${file.extension} » non reconnu pour l'impression IPP. " +
                        "Formats supportés : PDF, PNG, JPEG."
                )
            }

            val documentBytes = file.readBytes()
            val localResult = sendIppToHost(printerIp, IPP_PORT, file.name, mimeType, documentBytes)
            if (localResult.success) return@withContext localResult

            // Injoignable en local (donc éventuellement hors Wi-Fi domestique) — bascule sur
            // l'hôte distant configuré via set_printer_remote_host, si présent. Nécessite que
            // l'utilisateur ait redirigé le port IPP (631, ou un port de son choix) de sa
            // box/routeur vers l'imprimante — JARVIS ne peut pas créer cette redirection lui-même.
            val remoteHost = Prefs.getDefaultPrinterRemoteHost(context).takeIf { it.isNotBlank() }
                ?: return@withContext localResult
            val (remoteIp, remotePort) = parseHostPort(remoteHost, IPP_PORT)
            val remoteResult = sendIppToHost(remoteIp, remotePort, file.name, mimeType, documentBytes)
            if (remoteResult.success) remoteResult
            else Result(
                false,
                "❌ Impression échouée en local ET via l'accès distant ($remoteHost).\n" +
                    "Local : ${localResult.message}\nDistant : ${remoteResult.message}"
            )
        }

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = hostPort.trim()
        val idx = trimmed.lastIndexOf(':')
        return if (idx > 0 && trimmed.substring(idx + 1).toIntOrNull() != null) {
            trimmed.substring(0, idx) to trimmed.substring(idx + 1).toInt()
        } else {
            trimmed to defaultPort
        }
    }

    // BUG RÉEL CORRIGÉ : un seul chemin de ressource IPP codé en dur ("/ipp/print") était
    // essayé — or ce chemin dépend du fabricant/modèle de l'imprimante (normalement annoncé
    // via mDNS/Bonjour dans l'attribut "rp", que JARVIS ne résout pas ici). De nombreuses
    // imprimantes (dont beaucoup de modèles Canon) exposent leur file IPP à un chemin
    // différent, provoquant "l'imprimante ne répond pas au protocole IPP" alors qu'elle
    // supporte bel et bien IPP — juste pas à CE chemin précis. On essaie maintenant les
    // chemins les plus courants dans l'ordre, et on ne remonte l'échec que si AUCUN ne
    // fonctionne, avec le détail de chaque tentative pour un diagnostic honnête.
    private val COMMON_IPP_PATHS = listOf("/ipp/print", "/ipp/printer", "/printers/ipp/print", "/print", "/")

    private fun sendIppToHost(host: String, port: Int, fileName: String, mimeType: String, documentBytes: ByteArray): Result {
        val attempts = mutableListOf<String>()
        for (path in COMMON_IPP_PATHS) {
            val result = sendIppToHostAtPath(host, port, path, fileName, mimeType, documentBytes)
            if (result.success) return result
            attempts.add("$path → ${result.message}")
        }
        return Result(
            false,
            "❌ Impossible de joindre l'imprimante ($host) sur les chemins IPP courants testés :\n" +
                attempts.joinToString("\n") { "   • $it" } +
                "\n\nVérifie qu'elle est bien allumée, accessible sur ce réseau, et compatible IPP/AirPrint. " +
                "Certaines imprimantes exposent leur file d'attente à un chemin non standard, non couvert ici."
        )
    }

    private fun sendIppToHostAtPath(host: String, port: Int, path: String, fileName: String, mimeType: String, documentBytes: ByteArray): Result {
        return try {
            val requestBody = buildIppPrintJobRequest(host, path, fileName, mimeType, documentBytes)

            val url = URL("http://$host:$port$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/ipp")
                setFixedLengthStreamingMode(requestBody.size)
            }

            conn.outputStream.use { it.write(requestBody) }

            val httpCode = conn.responseCode
            val responseBytes = try {
                (if (httpCode in 200..299) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
            } finally {
                conn.disconnect()
            }

            if (httpCode !in 200..299) {
                return Result(false, "HTTP $httpCode")
            }

            val statusCode = if (responseBytes.size >= 4) {
                ((responseBytes[2].toInt() and 0xFF) shl 8) or (responseBytes[3].toInt() and 0xFF)
            } else -1

            if (statusCode in 0x0000..0x00FF) {
                Result(true, "🖨️ Impression de « $fileName » envoyée à l'imprimante ($host$path).")
            } else {
                Result(false, "statut IPP 0x${statusCode.toString(16)}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "erreur réseau")
        }
    }

    fun setRemotePrinterHost(context: Context, host: String): String {
        if (host.isBlank()) return "❌ Aucune adresse fournie."
        Prefs.saveDefaultPrinterRemoteHost(context, host)
        return "✅ Accès distant enregistré pour l'imprimante par défaut : $host. " +
            "Vérifie que le port correspondant est bien redirigé vers l'imprimante sur ta box/routeur."
    }

    /**
     * Découvre les imprimantes sur le réseau local en réutilisant le scan réseau
     * existant (NetworkController), en ne gardant que les appareils dont un port
     * d'impression (631 = IPP, 9100 = JetDirect/RAW) est ouvert.
     */
    suspend fun listPrinters(context: Context): String {
        val devices = NetworkController.scanNetwork(context)
        val printers = devices.filter { it.openPorts.contains(631) || it.openPorts.contains(9100) }

        if (printers.isEmpty()) {
            return "🖨️ Aucune imprimante détectée sur le réseau local. Vérifie qu'elle est allumée et connectée au même Wi-Fi que ce téléphone."
        }

        val defaultIp = Prefs.getDefaultPrinterIp(context)
        val sb = StringBuilder("🖨️ **Imprimantes détectées sur le réseau** :\n\n")
        printers.forEach { d ->
            val ippTag = if (d.openPorts.contains(631)) " (IPP)" else " (JetDirect uniquement — non pilotable directement)"
            val defaultTag = if (d.ip == defaultIp) " ⭐ par défaut" else ""
            sb.append("• ${d.label} — ${d.ip}$ippTag$defaultTag\n")
        }
        sb.append("\n💡 Utilise set_default_printer{ip} pour en définir une par défaut, ou précise l'IP directement dans print_file.")
        return sb.toString().trim()
    }

    fun setDefaultPrinter(context: Context, ip: String): String {
        if (ip.isBlank()) return "❌ Aucune adresse IP fournie."
        Prefs.saveDefaultPrinterIp(context, ip)
        return "✅ Imprimante par défaut enregistrée : $ip"
    }

    // ─── Construction de la requête IPP Print-Job (RFC 8010) ──────────────────

    private fun buildIppPrintJobRequest(printerIp: String, path: String, fileName: String, mimeType: String, documentBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // Version IPP 1.1
        out.write(0x01); out.write(0x01)
        // operation-id : Print-Job (0x0002)
        writeU16(out, 0x0002)
        // request-id
        writeU32(out, 1)
        // operation-attributes-tag
        out.write(0x01)

        writeIppAttr(out, 0x47, "attributes-charset", "utf-8")
        writeIppAttr(out, 0x48, "attributes-natural-language", "en")
        // printer-uri DOIT correspondre au chemin réellement utilisé pour la requête HTTP —
        // certaines imprimantes rejettent la requête si ces deux valeurs divergent.
        writeIppAttr(out, 0x45, "printer-uri", "ipp://$printerIp:$IPP_PORT$path")
        writeIppAttr(out, 0x42, "requesting-user-name", "JARVIS")
        writeIppAttr(out, 0x42, "job-name", fileName)
        writeIppAttr(out, 0x49, "document-format", mimeType)

        // end-of-attributes-tag
        out.write(0x03)

        out.write(documentBytes)
        return out.toByteArray()
    }

    private fun writeIppAttr(out: ByteArrayOutputStream, valueTag: Int, name: String, value: String) {
        out.write(valueTag)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        writeU16(out, nameBytes.size)
        out.write(nameBytes)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        writeU16(out, valueBytes.size)
        out.write(valueBytes)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
