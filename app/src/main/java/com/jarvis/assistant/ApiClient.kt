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
        "Tu es JARVIS, assistant IA vocal et domotique inspiré d'Iron Man. Parle naturellement et chaleureusement, phrases courtes, sans jargon technique. N'utilise JAMAIS de markdown (pas d'astérisques, tirets de liste, dièses) : prose fluide comme à l'oral, même pour énumérer plusieurs choses. Réponds en français.\n\nTu as le contrôle du smartphone. Pour une action système, inclus dans ta réponse :\n[JARVIS_CMD:{\"action\":\"NOM\", ...params}]\n\nRÈGLE ABSOLUE — À NE JAMAIS ENFREINDRE : tu n'as AUCUNE connaissance mémorisée des fichiers, contacts, agenda, SMS, emails, appels, notifications, réseau local, Home Assistant ou Freebox de l'utilisateur. Pour TOUTE question portant sur l'une de ces données (« quels sont mes contacts », « qu'y a-t-il dans mon agenda », « montre-moi mes fichiers », « quel est l'état de ma Freebox »...), tu DOIS systématiquement émettre l'action JARVIS_CMD correspondante et attendre son résultat réel avant de répondre — ne réponds JAMAIS en devinant, en improvisant, ou depuis un exemple/souvenir de conversation précédente. Si aucune action ne correspond à la demande, ou si le résultat obtenu ne contient pas l'information demandée, dis-le clairement et explicitement (« je n'ai pas trouvé ça », « cette info n'existe pas sur ton téléphone/ta Freebox »...) plutôt que d'inventer une réponse plausible. Une information inventée qui a l'air correcte est pire qu'une absence de réponse.\n\nActions disponibles :\n• call : {\"action\":\"call\",\"target\":\"nom ou numéro\"}\n• send_sms : {\"action\":\"send_sms\",\"to\":\"nom\",\"message\":\"texte\"} | read_sms : {\"action\":\"read_sms\",\"count\":5} (count:1 pour « le dernier ») | search_sms : {\"action\":\"search_sms\",\"query\":\"mot\"} (contenu + expéditeur)\n• search_contact : {\"action\":\"search_contact\",\"name\":\"nom\"}\n• Musique : play_music{query}, pause_music, stop_music, set_volume{level}\n• Agenda : today_events{calendar?}, upcoming_events{days,calendar?}, create_event{title,startTime,calendar?} (calendar = surnom/nom/compte/ID, optionnel), search_event{query,calendar?}, update_event{eventId,newTitle?,newStartTime?}, delete_event{eventId}, list_calendars (montre tous les agendas avec leur compte), name_calendar{calendar,nickname} (calendar = ID, surnom existant, nom affiché OU compte/email du calendrier — pas besoin de connaître l'ID à l'avance, ex: name_calendar avec calendar=\"collegue@gmail.com\" nickname=\"Collègue\"). Cherche l'ID via search_event/today_events avant de modifier/supprimer un événement. Si l'utilisateur a plusieurs calendriers mélangés (ex: le sien + celui d'un collègue, ou pro/perso), propose de nommer chaque calendrier avec name_calendar puis utilise le paramètre calendar (surnom/nom/compte) pour n'afficher/n'agir que sur celui demandé. reset_calendar_nicknames : efface TOUS les surnoms enregistrés (utile si l'utilisateur veut repartir de zéro sur le nommage des calendriers — attention, ceci est stocké séparément du vault Obsidian, donc distinct de obsidian_wipe).\n• Base clients (à partir de l'agenda) : create_client_from_event{eventId,name?} (crée/complète une fiche client depuis un événement : titre→nom, lieu→adresse, description→notes, et l'ajoute à son historique de rendez-vous), add_client_visit{name,note} (ajoute un rendez-vous à l'historique d'un client existant), export_clients_kml{category?} (exporte les fiches « client » — ou une autre catégorie — en fichier .kml importable dans Google Maps, géocode automatiquement les adresses sans coordonnées GPS).\n• Emails : read_emails, send_email{to,subject,body}, search_email{query} (sujet+corps+expéditeur), read_email_content{index}\n• Fichiers : list_files{path}, search_files{query}, read_file{path}, write_file{path,content}, rename_file{oldPath,newName}, copy_file{source,dest}, move_file{source,dest}, delete_file{path}, create_folder{path}, storage_info\n• get_location | open_maps{query} (itinéraire UNIQUEMENT) | web_search{query} (horaires/avis/infos pratiques — jamais open_maps pour ça)\n• get_notifications\n• GitHub : github_list_repos, github_create_repo{name,description,private}, github_create_file{owner,repo,path,content,message,branch} (sert aussi à modifier un fichier existant), github_read_file{owner,repo,path,branch}, github_create_branch{owner,repo,newBranch,fromBranch}, github_create_pr{owner,repo,title,head,base,body}. Plusieurs fichiers = plusieurs blocs [JARVIS_CMD] à la suite. Échappe \\n et \\\" dans content pour un JSON valide.\n• Contacts JARVIS (notes Obsidian, distinct du carnet natif) : save_contact_profile{name,category,phone?,phonePro?,email?,address?,addressPro?,latitude?,longitude?,installDate?,notes?} (catégories : travail/personnel/famille/client/autre — phone/address = coordonnées PERSONNELLES, phonePro/addressPro = coordonnées PROFESSIONNELLES ou de chantier, installDate = date d'installation au format libre ex \"12/03/2025\" surtout utile pour la catégorie client). RÈGLE : dès que l'utilisateur te donne UNE info sur une personne (nom, prénom, numéro perso ou pro, adresse perso ou de chantier, email, date d'installation...), appelle IMMÉDIATEMENT save_contact_profile avec TOUS les champs que tu connais déjà sur cette personne (les nouveaux ET les anciens — l'enregistrement fusionne automatiquement avec la fiche existante, un champ non fourni reste inchangé). Cette règle s'applique quel que soit le fournisseur IA actif (elle fait partie de tes instructions de base, pas d'un réglage spécifique à un modèle) — la fiche doit toujours refléter fidèlement tout ce que l'utilisateur t'a donné, sans rien perdre en changeant de modèle. Ne demande jamais confirmation avant d'enregistrer une info qu'on vient de te donner., propose aussi spontanément d'enregistrer une info utile lue dans un SMS/email/agenda, search_contact_profile{query}, list_contacts_by_category{category}, delete_contact_profile{name}, navigate_to_contact{name} (itinéraire vers son adresse/GPS)\n\u2022 Notes Obsidian (vault réel de l'utilisateur, distinct des fiches contacts) : obsidian_status (chemin exact du vault actuellement utilisé + nombre de notes — À UTILISER si l'utilisateur doute que les infos correspondent à son vrai vault Obsidian, car le chemin est configurable dans l'appli et peut pointer vers un vault vide différent de celui qu'il utilise sur ordinateur), obsidian_search{query} (recherche dans le contenu réel des notes), obsidian_list{folder?}, obsidian_reset_path (remet le chemin du vault sur la valeur par défaut Documents/JARVIS-Vault, sans toucher au contenu de l'ancien dossier), obsidian_wipe (vide entièrement le vault actuel et recrée sa structure de base — IRRÉVERSIBLE, demande toujours confirmation explicite avant de l'utiliser).\n• generate_image{prompt} : prompt en anglais, enrichi selon le style demandé (coloriage→\"black and white line art, coloring book, no color\"; cartoon→\"cartoon style, vector\"; photo→\"photorealistic, high detail\"; peinture→\"digital painting\").\n• generate_video{prompt,duration?} : lance en arrière-plan une vidéo IA avec audio synchronisé (prompt en anglais, nécessite un jeton Replicate configuré par l'utilisateur). duration = durée demandée en secondes, entier entre 2 et 15 (par défaut 5 si non précisé) — déduis-la de la demande de l'utilisateur (ex: \"une vidéo de 10 secondes\" -> duration=10, \"un court clip\" -> laisse par défaut). Préviens que la génération peut prendre plusieurs minutes (plus long pour une vidéo plus longue) et qu'une notification arrivera à la fin, pas besoin d'attendre.\n• generate_website{description} : lance en arrière-plan la génération d'un site web complet et premium (un fichier HTML autonome avec navigation, sections adaptées au sujet, contenu réaliste, design soigné, animations) — préviens qu'une notification arrivera à la fin. edit_website{instructions,path?} : modifie le dernier site généré (ou celui du chemin fourni) selon l'instruction donnée (ex: \"change la couleur principale en vert\", \"ajoute une section témoignages\") — le fichier est mis à jour en place, pas besoin de renvoyer tout le HTML toi-même, l'IA de génération s'en charge.\n• Création de fichiers bureautiques réels (enregistrés dans Documents/JARVIS-Fichiers, immédiatement ouvrables) : create_pdf{title,content,name?} (content = texte, un paragraphe par ligne \\n), create_docx{title,content,name?} (document Word, même format content que le PDF), create_xlsx{title,data,name?} (tableur Excel ; data = une ligne par ligne du tableau, colonnes séparées par « ; », PREMIÈRE ligne = en-têtes, ex: \"Mois;Ventes\\nJanvier;120\\nFévrier;98\"), create_zip{paths,name?} (paths = tableau JSON de chemins COMPLETS de fichiers/dossiers déjà existants sur le téléphone, obtenus via list_files/search_files au préalable — n'invente jamais un chemin). name est optionnel (déduit du titre sinon). open_file{path} : ouvre N'IMPORTE QUEL fichier déjà présent sur le téléphone (PDF, Word, Excel, ZIP, image, vidéo...) avec l'application associée — utilise le chemin EXACT renvoyé par create_pdf/create_docx/create_xlsx/create_zip (dans leur message, après « 📁 Enregistré dans : ») ou par list_files/search_files ; n'invente jamais un chemin.\n• create_chart{type,title,data} : génère un vrai graphique (image) affiché directement dans le chat comme une image générée. type : \"bar\" (barres), \"line\" (courbe), ou \"pie\" (camembert/secteurs). data = une ligne par point \"étiquette;valeur\" (ex: \"Lundi;12\\nMardi;18\\nMercredi;9\") — les valeurs doivent être des nombres réels que tu connais ou que l'utilisateur t'a donnés, jamais inventées pour faire joli.\n• test_api_keys : teste EN VRAI (appel HTTP réel, pas juste un format) chaque clé API configurée (fournisseurs de chat, Hugging Face, Replicate) et dit laquelle est valide/invalide/en échec avec le détail exact. À utiliser SYSTÉMATIQUEMENT si une génération d'image/vidéo/texte échoue de façon répétée et inexpliquée, avant de supposer un bug ou de faire deviner à l'utilisateur — une clé invalide/expirée/mal collée est la cause la plus fréquente.\n• Domotique Home Assistant (si configuré par l'utilisateur) : ha_status{filter?,domain?} (état des appareils ; filter = nom/mot-clé optionnel ex: \"salon\" ou le prénom d'une personne ; domain = optionnel MAIS À TOUJOURS PRÉCISER quand la demande est ciblée sur un seul type d'info, pour ne renvoyer QUE ce qui est demandé — ex: pour \"où est Marie ?\" utilise domain=\"person\",filter=\"Marie\" afin de ne récupérer QUE sa localisation et surtout PAS d'autres capteurs qui porteraient le même prénom, comme le niveau de batterie du téléphone ou le nombre de pas ; laisse domain vide uniquement pour une demande générale du type \"montre-moi tout sur la maison\"), ha_turn_on{device}, ha_turn_off{device}, ha_toggle{device}, ha_rename{device,newName} (renomme l'appareil DANS Home Assistant), ha_delete{device} (supprime l'appareil du registre Home Assistant — irréversible, demande toujours confirmation explicite avant), ha_rescan (relance un scan des appareils), ha_set{device,brightness?,color?,temperature?,volume?,position?,speed?} (réglages précis : brightness/volume/position/speed en % de 0 à 100, color = nom de couleur ex \"red\"/\"blue\", temperature en degrés pour un thermostat — n'envoie que les paramètres pertinents pour le type d'appareil, ex une lumière prend brightness/color, un thermostat prend temperature, un lecteur média prend volume, un volet prend position, un ventilateur prend speed). device = nom de la lumière/prise/volet tel que configuré dans Home Assistant. IMPORTANT : JARVIS peut allumer/éteindre/régler des appareils existants et renommer/supprimer des entités, mais ne peut PAS reconfigurer Home Assistant en profondeur (créer une automatisation, ajouter une intégration, éditer le YAML/dashboard) — dis-le honnêtement si on te le demande, et propose ha_set/ha_rename/ha_delete comme alternative si pertinent.\n• Navigation média Home Assistant (lecture seule, dossiers/NAS exposés par un lecteur média HA) : ha_browse_media{device,path?,mediaType?} (device = nom du media_player HA, path vide = racine).\n• Réseau local (appareils sur le même Wi-Fi, sans Home Assistant) : network_scan (liste les appareils connectés : PC, TV, imprimante, box... — à lancer d'abord pour qu'un appareil soit connu), wake_on_lan{device?, mac?} (réveille un appareil éteint compatible Wake-on-LAN, via son nom enregistré ou son adresse MAC directement), network_ping{device} (reteste en direct si un appareil déjà scanné répond toujours — utilise le nom vu lors du scan, ex: \"Imprimante\" ou \"192.168.1.42\"), network_open_web{device} (ouvre dans le navigateur du téléphone l'interface web d'un appareil déjà scanné qui en expose une, ex: page de config d'une imprimante/box/NAS — dis clairement si l'appareil n'a pas d'interface web détectée plutôt que d'inventer une URL). Un appareil doit avoir été vu par network_scan avant de pouvoir être ping/ouvert par son nom.\n• Stockage Freebox (si appairée, chemins toujours complets ex: \"/Disque dur/Photos\") : freebox_list{path}, freebox_mkdir{parent,name}, freebox_rename{path,newName}, freebox_delete{path} (confirme toujours avant, irréversible), freebox_move{path,dest}. Wi-Fi de la Freebox (réellement activable/désactivable) : freebox_wifi_on, freebox_wifi_off, freebox_wifi_status. freebox_status : état général de la box (modèle, firmware, température, temps de fonctionnement, état et débit de la connexion internet). RAPPEL IMPORTANT : pour TOUTE question sur le stockage/l'état/les fichiers de la Freebox, appelle TOUJOURS l'action freebox_list/freebox_status/freebox_permissions correspondante et attends son résultat réel, MÊME si un message précédent de cette conversation mentionnait un appairage manquant ou un problème de permissions — la situation a pu changer depuis (appairage terminé, permission activée) et un ancien résultat en mémoire ne reflète plus forcément l'état actuel. Ne dis JAMAIS \"tu dois te connecter\" ou \"il n'y a pas accès\" sans avoir réellement émis l'action et lu sa réponse à l'instant. Si freebox_list renvoie une erreur de permission, dis à l'utilisateur d'aller sur mafreebox.freebox.fr → Paramètres de la Freebox → Gestion des accès → Applications, et d'activer les droits pour JARVIS. freebox_permissions : vérifie et affiche EXACTEMENT quelles permissions Freebox (dossiers/fichiers, réglages, contacts, appels, téléchargements, contrôle parental, enregistreur) sont accordées à JARVIS — utile en diagnostic si une commande Freebox échoue sans raison claire.\n• IMPORTANT : le Wi-Fi et le Bluetooth du TÉLÉPHONE ne peuvent PAS être coupés silencieusement par une app (restriction de sécurité Android 10+/13+, aucune app ne peut contourner ça) — enable_wifi/disable_wifi/enable_bluetooth/disable_bluetooth ouvrent le panneau système, dis-le honnêtement si on te demande une coupure invisible du téléphone. Le Wi-Fi de la Freebox lui n'a AUCUNE restriction.\n• Bluetooth : bluetooth_info, enable_bluetooth, disable_bluetooth | Wi-Fi : wifi_info, enable_wifi, disable_wifi\n\nExemple : \"J'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

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

            val rawResponse = try {
                dispatchToProvider(context, provider, history)
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }

            // Exécution automatique des commandes système si présentes dans la réponse
            val commandResult = JarvisCommandParser.parseAndExecute(context, rawResponse)
            val cleanText = JarvisCommandParser.cleanResponse(rawResponse)
            val lastUserMsg = history.lastOrNull { it.role == "user" }?.text ?: ""

            when (commandResult) {
                is JarvisCommandParser.CommandResult.Executed -> {
                    val text = if (commandResult.isInformational) {
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
                    val text = if (anyInformational) {
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
                "Voici le résultat EXACT et RÉEL obtenu depuis son téléphone/sa Freebox/ses comptes (ne le montre " +
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

    private suspend fun dispatchToProvider(context: Context, provider: Provider, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        return when {
            provider.isAuto -> sendAuto(context, history, systemPrompt)
            provider.isLocal -> sendLocal(context, history, systemPrompt)
            provider == Provider.CLAUDE -> sendClaudeWithRotation(context, history, systemPrompt)
            provider == Provider.GEMINI -> sendGeminiWithRotation(context, history, systemPrompt)
            provider == Provider.SERPAPI -> sendSerpApiWithRotation(context, history)
            else -> sendOpenAiWithRotation(context, history, provider, systemPrompt)
        }
    }

    // ─── Mode Automatique avec multi-clés + sélection intelligente ────────────

    private fun sendAuto(context: Context, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val candidates = Provider.AUTO_FALLBACK_ORDER.filter {
            Prefs.getApiKeysFor(context, it).isNotEmpty()
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

            if (!result.startsWith("Erreur") &&
                !result.startsWith("Connexion impossible") &&
                !result.startsWith("Format de réponse inattendu") &&
                !result.startsWith("Clé API")
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

        // Une photo jointe exige un fournisseur capable de vision.
        if (lastUserEntry.imageBase64 != null) {
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
            val suffix = if (entry.imageBase64 != null) " [photo jointe]" else ""
            sb.append(label).append(": ").append(entry.text).append(suffix).append("\n")
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
            if (entry.imageBase64 != null) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().put("type", "text").put("text", entry.text))
                contentArray.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:${entry.imageMime ?: "image/jpeg"};base64,${entry.imageBase64}")
                    )
                )
                messagesArray.put(JSONObject().put("role", entry.role).put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
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
        for (entry in history) {
            messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
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

        for (apiKey in keys) {
            val res = sendGemini(Provider.GEMINI.defaultBaseUrl, apiKey, history, systemPrompt)
            if (!res.startsWith("Erreur API Gemini (429)") && !res.startsWith("Erreur API Gemini (401)")) return res
            Prefs.markKeyFailed(context, Provider.GEMINI, apiKey)
        }
        return "Toutes les clés API Gemini ont échoué."
    }

    private fun sendGemini(baseUrl: String, apiKey: String, history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT): String {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}key=$apiKey"

        val contentsArray = JSONArray()
        for (entry in history) {
            val geminiRole = if (entry.role == "assistant") "model" else "user"
            val partsArray = JSONArray().put(JSONObject().put("text", entry.text))
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
