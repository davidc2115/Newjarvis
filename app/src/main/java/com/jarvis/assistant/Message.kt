package com.jarvis.assistant

data class Message(
    val text: String,
    val isUser: Boolean,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    // Chemin local du fichier joint (photo OU document, ex: PDF) tel quel sur le disque —
    // distinct de imageBase64 (qui sert uniquement à l'envoyer en "vision" à l'IA). Permet
    // de retrouver le fichier original plus tard (ex: attach_contact_file) même après que
    // le message soit sorti de la fenêtre de contexte envoyée à l'IA.
    val attachmentPath: String? = null,
    val attachmentName: String? = null
)
