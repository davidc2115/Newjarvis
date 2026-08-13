package com.jarvis.assistant

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * FileGenController — création de vrais fichiers bureautiques depuis le chat/vocal :
 * ZIP (java.util.zip, standard), PDF (android.graphics.pdf.PdfDocument, API native
 * Android, aucune dépendance externe), DOCX et XLSX (OOXML minimal écrit à la main —
 * ce sont de simples archives ZIP contenant du XML normalisé ; pas besoin d'une
 * bibliothèque lourde type Apache POI pour un document/tableur simple avec du texte
 * et des données, juste produire le XML attendu par le format).
 *
 * Les fichiers créés sont de VRAIS documents ouvrables tels quels dans Word/Excel/
 * LibreOffice/Google Docs/Sheets (formatage minimal : titre, paragraphes pour DOCX ;
 * une feuille avec en-têtes et données pour XLSX) — pas de simulation.
 *
 * Enregistrés dans Documents/JARVIS-Fichiers/.
 */
object FileGenController {

    data class Result(val success: Boolean, val message: String, val filePath: String? = null)

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    private fun outputDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JARVIS-Fichiers")
            .also { it.mkdirs() }

    private fun safeFileName(name: String, extension: String): String {
        val base = name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim().ifBlank { "fichier" }
        return "${fileDateFormat.format(Date())}_$base.$extension"
    }

    // ─── ZIP ────────────────────────────────────────────────────────────────

