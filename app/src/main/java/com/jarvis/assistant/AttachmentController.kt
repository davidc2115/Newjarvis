package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Transforme un fichier joint au chat (déjà copié en local par MainActivity) en une ou
 * plusieurs Attachment exploitables par l'IA — voir Attachment.kt pour le modèle de données.
 *
 * Chaque type de fichier est traité honnêtement selon ce qui est RÉELLEMENT réalisable côté
 * Android sans dépendance externe lourde :
 * - Image : encodée en base64 (vision).
 * - PDF : chaque page (plafonnée à MAX_PDF_PAGES) est RENDUE en image via android.graphics.
 *   pdf.PdfRenderer (API native Android, aucune librairie tierce) et envoyée en vision — ça
 *   fonctionne aussi bien pour du texte que pour un PDF scanné/graphique, contrairement à une
 *   extraction de texte qui échouerait sur un PDF scanné. Limite honnête : pas d'OCR dédié, la
 *   qualité de lecture dépend de ce que le modèle de vision arrive à lire sur l'image de la page.
 * - DOCX : texte extrait directement du XML interne (word/document.xml, balises <w:t>) — fonctionne
 *   avec N'IMPORTE QUEL fournisseur IA, pas besoin de vision.
 * - TXT/MD/CSV/JSON/code source : lu tel quel comme texte.
 * - ZIP : liste le contenu, et extrait en plus le texte des petits fichiers texte qu'il contient.
 * - Vidéo : 1 à 2 images représentatives extraites (début + milieu) via MediaMetadataRetriever
 *   (API native) — un APERÇU visuel, PAS une analyse image par image de toute la vidéo
 *   (honnêteté : ce n'est pas réalisable simplement côté mobile).
 * - Autre type : gardé comme simple pièce jointe (chemin/nom), sans contenu analysable.
 */
object AttachmentController {

    private const val MAX_PDF_PAGES = 8
    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_ZIP_ENTRIES_LISTED = 50
    private const val MAX_ZIP_TEXT_FILE_BYTES = 30_000

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "yaml", "yml", "log",
        "kt", "java", "py", "js", "ts", "html", "css", "c", "cpp", "h"
    )

    suspend fun process(context: Context, localPath: String, originalName: String, mimeType: String): List<Attachment> =
        withContext(Dispatchers.IO) {
            val file = File(localPath)
            if (!file.exists()) return@withContext listOf(Attachment(localPath, originalName, mimeType))

            try {
                when {
                    mimeType.startsWith("image/") -> listOf(imageAttachment(file, originalName, mimeType))
                    mimeType == "application/pdf" -> pdfAttachments(file, originalName)
                    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                        listOf(docxAttachment(file, originalName, mimeType))
                    mimeType.startsWith("video/") -> videoAttachments(file, originalName, mimeType)
                    mimeType == "application/zip" || originalName.endsWith(".zip", true) ->
                        listOf(zipAttachment(file, originalName, mimeType))
                    isPlainTextLike(originalName, mimeType) -> listOf(textAttachment(file, originalName, mimeType))
                    else -> listOf(Attachment(file.absolutePath, originalName, mimeType))
                }
            } catch (e: Exception) {
                listOf(Attachment(file.absolutePath, originalName, mimeType, extractedText = "(analyse impossible : ${e.message})"))
            }
        }

    private fun imageAttachment(file: File, name: String, mimeType: String): Attachment {
        val bytes = file.readBytes()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Attachment(file.absolutePath, name, mimeType, imageBase64 = base64, imageMime = mimeType)
    }

    private fun pdfAttachments(file: File, name: String): List<Attachment> {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val result = mutableListOf<Attachment>()
        try {
            val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
            for (i in 0 until pageCount) {
                renderer.openPage(i).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    val pageLabel = if (renderer.pageCount > 1) "$name — page ${i + 1}/${renderer.pageCount}" else name
                    result.add(Attachment(file.absolutePath, pageLabel, "image/jpeg", imageBase64 = base64, imageMime = "image/jpeg"))
                }
            }
            if (renderer.pageCount > MAX_PDF_PAGES) {
                result.add(
                    Attachment(
                        file.absolutePath, name, "application/pdf",
                        extractedText = "(PDF de ${renderer.pageCount} pages — seules les $MAX_PDF_PAGES premières ont été envoyées pour analyse)"
                    )
                )
            }
        } finally {
            renderer.close()
            pfd.close()
        }
        return result
    }

    /** Extrait le texte brut d'un .docx (zip contenant word/document.xml) sans dépendance externe. */
    private fun docxAttachment(file: File, name: String, mimeType: String): Attachment {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: return Attachment(file.absolutePath, name, mimeType, extractedText = "(document Word illisible : word/document.xml introuvable)")
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                // Extrait le texte de chaque balise <w:t...>...</w:t>, en insérant un saut de
                // ligne à chaque fin de paragraphe (</w:p>) pour garder une structure lisible.
                val text = StringBuilder()
                val regex = Regex("<w:t[^>]*>(.*?)</w:t>|</w:p>", RegexOption.DOT_MATCHES_ALL)
                for (m in regex.findAll(xml)) {
                    if (m.value == "</w:p>") text.append("\n") else text.append(unescapeXml(m.groupValues[1]))
                }
                Attachment(file.absolutePath, name, mimeType, extractedText = text.toString().trim().take(MAX_TEXT_CHARS))
            }
        } catch (e: Exception) {
            Attachment(file.absolutePath, name, mimeType, extractedText = "(échec de l'extraction du texte : ${e.message})")
        }
    }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    private fun textAttachment(file: File, name: String, mimeType: String): Attachment {
        val text = file.readText(Charsets.UTF_8).take(MAX_TEXT_CHARS)
        return Attachment(file.absolutePath, name, mimeType, extractedText = text)
    }

    /** Liste le contenu d'un ZIP et extrait en plus le texte des petits fichiers texte qu'il contient. */
    private fun zipAttachment(file: File, name: String, mimeType: String): Attachment {
        return try {
            ZipFile(file).use { zip ->
                val entries = mutableListOf<ZipEntry>()
                val en = zip.entries()
                while (en.hasMoreElements()) entries.add(en.nextElement())

                val sb = StringBuilder("Contenu de l'archive « $name » (${entries.size} élément(s)) :\n")
                entries.take(MAX_ZIP_ENTRIES_LISTED).forEach { e ->
                    sb.append(if (e.isDirectory) "📁 ${e.name}\n" else "📄 ${e.name} (${e.size} octets)\n")
                }
                if (entries.size > MAX_ZIP_ENTRIES_LISTED) sb.append("… et ${entries.size - MAX_ZIP_ENTRIES_LISTED} de plus.\n")

                // Extrait aussi le texte des petits fichiers texte qu'elle contient, pour une
                // vraie analyse de contenu et pas juste un listing de noms.
                val textEntries = entries.filter {
                    !it.isDirectory && it.size in 1..MAX_ZIP_TEXT_FILE_BYTES &&
                        TEXT_EXTENSIONS.contains(it.name.substringAfterLast('.', "").lowercase())
                }.take(10)
                for (e in textEntries) {
                    val content = zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText().take(3000)
                    sb.append("\n--- ${e.name} ---\n$content\n")
                }
                Attachment(file.absolutePath, name, mimeType, extractedText = sb.toString().take(MAX_TEXT_CHARS))
            }
        } catch (e: Exception) {
            Attachment(file.absolutePath, name, mimeType, extractedText = "(archive illisible : ${e.message})")
        }
    }

    /**
     * Extrait 1 à 2 images représentatives d'une vidéo (début + milieu) — un APERÇU visuel
     * seulement, pas une analyse de chaque image de la vidéo (irréaliste à faire simplement
     * côté mobile sans service dédié).
     */
    private fun videoAttachments(file: File, name: String, mimeType: String): List<Attachment> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val timestampsUs = if (durationMs > 2000) listOf(500_000L, durationMs * 1000 / 2) else listOf(500_000L)

            val result = mutableListOf<Attachment>()
            timestampsUs.forEachIndexed { i, us ->
                val frame = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val out = ByteArrayOutputStream()
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    result.add(
                        Attachment(
                            file.absolutePath, "$name — aperçu ${i + 1}/${timestampsUs.size}", "image/jpeg",
                            imageBase64 = base64, imageMime = "image/jpeg"
                        )
                    )
                }
            }
            if (result.isEmpty()) {
                result.add(Attachment(file.absolutePath, name, mimeType, extractedText = "(impossible d'extraire une image d'aperçu de cette vidéo)"))
            } else {
                result.add(
                    Attachment(
                        file.absolutePath, name, mimeType,
                        extractedText = "⚠️ Aperçu vidéo : ${result.size} image(s) extraite(s) (début/milieu), PAS une analyse complète de toute la vidéo."
                    )
                )
            }
            result
        } catch (e: Exception) {
            listOf(Attachment(file.absolutePath, name, mimeType, extractedText = "(aperçu vidéo impossible : ${e.message})"))
        } finally {
            retriever.release()
        }
    }

    private fun isPlainTextLike(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("text/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return TEXT_EXTENSIONS.contains(ext)
    }

    /** Énumère les fichiers d'un dossier choisi via ACTION_OPEN_DOCUMENT_TREE (un seul niveau, pas récursif). */
    fun listFolderChildren(context: Context, treeUri: Uri): List<Uri> {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<Uri>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val mime = cursor.getString(1)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
            }
        }
        return result
    }
}
