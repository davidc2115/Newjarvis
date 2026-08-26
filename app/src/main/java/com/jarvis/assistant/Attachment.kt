package com.jarvis.assistant

/**
 * Une pièce jointe de chat, après traitement par AttachmentController — image, page de PDF
 * rendue en image, image extraite d'une vidéo, ou texte extrait d'un document.
 *
 * - imageBase64/imageMime : présents pour une image exploitable en "vision" par l'IA (photo,
 *   page de PDF rendue en bitmap, frame extraite d'une vidéo). Envoyés tels quels aux
 *   fournisseurs qui supportent la vision (Claude, Gemini, GPT/OpenAI-compatible).
 * - extractedText : présent quand du texte a pu être extrait directement du fichier (DOCX,
 *   TXT/MD/CSV/JSON, listing d'un ZIP) — inséré dans le message envoyé à l'IA, fonctionne avec
 *   N'IMPORTE QUEL fournisseur, y compris ceux qui ne supportent pas la vision.
 * - Un seul fichier source peut produire PLUSIEURS Attachment (ex : un PDF de 5 pages devient
 *   jusqu'à 5 Attachment, un pour chaque page rendue en image — voir AttachmentController).
 */
data class Attachment(
    val path: String,
    val name: String,
    val mimeType: String,
    val imageBase64: String? = null,
    val imageMime: String? = null,
    val extractedText: String? = null
)
