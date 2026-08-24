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

    /** Genere un vrai fichier .docx (Word) : titre en gras (optionnel) + un paragraphe par
     *  ligne du contenu -- OOXML minimal ecrit a la main, s'ouvre normalement dans Word/
     *  LibreOffice/Google Docs. */
    fun createDocx(context: Context, fileName: String, title: String, content: String): File? {
        return try {
            val body = StringBuilder()
            if (title.isNotBlank()) {
                body.append(
                    "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr>" +
                        "<w:t xml:space=\"preserve\">" + escapeXml(title) + "</w:t></w:r></w:p><w:p/>"
                )
            }
            content.split("\n").forEach { paragraph ->
                if (paragraph.isBlank()) {
                    body.append("<w:p/>")
                } else {
                    body.append("<w:p><w:r><w:t xml:space=\"preserve\">" + escapeXml(paragraph) + "</w:t></w:r></w:p>")
                }
            }

            val documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                body.toString() +
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\"/></w:sectPr>" +
                "</w:body></w:document>"

            val file = File(outputDir(context), fileName)
            ZipOutputStream(FileOutputStream(file)).use { zos ->
                writeZipEntry(zos, "[Content_Types].xml", CONTENT_TYPES_DOCX)
                writeZipEntry(zos, "_rels/.rels", RELS_ROOT_DOCX)
                writeZipEntry(zos, "word/document.xml", documentXml)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private const val CONTENT_TYPES_DOCX = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"

    private const val RELS_ROOT_DOCX = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"

    /** Genere un vrai fichier .xlsx (Excel) : une feuille avec les donnees fournies.
     *  [csvContent] : une ligne par ligne de tableau, colonnes separees par « ; ». Detecte
     *  automatiquement les nombres (point ou virgule decimale) pour les ecrire comme vraies
     *  valeurs numeriques Excel, pas du texte. */
    fun createXlsx(context: Context, fileName: String, sheetTitle: String, csvContent: String): File? {
        val rows = csvContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null

        return try {
            val sheetName = sheetTitle.ifBlank { "Feuille1" }.take(31).replace(Regex("[\\\\/*?\\[\\]:]"), "-")
            val sheetXml = StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            )

            rows.forEachIndexed { rowIdx, rowLine ->
                val cells = rowLine.split(";").map { it.trim() }
                sheetXml.append("<row r=\"" + (rowIdx + 1) + "\">")
                cells.forEachIndexed { colIdx, cellValue ->
                    val ref = columnLetter(colIdx) + (rowIdx + 1)
                    val numeric = cellValue.replace(",", ".").toDoubleOrNull()
                    if (numeric != null && cellValue.isNotBlank()) {
                        sheetXml.append("<c r=\"" + ref + "\"><v>" + numeric + "</v></c>")
                    } else {
                        sheetXml.append("<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escapeXml(cellValue) + "</t></is></c>")
                    }
                }
                sheetXml.append("</row>")
            }
            sheetXml.append("</sheetData></worksheet>")

            val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"" +
                escapeXml(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

            val file = File(outputDir(context), fileName)
            ZipOutputStream(FileOutputStream(file)).use { zos ->
                writeZipEntry(zos, "[Content_Types].xml", CONTENT_TYPES_XLSX)
                writeZipEntry(zos, "_rels/.rels", RELS_ROOT_XLSX)
                writeZipEntry(zos, "xl/workbook.xml", workbookXml)
                writeZipEntry(zos, "xl/_rels/workbook.xml.rels", RELS_WORKBOOK_XLSX)
                writeZipEntry(zos, "xl/worksheets/sheet1.xml", sheetXml.toString())
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Convertit un index de colonne (0-based) en reference Excel (0->A, 25->Z, 26->AA...). */
    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES_XLSX = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"

    private const val RELS_ROOT_XLSX = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"

    private const val RELS_WORKBOOK_XLSX = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"

    private fun writeZipEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