    /**
     * Compresse une liste de fichiers/dossiers déjà existants sur le téléphone en
     * une seule archive .zip. [sourcePaths] doit contenir des chemins absolus réels
     * (ex: obtenus via list_files/search_files) — aucune invention de contenu ici,
     * uniquement de la compression de fichiers qui existent vraiment.
     */
    fun createZip(sourcePaths: List<String>, name: String): Result {
        if (sourcePaths.isEmpty()) return Result(false, "❌ Aucun fichier à compresser. Précise au moins un chemin existant (via list_files/search_files).")

        val missing = sourcePaths.filter { !File(it).exists() }
        if (missing.isNotEmpty()) {
            return Result(false, "❌ Introuvable(s), impossible de compresser : ${missing.joinToString(", ")}")
        }

        val outFile = File(outputDir(), safeFileName(name, "zip"))
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                sourcePaths.forEach { path -> addToZip(zos, File(path), File(path).name) }
            }
            Result(true, "🗜️ Archive créée avec ${sourcePaths.size} élément(s).\n📁 Enregistrée dans : ${outFile.absolutePath}", outFile.absolutePath)
        } catch (e: Exception) {
            outFile.delete()
            Result(false, "❌ Échec de la création de l'archive : ${e.message}")
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryPath: String) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return
            if (children.isEmpty()) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
            } else {
                children.forEach { addToZip(zos, it, "$entryPath/${it.name}") }
            }
        } else {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    // ─── PDF (API native Android, aucune dépendance) ───────────────────────

    /**
     * Génère un PDF réel (texte, paginé automatiquement) via android.graphics.pdf.PdfDocument
     * — l'API PDF intégrée à Android, pas une simulation. Format A4 (595x842 pt).
     */
    fun createPdf(title: String, content: String, name: String): Result {
        if (content.isBlank() && title.isBlank()) return Result(false, "❌ Aucun contenu fourni pour le PDF.")

        val pageWidth = 595
        val pageHeight = 842
        val marginX = 48f
        var marginTop = 56f
        val marginBottom = 56f

        val titlePaint = Paint().apply { textSize = 20f; isAntiAlias = true; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f; isAntiAlias = true }
        val lineHeight = 16f
        val maxWidth = pageWidth - marginX * 2

        val document = PdfDocument()
        return try {
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = document.startPage(pageInfo)
            var canvas = page.canvas
            var y = marginTop

            if (title.isNotBlank()) {
                wrapText(title, titlePaint, maxWidth).forEach { line ->
                    canvas.drawText(line, marginX, y, titlePaint)
                    y += 26f
                }
                y += 10f
            }

            content.split("\n").forEach { paragraph ->
                val wrapped = if (paragraph.isBlank()) listOf("") else wrapText(paragraph, bodyPaint, maxWidth)
                wrapped.forEach { line ->
                    if (y > pageHeight - marginBottom) {
                        document.finishPage(page)
                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = document.startPage(pageInfo)
                        canvas = page.canvas
                        y = marginTop
                    }
                    canvas.drawText(line, marginX, y, bodyPaint)
                    y += lineHeight
                }
            }
            document.finishPage(page)

            val outFile = File(outputDir(), safeFileName(name.ifBlank { title }, "pdf"))
            FileOutputStream(outFile).use { document.writeTo(it) }
            document.close()
            Result(true, "📄 PDF créé (${pageNumber} page${if (pageNumber > 1) "s" else ""}).\n📁 Enregistré dans : ${outFile.absolutePath}", outFile.absolutePath)
        } catch (e: Exception) {
            try { document.close() } catch (_: Exception) {}
            Result(false, "❌ Échec de la création du PDF : ${e.message}")
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    // ─── DOCX (OOXML minimal — un .docx EST une archive zip de XML) ────────

    /**
     * Génère un vrai fichier .docx (Word) : titre en gras + paragraphes (un par
     * ligne du contenu fourni). OOXML minimal écrit à la main plutôt qu'avec Apache
     * POI (bibliothèque lourde, mal adaptée à Android) — le résultat s'ouvre
     * normalement dans Word/LibreOffice/Google Docs.
     */
    fun createDocx(title: String, content: String, name: String): Result {
        if (content.isBlank() && title.isBlank()) return Result(false, "❌ Aucun contenu fourni pour le document.")

        val body = StringBuilder()
        if (title.isNotBlank()) {
            body.append(
                "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr><w:t xml:space=\"preserve\">${escapeXml(title)}</w:t></w:r></w:p>"
            )
            body.append("<w:p/>")
        }
        content.split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) {
                body.append("<w:p/>")
            } else {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(paragraph)}</w:t></w:r></w:p>")
            }
        }

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417"/></w:sectPr></w:body></w:document>"""

        val outFile = File(outputDir(), safeFileName(name.ifBlank { title }, "docx"))
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                writeZipEntry(zos, "[Content_Types].xml", CONTENT_TYPES_DOCX)
                writeZipEntry(zos, "_rels/.rels", RELS_ROOT_DOCX)
                writeZipEntry(zos, "word/document.xml", documentXml)
            }
            Result(true, "📝 Document Word créé.\n📁 Enregistré dans : ${outFile.absolutePath}", outFile.absolutePath)
        } catch (e: Exception) {
            outFile.delete()
            Result(false, "❌ Échec de la création du document : ${e.message}")
        }
    }

    private const val CONTENT_TYPES_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

    private const val RELS_ROOT_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    // ─── XLSX (OOXML minimal — un .xlsx EST une archive zip de XML) ────────

    /**
     * Génère un vrai fichier .xlsx (Excel) : une feuille avec les données fournies.
     * [csvContent] : une ligne par ligne du tableau, colonnes séparées par « ; »
     * (format simple à produire pour l'IA, pas de JSON imbriqué). La première ligne
     * est traitée comme l'en-tête. Détecte automatiquement les nombres (point ou
     * virgule décimale) pour les écrire comme vraies valeurs numériques Excel,
     * pas du texte.
     */
    fun createXlsx(sheetTitle: String, csvContent: String, name: String): Result {
        val rows = csvContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return Result(false, "❌ Aucune donnée fournie pour le tableur (une ligne par ligne, colonnes séparées par «;»).")

        val sheetName = sheetTitle.ifBlank { "Feuille1" }.take(31).replace(Regex("[\\\\/*?\\[\\]:]"), "-")
        val sheetXml = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")

        rows.forEachIndexed { rowIdx, rowLine ->
            val cells = rowLine.split(";").map { it.trim() }
            sheetXml.append("<row r=\"${rowIdx + 1}\">")
            cells.forEachIndexed { colIdx, cellValue ->
                val ref = "${columnLetter(colIdx)}${rowIdx + 1}"
                val numeric = cellValue.replace(",", ".").toDoubleOrNull()
                if (numeric != null && cellValue.isNotBlank()) {
                    sheetXml.append("<c r=\"$ref\"><v>${numeric}</v></c>")
                } else {
                    sheetXml.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(cellValue)}</t></is></c>")
                }
            }
            sheetXml.append("</row>")
        }
        sheetXml.append("</sheetData></worksheet>")

        val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

        val outFile = File(outputDir(), safeFileName(name.ifBlank { sheetTitle }, "xlsx"))
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                writeZipEntry(zos, "[Content_Types].xml", CONTENT_TYPES_XLSX)
                writeZipEntry(zos, "_rels/.rels", RELS_ROOT_XLSX)
                writeZipEntry(zos, "xl/workbook.xml", workbookXml)
                writeZipEntry(zos, "xl/_rels/workbook.xml.rels", RELS_WORKBOOK_XLSX)
                writeZipEntry(zos, "xl/worksheets/sheet1.xml", sheetXml.toString())
            }
            Result(true, "📊 Tableur créé (${rows.size} ligne(s)).\n📁 Enregistré dans : ${outFile.absolutePath}", outFile.absolutePath)
        } catch (e: Exception) {
            outFile.delete()
            Result(false, "❌ Échec de la création du tableur : ${e.message}")
        }
    }

    /** Convertit un index de colonne (0-based) en référence Excel (0->A, 25->Z, 26->AA...). */
    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

    private const val RELS_ROOT_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val RELS_WORKBOOK_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""

    // ─── Utilitaires communs ────────────────────────────────────────────────

    private fun writeZipEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Type MIME approprié pour ouvrir un fichier généré selon son extension. */
    fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "*/*"
    }

    /**
     * Ouvre un fichier déjà présent sur le téléphone avec l'application associée à son
     * type (lecteur PDF, Word, Excel, gestionnaire d'archives...), via FileProvider —
     * répond à la demande directe "ouvre ce fichier" en chat/vocal, sans passer par
     * l'écran 🎨 Génération. Fonctionne pour n'importe quel fichier existant, pas
     * seulement ceux créés par JARVIS (ex: un fichier trouvé via list_files).
     */
    fun openFile(context: Context, path: String): String {
        val file = File(path)
        if (!file.exists()) return "❌ Fichier introuvable : $path"
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = mimeTypeFor(path)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "📂 Ouverture de « ${file.name} »."
        } catch (e: android.content.ActivityNotFoundException) {
            "❌ Aucune application installée sur ce téléphone ne sait ouvrir ce type de fichier (${mimeTypeFor(path)}). Installe une app compatible (ex: un lecteur PDF, Word, ou une appli de fichiers/archives)."
        } catch (e: Exception) {
            "❌ Impossible d'ouvrir le fichier : ${e.message}"
        }
    }
}
