package com.jarvis.assistant

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lot 6 "contrôle téléphone" : génération de fichiers (PDF, ZIP, KML) directement sur le
 * téléphone, sans aucune dépendance externe -- tout est déjà dans le SDK Android :
 * android.graphics.pdf.PdfDocument (API 19+) pour le PDF, java.util.zip pour le ZIP, et KML
 * n'est jamais que du XML texte brut. Les fichiers sont écrits dans le dossier Documents de
 * l'appli (getExternalFilesDir), visible sans permission spéciale de stockage.
 *
 * Word/Excel (.docx/.xlsx) sont traités à part dans DocumentBuilder.kt : Apache POI n'est pas
 * fiable sur Android (voir la note dans ce fichier), on les génère aussi à la main (zip + XML,
 * même principe que python-docx/openpyxl).
 */
object FileGenController {

    fun outputDir(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

    /** Génère un PDF simple : une page par élément de [pages] (texte multi-lignes). */
    fun createPdf(context: Context, fileName: String, pages: List<String>): File? {
        return try {
            val document = PdfDocument()
            val paint = Paint().apply { textSize = 12f }
            val pageWidth = 595 // A4 à 72dpi
            val pageHeight = 842

            pages.forEachIndexed { index, text ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                var y = 40f
                text.split("\n").forEach { line ->
                    page.canvas.drawText(line, 24f, y, paint)
                    y += 16f
                }
                document.finishPage(page)
            }

            val file = File(outputDir(context), fileName)
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Zippe TOUT le contenu actuel du dossier de sortie de l'appli (hors .zip existants, pour
     *  ne pas se zipper soi-même) -- commande vocale "crée un zip appelé X" (voir
     *  CommandInterpreter.Command.CreateZip), plus simple pour l'utilisateur que de devoir
     *  lister les fichiers un par un. */
    fun zipOutputDir(context: Context, fileName: String): File? {
        val files = outputDir(context).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".zip", ignoreCase = true) }
            ?: emptyList()
        return createZip(context, fileName, files)
    }

    /** Zippe une liste de fichiers existants en un seul fichier .zip. */
    fun createZip(context: Context, fileName: String, sourceFiles: List<File>): File? {
        return try {
            val file = File(outputDir(context), fileName)
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                sourceFiles.forEach { source ->
                    if (source.exists()) {
                        zip.putNextEntry(ZipEntry(source.name))
                        source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    data class KmlPlacemark(val name: String, val lat: Double, val lon: Double, val description: String = "")

    /** Génère un .kml minimal (points d'intérêt) -- lisible par Google Earth/Maps et compatible. */
    fun createKml(context: Context, fileName: String, placemarks: List<KmlPlacemark>): File? {
        return try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
            placemarks.forEach { p ->
                sb.append("<Placemark>")
                sb.append("<name>").append(escapeXml(p.name)).append("</name>")
                if (p.description.isNotBlank()) {
                    sb.append("<description>").append(escapeXml(p.description)).append("</description>")
                }
                sb.append("<Point><coordinates>").append(p.lon).append(",").append(p.lat).append(",0</coordinates></Point>")
                sb.append("</Placemark>\n")
            }
            sb.append("</Document></kml>")

            val file = File(outputDir(context), fileName)
            file.writeText(sb.toString())
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
