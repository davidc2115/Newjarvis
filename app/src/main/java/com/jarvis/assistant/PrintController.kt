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

            try {
                val requestBody = buildIppPrintJobRequest(printerIp, file.name, mimeType, file.readBytes())

                val url = URL("http://$printerIp:$IPP_PORT/ipp/print")
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
                    return@withContext Result(false, "❌ L'imprimante ($printerIp) a refusé la connexion HTTP (code $httpCode).")
                }

                val statusCode = if (responseBytes.size >= 4) {
                    ((responseBytes[2].toInt() and 0xFF) shl 8) or (responseBytes[3].toInt() and 0xFF)
                } else -1

                if (statusCode in 0x0000..0x00FF) {
                    Result(true, "🖨️ Impression de « ${file.name} » envoyée à l'imprimante ($printerIp).")
                } else {
                    Result(
                        false,
                        "❌ L'imprimante ($printerIp) a rejeté le document (code de statut IPP : 0x${statusCode.toString(16)}). " +
                            "Vérifie qu'elle supporte bien IPP/AirPrint et le format $mimeType."
                    )
                }
            } catch (e: Exception) {
                Result(
                    false,
                    "❌ Impossible de joindre l'imprimante ($printerIp) : ${e.message}. " +
                        "Vérifie qu'elle est bien allumée, connectée au même réseau Wi-Fi, et compatible IPP/AirPrint."
                )
            }
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

    private fun buildIppPrintJobRequest(printerIp: String, fileName: String, mimeType: String, documentBytes: ByteArray): ByteArray {
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
        writeIppAttr(out, 0x45, "printer-uri", "ipp://$printerIp:$IPP_PORT/ipp/print")
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
