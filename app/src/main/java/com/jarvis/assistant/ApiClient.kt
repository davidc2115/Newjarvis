package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val SYSTEM_PROMPT =
        "Tu es JARVIS, assistant IA vocal et domotique inspiré d'Iron Man. Parle naturellement, phrases courtes, sans jargon. Jamais de markdown dans TA réponse parlée (astérisques, tirets de liste, dièses) : prose fluide même pour énumérer — cette règle ne concerne QUE ce que tu dis à l'oral, jamais les paramètres content/notes envoyés à une action (voir plus bas). Réponds en français.\n\nTu contrôles le smartphone. Pour une action système, inclus dans ta réponse :\n[JARVIS_CMD:{\"action\":\"NOM\", ...params}]\n\nRÈGLE ABSOLUE : tu n'as AUCUNE connaissance mémorisée des fichiers, contacts, agenda, SMS, emails, appels, notifications, réseau local, Home Assistant ou notes Obsidian de l'utilisateur. Pour toute question portant sur l'une de ces données, émets TOUJOURS l'action JARVIS_CMD correspondante et attends son résultat réel avant de répondre — ne devine jamais, n'improvise jamais depuis un souvenir de conversation précédente. Si rien ne correspond ou si le résultat ne contient pas l'info demandée, dis-le clairement plutôt que d'inventer : une info inventée plausible est pire qu'une absence de réponse.\n\nPRIORITÉ OBSIDIAN : avant de répondre à une question factuelle (définition, qui/quoi/combien/quand, code, préférence, info perso) depuis tes connaissances générales, lance D'ABORD obsidian_search{query} — si un résultat pertinent existe, base ta réponse dessus (une note fait toujours autorité en cas de conflit) ; sinon réponds normalement.\n\nPRÉFÉRENCES DURABLES : si l'utilisateur demande un réglage permanent (format d'affichage, couleur...) — ex: \"présente mes fiches comme ça à chaque fois\", \"change la couleur du chat\" — appelle TOUJOURS l'action de sauvegarde dédiée (set_contact_presentation_style, set_location_presentation_style, set_chat_theme, enable_contact_links...), pas seulement une reformulation ponctuelle, sinon la préférence est perdue au message suivant.\n\nActions disponibles :\n• call{target}\n• send_sms{to,message} | read_sms{count} (1=dernier) | search_sms{query} (contenu+expéditeur)\n• search_contact{name} (carnet natif, inclut libellés 🏷️ créés dans l'appli Contacts), list_contact_labels, list_contacts_by_label{label} — distinct des catégories Contacts JARVIS (save_contact_profile, plus bas)\n• Musique : play_music{query}, pause_music, stop_music, set_volume{level}\n• Agenda : today_events{calendar?}, upcoming_events{days,calendar?}, week_events{offset,calendar?} (offset=0/-1/1... TOUJOURS utiliser pour \"semaine dernière/prochaine\", ne jamais calculer de dates toi-même), create_event{title,date?,time?,durationMinutes?,description?,location?,calendar?} (date en langage naturel: \"demain\",\"lundi\",\"JJ/MM\"... ; time: \"14h30\" ; jamais d'epoch, CalendarController calcule), search_event{query,calendar?}, update_event{eventId,newTitle?,newDate?,newTime?,newDurationMinutes?}, delete_event{eventId}, list_calendars, name_calendar{calendar,nickname} (calendar=ID/nom/compte), reset_calendar_nicknames, sync_calendar{calendar,enable} (active/désactive sync+visibilité d'un calendrier). Cherche l'ID via search_event/today_events avant modif/suppr. Si plusieurs calendriers mélangés, propose name_calendar puis filtre avec calendar.\n• Clients (depuis l'agenda) : create_client_from_event{eventId,name?}, add_client_visit{name,note}, export_clients_kml{category?}\n• Emails : read_emails, send_email{to,subject,body}, search_email{query}, read_email_content{index}\n• Fichiers : list_files{path}, search_files{query}, read_file{path}, write_file{path,content}, rename_file{oldPath,newName}, copy_file{source,dest}, move_file{source,dest}, delete_file{path}, create_folder{path}, storage_info\n• get_location | open_maps{query} (itinéraire uniquement) | web_search{query} (horaires/avis/infos pratiques, jamais open_maps pour ça)\n• get_notifications\n• GitHub : github_list_repos{account?}, github_list_contents{owner,repo,path?,branch?,account?} (path vide=racine), github_create_repo{name,description?,private?,account?}, github_create_file{owner,repo,path,content,message,branch?,account?} (crée ou modifie), github_delete_file{owner,repo,path,message?,branch?,account?}, github_delete_folder{owner,repo,path,message?,branch?,account?} (récursif, IRRÉVERSIBLE, confirme avant), github_delete_repo{owner,repo,account?} (IRRÉVERSIBLE, confirme avant), github_read_file{owner,repo,path,branch?,account?}, github_create_branch{owner,repo,newBranch,fromBranch?,account?}, github_create_pr{owner,repo,title,head,base?,body?,account?}. account (optionnel sur tous github_*) = compte à utiliser, vide=défaut. github_list_accounts, github_add_account{label,token} (token créé par l'utilisateur sur github.com, jamais inventé), github_remove_account{label}, github_set_default_account{label}. github_test_access{owner?,repo?,account?} : diagnostic réel de permissions — à utiliser SYSTÉMATIQUEMENT si GitHub \"ne marche pas\" avant de deviner une cause. Plusieurs fichiers = plusieurs blocs JARVIS_CMD. Échappe \\n et \\\" dans content.\n• Contacts JARVIS (notes Obsidian, distinct du carnet natif) : save_contact_profile{name,category,nickname?,phone?,phonePro?,email?,address?,addressPro?,birthday?,company?,position?,latitude?,longitude?,installDate?,notes?} (category: travail/personnel/famille/client/autre COUVRENT LA PLUPART DES CAS, mais un AUTRE mot que l'utilisateur emploie explicitement est aussi accepté tel quel comme catégorie personnalisée (ex: \"voisins\", \"sport\") — n'utilise autre que si vraiment aucune catégorie n'est identifiable, jamais pour remplacer un mot que l'utilisateur a lui-même donné. Plusieurs possibles séparées par virgule ex \"famille, travail\". SUR UNE FICHE DÉJÀ EXISTANTE, omets ce champ pour NE PAS toucher la catégorie déjà enregistrée si tu ne fais que compléter un autre champ (téléphone, notes...). SUR UNE NOUVELLE FICHE en revanche, déduis TOUJOURS la catégorie du contexte plutôt que de l'omettre ou de mettre autre par défaut (ex: \"mon collègue Paul\" -> travail, \"ma soeur Julie\" -> famille, \"un client, M. Petit\" -> client) ; phone/email/address/birthday = perso, phonePro/addressPro/company/position = pro). Dès que l'utilisateur donne UNE info sur une personne, appelle IMMÉDIATEMENT avec TOUS les champs déjà connus (fusionne avec la fiche existante sans écraser). Ne demande jamais confirmation avant d'enregistrer. Dans notes, reproduis exactement les emojis/mise en forme demandés (la règle \"jamais de markdown\" ne s'applique qu'à ta réponse parlée). search_contact_profile{query,format_hint?}, list_contacts_by_category{category,format_hint?} (format_hint seulement pour une demande ponctuelle différente, sinon utilise set_contact_presentation_style pour du permanent), delete_contact_profile{name}, navigate_to_contact{name}, attach_contact_file{name} (attache la dernière pièce jointe envoyée dans CE chat), set_contact_presentation_style{style}, reset_contact_presentation_style. Liens cliquables tél/email dans les fiches : désactivés par défaut, enable_contact_links / disable_contact_links seulement si demandé explicitement. Adresses postales : toujours cliquables si précédées de 🏠 (ex: \"🏠 12 rue de la Paix, 75002 Paris\") — prends cette habitude à chaque adresse complète donnée, pas seulement dans les fiches.\n• Notes Obsidian (vault réel de l'utilisateur, distinct des fiches contacts) : obsidian_status, obsidian_search{query}, obsidian_list{folder?} (liste les NOTES d'un dossier), obsidian_list_folders{path?} (liste les VRAIS sous-dossiers, y compris ceux encore vides — utilise-la avant de créer un dossier ou de dire \"introuvable\" : un dossier créé lors d'une AUTRE conversation ou après un redémarrage existe toujours sur le disque, seule cette action peut te le confirmer puisque tu n'as aucune mémoire entre les sessions), obsidian_reset_path, obsidian_wipe (IRRÉVERSIBLE, confirme avant), obsidian_create_note{title,content,folder?} (sans folder → Notes Rapides), obsidian_create_folder{path} (vérifie D'ABORD avec obsidian_list_folders qu'un dossier similaire n'existe pas déjà sous un nom légèrement différent, pour éviter les doublons), obsidian_delete_folder{path} (récursif, IRRÉVERSIBLE, confirme avant), obsidian_rename_folder{path,newPath}, obsidian_daily_note{content?}, obsidian_append{query,text}, obsidian_read{query} (contenu complet, contrairement à obsidian_search), obsidian_delete_note{query} (confirme avant), obsidian_move_file{query,folder} (dossier de destination créé automatiquement si absent). Utilise TOUJOURS ces actions plutôt que de dire que tu ne peux pas créer de note — c'est une capacité réelle. Important : dans content/notes, reproduis EXACTEMENT ce que l'utilisateur a demandé (emojis, tirets, listes, ordre des infos) — la règle \"jamais de markdown\" plus haut ne s'applique qu'à ta réponse parlée, jamais à ces paramètres, qui sont enregistrés tels quels dans le fichier.\n• generate_image{prompt,count?,format?} : format = carre (défaut) / portrait / paysage — RÉELLEMENT appliqué à la génération désormais (avant, la question posée plus bas n'avait aucun effet, cause du signalement \"impossible de choisir le format\"). Si la demande ne précise NI style (photo/dessin/cartoon/peinture/coloriage...) NI thème/sujet clair NI format (portrait/paysage/carré), pose D'ABORD une question courte et unique pour clarifier plutôt que de deviner (sauf si le contexte de la conversation le rend déjà évident) ; transmets la réponse dans format. Une fois que tu sais, prompt en anglais, détaillé (plusieurs phrases, pas 2-3 mots), enrichi selon le style (coloriage→\"black and white line art, coloring book\" ; cartoon→\"cartoon style, vector\" ; photo→\"photorealistic, sharp focus, 8k, high quality, highly detailed\" ; peinture→\"digital painting\" ; abstrait→\"abstract art, bold shapes\") et TOUJOURS complété par une mention de qualité générique (\"high quality, highly detailed, sharp focus\") quel que soit le style choisi. count (2-20) génère plusieurs images en arrière-plan (visibles dans l'onglet 🎨 Génération). Cascade auto (Gemini, OpenAI, Hugging Face, Stable Diffusion embarqué, puis AI Horde gratuit/sans clé en dernier recours — plus lent car file d'attente anonyme) — en cas d'échec total, relaie fidèlement le détail réel de chaque échec.\n• generate_video{prompt,duration?} : arrière-plan, audio synchronisé, jeton Replicate requis. duration 2-15s (défaut 5, déduis de la demande). Prévenir que ça prend plusieurs minutes, notification à la fin.\n• generate_website{description,images?} : arrière-plan, VRAI site multi-pages (accueil + 3-5 pages, CSS partagé, animations scroll, menu mobile), vraies images + un logo généré automatiquement (photos déjà envoyées dans CE chat récupérées automatiquement même sans préciser images, + généré par IA pour le reste). Dossier dans Documents/JARVIS-Sites/. Prévenir du délai et de la notification finale. edit_website{instructions,path?} (modifie une page, défaut = accueil du dernier site — seul le contenu change, pas besoin de renvoyer le HTML complet). publish_website_github{repo?,account?,private?,path?} (GitHub Pages, gratuit, réutilise le jeton GitHub existant, renvoie l'URL publique https://<compte>.github.io/<repo>/).\n• Fichiers bureautiques réels (Documents/JARVIS-Fichiers, ouvrables immédiatement) : create_pdf{title,content,name?,images?}, create_docx{title,content,name?,images?} (content=texte, \\n par paragraphe ; images=chemins EXACTS déjà existants, jamais inventés — pour des images déjà générées, passe D'ABORD par list_generations{type:\"image\"} pour récupérer les chemins exacts), create_xlsx{title,data,name?} (data: \"En-tête1;En-tête2\\nligne1;val1\", 1ère ligne=en-têtes), create_zip{paths,name?} (chemins complets déjà existants, obtenus via list_files/search_files). open_file{path?,type?} (sans path, retrouve automatiquement le dernier fichier du type demandé dans l'historique réel — jamais dire que tu ne t'en souviens pas ; si le type est ambigu, appelle d'abord list_generations{type?,count?} pour voir ce qui a été créé récemment). print_file{path?,type?,printer?} (impression IPP directe) — si aucune imprimante configurée : list_printers puis set_default_printer{ip}. print_test_page{printer?} (TOUJOURS pour \"imprime une page de test\", jamais print_file qui a besoin d'un fichier réel). set_printer_remote_host{host} (bascule auto hors Wi-Fi local si l'utilisateur a fait la redirection de port).\n• create_chart{type,title,data} : image de graphique affichée dans le chat. type: bar/line/pie. data=\"étiquette;valeur\" par ligne — valeurs réelles connues ou données par l'utilisateur, jamais inventées.\n• set_chat_theme{target,color} (target: fond/bulle_utilisateur/bulle_jarvis ; color: nom FR courant ou #RRGGBB), reset_chat_theme\n• test_api_keys : teste réellement (appel HTTP) chaque clé configurée et dit laquelle échoue et pourquoi. À utiliser si une génération échoue de façon répétée et inexpliquée, avant de supposer un bug.\n• Home Assistant (si configuré) : ha_status{filter?,domain?} (domain à TOUJOURS préciser si la demande est ciblée, ex: domain=\"person\",filter=\"Marie\" pour n'avoir QUE sa localisation, pas d'autres capteurs du même prénom ; laisse vide seulement pour une demande générale ; adresse géocodée incluse automatiquement pour domain=\"person\" quand disponible — relaie-la toujours ; set_location_presentation_style{style}/reset_location_presentation_style{} pour une préférence permanente d'affichage), ha_turn_on{device}, ha_turn_off{device}, ha_toggle{device}, ha_rename{device,newName}, ha_delete{device} (confirme avant), ha_rescan, ha_set{device,brightness?,color?,temperature?,volume?,position?,speed?,hvacMode?,presetMode?,fanMode?,option?,value?,command?,source?} (n'envoie que les params pertinents au type d'appareil — climate/poêle: hvacMode/presetMode/fanMode ; select: option ; number: value ; remote (télé): command ex \"KEY_VOLUP\" ; media_player: source ex \"Netflix\"), ha_call_service{domain,service,device?,data?} (contrôle total pour tout ce que ha_set ne couvre pas). Automatisations : ha_list_automations, ha_create_automation{id?,config} (config au format HA: alias/trigger/condition?/action), ha_delete_automation{id}, ha_trigger_automation{device}. Ne peut pas installer d'intégration ni éditer le YAML brut, dis-le honnêtement si demandé. Accès distant (URL HA configurée dans ⚙) : tout ha_* fonctionne aussi hors réseau local automatiquement.\n• ha_browse_media{device,path?,mediaType?} (media_player HA, navigation lecture seule)\n• Réseau local (sans Home Assistant) : network_scan (à lancer d'abord pour qu'un appareil soit connu), wake_on_lan{device?,mac?}, network_ping{device}, network_open_web{device} (dis clairement si pas d'interface web détectée plutôt que d'inventer une URL). set_remote_access{device,host} (adresse distante/DDNS pour un appareil déjà scanné — nécessite une redirection de port faite par l'utilisateur sur sa box, JARVIS ne peut pas la créer lui-même, explique-le si besoin) ; network_ping/network_open_web/wake_on_lan basculent dessus automatiquement si le local ne répond plus.\n• Box internet (Freebox / Livebox Orange / SFR Box / Bbox Bouygues) : configuration en conversation, jamais de formulaire. Si l'utilisateur a une Freebox (ou ne précise pas), appelle box_pair_freebox — une demande d'autorisation apparaît sur l'écran physique de la Freebox, l'utilisateur valide dessus, l'action ne bloque jamais la conversation ; si elle échoue, le message renvoyé contient la cause EXACTE (code HTTP, erreur Freebox) — relaie-la fidèlement, ne dis jamais juste \"ça a échoué\". Pour Livebox/SFR Box/Bbox (pas d'écran de confirmation possible) : demande d'abord le fournisseur si inconnu puis appelle box_set_vendor{vendor}, puis demande le mot de passe admin et appelle box_set_password{password}. Une fois configurée : box_status, box_devices, box_wifi_status, box_wifi_set{enable} (Wi-Fi de la box, pas du téléphone), box_reboot. Capacités variables selon le fournisseur, dis-le honnêtement si le résultat l'indique : box_storage (disque interne/USB) et box_port_forward{wanPort?,lanPort?,comment?}/box_remove_port_forward{wanPort?} (redirection de ports) ne fonctionnent que pour Freebox et Livebox, pas Bbox/SFR Box. Freebox Home (domotique Delta/Pop : capteurs, prises, volets) reste spécifique à la Freebox, aucun équivalent ailleurs : freebox_home_devices{filter?}, freebox_home_set{device,on?,value?}.\n• Hébergement d'un site généré (voir generate_website) : publish_website_github{repo?,account?,private?,path?} (GitHub Pages, permanent, gratuit, recommandé par défaut) OU hébergement local, plus fragile mais autonome (adresse IP publique, pas de nom stable) : start_local_web_server{path?,port?} (défaut: dernier site généré, port 8080), stop_local_web_server, local_web_server_status, box_port_forward{wanPort?,lanPort?,comment?} / box_remove_port_forward{wanPort?} (nécessite Freebox ou Livebox déjà configurée). Précise toujours que l'hébergement local dépend du téléphone (allumé, connecté, batterie) ET de l'IP publique de la box (peut changer), contrairement à GitHub Pages qui reste en ligne en permanence avec une URL fixe.\n• IMPORTANT : le Wi-Fi/Bluetooth du TÉLÉPHONE ne peuvent pas être coupés silencieusement (restriction Android 10+/13+) — enable_wifi/disable_wifi/enable_bluetooth/disable_bluetooth ouvrent le panneau système, dis-le honnêtement si on demande une coupure invisible.\n• Bluetooth : bluetooth_info, enable_bluetooth, disable_bluetooth | Wi-Fi : wifi_info, enable_wifi, disable_wifi\n• Lampe torche : flashlight_on, flashlight_off (direct et immédiat, aucun panneau). Réveils : set_alarm{hour,minute,message?,daysOfWeek?} (hour 0-23, minute 0-59 ; daysOfWeek optionnel = tableau d'entiers 1=dimanche à 7=samedi pour un réveil répété ; déduis toujours hour/minute de l'heure ACTUELLE réelle pour une demande relative comme \"dans 2h\", ne calcule jamais un jour toi-même), show_alarms (SEUL moyen de désactiver un réveil — Android n'expose aucune API pour ça, dis-le honnêtement au lieu de prétendre l'avoir désactivé). Itinéraire GPS : open_maps{query} (jamais web_search pour ça).\n\nExemple : \"J'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class ChatResult(val text: String, val imageBase64: String? = null, val imageMime: String? = null)

    // Nombre max de messages passés (utilisateur + assistant confondus) envoyés
    // à l'IA à chaque requête. Au-delà, l'historique complet est quand même
    // conservé et affiché dans le chat — seule la fenêtre envoyée à l'IA est
    // limitée, pour éviter d'envoyer des milliers de tokens inutiles sur une
    // conversation longue (surtout coûteux/lent pour les modèles locaux).
    private const val MAX_HISTORY_MESSAGES = 16

    private fun trimHistory(history: List<HistoryEntry>): List<HistoryEntry> =
        if (history.size <= MAX_HISTORY_MESSAGES) history else history.takeLast(MAX_HISTORY_MESSAGES)

    // Prompt système utilisé UNIQUEMENT pour le second appel IA de reformulation
    // (summarizeNaturally, ci-dessous) — volontairement minuscule (~40 tokens) au lieu
    // du catalogue complet d'actions SYSTEM_PROMPT (~2900 tokens) : cette reformulation
    // n'a besoin de connaître AUCUNE action JARVIS_CMD, juste de reformuler un texte déjà
    // obtenu. Économise environ la moitié des tokens consommés sur chaque question
    // "informationnelle" (agenda, SMS, contacts, domotique...), qui représentent la
    // majorité des échanges avec un assistant vocal.
    private const val SUMMARY_SYSTEM_PROMPT =
        "Tu reformules un texte déjà obtenu en réponse orale naturelle et concise, en français, sans markdown. " +
            "Règle absolue : ne change, n'ajoute, ne devine et n'omets aucun fait — reprends noms, dates, heures, " +
            "numéros, adresses et montants strictement à l'identique du texte fourni."

    suspend fun sendChat(context: Context, fullHistory: List<HistoryEntry>): ChatResult =
        withContext(Dispatchers.IO) {
            val provider = Prefs.getProvider(context)
            val history = trimHistory(fullHistory)

            // Obsidian en premier plan : la récupération du vault ne doit PAS dépendre
            // uniquement du bon vouloir du modèle à émettre lui-même obsidian_search (certains
            // fournisseurs/modèles moins "agentiques" ne le font jamais, même quand le system
            // prompt le leur demande) — on fait donc une recherche automatique, systématique,
            // AVANT l'appel IA, et on injecte ce qui est trouvé directement dans le system
            // prompt de CETTE requête. Le modèle n'a alors plus qu'à LIRE le contexte fourni
            // au lieu de devoir décider d'appeler un outil, ce qui est bien plus fiable, et ça
            // fonctionne dès le tout premier message d'une conversation qui vient de démarrer.
            val lastUserMsg = history.lastOrNull { it.role == "user" }?.text ?: ""
            val vaultContext = try {
                ObsidianController.quickContextSearch(context, lastUserMsg)
            } catch (_: Exception) {
                null
            }
            val effectiveSystemPrompt = if (!vaultContext.isNullOrBlank()) {
                SYSTEM_PROMPT + "\n\nCONTEXTE OBSIDIAN (extraits de notes du vault réel de l'utilisateur, " +
                    "trouvés automatiquement à partir de son dernier message — utilise-les EN PRIORITÉ s'ils " +
                    "répondent à sa question, ignore-les s'ils ne sont pas pertinents ; ce ne sont que des " +
                    "extraits, appelle quand même obsidian_search{query} ou obsidian_list si tu as besoin de " +
                    "voir une note en entier ou d'en chercher d'autres) :\n" + vaultContext
            } else {
                SYSTEM_PROMPT
            }

            val rawResponse = try {
                dispatchToProvider(context, provider, history, effectiveSystemPrompt)
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }

            // Exécution automatique des commandes système si présentes dans la réponse
            val commandResult = JarvisCommandParser.parseAndExecute(context, rawResponse)
            val cleanText = JarvisCommandParser.cleanResponse(rawResponse)

            when (commandResult) {
                is JarvisCommandParser.CommandResult.Executed -> {
                    val marker = listOf(JarvisCommandParser.CONTACT_FORMAT_MARKER, JarvisCommandParser.LOCATION_FORMAT_MARKER)
                        .firstOrNull { commandResult.outputMessage.contains(it) }
                    val text = if (marker != null) {
                        val formatted = applyMarkerFormatting(context, provider, commandResult.outputMessage, marker)
                        if (cleanText.isBlank()) formatted else "$cleanText\n\n$formatted"
                    } else if (commandResult.isInformational) {
                        summarizeNaturally(context, provider, lastUserMsg, commandResult.outputMessage)
                    } else if (cleanText.isBlank()) {
                        commandResult.outputMessage
                    } else {
                        "$cleanText\n\n${commandResult.outputMessage}"
                    }
                    ChatResult(text, commandResult.imageBase64, commandResult.imageMime)
                }
                is JarvisCommandParser.CommandResult.ExecutedMultiple -> {
                    // Cas d'un projet à plusieurs fichiers / plusieurs actions d'un coup.
                    val combined = commandResult.results.joinToString("\n\n") { it.outputMessage }
                    val anyInformational = commandResult.results.any { it.isInformational }
                    val combinedMarker = listOf(JarvisCommandParser.CONTACT_FORMAT_MARKER, JarvisCommandParser.LOCATION_FORMAT_MARKER)
                        .firstOrNull { combined.contains(it) }
                    val text = if (combinedMarker != null) {
                        val formatted = applyMarkerFormatting(context, provider, combined, combinedMarker)
                        if (cleanText.isBlank()) formatted else "$cleanText\n\n$formatted"
                    } else if (anyInformational) {
                        summarizeNaturally(context, provider, lastUserMsg, combined)
                    } else if (cleanText.isBlank()) {
                        combined
                    } else {
                        "$cleanText\n\n$combined"
                    }
                    val imageResult = commandResult.results.firstOrNull { it.imageBase64 != null }
                    ChatResult(text, imageResult?.imageBase64, imageResult?.imageMime)
                }
                JarvisCommandParser.CommandResult.None -> ChatResult(rawResponse)
            }
        }

    /** Demande à l'IA de reformuler naturellement un résultat brut de commande. */
    private suspend fun summarizeNaturally(
        context: Context,
        provider: Provider,
        userQuestion: String,
        rawOutput: String
    ): String {
        // Filet de sécurité : une erreur ou une absence de résultat ne passe JAMAIS par
        // la reformulation IA — on l'affiche telle quelle. Ça évite qu'un modèle
        // "arrondisse les angles" et transforme un échec/une absence de données en
        // réponse positive inventée (exactement le genre de dérive que l'utilisateur
        // a signalé : des infos qui ne correspondent à rien de réel).
        val trimmed = rawOutput.trim()
        if (trimmed.startsWith("❌") || trimmed.startsWith("🔍 Aucun") || trimmed.contains("aucun événement trouvé") ||
            trimmed.contains("Aucune génération") || trimmed.contains("aucun résultat")
        ) {
            return rawOutput
        }

        val summaryPrompt =
            "L'utilisateur a demandé : \"$userQuestion\"\n\n" +
                "Voici le résultat EXACT et RÉEL obtenu depuis son téléphone/son réseau/ses comptes (ne le montre " +
                "jamais tel quel avec son formatage brut) :\n" +
                "$rawOutput\n\n" +
                "Reformule ce résultat en réponse naturelle et orale, MAIS règle absolue : ne change, n'ajoute, " +
                "ne devine et n'omets AUCUN fait — chaque nom propre, date, heure, numéro, adresse, montant ou " +
                "identifiant doit être repris strictement à l'identique du texte ci-dessus, jamais inventé ni " +
                "approximé. Tu ne fais que changer la forme (phrases fluides au lieu d'une liste), jamais le fond. " +
                "Si le texte ci-dessus ne contient pas l'information précise demandée, dis-le clairement " +
                "(« je ne trouve pas ça », « cette info n'y est pas ») au lieu de compléter avec une supposition. " +
                "Sois concis : si l'utilisateur a demandé UNE seule chose (« le dernier SMS », « le dernier email »...), " +
                "ne donne que celle-là avec l'expéditeur et le contenu exacts, sans lister le reste. Ne mentionne " +
                "jamais de commande système, d'action JSON ni de terme technique. N'utilise aucune mise " +
                "en forme markdown (pas d'astérisques, pas de tirets de liste, pas de dièses) : écris en " +
                "prose naturelle comme à l'oral."
        return try {
            // systemPrompt minimal (voir SUMMARY_SYSTEM_PROMPT) : cette reformulation n'a besoin
            // d'aucune action JARVIS_CMD, donc pas besoin du catalogue complet SYSTEM_PROMPT —
            // économise environ 2900 tokens à chaque question informationnelle.
            val summary = dispatchToProvider(
                context, provider, listOf(HistoryEntry("user", summaryPrompt)),
                systemPrompt = SUMMARY_SYSTEM_PROMPT
            )
            val cleaned = JarvisCommandParser.cleanResponse(summary).trim()
            if (cleaned.isBlank()) rawOutput else cleaned
        } catch (e: Exception) {
            rawOutput // repli sur le résultat brut si la reformulation échoue
        }
    }

    /**
     * Reformate un résultat structuré (fiche contact OU localisation d'une personne) selon la
     * consigne détectée via un marqueur (JarvisCommandParser.CONTACT_FORMAT_MARKER ou
     * LOCATION_FORMAT_MARKER, consigne persistée via set_contact_presentation_style /
     * set_location_presentation_style et/ou demande ponctuelle format_hint) — c'est le SEUL
     * point d'entrée qui peut réellement changer la présentation par défaut, puisque
     * search_contact_profile/list_contacts_by_category/ha_status(person) ne passent pas par
     * summarizeNaturally (qui produit une réponse orale sans mise en forme, incompatible avec
     * une fiche visuelle en sections/emojis). Contrairement à summarizeNaturally, ce prompt
     * autorise explicitement les retours à la ligne, sections et emojis. Factorisé en un seul
     * endroit (au lieu d'une copie par type de donnée) pour ne pas dupliquer ce filet de
     * sécurité anti-hallucination et la consigne "aucun champ vide" à deux endroits.
     */
    private suspend fun applyMarkerFormatting(context: Context, provider: Provider, rawWithInstruction: String, marker: String): String {
        val idx = rawWithInstruction.indexOf(marker)
        if (idx < 0) return rawWithInstruction
        val data = rawWithInstruction.substring(0, idx).trim()
        val instruction = rawWithInstruction.substring(idx + marker.length).trim()

        // Même filet de sécurité que summarizeNaturally : une erreur/absence de résultat
        // ne passe jamais par une reformulation IA qui pourrait inventer un succès.
        if (data.startsWith("❌") || data.contains("Aucun contact trouvé") || data.contains("aucun résultat") ||
            data.contains("Aucun appareil Home Assistant")
        ) {
            return data
        }

        val prompt =
            "Voici un résultat (fiche contact, liste de fiches, ou localisation), avec des données EXACTES " +
                "et RÉELLES extraites de l'application :\n\n$data\n\n" +
                "Consigne(s) de présentation à appliquer : $instruction\n\n" +
                "Réécris ce résultat en respectant ces consignes. Règle absolue : ne change, n'ajoute, " +
                "ne devine et n'omets AUCUNE donnée (noms, numéros, adresses, dates, notes, historique, " +
                "coordonnées restent strictement identiques à ceux fournis ci-dessus) — seule la FORME peut " +
                "changer (ordre, libellés, structure en sections, tableau texte...), jamais le fond. " +
                "Règle absolue supplémentaire (TOUJOURS active, même si la consigne ci-dessus n'en parle pas) : " +
                "si un champ n'a PAS de valeur dans les données ci-dessus (absent, vide, null), NE L'AFFICHE " +
                "PAS DU TOUT — ni le libellé, ni un texte de substitution type \"non renseigné\"/\"inconnu\" : " +
                "un champ vide doit être totalement invisible dans le résultat final, pas juste vidé de son " +
                "contenu. Règle absolue supplémentaire (TOUJOURS active par défaut) : CONSERVE les emojis déjà " +
                "présents dans les données ci-dessus (📞🏠📧🎂🏢 etc., un par type d'info) — ne les retire QUE " +
                "si la consigne de l'utilisateur demande explicitement un format sans emoji (ex: \"sans " +
                "emojis\", \"texte simple\", \"format sobre\"). Dans tous les autres cas, y compris un format " +
                "en tableau, GARDE un emoji par ligne/catégorie pour l'identifier visuellement. Tu PEUX utiliser " +
                "des retours à la ligne et une structure visuelle : ceci est affiché dans un chat, pas une " +
                "phrase à prononcer à l'oral. Ne mentionne jamais de commande système, de terme technique ni " +
                "la consigne elle-même — donne directement le résultat final tel qu'il doit apparaître à l'écran."
        return try {
            val response = dispatchToProvider(
                context, provider, listOf(HistoryEntry("user", prompt)),
                systemPrompt = "Tu mets en forme un résultat (fiche contact ou localisation) selon une consigne de présentation donnée, sans jamais altérer ni omettre les données fournies, et sans jamais afficher un champ vide."
            )
            val cleaned = JarvisCommandParser.cleanResponse(response).trim()
            if (cleaned.isBlank()) data else cleaned
        } catch (e: Exception) {
            data // repli sur les données brutes (sans la consigne) si la reformulation échoue
        }
    }

    private suspend fun dispatchToProvider(context: Context, provider: Provider, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val grounded = withCurrentDateTime(systemPrompt)
        return when {
            provider.isAuto -> sendAuto(context, history, grounded)
            provider.isLocal -> sendLocal(context, history, grounded)
            provider == Provider.CLAUDE -> sendClaudeWithRotation(context, history, grounded)
            provider == Provider.GEMINI -> sendGeminiWithRotation(context, history, grounded)
            provider == Provider.SERPAPI -> sendSerpApiWithRotation(context, history)
            else -> sendOpenAiWithRotation(context, history, provider, grounded)
        }
    }

    /**
     * Préfixe [prompt] avec la date et l'heure RÉELLES actuelles de l'appareil — demandé
     * explicitement par l'utilisateur pour que JARVIS "sache exactement la date et l'heure
     * actuelle" et comprenne correctement "demain", "hier", "ce week-end", etc. dans ses
     * réponses. Calculé à CHAQUE requête (impossible à figer dans SYSTEM_PROMPT, qui est un
     * `const val` — une constante Kotlin ne peut PAS contenir de valeur dynamique), donc
     * toujours à jour, y compris après minuit sans redémarrer l'app. Point d'entrée UNIQUE
     * (dispatchToProvider) : s'applique à TOUTES les requêtes, y compris la reformulation
     * légère et le formatage de fiches contact, pas seulement le chat principal.
     *
     * Ceci ne remplace PAS le calcul de date déjà fait côté Kotlin pour create_event/
     * getEventsForWeek (voir CalendarController.resolveDate) — ce calcul reste la source de
     * vérité pour la logique métier, cette grounding sert uniquement à ce que le TEXTE généré
     * par le modèle (ex: une confirmation orale) référence la bonne date.
     */
    private fun withCurrentDateTime(prompt: String): String {
        val sdf = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.FRENCH)
        val now = sdf.format(java.util.Date())
        return "Date et heure actuelles (réelles, celles de l'appareil de l'utilisateur EN CE MOMENT) : $now. " +
            "Base-toi TOUJOURS sur cette date/heure exacte pour comprendre \"aujourd'hui\", \"demain\", \"hier\", " +
            "\"ce week-end\", \"dans 3 jours\", etc. — ne devine ni n'invente jamais une autre date.\n\n$prompt"
    }

    // ─── Mode Automatique avec multi-clés + sélection intelligente ────────────

    private fun sendAuto(context: Context, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        // Un fournisseur est candidat s'il a au moins une clé configurée, OU s'il ne nécessite
        // aucune clé (ex: POLLINATIONS, filet de secours gratuit/anonyme placé en dernier dans
        // AUTO_FALLBACK_ORDER) — avant ce correctif, needsApiKey=false n'était vérifié nulle part
        // ici, donc un fournisseur sans clé n'apparaissait JAMAIS dans les candidats malgré sa
        // présence dans l'ordre de repli, et le mode Automatique échouait immédiatement si
        // l'utilisateur n'avait configuré aucune clé — alors qu'un vrai filet de secours gratuit
        // existe désormais et devrait toujours être tenté en dernier recours.
        val candidates = Provider.AUTO_FALLBACK_ORDER.filter {
            !it.needsApiKey || Prefs.getApiKeysFor(context, it).isNotEmpty()
        }

        if (candidates.isEmpty()) {
            return "Aucune IA configurée pour le mode Automatique. " +
                "Ouvre ⚙ Paramètres → onglet « Clés API » et ajoute au moins une clé."
        }

        // Ordonne les candidats selon la nature de la demande de l'utilisateur,
        // avant de retomber sur l'ordre de repli standard si rien ne correspond.
        val lastUserEntry = history.lastOrNull { it.role == "user" }
        val orderedCandidates = rankProvidersForRequest(candidates, lastUserEntry)

        var lastError = ""
        for (provider in orderedCandidates) {
            val result = try {
                when (provider) {
                    Provider.CLAUDE -> sendClaudeWithRotation(context, history, systemPrompt)
                    Provider.GEMINI -> sendGeminiWithRotation(context, history, systemPrompt)
                    else -> sendOpenAiWithRotation(context, history, provider, systemPrompt)
                }
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }

            // BUG RÉEL CORRIGÉ : sendClaudeWithRotation/sendGeminiWithRotation renvoient leurs
            // propres messages d'échec final ("Toutes les clés API X ont échoué...", "Aucune
            // clé API X configurée.") qui NE COMMENÇAIENT PAR AUCUN des préfixes reconnus
            // ci-dessous — le mode Automatique les traitait donc comme une VRAIE réponse de
            // l'IA et s'arrêtait là, sans jamais essayer les fournisseurs suivants (OpenAI,
            // Mistral... jusqu'à Pollinations en dernier recours). Cas réel observé : toutes
            // les clés Gemini en quota dépassé (429) → l'utilisateur voyait littéralement
            // "Toutes les clés API Gemini ont échoué" affiché comme réponse de JARVIS, alors
            // que d'autres fournisseurs configurés (voire le filet de secours gratuit) auraient
            // pu répondre à sa place.
            if (!result.startsWith("Erreur") &&
                !result.startsWith("Connexion impossible") &&
                !result.startsWith("Format de réponse inattendu") &&
                !result.startsWith("Clé API") &&
                !result.startsWith("Toutes les clés") &&
                !result.startsWith("Aucune clé API")
            ) {
                return result
            }
            lastError = "[${provider.displayName}] $result"
        }

        return "Toutes les IA configurées ont échoué. Dernière erreur : $lastError"
    }

    /**
     * Classement heuristique (mots-clés) des fournisseurs disponibles selon
     * la nature de la demande. Ce n'est pas une IA de routage à proprement
     * parler — juste des règles simples pour prioriser un fournisseur mieux
     * adapté avant de retomber sur l'ordre de repli standard.
     */
    private fun rankProvidersForRequest(candidates: List<Provider>, lastUserEntry: HistoryEntry?): List<Provider> {
        if (lastUserEntry == null) return candidates

        // Une photo jointe (directe, ou n'importe laquelle des pièces jointes — ex: une image
        // qui n'est pas la première du lot, ou une page de PDF rendue en image) exige un
        // fournisseur capable de vision.
        val hasAnyImage = lastUserEntry.imageBase64 != null || lastUserEntry.attachments.any { it.imageBase64 != null }
        if (hasAnyImage) {
            val visionCapable = listOf(Provider.CLAUDE, Provider.OPENAI, Provider.GEMINI)
            val preferred = candidates.filter { it in visionCapable }
            if (preferred.isNotEmpty()) {
                return preferred + candidates.filterNot { it in preferred }
            }
        }

        val text = lastUserEntry.text.lowercase()

        val codeKeywords = listOf(
            "code", "fonction", "bug", "python", "kotlin", "java", "script",
            "programme", "compile", "erreur de", "debug", "sql", "regex", "api"
        )
        val creativeKeywords = listOf(
            "histoire", "poème", "poeme", "écris", "ecris", "raconte", "imagine", "rédige", "redige"
        )
        val quickKeywords = listOf(
            "rapide", "vite", "en bref", "résume", "resume", "en une phrase"
        )

        val preferredOrder: List<Provider> = when {
            codeKeywords.any { text.contains(it) } -> listOf(Provider.CLAUDE, Provider.OPENAI, Provider.DEEPSEEK)
            creativeKeywords.any { text.contains(it) } -> listOf(Provider.CLAUDE, Provider.OPENAI)
            quickKeywords.any { text.contains(it) } -> listOf(Provider.GROQ, Provider.GEMINI)
            else -> emptyList()
        }

        if (preferredOrder.isEmpty()) return candidates

        val preferred = preferredOrder.filter { it in candidates }
        return preferred + candidates.filterNot { it in preferred }
    }

    // ─── Modèle local sur l'appareil ──────────────────────────────────────────

    private suspend fun sendLocal(context: Context, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val modelPath = Prefs.getLocalModelPath(context)
        if (modelPath.isBlank()) {
            return "Aucun modèle local configuré. Ouvre ⚙ Paramètres → onglet « Local » et télécharge un modèle."
        }
        val prompt = buildPromptFromHistory(history, systemPrompt)
        return LocalLlmManager.generate(context, modelPath, prompt)
    }

    private fun buildPromptFromHistory(history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val recent = history.takeLast(8)
        val sb = StringBuilder(systemPrompt).append("\n\n")
        for (entry in recent) {
            val label = if (entry.role == "user") "Utilisateur" else "JARVIS"
            // Le modèle local n'a pas de vision : une image jointe est juste signalée en texte,
            // mais le texte extrait d'un document (DOCX/TXT/ZIP...) fonctionne, lui, sans vision.
            val hasImage = entry.imageBase64 != null || entry.attachments.any { it.imageBase64 != null }
            val suffix = if (hasImage) " [photo jointe — non visible par ce modèle local, pas de vision]" else ""
            sb.append(label).append(": ").append(textWithAttachments(entry)).append(suffix).append("\n")
        }
        sb.append("JARVIS: ")
        return sb.toString()
    }

    // ─── OpenAI-compatible avec rotation de clés ──────────────────────────────

    private fun sendOpenAiWithRotation(
        context: Context,
        history: List<HistoryEntry>,
        provider: Provider,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        val keys = Prefs.getApiKeysFor(context, provider)
        val baseUrl = if (!provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM) provider.defaultBaseUrl else Prefs.getBaseUrl(context)
        val model = if (!provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM) provider.defaultModel else Prefs.getModel(context)

        if (keys.isEmpty() && provider.needsApiKey) {
            return "Aucune clé API configurée pour ${provider.displayName}. Ajoute-en dans ⚙ Paramètres → Clés API."
        }

        val maxAttempts = maxOf(1, keys.size)
        var lastErr = ""

        for (attempt in 0 until maxAttempts) {
            val apiKey = if (keys.isNotEmpty()) Prefs.getNextApiKey(context, provider) else ""
            val result = sendOpenAiCompatible(baseUrl, model, apiKey, history, provider, systemPrompt)

            if (!result.startsWith("Erreur API (429)") && !result.startsWith("Erreur API (401)")) {
                return result
            }

            if (apiKey.isNotBlank()) Prefs.markKeyFailed(context, provider, apiKey)
            lastErr = result
        }

        return lastErr
    }

    // ─── Pièces jointes multiples : helpers partagés par tous les fournisseurs ─────────────
    // entry.attachments (voir Attachment.kt) est la source de vérité pour les messages RÉCENTS
    // (plusieurs fichiers, pages de PDF...) ; entry.imageBase64/imageMime restent le repli pour
    // les anciens appels qui ne remplissent que ces champs (ex: mode vocal, réponse assistant).

    /** Toutes les images exploitables en "vision" d'une entrée — base64 + mime. */
    private fun collectImageParts(entry: HistoryEntry): List<Pair<String, String>> {
        if (entry.attachments.isNotEmpty()) {
            return entry.attachments.mapNotNull { a -> a.imageBase64?.let { it to (a.imageMime ?: "image/jpeg") } }
        }
        return entry.imageBase64?.let { listOf(it to (entry.imageMime ?: "image/jpeg")) } ?: emptyList()
    }

    /** Texte du message + tout texte extrait des pièces jointes (DOCX/TXT/ZIP...), pour un envoi universel (fonctionne sans vision). */
    private fun textWithAttachments(entry: HistoryEntry): String {
        val texts = entry.attachments.mapNotNull { it.extractedText }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return entry.text
        return entry.text + "\n\n" + texts.joinToString("\n\n") { "[Contenu d'un fichier joint]\n$it" }
    }

    private fun sendOpenAiCompatible(
        baseUrl: String,
        model: String,
        apiKey: String,
        history: List<HistoryEntry>,
        provider: Provider,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (entry in history) {
            val images = collectImageParts(entry)
            val textContent = textWithAttachments(entry)
            if (images.isNotEmpty()) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().put("type", "text").put("text", textContent))
                images.forEach { (b64, mime) ->
                    contentArray.put(
                        JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$b64"))
                    )
                }
                messagesArray.put(JSONObject().put("role", entry.role).put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", entry.role).put("content", textContent))
            }
        }

        val bodyObj = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0.7)

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(bodyObj.toString().toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (provider == Provider.OPENROUTER) {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/davidc2115/APK-DEV")
                .addHeader("X-Title", "JARVIS Android")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                return message?.optString("content") ?: "Réponse vide reçue du serveur."
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── Claude (Anthropic) avec rotation ──────────────────────────────────────

    private fun sendClaudeWithRotation(context: Context, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val keys = Prefs.getApiKeysFor(context, Provider.CLAUDE)
        if (keys.isEmpty()) return "Aucune clé API Claude configurée."

        for (apiKey in keys) {
            val res = sendClaude(Provider.CLAUDE.defaultBaseUrl, Provider.CLAUDE.defaultModel, apiKey, history, systemPrompt)
            if (!res.startsWith("Erreur API Claude (429)") && !res.startsWith("Erreur API Claude (401)")) return res
            Prefs.markKeyFailed(context, Provider.CLAUDE, apiKey)
        }
        return "Toutes les clés API Claude ont échoué."
    }

    private fun sendClaude(baseUrl: String, model: String, apiKey: String, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val messagesArray = JSONArray()
        // BUG RÉEL CORRIGÉ : cette fonction ignorait complètement les images jointes (seul le
        // texte était envoyé), alors que rankProvidersForRequest priorise pourtant Claude comme
        // fournisseur "vision" dès qu'une image est attachée — l'IA répondait donc à côté sans
        // jamais voir l'image. Format Claude : blocs "image" (base64) + "text" dans le content.
        for (entry in history) {
            val images = collectImageParts(entry)
            val textContent = textWithAttachments(entry)
            if (images.isNotEmpty()) {
                val contentArray = JSONArray()
                images.forEach { (b64, mime) ->
                    contentArray.put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64").put("media_type", mime).put("data", b64)
                        )
                    )
                }
                contentArray.put(JSONObject().put("type", "text").put("text", textContent))
                messagesArray.put(JSONObject().put("role", entry.role).put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", entry.role).put("content", textContent))
            }
        }

        val body = JSONObject()
            .put("model", model)
            // 1024 était TROP BAS : une commande [JARVIS_CMD] avec plusieurs chemins de fichiers
            // (ex: create_pdf{images:[...]} avec 5 images) + du texte d'accompagnement dépasse
            // facilement ce budget, tronquant le JSON en plein milieu — cause réelle et vérifiée
            // d'erreurs "Unterminated array"/JSON invalide qui cassaient l'action ENTIÈRE.
            .put("max_tokens", 4096)
            .put("system", systemPrompt)
            .put("messages", messagesArray)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(baseUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API Claude (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val content = json.optJSONArray("content")
            if (content != null && content.length() > 0) {
                return content.getJSONObject(0).optString("text", "Réponse vide.")
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── Google Gemini avec rotation ──────────────────────────────────────────

    private fun sendGeminiWithRotation(context: Context, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val keys = Prefs.getApiKeysFor(context, Provider.GEMINI)
        if (keys.isEmpty()) return "Aucune clé API Gemini configurée."

        // Garde le détail RÉEL de la dernière erreur (ex: "429" quota dépassé) au lieu d'un
        // message générique "toutes les clés ont échoué" qui masquait la vraie cause — corrigé
        // suite à un cas réel où TOUTES les clés Gemini d'un utilisateur renvoyaient 429 à cause
        // d'un modèle Preview au quota gratuit trop restrictif, sans qu'aucun message ne le
        // dise explicitement.
        var lastDetail = ""
        for (apiKey in keys) {
            val res = sendGemini(Provider.GEMINI.defaultBaseUrl, apiKey, history, systemPrompt)
            if (!res.startsWith("Erreur API Gemini (429)") && !res.startsWith("Erreur API Gemini (401)")) return res
            lastDetail = res
            Prefs.markKeyFailed(context, Provider.GEMINI, apiKey)
        }
        return "Toutes les clés API Gemini ont échoué (${keys.size} clé(s) testée(s)) — dernière erreur : $lastDetail"
    }

    private fun sendGemini(baseUrl: String, apiKey: String, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}key=$apiKey"

        val contentsArray = JSONArray()
        // BUG RÉEL CORRIGÉ : comme pour Claude, cette fonction ignorait les images jointes
        // malgré Gemini priorisé comme fournisseur "vision" — voir sendClaude ci-dessus pour
        // le détail. Format Gemini : inlineData (base64) en plus de la part texte.
        for (entry in history) {
            val geminiRole = if (entry.role == "assistant") "model" else "user"
            val images = collectImageParts(entry)
            val partsArray = JSONArray().put(JSONObject().put("text", textWithAttachments(entry)))
            images.forEach { (b64, mime) ->
                partsArray.put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", b64)))
            }
            contentsArray.put(JSONObject().put("role", geminiRole).put("parts", partsArray))
        }

        val body = JSONObject()
            .put("contents", contentsArray)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder().url(url).post(body).addHeader("Content-Type", "application/json").build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API Gemini (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "Réponse vide.")
                }
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── SerpAPI avec rotation ────────────────────────────────────────────────

    private fun sendSerpApiWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.SERPAPI)
        if (keys.isEmpty()) return "Aucune clé API SerpAPI configurée."

        val query = history.lastOrNull { it.role == "user" }?.text ?: return "Aucune question à rechercher."

        for (apiKey in keys) {
            val url = "https://serpapi.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&api_key=$apiKey&engine=google&hl=fr&gl=fr&num=5"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    val organic = json.optJSONArray("organic_results")
                    if (organic != null && organic.length() > 0) {
                        val sb = StringBuilder("🔍 Résultats web pour « $query » :\n\n")
                        for (i in 0 until minOf(3, organic.length())) {
                            val item = organic.getJSONObject(i)
                            sb.append("${i + 1}. **${item.optString("title")}**\n${item.optString("snippet")}\n🔗 ${item.optString("link")}\n\n")
                        }
                        return sb.toString().trimEnd()
                    }
                } else if (response.code == 429 || response.code == 401) {
                    Prefs.markKeyFailed(context, Provider.SERPAPI, apiKey)
                }
            }
        }
        return "Toutes les clés SerpAPI ont échoué."
    }
}
