package com.jarvis.assistant

import android.content.Context
import org.json.JSONObject

object JarvisCommandParser {

    sealed class CommandResult {
        data class Executed(
            val outputMessage: String,
            val action: String,
            val isInformational: Boolean,
            val imageBase64: String? = null,
            val imageMime: String? = null
        ) : CommandResult()
        data class ExecutedMultiple(val results: List<Executed>) : CommandResult()
        object None : CommandResult()
    }

    // Canal temporaire pour faire remonter une image générée jusqu'au chat
    // (executeAction renvoie un simple texte ; l'image est déposée ici par
    // l'action generate_image puis consommée immédiatement après par
    // parseAndExecute, avant que la commande suivante ne s'exécute).
    private var pendingImageBase64: String? = null
    private var pendingImageMime: String? = null

    // Actions qui RENVOIENT une information à présenter (l'IA doit reformuler
    // naturellement le résultat). Les autres actions sont des confirmations
    // d'exécution (ex: "SMS envoyé") qui n'ont pas besoin d'être reformulées.
    // Remarque : search_contact_profile / list_contacts_by_category sont volontairement
    // ABSENTS de cette liste — les fiches contact Obsidian doivent
    // s'afficher exactement comme formatées par PeopleController (liste propre avec
    // emojis), pas reformulées en prose par un second appel IA qui pourrait perdre des
    // champs ou casser la mise en forme demandée.
    private val INFORMATIONAL_ACTIONS = setOf(
        "list_files", "search_files", "read_file", "storage_info",
        "today_events", "upcoming_events", "search_event", "list_calendars",
        "read_sms", "read_unread_sms", "search_sms", "recent_calls",
        "read_emails", "read_unread_emails", "search_email", "read_email_content",
        "get_notifications", "bluetooth_info", "wifi_info",
        "web_search", "get_location", "search_contact",
        "github_list_repos", "github_read_file"
    )

    /**
     * Exécute une ou plusieurs commandes trouvées dans la réponse de l'IA.
     * Plusieurs blocs [JARVIS_CMD:...] peuvent apparaître dans une seule
     * réponse — utile par exemple pour créer un projet GitHub complet
     * (plusieurs fichiers) en une seule fois.
     */
    suspend fun parseAndExecute(context: Context, llmResponse: String): CommandResult {
        val regex = Regex("\\[JARVIS_CMD:(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(llmResponse).toList()
        if (matches.isEmpty()) return CommandResult.None

        val results = matches.map { match ->
            val jsonStr = match.groupValues[1].trim()
            try {
                val json = JSONObject(jsonStr)
                val action = json.optString("action", "").lowercase()
                val resultText = executeAction(context, action, json)
                val img = pendingImageBase64
                val mime = pendingImageMime
                pendingImageBase64 = null
                pendingImageMime = null
                CommandResult.Executed(resultText, action, action in INFORMATIONAL_ACTIONS, img, mime)
            } catch (e: Exception) {
                CommandResult.Executed("❌ Erreur d'exécution de la commande système : ${e.message}", "", false)
            }
        }

        return if (results.size == 1) results[0] else CommandResult.ExecutedMultiple(results)
    }

    private suspend fun executeAction(context: Context, action: String, json: JSONObject): String {
        return when (action) {
            "call" -> {
                val target = json.optString("target", "").ifBlank { json.optString("contact", "") }
                if (target.isBlank()) "❌ Aucun destinataire d'appel spécifié."
                else PhoneController.makeCall(context, target)
            }
            "end_call" -> PhoneController.endCall(context)
            "recent_calls" -> PhoneController.getRecentCalls(context, json.optInt("count", 10))

            "send_sms" -> {
                val to = json.optString("to", "").ifBlank { json.optString("contact", "") }
                val body = json.optString("message", "").ifBlank { json.optString("body", "") }
                if (to.isBlank() || body.isBlank()) "❌ Destinataire ou message SMS manquant."
                else SmsController.sendSms(context, to, body)
            }
            "read_sms" -> SmsController.readInboxSms(context, json.optInt("count", 5))
            "search_sms" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun mot-clé de recherche fourni."
                else SmsController.searchSms(context, query, json.optInt("count", 10))
            }
            "read_unread_sms" -> SmsController.readUnreadSms(context)

            "search_contact" -> {
                val name = json.optString("name", "").ifBlank { json.optString("query", "") }
                if (name.isBlank()) ContactsController.getContactList(context, json.optInt("count", 100))
                else ContactsController.searchContacts(context, name)
            }
            "add_contact" -> {
                val name = json.optString("name", "")
                val phone = json.optString("phone", "")
                val email = json.optString("email", "")
                if (name.isBlank() || phone.isBlank()) "❌ Nom ou numéro manquant pour le contact."
                else ContactsController.addContact(context, name, phone, email)
            }

            "play_music" -> {
                val query = json.optString("query", "")
                MediaController.playMusic(context, query)
            }
            "pause_music" -> MediaController.pauseMusic(context)
            "resume_music" -> MediaController.resumeMusic(context)
            "stop_music" -> MediaController.stopMusic(context)
            "next_track" -> MediaController.nextTrack(context)
            "set_volume" -> {
                val level = json.optInt("level", 5)
                MediaController.setVolume(context, level)
            }

            "today_events" -> CalendarController.getTodayEvents(context, json.optString("calendar", "").ifBlank { null })
            "upcoming_events" -> CalendarController.getUpcomingEvents(context, json.optInt("days", 7), json.optString("calendar", "").ifBlank { null })
            "create_event" -> {
                val title = json.optString("title", "Événement")
                val start = json.optLong("startTime", System.currentTimeMillis() + 3600000)
                val end = json.optLong("endTime", start + 3600000)
                val desc = json.optString("description", "")
                val loc = json.optString("location", "")
                val calendarRef = json.optString("calendar", "").ifBlank { null }
                CalendarController.createEvent(context, title, start, end, desc, loc, calendarRef)
            }
            "list_calendars" -> CalendarController.getCalendarList(context)
            "name_calendar" -> {
                // "calendar" accepte un ID, un nom affiché, ou un compte (email) — pas besoin
                // d'appeler list_calendars avant. "calendarId" reste accepté pour compatibilité.
                val calendarRef = json.optString("calendar", "").ifBlank {
                    val legacyId = json.optLong("calendarId", -1)
                    if (legacyId != -1L) legacyId.toString() else ""
                }
                val nickname = json.optString("nickname", "")
                if (calendarRef.isBlank() || nickname.isBlank()) "❌ Calendrier ou surnom manquant. Précise le nom affiché du calendrier, son compte (email), ou son ID (via list_calendars)."
                else CalendarController.nameCalendar(context, calendarRef, nickname)
            }
            "reset_calendar_nicknames" -> CalendarController.resetCalendarNicknames(context)

            "read_emails" -> EmailController.readInbox(context, json.optInt("count", 5))
            "read_unread_emails" -> EmailController.readUnread(context)
            "search_email" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun mot-clé de recherche fourni."
                else EmailController.searchEmails(context, query)
            }
            "read_email_content" -> EmailController.readEmailContent(context, json.optInt("index", 1))
            "send_email" -> {
                val to = json.optString("to", "")
                val subject = json.optString("subject", "")
                val body = json.optString("body", "")
                if (to.isBlank()) "❌ Adresse email destinataire manquante."
                else EmailController.sendEmail(context, to, subject, body)
            }

            "list_files" -> {
                val path = json.optString("path", "/sdcard")
                StorageController.listFiles(context, path)
            }
            "search_files" -> {
                val query = json.optString("query", "")
                StorageController.searchFiles(context, query)
            }
            "read_file" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin de fichier manquant."
                else StorageController.readTextFile(context, path)
            }
            "write_file" -> {
                val path = json.optString("path", "")
                val content = json.optString("content", "")
                if (path.isBlank()) "❌ Chemin de fichier manquant."
                else StorageController.writeTextFile(context, path, content)
            }
            "rename_file" -> {
                val oldPath = json.optString("oldPath", "").ifBlank { json.optString("path", "") }
                val newName = json.optString("newName", "").ifBlank { json.optString("newPath", "") }
                if (oldPath.isBlank() || newName.isBlank()) "❌ Ancien ou nouveau nom manquant."
                else StorageController.renameFile(context, oldPath, newName)
            }
            "copy_file" -> {
                val src = json.optString("source", "").ifBlank { json.optString("src", "") }
                val dest = json.optString("dest", "").ifBlank { json.optString("destination", "") }
                if (src.isBlank() || dest.isBlank()) "❌ Source ou destination manquante."
                else StorageController.copyFile(context, src, dest)
            }
            "move_file" -> {
                val src = json.optString("source", "").ifBlank { json.optString("src", "") }
                val dest = json.optString("dest", "").ifBlank { json.optString("destination", "") }
                if (src.isBlank() || dest.isBlank()) "❌ Source ou destination manquante."
                else StorageController.moveFile(context, src, dest)
            }
            "delete_file" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin de fichier manquant."
                else StorageController.deleteFile(context, path)
            }
            "create_folder" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin de dossier manquant."
                else StorageController.createFolder(context, path)
            }
            "storage_info" -> StorageController.getStorageInfo(context)

            "get_notifications" -> JarvisNotificationListenerService.getRecent(json.optInt("count", 5))

            "get_location" -> {
                var res = ""
                LocationController.getLastKnownLocation(context) { res = it }
                res.ifBlank { "📍 Recherche de localisation lancée..." }
            }
            "open_maps" -> {
                val query = json.optString("query", "")
                LocationController.openMaps(context, query)
            }

            "bluetooth_info" -> BluetoothController.getPairedDevices(context)
            "enable_bluetooth" -> BluetoothController.enableBluetooth(context)
            "disable_bluetooth" -> BluetoothController.disableBluetooth(context)

            "wifi_info" -> WifiController.getWifiInfo(context)
            "enable_wifi" -> WifiController.enableWifi(context)
            "disable_wifi" -> WifiController.disableWifi(context)

            "web_search" -> {
                val query = json.optString("query", "")
                WebSearchController.search(context, query)
            }

            "delete_event" -> {
                val eventId = json.optLong("eventId", -1)
                if (eventId == -1L) "❌ Identifiant d'événement manquant."
                else CalendarController.deleteEvent(context, eventId)
            }
            "update_event" -> {
                val eventId = json.optLong("eventId", -1)
                if (eventId == -1L) {
                    "❌ Identifiant d'événement manquant."
                } else {
                    CalendarController.updateEvent(
                        context,
                        eventId,
                        newTitle = json.optString("newTitle", "").ifBlank { null },
                        newStartTimeMillis = if (json.has("newStartTime")) json.optLong("newStartTime") else null,
                        newEndTimeMillis = if (json.has("newEndTime")) json.optLong("newEndTime") else null,
                        newDescription = json.optString("newDescription", "").ifBlank { null },
                        newLocation = json.optString("newLocation", "").ifBlank { null }
                    )
                }
            }
            "search_event" -> {
                val query = json.optString("query", "")
                CalendarController.searchEvents(context, query, json.optString("calendar", "").ifBlank { null })
            }

            "create_client_from_event" -> {
                val eventId = json.optLong("eventId", -1)
                if (eventId == -1L) "❌ Identifiant d'événement manquant (cherche-le d'abord via search_event/today_events)."
                else {
                    val event = CalendarController.getEventDetails(context, eventId)
                    if (event == null) "❌ Événement introuvable."
                    else PeopleController.createClientFromEvent(context, event, json.optString("name", "").ifBlank { null })
                }
            }
            "add_client_visit" -> {
                val name = json.optString("name", "")
                val note = json.optString("note", "").ifBlank { json.optString("visit", "") }
                if (name.isBlank() || note.isBlank()) "❌ Nom du client ou détail du rendez-vous manquant."
                else PeopleController.addVisit(context, name, note)
            }
            "export_clients_kml" -> {
                val category = json.optString("category", "client")
                KmlExportController.exportToKml(context, category).message
            }

            "github_list_repos" -> GitHubController.listRepos(context)

            "github_create_repo" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom de dépôt manquant."
                else GitHubController.createRepo(
                    context, name,
                    json.optString("description", ""),
                    json.optBoolean("private", false)
                )
            }

            "github_create_file", "github_update_file" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val path = json.optString("path", "")
                val content = json.optString("content", "")
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) {
                    "❌ Paramètres manquants (owner, repo et path sont requis)."
                } else {
                    GitHubController.createOrUpdateFile(
                        context, owner, repo, path, content,
                        json.optString("message", "Mise à jour via JARVIS"),
                        json.optString("branch", "main")
                    )
                }
            }

            "github_read_file" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val path = json.optString("path", "")
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) "❌ Paramètres manquants."
                else GitHubController.readFile(context, owner, repo, path, json.optString("branch", "main"))
            }

            "github_create_branch" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val newBranch = json.optString("newBranch", "")
                if (owner.isBlank() || repo.isBlank() || newBranch.isBlank()) "❌ Paramètres manquants."
                else GitHubController.createBranch(context, owner, repo, newBranch, json.optString("fromBranch", "main"))
            }

            "github_create_pr" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val title = json.optString("title", "")
                val head = json.optString("head", "")
                if (owner.isBlank() || repo.isBlank() || title.isBlank() || head.isBlank()) {
                    "❌ Paramètres manquants (owner, repo, title et head sont requis)."
                } else {
                    GitHubController.createPullRequest(
                        context, owner, repo, title, head,
                        json.optString("base", "main"),
                        json.optString("body", "")
                    )
                }
            }

            "save_contact_profile" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else PeopleController.saveContact(
                    context = context,
                    name = name,
                    category = json.optString("category", "autre"),
                    phone = json.optString("phone", "").ifBlank { null },
                    phonePro = json.optString("phonePro", "").ifBlank { null },
                    email = json.optString("email", "").ifBlank { null },
                    address = json.optString("address", "").ifBlank { null },
                    addressPro = json.optString("addressPro", "").ifBlank { null },
                    latitude = if (json.has("latitude")) json.optDouble("latitude") else null,
                    longitude = if (json.has("longitude")) json.optDouble("longitude") else null,
                    installDate = json.optString("installDate", "").ifBlank { null },
                    notes = json.optString("notes", "").ifBlank { null }
                )
            }
            "search_contact_profile" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun terme de recherche fourni."
                else PeopleController.searchContacts(context, query)
            }
            "list_contacts_by_category" -> PeopleController.listByCategory(context, json.optString("category", ""))
            "delete_contact_profile" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else PeopleController.deleteContact(context, name)
            }
            "navigate_to_contact" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else PeopleController.navigateToContact(context, name)
            }

            // Vault Obsidian réel (notes libres, distinct des fiches contacts ci-dessus) —
            // toujours utiliser ces actions plutôt que de deviner un contenu : le chemin
            // du vault est configurable (⚙ Obsidian) et peut différer de ce qu'on attend.
            "obsidian_status" -> {
                val path = ObsidianController.getVaultRoot(context).absolutePath
                "📂 Vault Obsidian actuel : $path\n\n" + ObsidianController.getVaultStats(context)
            }
            "obsidian_search" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun terme de recherche fourni."
                else ObsidianController.searchNotes(context, query)
            }
            "obsidian_list" -> ObsidianController.listNotes(context, json.optString("folder", ""))
            "obsidian_reset_path" -> ObsidianController.resetVaultPath(context)
            "obsidian_wipe" -> ObsidianController.wipeVault(context)

            "generate_image" -> {
                // L'image reste générée immédiatement (pas en arrière-plan) : c'est le cas le
                // plus rapide (quelques secondes via Gemini/OpenAI) et JARVIS l'affiche tout de
                // suite dans le chat. Voir generate_video/generate_website pour le mode différé.
                // Toujours enregistrée dans l'historique (🎨 Génération) pour rester cohérent
                // avec les générations lancées en arrière-plan.
                val prompt = json.optString("prompt", "")
                val recordId = "${System.currentTimeMillis()}_${(0..9999).random()}"
                Prefs.addGenerationRecord(
                    context,
                    Prefs.GenerationRecord(id = recordId, type = "image", prompt = prompt, status = "pending", timestamp = System.currentTimeMillis())
                )
                val result = ImageGenController.generateImage(context, prompt)
                Prefs.updateGenerationRecord(context, recordId) { record ->
                    record.copy(
                        status = if (result.base64 != null) "success" else "failed",
                        resultPath = result.savedPath,
                        errorMessage = if (result.base64 == null) result.message else null
                    )
                }
                pendingImageBase64 = result.base64
                pendingImageMime = result.mime
                result.message
            }
            "generate_video" -> {
                val prompt = json.optString("prompt", "")
                if (prompt.isBlank()) "❌ Aucune description de vidéo fournie."
                else {
                    GenerationService.enqueue(context, "video", prompt)
                    "🎬 Génération de la vidéo lancée en arrière-plan (1 à 3 minutes). " +
                        "Tu peux continuer à utiliser JARVIS ou fermer l'app — une notification " +
                        "t'avertira dès que c'est prêt (ou en cas d'échec)."
                }
            }
            "generate_website" -> {
                val description = json.optString("description", "").ifBlank { json.optString("prompt", "") }
                if (description.isBlank()) "❌ Aucune description de site fournie."
                else {
                    GenerationService.enqueue(context, "website", description)
                    "🌐 Génération du site lancée en arrière-plan. Une notification t'avertira " +
                        "dès que c'est prêt (ou en cas d'échec)."
                }
            }
            "edit_website" -> {
                val instructions = json.optString("instructions", "").ifBlank { json.optString("changes", "") }
                if (instructions.isBlank()) "❌ Précise la modification à apporter au site."
                else {
                    // Si aucun chemin n'est fourni, on modifie le dernier site généré avec succès.
                    val path = json.optString("path", "").ifBlank {
                        Prefs.getGenerationHistory(context)
                            .firstOrNull { it.type in setOf("website", "website_edit") && it.status == "success" && !it.resultPath.isNullOrBlank() }
                            ?.resultPath
                    }
                    if (path.isNullOrBlank()) "❌ Aucun site généré à modifier pour l'instant. Génère-en un d'abord avec generate_website."
                    else {
                        GenerationService.enqueue(context, "website_edit", instructions, existingPath = path)
                        "✏️ Modification du site lancée en arrière-plan (« $instructions »). Une notification t'avertira dès que c'est prêt."
                    }
                }
            }

            "ha_status" -> HomeAssistantController.summarize(context, json.optString("filter", ""), json.optString("domain", ""))
            "ha_turn_on" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.turnOn(context, entity.entityId).message
            }
            "ha_turn_off" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.turnOff(context, entity.entityId).message
            }
            "ha_toggle" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.toggle(context, entity.entityId).message
            }
            "ha_rename" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val newName = json.optString("newName", "").ifBlank { json.optString("new_name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else if (newName.isBlank()) "❌ Précise le nouveau nom souhaité."
                else HomeAssistantController.renameEntity(context, entity.entityId, newName).message
            }
            "ha_delete" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.deleteEntity(context, entity.entityId).message
            }
            "ha_rescan" -> HomeAssistantController.summarize(context, json.optString("filter", ""), json.optString("domain", ""))
            "ha_set" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.setValue(
                    context, entity.entityId,
                    brightnessPct = if (json.has("brightness")) json.optInt("brightness") else null,
                    colorName = json.optString("color", "").ifBlank { null },
                    temperature = if (json.has("temperature")) json.optDouble("temperature") else null,
                    volumePct = if (json.has("volume")) json.optInt("volume") else null,
                    positionPct = if (json.has("position")) json.optInt("position") else null,
                    speedPct = if (json.has("speed")) json.optInt("speed") else null
                ).message
            }
            "ha_browse_media" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun lecteur média Home Assistant trouvé pour « $name »."
                else HomeAssistantController.browseMedia(
                    context, entity.entityId,
                    json.optString("path", "").ifBlank { null },
                    json.optString("mediaType", "").ifBlank { null }
                )
            }

            "network_scan" -> {
                val devices = NetworkController.scanNetwork(context)
                Prefs.saveScannedDevices(context, devices)
                NetworkController.formatScanResult(devices)
            }
            "network_ping" -> {
                val device = json.optString("device", "").ifBlank { json.optString("name", "") }
                if (device.isBlank()) "❌ Précise le nom ou l'IP de l'appareil à tester."
                else NetworkController.pingDevice(context, device)
            }
            "network_open_web" -> {
                val device = json.optString("device", "").ifBlank { json.optString("name", "") }
                if (device.isBlank()) "❌ Précise le nom ou l'IP de l'appareil dont ouvrir l'interface web."
                else NetworkController.openWebInterface(context, device)
            }

            "freebox_list" -> {
                val path = json.optString("path", "/")
                val result = FreeboxController.listDirectory(context, path)
                FreeboxController.formatDirectoryListing(path, result)
            }
            "freebox_mkdir" -> {
                val parent = json.optString("parent", "/")
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom de dossier manquant."
                else FreeboxController.createFolder(context, parent, name).message
            }
            "freebox_rename" -> {
                val path = json.optString("path", "")
                val newName = json.optString("newName", "")
                if (path.isBlank() || newName.isBlank()) "❌ Chemin ou nouveau nom manquant."
                else FreeboxController.renameEntry(context, path, newName).message
            }
            "freebox_delete" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin manquant."
                else FreeboxController.deleteEntry(context, path).message
            }
            "freebox_move" -> {
                val path = json.optString("path", "")
                val dest = json.optString("dest", "")
                if (path.isBlank() || dest.isBlank()) "❌ Chemin source ou destination manquant."
                else FreeboxController.moveEntry(context, path, dest).message
            }
            "freebox_wifi_on" -> FreeboxController.setWifiState(context, true).message
            "freebox_wifi_off" -> FreeboxController.setWifiState(context, false).message
            "freebox_wifi_status" -> FreeboxController.getWifiStatus(context).message
            "freebox_status" -> FreeboxController.getSystemStatus(context).message
            "freebox_permissions" -> FreeboxController.getPermissionsStatus(context).message
            "wake_on_lan" -> {
                val mac = json.optString("mac", "")
                val deviceName = json.optString("device", "").ifBlank { json.optString("name", "") }
                val resolvedMac = mac.ifBlank {
                    Prefs.getSavedNetworkDevices(context).firstOrNull {
                        it.name.equals(deviceName, ignoreCase = true)
                    }?.mac ?: ""
                }
                if (resolvedMac.isBlank()) "❌ Adresse MAC inconnue. Précise l'adresse MAC ou enregistre d'abord l'appareil dans 🏠 → Réseau local."
                else NetworkController.sendWakeOnLan(context, resolvedMac)
            }

            "create_zip" -> {
                val paths = mutableListOf<String>()
                json.optJSONArray("paths")?.let { arr -> for (i in 0 until arr.length()) paths.add(arr.optString(i)) }
                if (paths.isEmpty()) json.optString("path", "").ifBlank { null }?.let { paths.add(it) }
                val name = json.optString("name", "").ifBlank { "archive" }
                val result = FileGenController.createZip(paths, name)
                logFileRecord(context, "zip", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_pdf" -> {
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                val name = json.optString("name", "").ifBlank { title }
                val result = FileGenController.createPdf(title, content, name)
                logFileRecord(context, "pdf", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_docx" -> {
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                val name = json.optString("name", "").ifBlank { title }
                val result = FileGenController.createDocx(title, content, name)
                logFileRecord(context, "docx", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_xlsx" -> {
                val title = json.optString("title", "").ifBlank { json.optString("sheetName", "") }
                val data = json.optString("data", "").ifBlank { json.optString("content", "") }
                val name = json.optString("name", "").ifBlank { title }
                val result = FileGenController.createXlsx(title, data, name)
                logFileRecord(context, "xlsx", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_chart" -> {
                val type = json.optString("type", "bar")
                val title = json.optString("title", "")
                val data = json.optString("data", "")
                val recordId = "${System.currentTimeMillis()}_${(0..9999).random()}"
                Prefs.addGenerationRecord(
                    context,
                    Prefs.GenerationRecord(id = recordId, type = "chart", prompt = title.ifBlank { "Graphique" }, status = "pending", timestamp = System.currentTimeMillis())
                )
                val result = ChartController.generateChart(type, title, data)
                Prefs.updateGenerationRecord(context, recordId) { record ->
                    record.copy(
                        status = if (result.success) "success" else "failed",
                        resultPath = result.savedPath,
                        errorMessage = if (!result.success) result.message else null
                    )
                }
                pendingImageBase64 = result.base64
                pendingImageMime = result.mime
                result.message
            }

            else -> "❌ Commande système inconnue : « $action »."
        }
    }

    /** Enregistre un fichier créé (zip/pdf/docx/xlsx) dans l'historique de 🎨 Génération, pour rester cohérent avec les autres types de génération et pouvoir le retrouver plus tard depuis la galerie. */
    private fun logFileRecord(context: Context, kind: String, name: String, success: Boolean, path: String?, message: String) {
        val id = "${System.currentTimeMillis()}_${(0..9999).random()}"
        Prefs.addGenerationRecord(
            context,
            Prefs.GenerationRecord(
                id = id, type = "file_$kind", prompt = name.ifBlank { kind }, status = if (success) "success" else "failed",
                timestamp = System.currentTimeMillis(), resultPath = path, errorMessage = if (!success) message else null
            )
        )
    }

    fun cleanResponse(llmResponse: String): String {
        return llmResponse.replace(Regex("\\[JARVIS_CMD:.*?\\]", RegexOption.DOT_MATCHES_ALL), "").trim()
    }
}
