package com.jarvis.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        "Tu es JARVIS, assistant IA vocal et domotique inspiré d'Iron Man. Parle naturellement, phrases courtes, sans jargon. Jamais de markdown dans TA réponse parlée (astérisques, tirets de liste, dièses) : prose fluide même pour énumérer — cette règle ne concerne QUE ce que tu dis à l'oral, jamais les paramètres content/notes envoyés à une action (voir plus bas). Réponds en français.\n\nTu contrôles le smartphone. Pour une action système, inclus dans ta réponse :\n[JARVIS_CMD:{\"action\":\"NOM\", ...params}]\n\nRÈGLE ABSOLUE : tu n'as AUCUNE connaissance mémorisée des fichiers, contacts, agenda, SMS, emails, appels, notifications, réseau local, Home Assistant ou notes Obsidian de l'utilisateur. Pour toute question portant sur l'une de ces données, émets TOUJOURS l'action JARVIS_CMD correspondante et attends son résultat réel avant de répondre — ne devine jamais, n'improvise jamais depuis un souvenir de conversation précédente. Si rien ne correspond ou si le résultat ne contient pas l'info demandée, dis-le clairement plutôt que d'inventer : une info inventée plausible est pire qu'une absence de réponse.\n\nPRIORITÉ OBSIDIAN : avant de répondre à une question factuelle (définition, qui/quoi/combien/quand, code, préférence, info perso) depuis tes connaissances générales, lance D'ABORD obsidian_search{query} — si un résultat pertinent existe, base ta réponse dessus (une note fait toujours autorité en cas de conflit) ; sinon réponds normalement.\n\nPRÉFÉRENCES DURABLES : si l'utilisateur demande un réglage permanent (format d'affichage, couleur...) — ex: \"présente mes fiches comme ça à chaque fois\", \"change la couleur du chat\" — appelle TOUJOURS l'action de sauvegarde dédiée (set_contact_presentation_style, set_location_presentation_style, set_chat_theme, enable_contact_links...), pas seulement une reformulation ponctuelle, sinon la préférence est perdue au message suivant.\n\nMÉMOIRE DURABLE (voir remember_fact/forget_fact plus bas) : dès que l\'utilisateur partage un fait DURABLE sur lui/sa vie qui mérite d\'être connu dans TOUTES les conversations futures (métier, projet en cours, contrainte de santé, situation familiale, objectif à long terme...) et qui n\'est pas déjà couvert par une action plus spécifique (fiches contact, préférences d\'affichage ci-dessus), appelle remember_fact{fact} IMMÉDIATEMENT — c\'est ce qui évite de tout redemander à chaque nouvelle conversation. Si l\'utilisateur dit explicitement \"retiens que...\"/\"souviens-toi que...\", c\'est TOUJOURS remember_fact, jamais une simple confirmation orale sans action. Si un fait devient faux/obsolète (\"oublie que...\", \"c\'est plus le cas\"), appelle forget_fact{query}. Ne retiens jamais d\'info déjà mieux rangée ailleurs (contact -> save_contact_profile, préférence d\'affichage -> set_*_presentation_style).\n\nActions disponibles :\n• call{target}\n• send_sms{to,message} | read_sms{count} (1=dernier) | search_sms{query} (contenu+expéditeur)\n• search_contact{name} (carnet natif, inclut libellés 🏷️ créés dans l'appli Contacts), list_contact_labels, list_contacts_by_label{label} — distinct des catégories Contacts JARVIS (save_contact_profile, plus bas)\n• Musique : play_music{query}, pause_music, stop_music, set_volume{level}\n• Agenda : today_events{calendar?}, upcoming_events{days,calendar?}, week_events{offset,calendar?} (offset=0/-1/1... TOUJOURS utiliser pour \"semaine dernière/prochaine\", ne jamais calculer de dates toi-même), create_event{title,date?,time?,durationMinutes?,description?,location?,calendar?} (date en langage naturel: \"demain\",\"lundi\",\"JJ/MM\"... ; time: \"14h30\" ; jamais d'epoch, CalendarController calcule), search_event{query,calendar?}, update_event{eventId,newTitle?,newDate?,newTime?,newDurationMinutes?}, delete_event{eventId}, list_calendars, name_calendar{calendar,nickname} (calendar=ID/nom/compte), reset_calendar_nicknames, sync_calendar{calendar,enable} (active/désactive sync+visibilité d'un calendrier). Cherche l'ID via search_event/today_events avant modif/suppr. Si plusieurs calendriers mélangés, propose name_calendar puis filtre avec calendar.\n• Clients (depuis l'agenda) : create_client_from_event{eventId,name?}, add_client_visit{name,note}, export_clients_kml{category?}\n• Emails : read_emails, send_email{to,subject,body}, search_email{query}, read_email_content{index}\n• Fichiers : list_files{path}, search_files{query}, read_file{path}, write_file{path,content}, rename_file{oldPath,newName}, copy_file{source,dest}, move_file{source,dest}, delete_file{path}, create_folder{path}, storage_info\n• get_location | open_maps{query} (itinéraire uniquement) | web_search{query} (horaires/avis/infos pratiques, jamais open_maps pour ça)\n• Recherche/lecture web avancée (comptes distincts, clés jamais codées en dur) : perplexity_search{query} (réponse synthétisée + sources, via la clé Perplexity AI déjà configurable dans ⚙ → Clés API — préfère web_search pour une simple liste de liens, perplexity_search quand une réponse rédigée et sourcée est plus utile) ; firecrawl_scrape{url} (lit le contenu propre d'une page déjà connue, y compris JS — jamais pour \"chercher\", seulement pour lire une URL précise donnée par l'utilisateur ou trouvée via web_search/perplexity_search) ; run_glif{prompt,projectId?} (Glif, agent IA de composition média glif.app — décris en langage naturel l'image/vidéo/audio à générer, projectId optionnel pour continuer un projet déjà commencé plutôt que d'en recréer un ; la génération peut prendre plusieurs minutes, prévenir l'utilisateur).\n• Stable Diffusion 100% local (opt-in, voir ⚙ → Génération d'images) : termux_sd_setup (lance l'installation/le démarrage du serveur local via Termux — nécessite que l'utilisateur ait fait les 3 étapes préalables affichées dans les réglages ; si un prérequis manque, le message renvoyé l'indique précisément, relaie-le fidèlement) et termux_sd_status (vérifie si le serveur local est prêt). Si activé et prêt, generate_image l'utilise automatiquement en priorité (gratuit, sans limite, hors-ligne).\n• get_notifications\n• GitHub : github_list_repos{account?}, github_list_contents{owner,repo,path?,branch?,account?} (path vide=racine), github_create_repo{name,description?,private?,account?}, github_create_file{owner,repo,path,content,message,branch?,account?} (crée ou modifie), github_delete_file{owner,repo,path,message?,branch?,account?}, github_delete_folder{owner,repo,path,message?,branch?,account?} (récursif, IRRÉVERSIBLE, confirme avant), github_delete_repo{owner,repo,account?} (IRRÉVERSIBLE, confirme avant), github_read_file{owner,repo,path,branch?,account?}, github_create_branch{owner,repo,newBranch,fromBranch?,account?}, github_create_pr{owner,repo,title,head,base?,body?,account?}. account (optionnel sur tous github_*) = compte à utiliser, vide=défaut. github_list_accounts, github_add_account{label,token} (token créé par l'utilisateur sur github.com, jamais inventé), github_remove_account{label}, github_set_default_account{label}. github_test_access{owner?,repo?,account?} : diagnostic réel de permissions — à utiliser SYSTÉMATIQUEMENT si GitHub \"ne marche pas\" avant de deviner une cause. Plusieurs fichiers = plusieurs blocs JARVIS_CMD. Échappe \\n et \\\" dans content.\n• Contacts JARVIS (notes Obsidian, distinct du carnet natif) : save_contact_profile{name,category,nickname?,phone?,phonePro?,email?,address?,addressPro?,birthday?,company?,position?,latitude?,longitude?,installDate?,notes?,template?} (category: travail/personnel/famille/client/autre COUVRENT LA PLUPART DES CAS, mais un AUTRE mot que l'utilisateur emploie explicitement est aussi accepté tel quel comme catégorie personnalisée (ex: \"voisins\", \"sport\") — n'utilise autre que si vraiment aucune catégorie n'est identifiable, jamais pour remplacer un mot que l'utilisateur a lui-même donné. Plusieurs possibles séparées par virgule ex \"famille, travail\". SUR UNE FICHE DÉJÀ EXISTANTE, omets ce champ pour NE PAS toucher la catégorie déjà enregistrée si tu ne fais que compléter un autre champ (téléphone, notes...). SUR UNE NOUVELLE FICHE en revanche, déduis TOUJOURS la catégorie du contexte plutôt que de l'omettre ou de mettre autre par défaut (ex: \"mon collègue Paul\" -> travail, \"ma soeur Julie\" -> famille, \"un client, M. Petit\" -> client) ; phone/email/address/birthday = perso, phonePro/addressPro/company/position = pro). template (optionnel, nom exact d'un modèle créé via save_contact_template) applique les valeurs de ce modèle comme défaut : utile pour pré-remplir un NOUVEAU contact ou pour mettre à jour un contact EXISTANT avec les valeurs du modèle — tout champ que TU fournis explicitement dans le même appel reste toujours prioritaire sur le modèle. Dès que l'utilisateur donne UNE info sur une personne, appelle IMMÉDIATEMENT avec TOUS les champs déjà connus (fusionne avec la fiche existante sans écraser). Ne demande jamais confirmation avant d'enregistrer. Dans notes, reproduis exactement les emojis/mise en forme demandés (la règle \"jamais de markdown\" ne s'applique qu'à ta réponse parlée). search_contact_profile{query,format_hint?}, list_contacts_by_category{category,format_hint?} (format_hint seulement pour une demande ponctuelle différente, sinon utilise set_contact_presentation_style pour du permanent), delete_contact_profile{name}, navigate_to_contact{name}, attach_contact_file{name} (attache la dernière pièce jointe envoyée dans CE chat), set_contact_presentation_style{style}, reset_contact_presentation_style. Si l'utilisateur signale des fiches contact incohérentes entre elles (vues directement dans l'app Obsidian, pas dans ce chat) — cause fréquente : certaines fiches n'ont pas été retouchées depuis un ancien format —, propose/utilise refresh_all_contacts (réécrit toutes les fiches au gabarit actuel, sans jamais changer les données). Modèles de fiche contact (jeu de valeurs par défaut nommé, réutilisable) : save_contact_template{name,category?,nickname?,phone?,phonePro?,email?,address?,addressPro?,birthday?,company?,position?,notes?} (crée ou remplace entièrement le modèle existant du même nom), list_contact_templates (liste les modèles enregistrés), delete_contact_template{name}. Un modèle sert ensuite via save_contact_profile{template} — voir plus haut. Liens cliquables tél/email dans les fiches : désactivés par défaut, enable_contact_links / disable_contact_links seulement si demandé explicitement. Adresses postales : toujours cliquables si précédées de 🏠 (ex: \"🏠 12 rue de la Paix, 75002 Paris\") — prends cette habitude à chaque adresse complète donnée, pas seulement dans les fiches.\n• Notes Obsidian (vault réel de l'utilisateur, distinct des fiches contacts) : obsidian_status, obsidian_search{query}, obsidian_list{folder?} (liste les NOTES d'un dossier), obsidian_list_folders{path?} (liste les VRAIS sous-dossiers, y compris ceux encore vides — utilise-la avant de créer un dossier ou de dire \"introuvable\" : un dossier créé lors d'une AUTRE conversation ou après un redémarrage existe toujours sur le disque, seule cette action peut te le confirmer puisque tu n'as aucune mémoire entre les sessions), obsidian_reset_path, obsidian_wipe (IRRÉVERSIBLE, confirme avant), obsidian_create_note{title,content,folder?} (sans folder → Notes Rapides), obsidian_create_folder{path} (vérifie D'ABORD avec obsidian_list_folders qu'un dossier similaire n'existe pas déjà sous un nom légèrement différent, pour éviter les doublons), obsidian_delete_folder{path} (récursif, IRRÉVERSIBLE, confirme avant), obsidian_rename_folder{path,newPath}, obsidian_daily_note{content?}, obsidian_append{query,text}, obsidian_read{query} (contenu complet, contrairement à obsidian_search), obsidian_delete_note{query} (confirme avant), obsidian_move_file{query,folder} (dossier de destination créé automatiquement si absent). Utilise TOUJOURS ces actions plutôt que de dire que tu ne peux pas créer de note — c'est une capacité réelle. Important : dans content/notes, reproduis EXACTEMENT ce que l'utilisateur a demandé (emojis, tirets, listes, ordre des infos) — la règle \"jamais de markdown\" plus haut ne s'applique qu'à ta réponse parlée, jamais à ces paramètres, qui sont enregistrés tels quels dans le fichier.\n• Mémoire durable (second brain, voir MÉMOIRE DURABLE plus haut) : remember_fact{fact} (ajoute un fait à la note spéciale \"Mémoire JARVIS\", relue EN ENTIER à CHAQUE conversation, contrairement aux autres notes qui ne remontent que par mot-clé), forget_fact{query} (retire les faits correspondant à query). Distinct des notes normales : ne PAS utiliser obsidian_create_note pour ça.\n• Wiki structuré (pattern \"LLM Wiki\", second brain avancé, voir WikiController) : pour approfondir et recouper un SUJET dans la durée (article/source lu en profondeur, personne/outil/organisation récurrente, concept, réponse de recherche substantielle) — PAS pour un fait ponctuel sur l'UTILISATEUR lui-même (-> remember_fact), PAS pour une fiche PERSONNE connue (-> save_contact_profile), PAS pour une note jetable rapide (-> obsidian_create_note). wiki_init (crée une fois le squelette Wiki/sources, entities, concepts, synthesis + index.md + log.md + overview.md dans le vault — idempotent, inutile de vérifier avant, s'auto-appelle aussi si besoin). wiki_page{type,title,content,summary?,tags?} (type: source/entity/concept/synthesis — crée OU met à jour LA page ET répercute automatiquement index.md + log.md, AUCUNE étape supplémentaire à faire toi-même). Ingestion d'une source riche : appelle wiki_page plusieurs fois — d'abord type=source pour la page résumé, puis type=entity/concept pour chaque personne/outil/idée qu'elle éclaire (normal d'en faire 5 à 15 par source). Réponse substantielle à une question de recherche : archive-la avec type=synthesis pour qu'elle compounde dans le wiki plutôt que de disparaître du chat. wiki_status (vue d'ensemble : compteurs par section + dernières entrées du journal). wiki_lint (diagnostic : pages orphelines, entrées d'index périmées, liens cassés) — à proposer périodiquement, jamais à lancer sans qu'on te le demande.\n• generate_image{prompt,count?,format?} : format = carre (défaut) / portrait / paysage — RÉELLEMENT appliqué à la génération désormais (avant, la question posée plus bas n'avait aucun effet, cause du signalement \"impossible de choisir le format\"). Si la demande ne précise NI style (photo/dessin/cartoon/peinture/coloriage...) NI thème/sujet clair NI format (portrait/paysage/carré), pose D'ABORD une question courte et unique pour clarifier plutôt que de deviner (sauf si le contexte de la conversation le rend déjà évident) ; transmets la réponse dans format. FIDÉLITÉ AU SUJET (cause fréquente de signalement \"l'image ne correspond pas du tout\") : traduis le sujet EXACT de l'utilisateur en anglais SANS rien changer, ajouter, retirer ni remplacer (mêmes objets, nombre, couleurs, pose, action, décor que ce qui a été demandé) — les mentions de style/qualité ci-dessous s'AJOUTENT à cette description fidèle, elles ne la remplacent JAMAIS par une scène différente. Une fois que tu sais, prompt en anglais, détaillé (plusieurs phrases, pas 2-3 mots), enrichi selon le style (coloriage→\"black and white line art, coloring book\" ; cartoon→\"cartoon style, vector\" ; photo→\"photorealistic, sharp focus, 8k, high quality, highly detailed\" ; peinture→\"digital painting\" ; abstrait→\"abstract art, bold shapes\") et TOUJOURS complété par une mention de qualité générique (\"high quality, highly detailed, sharp focus\") quel que soit le style choisi. count (2-20) génère plusieurs images en arrière-plan (visibles dans l'onglet 🎨 Génération). Si le rendu déçoit malgré un prompt fidèle (flou/moche/générique), regarde le nom du fournisseur entre parenthèses à la fin du message de résultat — Gemini/OpenAI sont nettement plus fidèles que le secours gratuit AI Horde ou le modèle embarqué ; si une clé cloud est pourtant configurée mais semble ne jamais être utilisée, suggère test_api_keys. Cascade auto (Gemini, OpenAI, Hugging Face, Stable Diffusion embarqué, puis AI Horde gratuit/sans clé en dernier recours — plus lent car file d'attente anonyme) — en cas d'échec total, relaie fidèlement le détail réel de chaque échec.\n• generate_video{prompt,duration?} : arrière-plan, audio synchronisé, jeton Replicate requis. duration 2-15s (défaut 5, déduis de la demande). Prévenir que ça prend plusieurs minutes, notification à la fin.\n• generate_website{description,images?} : arrière-plan, VRAI site multi-pages (accueil + 3-5 pages, CSS partagé, animations scroll, menu mobile), vraies images + un logo généré automatiquement (photos déjà envoyées dans CE chat récupérées automatiquement même sans préciser images, + généré par IA pour le reste). Dossier dans Documents/JARVIS-Sites/. Prévenir du délai et de la notification finale. edit_website{instructions,path?} (modifie une page, défaut = accueil du dernier site — seul le contenu change, pas besoin de renvoyer le HTML complet). publish_website_github{repo?,account?,private?,path?} (GitHub Pages, gratuit, réutilise le jeton GitHub existant, renvoie l'URL publique https://<compte>.github.io/<repo>/).\n• Fichiers bureautiques réels (Documents/JARVIS-Fichiers, ouvrables immédiatement) : create_pdf{title,content,name?,images?}, create_docx{title,content,name?,images?} (content=texte, \\n par paragraphe ; images=chemins EXACTS déjà existants, jamais inventés — pour des images déjà générées, passe D'ABORD par list_generations{type:\"image\"} pour récupérer les chemins exacts), create_xlsx{title,data,name?} (data: \"En-tête1;En-tête2\\nligne1;val1\", 1ère ligne=en-têtes), create_zip{paths,name?} (chemins complets déjà existants, obtenus via list_files/search_files). open_file{path?,type?} (sans path, retrouve automatiquement le dernier fichier du type demandé dans l'historique réel — jamais dire que tu ne t'en souviens pas ; si le type est ambigu, appelle d'abord list_generations{type?,count?} pour voir ce qui a été créé récemment). print_file{path?,type?,printer?} (impression IPP directe) — si aucune imprimante configurée : list_printers puis set_default_printer{ip}. print_test_page{printer?} (TOUJOURS pour \"imprime une page de test\", jamais print_file qui a besoin d'un fichier réel). set_printer_remote_host{host} (bascule auto hors Wi-Fi local si l'utilisateur a fait la redirection de port).\n• create_chart{type,title,data} : image de graphique affichée dans le chat. type: bar/line/pie. data=\"étiquette;valeur\" par ligne — valeurs réelles connues ou données par l'utilisateur, jamais inventées.\n• clear_all_conversations (supprime définitivement TOUT l'historique de conversations enregistré, IRRÉVERSIBLE, confirme avant) ; clear_generation_history (vide la liste de l'historique des générations visible dans l'onglet 🎨 Génération/list_generations — ne supprime PAS les fichiers déjà créés sur le disque, seule la liste est réinitialisée, IRRÉVERSIBLE, confirme avant).\n• set_chat_theme{target,color} (target: fond/bulle_utilisateur/bulle_jarvis ; color: nom FR courant ou #RRGGBB), reset_chat_theme\n• test_api_keys : teste réellement (appel HTTP) chaque clé configurée et dit laquelle échoue et pourquoi. À utiliser si une génération échoue de façon répétée et inexpliquée, avant de supposer un bug.\n• read_debug_logs : journal local des derniers échecs réels (cascade IA texte \"Toutes les IA ont échoué\", cascade image, commandes en erreur), horodatés — AUCUN accès distant de ta part au téléphone, uniquement ce journal consultable en conversation. À utiliser si l'utilisateur signale une erreur déjà disparue de l'écran (\"j'ai souvent une erreur...\") pour retrouver le détail exact au lieu de deviner. clear_debug_logs vide le journal. token_usage : estimation (~4 caractères = 1 token, approximatif, aucun compteur officiel côté téléphone) de la consommation de tokens — dernière requête (prompt envoyé/réponse reçue) et cumul depuis l'installation. À utiliser si l'utilisateur demande combien de tokens ça consomme ou pourquoi une réponse est lente/coûteuse. clear_token_usage remet le cumul à zéro.\n• ollama_status : vérifie si l'IA locale réseau configurée dans ⚙ → Local (Ollama sur PC/NAS/Freebox) est joignable, et rappelle si elle est activée en mode Automatique. ollama_list_models : liste les VRAIS modèles installés (nom+taille) sur ce serveur Ollama — utilise-la si l'utilisateur demande quels modèles il a, jamais une supposition. ollama_pull_model{name} : déclenche le téléchargement d'un NOUVEAU modèle sur ce même serveur Ollama (ex: name=\"llama3.1:8b\", \"qwen2.5:14b\", \"dolphin-mixtral\", \"dolphin-llama3\" — Dolphin est une famille de modèles non censurés distribuée comme n'importe quel modèle Ollama, pas une API à part) — peut prendre plusieurs minutes pour un gros modèle, préviens l'utilisateur ; en cas de timeout côté téléphone le téléchargement continue probablement côté serveur, propose de vérifier avec ollama_list_models un peu plus tard plutôt que de conclure à un échec. Rotation multi-modèles (Réglages → Local → Ollama, champ « modèles de secours ») : si le modèle principal échoue/timeout, JARVIS essaie automatiquement chaque modèle de secours avant de basculer sur le cloud — utile pour combiner un modèle rapide/léger en priorité et un modèle plus puissant/lent en secours (ou l'inverse).\n• Home Assistant (si configuré) : ha_status{filter?,domain?} (domain à TOUJOURS préciser si la demande est ciblée, ex: domain=\"person\",filter=\"Marie\" pour n'avoir QUE sa localisation, pas d'autres capteurs du même prénom ; laisse vide seulement pour une demande générale ; adresse géocodée incluse automatiquement pour domain=\"person\" quand disponible — relaie-la toujours ; set_location_presentation_style{style}/reset_location_presentation_style{} pour une préférence permanente d'affichage), ha_turn_on{device}, ha_turn_off{device}, ha_toggle{device}, ha_rename{device,newName}, ha_delete{device} (confirme avant), ha_rescan, ha_set{device,brightness?,color?,temperature?,volume?,position?,speed?,hvacMode?,presetMode?,fanMode?,option?,value?,command?,source?} (n'envoie que les params pertinents au type d'appareil — climate/poêle: hvacMode/presetMode/fanMode ; select: option ; number: value ; remote (télé): command ex \"KEY_VOLUP\" ; media_player: source ex \"Netflix\"), ha_call_service{domain,service,device?,data?} (contrôle total pour tout ce que ha_set ne couvre pas). Automatisations : ha_list_automations, ha_create_automation{id?,config} (config au format HA: alias/trigger/condition?/action), ha_delete_automation{id}, ha_trigger_automation{device}. Ne peut pas installer d'intégration ni éditer le YAML brut, dis-le honnêtement si demandé. Accès distant (URL HA configurée dans ⚙) : tout ha_* fonctionne aussi hors réseau local automatiquement.\n• ha_browse_media{device,path?,mediaType?} (media_player HA, navigation lecture seule)\n• Réseau local (sans Home Assistant) : network_scan (à lancer d'abord pour qu'un appareil soit connu), wake_on_lan{device?,mac?}, network_ping{device}, network_open_web{device} (dis clairement si pas d'interface web détectée plutôt que d'inventer une URL). set_remote_access{device,host} (adresse distante/DDNS pour un appareil déjà scanné — nécessite une redirection de port faite par l'utilisateur sur sa box, JARVIS ne peut pas la créer lui-même, explique-le si besoin) ; network_ping/network_open_web/wake_on_lan basculent dessus automatiquement si le local ne répond plus.\n• Box internet (Freebox / Livebox Orange / SFR Box / Bbox Bouygues) : configuration en conversation, jamais de formulaire. Si l'utilisateur a une Freebox (ou ne précise pas), appelle box_pair_freebox — une demande d'autorisation apparaît sur l'écran physique de la Freebox, l'utilisateur valide dessus, l'action ne bloque jamais la conversation ; si elle échoue, le message renvoyé contient la cause EXACTE (code HTTP, erreur Freebox) — relaie-la fidèlement, ne dis jamais juste \"ça a échoué\". Pour Livebox/SFR Box/Bbox (pas d'écran de confirmation possible) : demande d'abord le fournisseur si inconnu puis appelle box_set_vendor{vendor}, puis demande le mot de passe admin et appelle box_set_password{password}. Une fois configurée : box_status, box_devices, box_wifi_status, box_wifi_set{enable} (Wi-Fi de la box, pas du téléphone), box_reboot. Capacités variables selon le fournisseur, dis-le honnêtement si le résultat l'indique : box_storage (disque interne/USB) et box_port_forward{wanPort?,lanPort?,comment?}/box_remove_port_forward{wanPort?} (redirection de ports) ne fonctionnent que pour Freebox et Livebox, pas Bbox/SFR Box. Freebox Home (domotique Delta/Pop : capteurs, prises, volets) reste spécifique à la Freebox, aucun équivalent ailleurs : freebox_home_devices{filter?}, freebox_home_set{device,on?,value?}.\n• Hébergement d'un site généré (voir generate_website) : publish_website_github{repo?,account?,private?,path?} (GitHub Pages, permanent, gratuit, recommandé par défaut) OU hébergement local, plus fragile mais autonome (adresse IP publique, pas de nom stable) : start_local_web_server{path?,port?} (défaut: dernier site généré, port 8080), stop_local_web_server, local_web_server_status, box_port_forward{wanPort?,lanPort?,comment?} / box_remove_port_forward{wanPort?} (nécessite Freebox ou Livebox déjà configurée). Précise toujours que l'hébergement local dépend du téléphone (allumé, connecté, batterie) ET de l'IP publique de la box (peut changer), contrairement à GitHub Pages qui reste en ligne en permanence avec une URL fixe.\n• IMPORTANT : le Wi-Fi/Bluetooth du TÉLÉPHONE ne peuvent pas être coupés silencieusement (restriction Android 10+/13+) — enable_wifi/disable_wifi/enable_bluetooth/disable_bluetooth ouvrent le panneau système, dis-le honnêtement si on demande une coupure invisible.\n• Bluetooth : bluetooth_info, enable_bluetooth, disable_bluetooth | Wi-Fi : wifi_info, enable_wifi, disable_wifi\n• Lampe torche : flashlight_on, flashlight_off (direct et immédiat, aucun panneau). Réveils : set_alarm{hour,minute,message?,daysOfWeek?} (hour 0-23, minute 0-59 ; daysOfWeek optionnel = tableau d'entiers 1=dimanche à 7=samedi pour un réveil répété ; déduis toujours hour/minute de l'heure ACTUELLE réelle pour une demande relative comme \"dans 2h\", ne calcule jamais un jour toi-même), show_alarms (SEUL moyen de désactiver un réveil — Android n'expose aucune API pour ça, dis-le honnêtement au lieu de prétendre l'avoir désactivé). Minuteur : set_timer{minutes?,seconds?,message?} (donne l'un OU l'autre, jamais les deux ; ex \"minuteur de 10 minutes\" -> minutes=10 ; \"minuteur de 90 secondes\" -> seconds=90) — même limitation qu'un réveil : impossible à annuler par programme, dis-le si demandé. Itinéraire GPS : open_maps{query} (jamais web_search pour ça).\n\u2022 wakeword_status : diagnostic réel de l'écoute permanente (mot-clé configuré, modèles openWakeWord présents ou non dans l'appli, dernier statut connu) — à utiliser SYSTÉMATIQUEMENT si l'utilisateur signale que le mot d'activation \"ne marche pas\" avant de deviner une cause ; 100% gratuit et sans compte (aucune dépendance à un service tiers payant pour l'écoute permanente).\n\nExemple : \"J'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "quand je télécharge une IA locale comme Qwen,
    // la conversation est trop longue pour le modèle local") : sendLocal() envoyait jusqu'ici le
    // SYSTEM_PROMPT complet ci-dessus (~26 900 caractères, ~6700 tokens) à un modèle .gguf/.task
    // embarqué sur le téléphone — un contexte natif de 2048 tokens (voir jarvis_llama_jni.cpp,
    // désormais 4096) ne peut techniquement PAS contenir ce seul prompt système, avant même
    // d'ajouter l'historique : l'échec était donc systématique dès le premier message, pas lié à
    // la longueur réelle de la conversation. Un modèle local 1-4B ne peut de toute façon pas
    // suivre fiablement l'immense liste d'actions JARVIS_CMD ci-dessus (conçue pour de gros
    // modèles cloud) — ce prompt réduit garde uniquement l'essentiel (ton, langue, quelques
    // actions vraiment utiles hors-ligne) pour rester largement sous le budget de contexte.
    private const val LOCAL_SYSTEM_PROMPT =
        "Tu es JARVIS, assistant IA vocal, en mode 100% local hors-ligne sur ce téléphone (pas de connexion internet ni de service cloud). Réponds en français, phrases courtes et naturelles, sans markdown (pas d'astérisques ni de dièses). Sois concis : ce modèle local est plus limité qu'un modèle cloud, privilégie des réponses courtes et directes.\n\nTu peux déclencher quelques actions simples avec [JARVIS_CMD:{\"action\":\"NOM\", ...params}] : flashlight_on, flashlight_off, set_alarm{hour,minute}, set_timer{minutes?,seconds?}. Pour toute autre demande nécessitant des données réelles du téléphone (contacts, agenda, fichiers, notes...) que tu ne peux pas obtenir toi-même, dis honnêtement que ces fonctions nécessitent une IA en ligne, plutôt que d'inventer une réponse."


    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "les IA échouent souvent alors qu'Ollama est
    // configuré illimité") : le client HTTP partagé ci-dessus (readTimeout 30s, calibré pour des
    // API cloud rapides) était aussi utilisé pour Ollama — un serveur local tournant souvent sur
    // CPU, où une génération peut légitimement prendre bien plus de 30s selon la taille du modèle
    // et la longueur de la conversation. Ollama était alors interrompu en pleine génération,
    // remonté comme une "Erreur" de timeout, et le mode Automatique basculait sur le cloud — qui
    // échoue aussi si aucune clé cloud n'est configurée (cas exact de cet utilisateur, qui compte
    // sur Ollama comme IA "illimitée" sans clé payante) → "Toutes les IA ont échoué" alors
    // qu'Ollama aurait fini par répondre correctement avec plus de temps.
    // readTimeout porté à 300s (était 180s) : signalement utilisateur "toutes les IA échouent
    // même en réglant sur IA locale Ollama uniquement" -- un CPU de Freebox/NAS sans GPU dédié
    // peut légitimement mettre plusieurs minutes à générer une réponse complète pour un modèle
    // 7-8B avec un system prompt de plusieurs milliers de tokens, largement au-delà de ce
    // qu'accepterait une API cloud rapide (d'où readTimeout distinct du client cloud ci-dessus).
    private val ollamaClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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
    // BUG RÉEL / DEMANDE UTILISATEUR ("regarde pour utiliser moins de token, un autre
    // système pour créer des prompts fiables précis avec beaucoup moins de token") :
    // SYSTEM_PROMPT ci-dessus est envoyé EN ENTIER à CHAQUE message, alors que la très
    // grande majorité des échanges ("allume la lampe torche", "mets une alarme à 7h",
    // "note ça dans mes notes"...) n'a besoin QUE d'une petite fraction de ce catalogue —
    // GitHub, génération d'image/vidéo/site web, Home Assistant, box internet, wiki
    // structuré... ne sont utiles QUE pour les demandes qui les concernent réellement.
    // CORE_SYSTEM_PROMPT ci-dessous ne garde QUE les actions les plus fréquentes
    // (identité, agenda, contacts natifs, fichiers, notes Obsidian, mémoire durable,
    // lampe/réveil/minuteur, diagnostics...) — environ 2500 tokens contre ~6700 pour le
    // catalogue complet. Les MODULE_* qui suivent contiennent le texte EXACT (extrait tel
    // quel de SYSTEM_PROMPT ci-dessus, aucune reformulation qui risquerait de perdre une
    // précision issue d'un bug réel déjà corrigé) pour chaque capacité spécialisée,
    // rajoutés par buildSystemPrompt() UNIQUEMENT quand le message récent de l'utilisateur
    // contient un mot-clé pertinent, OU quand l'intégration correspondante est déjà
    // configurée (GitHub/Ollama/Home Assistant/box) — dans ce cas mieux vaut l'inclure
    // systématiquement plutôt que de rater une formulation naturelle imprévue.
    private const val CORE_SYSTEM_PROMPT =
        "Tu es JARVIS, assistant IA vocal et domotique inspiré d'Iron Man. Parle naturellement, phrases courtes, sans jargon. Jamais de markdown dans TA réponse parlée (astérisques, tirets de liste, dièses) : prose fluide même pour énumérer — cette règle ne concerne QUE ce que tu dis à l'oral, jamais les paramètres content/notes envoyés à une action (voir plus bas). Réponds en français.\n\nTu contrôles le smartphone. Pour une action système, inclus dans ta réponse :\n[JARVIS_CMD:{\"action\":\"NOM\", ...params}]\n\nRÈGLE ABSOLUE : tu n'as AUCUNE connaissance mémorisée des fichiers, contacts, agenda, SMS, emails, appels, notifications, réseau local, Home Assistant ou notes Obsidian de l'utilisateur. Pour toute question portant sur l'une de ces données, émets TOUJOURS l'action JARVIS_CMD correspondante et attends son résultat réel avant de répondre — ne devine jamais, n'improvise jamais depuis un souvenir de conversation précédente. Si rien ne correspond ou si le résultat ne contient pas l'info demandée, dis-le clairement plutôt que d'inventer : une info inventée plausible est pire qu'une absence de réponse.\n\nPRIORITÉ OBSIDIAN : avant de répondre à une question factuelle (définition, qui/quoi/combien/quand, code, préférence, info perso) depuis tes connaissances générales, lance D'ABORD obsidian_search{query} — si un résultat pertinent existe, base ta réponse dessus (une note fait toujours autorité en cas de conflit) ; sinon réponds normalement.\n\nPRÉFÉRENCES DURABLES : si l'utilisateur demande un réglage permanent (format d'affichage, couleur...) — ex: \"présente mes fiches comme ça à chaque fois\", \"change la couleur du chat\" — appelle TOUJOURS l'action de sauvegarde dédiée (set_contact_presentation_style, set_location_presentation_style, set_chat_theme, enable_contact_links...), pas seulement une reformulation ponctuelle, sinon la préférence est perdue au message suivant.\n\nMÉMOIRE DURABLE (voir remember_fact/forget_fact plus bas) : dès que l\'utilisateur partage un fait DURABLE sur lui/sa vie qui mérite d\'être connu dans TOUTES les conversations futures (métier, projet en cours, contrainte de santé, situation familiale, objectif à long terme...) et qui n\'est pas déjà couvert par une action plus spécifique (fiches contact, préférences d\'affichage ci-dessus), appelle remember_fact{fact} IMMÉDIATEMENT — c\'est ce qui évite de tout redemander à chaque nouvelle conversation. Si l\'utilisateur dit explicitement \"retiens que...\"/\"souviens-toi que...\", c\'est TOUJOURS remember_fact, jamais une simple confirmation orale sans action. Si un fait devient faux/obsolète (\"oublie que...\", \"c\'est plus le cas\"), appelle forget_fact{query}. Ne retiens jamais d\'info déjà mieux rangée ailleurs (contact -> save_contact_profile, préférence d\'affichage -> set_*_presentation_style).\n\nActions disponibles :\n• call{target}\n• send_sms{to,message} | read_sms{count} (1=dernier) | search_sms{query} (contenu+expéditeur)\n• search_contact{name} (carnet natif, inclut libellés 🏷️ créés dans l'appli Contacts), list_contact_labels, list_contacts_by_label{label} — distinct des catégories Contacts JARVIS (save_contact_profile, plus bas)\n• Musique : play_music{query}, pause_music, stop_music, set_volume{level}\n• Agenda : today_events{calendar?}, upcoming_events{days,calendar?}, week_events{offset,calendar?} (offset=0/-1/1... TOUJOURS utiliser pour \"semaine dernière/prochaine\", ne jamais calculer de dates toi-même), create_event{title,date?,time?,durationMinutes?,description?,location?,calendar?} (date en langage naturel: \"demain\",\"lundi\",\"JJ/MM\"... ; time: \"14h30\" ; jamais d'epoch, CalendarController calcule), search_event{query,calendar?}, update_event{eventId,newTitle?,newDate?,newTime?,newDurationMinutes?}, delete_event{eventId}, list_calendars, name_calendar{calendar,nickname} (calendar=ID/nom/compte), reset_calendar_nicknames, sync_calendar{calendar,enable} (active/désactive sync+visibilité d'un calendrier). Cherche l'ID via search_event/today_events avant modif/suppr. Si plusieurs calendriers mélangés, propose name_calendar puis filtre avec calendar.\n• Clients (depuis l'agenda) : create_client_from_event{eventId,name?}, add_client_visit{name,note}, export_clients_kml{category?}\n• Emails : read_emails, send_email{to,subject,body}, search_email{query}, read_email_content{index}\n• Fichiers : list_files{path}, search_files{query}, read_file{path}, write_file{path,content}, rename_file{oldPath,newName}, copy_file{source,dest}, move_file{source,dest}, delete_file{path}, create_folder{path}, storage_info\n• get_location | open_maps{query} (itinéraire uniquement) | web_search{query} (horaires/avis/infos pratiques, jamais open_maps pour ça)\n• get_notifications\n• Notes Obsidian (vault réel de l'utilisateur, distinct des fiches contacts) : obsidian_status, obsidian_search{query}, obsidian_list{folder?} (liste les NOTES d'un dossier), obsidian_list_folders{path?} (liste les VRAIS sous-dossiers, y compris ceux encore vides — utilise-la avant de créer un dossier ou de dire \"introuvable\" : un dossier créé lors d'une AUTRE conversation ou après un redémarrage existe toujours sur le disque, seule cette action peut te le confirmer puisque tu n'as aucune mémoire entre les sessions), obsidian_reset_path, obsidian_wipe (IRRÉVERSIBLE, confirme avant), obsidian_create_note{title,content,folder?} (sans folder → Notes Rapides), obsidian_create_folder{path} (vérifie D'ABORD avec obsidian_list_folders qu'un dossier similaire n'existe pas déjà sous un nom légèrement différent, pour éviter les doublons), obsidian_delete_folder{path} (récursif, IRRÉVERSIBLE, confirme avant), obsidian_rename_folder{path,newPath}, obsidian_daily_note{content?}, obsidian_append{query,text}, obsidian_read{query} (contenu complet, contrairement à obsidian_search), obsidian_delete_note{query} (confirme avant), obsidian_move_file{query,folder} (dossier de destination créé automatiquement si absent). Utilise TOUJOURS ces actions plutôt que de dire que tu ne peux pas créer de note — c'est une capacité réelle. Important : dans content/notes, reproduis EXACTEMENT ce que l'utilisateur a demandé (emojis, tirets, listes, ordre des infos) — la règle \"jamais de markdown\" plus haut ne s'applique qu'à ta réponse parlée, jamais à ces paramètres, qui sont enregistrés tels quels dans le fichier.\n• Mémoire durable (second brain, voir MÉMOIRE DURABLE plus haut) : remember_fact{fact} (ajoute un fait à la note spéciale \"Mémoire JARVIS\", relue EN ENTIER à CHAQUE conversation, contrairement aux autres notes qui ne remontent que par mot-clé), forget_fact{query} (retire les faits correspondant à query). Distinct des notes normales : ne PAS utiliser obsidian_create_note pour ça.\n• clear_all_conversations (supprime définitivement TOUT l'historique de conversations enregistré, IRRÉVERSIBLE, confirme avant) ; clear_generation_history (vide la liste de l'historique des générations visible dans l'onglet 🎨 Génération/list_generations — ne supprime PAS les fichiers déjà créés sur le disque, seule la liste est réinitialisée, IRRÉVERSIBLE, confirme avant).\n• set_chat_theme{target,color} (target: fond/bulle_utilisateur/bulle_jarvis ; color: nom FR courant ou #RRGGBB), reset_chat_theme\n• test_api_keys : teste réellement (appel HTTP) chaque clé configurée et dit laquelle échoue et pourquoi. À utiliser si une génération échoue de façon répétée et inexpliquée, avant de supposer un bug.\n• read_debug_logs : journal local des derniers échecs réels (cascade IA texte \"Toutes les IA ont échoué\", cascade image, commandes en erreur), horodatés — AUCUN accès distant de ta part au téléphone, uniquement ce journal consultable en conversation. À utiliser si l'utilisateur signale une erreur déjà disparue de l'écran (\"j'ai souvent une erreur...\") pour retrouver le détail exact au lieu de deviner. clear_debug_logs vide le journal. token_usage : estimation (~4 caractères = 1 token, approximatif, aucun compteur officiel côté téléphone) de la consommation de tokens — dernière requête (prompt envoyé/réponse reçue) et cumul depuis l'installation. À utiliser si l'utilisateur demande combien de tokens ça consomme ou pourquoi une réponse est lente/coûteuse. clear_token_usage remet le cumul à zéro.\n• IMPORTANT : le Wi-Fi/Bluetooth du TÉLÉPHONE ne peuvent pas être coupés silencieusement (restriction Android 10+/13+) — enable_wifi/disable_wifi/enable_bluetooth/disable_bluetooth ouvrent le panneau système, dis-le honnêtement si on demande une coupure invisible.\n• Bluetooth : bluetooth_info, enable_bluetooth, disable_bluetooth | Wi-Fi : wifi_info, enable_wifi, disable_wifi\n• Lampe torche : flashlight_on, flashlight_off (direct et immédiat, aucun panneau). Réveils : set_alarm{hour,minute,message?,daysOfWeek?} (hour 0-23, minute 0-59 ; daysOfWeek optionnel = tableau d'entiers 1=dimanche à 7=samedi pour un réveil répété ; déduis toujours hour/minute de l'heure ACTUELLE réelle pour une demande relative comme \"dans 2h\", ne calcule jamais un jour toi-même), show_alarms (SEUL moyen de désactiver un réveil — Android n'expose aucune API pour ça, dis-le honnêtement au lieu de prétendre l'avoir désactivé). Minuteur : set_timer{minutes?,seconds?,message?} (donne l'un OU l'autre, jamais les deux ; ex \"minuteur de 10 minutes\" -> minutes=10 ; \"minuteur de 90 secondes\" -> seconds=90) — même limitation qu'un réveil : impossible à annuler par programme, dis-le si demandé. Itinéraire GPS : open_maps{query} (jamais web_search pour ça).\n\u2022 wakeword_status : diagnostic réel de l'écoute permanente (mot-clé configuré, modèles openWakeWord présents ou non dans l'appli, dernier statut connu) — à utiliser SYSTÉMATIQUEMENT si l'utilisateur signale que le mot d'activation \"ne marche pas\" avant de deviner une cause ; 100% gratuit et sans compte (aucune dépendance à un service tiers payant pour l'écoute permanente).\n\nExemple : \"J'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

    private const val MODULE_CONTACTS_JARVIS =
        "\n• Contacts JARVIS (notes Obsidian, distinct du carnet natif) : save_contact_profile{name,category,nickname?,phone?,phonePro?,email?,address?,addressPro?,birthday?,company?,position?,latitude?,longitude?,installDate?,notes?,template?} (category: travail/personnel/famille/client/autre COUVRENT LA PLUPART DES CAS, mais un AUTRE mot que l'utilisateur emploie explicitement est aussi accepté tel quel comme catégorie personnalisée (ex: \"voisins\", \"sport\") — n'utilise autre que si vraiment aucune catégorie n'est identifiable, jamais pour remplacer un mot que l'utilisateur a lui-même donné. Plusieurs possibles séparées par virgule ex \"famille, travail\". SUR UNE FICHE DÉJÀ EXISTANTE, omets ce champ pour NE PAS toucher la catégorie déjà enregistrée si tu ne fais que compléter un autre champ (téléphone, notes...). SUR UNE NOUVELLE FICHE en revanche, déduis TOUJOURS la catégorie du contexte plutôt que de l'omettre ou de mettre autre par défaut (ex: \"mon collègue Paul\" -> travail, \"ma soeur Julie\" -> famille, \"un client, M. Petit\" -> client) ; phone/email/address/birthday = perso, phonePro/addressPro/company/position = pro). template (optionnel, nom exact d'un modèle créé via save_contact_template) applique les valeurs de ce modèle comme défaut : utile pour pré-remplir un NOUVEAU contact ou pour mettre à jour un contact EXISTANT avec les valeurs du modèle — tout champ que TU fournis explicitement dans le même appel reste toujours prioritaire sur le modèle. Dès que l'utilisateur donne UNE info sur une personne, appelle IMMÉDIATEMENT avec TOUS les champs déjà connus (fusionne avec la fiche existante sans écraser). Ne demande jamais confirmation avant d'enregistrer. Dans notes, reproduis exactement les emojis/mise en forme demandés (la règle \"jamais de markdown\" ne s'applique qu'à ta réponse parlée). search_contact_profile{query,format_hint?}, list_contacts_by_category{category,format_hint?} (format_hint seulement pour une demande ponctuelle différente, sinon utilise set_contact_presentation_style pour du permanent), delete_contact_profile{name}, navigate_to_contact{name}, attach_contact_file{name} (attache la dernière pièce jointe envoyée dans CE chat), set_contact_presentation_style{style}, reset_contact_presentation_style. Si l'utilisateur signale des fiches contact incohérentes entre elles (vues directement dans l'app Obsidian, pas dans ce chat) — cause fréquente : certaines fiches n'ont pas été retouchées depuis un ancien format —, propose/utilise refresh_all_contacts (réécrit toutes les fiches au gabarit actuel, sans jamais changer les données). Modèles de fiche contact (jeu de valeurs par défaut nommé, réutilisable) : save_contact_template{name,category?,nickname?,phone?,phonePro?,email?,address?,addressPro?,birthday?,company?,position?,notes?} (crée ou remplace entièrement le modèle existant du même nom), list_contact_templates (liste les modèles enregistrés), delete_contact_template{name}. Un modèle sert ensuite via save_contact_profile{template} — voir plus haut. Liens cliquables tél/email dans les fiches : désactivés par défaut, enable_contact_links / disable_contact_links seulement si demandé explicitement. Adresses postales : toujours cliquables si précédées de 🏠 (ex: \"🏠 12 rue de la Paix, 75002 Paris\") — prends cette habitude à chaque adresse complète donnée, pas seulement dans les fiches."

    private const val MODULE_RECHERCHE_AVANCEE =
        "\n• Recherche/lecture web avancée (comptes distincts, clés jamais codées en dur) : perplexity_search{query} (réponse synthétisée + sources, via la clé Perplexity AI déjà configurable dans ⚙ → Clés API — préfère web_search pour une simple liste de liens, perplexity_search quand une réponse rédigée et sourcée est plus utile) ; firecrawl_scrape{url} (lit le contenu propre d'une page déjà connue, y compris JS — jamais pour \"chercher\", seulement pour lire une URL précise donnée par l'utilisateur ou trouvée via web_search/perplexity_search) ; run_glif{prompt,projectId?} (Glif, agent IA de composition média glif.app — décris en langage naturel l'image/vidéo/audio à générer, projectId optionnel pour continuer un projet déjà commencé plutôt que d'en recréer un ; la génération peut prendre plusieurs minutes, prévenir l'utilisateur).\n• Stable Diffusion 100% local (opt-in, voir ⚙ → Génération d'images) : termux_sd_setup (lance l'installation/le démarrage du serveur local via Termux — nécessite que l'utilisateur ait fait les 3 étapes préalables affichées dans les réglages ; si un prérequis manque, le message renvoyé l'indique précisément, relaie-le fidèlement) et termux_sd_status (vérifie si le serveur local est prêt). Si activé et prêt, generate_image l'utilise automatiquement en priorité (gratuit, sans limite, hors-ligne)."

    private const val MODULE_GITHUB =
        "\n• GitHub : github_list_repos{account?}, github_list_contents{owner,repo,path?,branch?,account?} (path vide=racine), github_create_repo{name,description?,private?,account?}, github_create_file{owner,repo,path,content,message,branch?,account?} (crée ou modifie), github_delete_file{owner,repo,path,message?,branch?,account?}, github_delete_folder{owner,repo,path,message?,branch?,account?} (récursif, IRRÉVERSIBLE, confirme avant), github_delete_repo{owner,repo,account?} (IRRÉVERSIBLE, confirme avant), github_read_file{owner,repo,path,branch?,account?}, github_create_branch{owner,repo,newBranch,fromBranch?,account?}, github_create_pr{owner,repo,title,head,base?,body?,account?}. account (optionnel sur tous github_*) = compte à utiliser, vide=défaut. github_list_accounts, github_add_account{label,token} (token créé par l'utilisateur sur github.com, jamais inventé), github_remove_account{label}, github_set_default_account{label}. github_test_access{owner?,repo?,account?} : diagnostic réel de permissions — à utiliser SYSTÉMATIQUEMENT si GitHub \"ne marche pas\" avant de deviner une cause. Plusieurs fichiers = plusieurs blocs JARVIS_CMD. Échappe \\n et \\\" dans content."

    private const val MODULE_WIKI =
        "\n• Wiki structuré (pattern \"LLM Wiki\", second brain avancé, voir WikiController) : pour approfondir et recouper un SUJET dans la durée (article/source lu en profondeur, personne/outil/organisation récurrente, concept, réponse de recherche substantielle) — PAS pour un fait ponctuel sur l'UTILISATEUR lui-même (-> remember_fact), PAS pour une fiche PERSONNE connue (-> save_contact_profile), PAS pour une note jetable rapide (-> obsidian_create_note). wiki_init (crée une fois le squelette Wiki/sources, entities, concepts, synthesis + index.md + log.md + overview.md dans le vault — idempotent, inutile de vérifier avant, s'auto-appelle aussi si besoin). wiki_page{type,title,content,summary?,tags?} (type: source/entity/concept/synthesis — crée OU met à jour LA page ET répercute automatiquement index.md + log.md, AUCUNE étape supplémentaire à faire toi-même). Ingestion d'une source riche : appelle wiki_page plusieurs fois — d'abord type=source pour la page résumé, puis type=entity/concept pour chaque personne/outil/idée qu'elle éclaire (normal d'en faire 5 à 15 par source). Réponse substantielle à une question de recherche : archive-la avec type=synthesis pour qu'elle compounde dans le wiki plutôt que de disparaître du chat. wiki_status (vue d'ensemble : compteurs par section + dernières entrées du journal). wiki_lint (diagnostic : pages orphelines, entrées d'index périmées, liens cassés) — à proposer périodiquement, jamais à lancer sans qu'on te le demande."

    private const val MODULE_IMAGE =
        "\n• generate_image{prompt,count?,format?} : format = carre (défaut) / portrait / paysage — RÉELLEMENT appliqué à la génération désormais (avant, la question posée plus bas n'avait aucun effet, cause du signalement \"impossible de choisir le format\"). Si la demande ne précise NI style (photo/dessin/cartoon/peinture/coloriage...) NI thème/sujet clair NI format (portrait/paysage/carré), pose D'ABORD une question courte et unique pour clarifier plutôt que de deviner (sauf si le contexte de la conversation le rend déjà évident) ; transmets la réponse dans format. FIDÉLITÉ AU SUJET (cause fréquente de signalement \"l'image ne correspond pas du tout\") : traduis le sujet EXACT de l'utilisateur en anglais SANS rien changer, ajouter, retirer ni remplacer (mêmes objets, nombre, couleurs, pose, action, décor que ce qui a été demandé) — les mentions de style/qualité ci-dessous s'AJOUTENT à cette description fidèle, elles ne la remplacent JAMAIS par une scène différente. Une fois que tu sais, prompt en anglais, détaillé (plusieurs phrases, pas 2-3 mots), enrichi selon le style (coloriage→\"black and white line art, coloring book\" ; cartoon→\"cartoon style, vector\" ; photo→\"photorealistic, sharp focus, 8k, high quality, highly detailed\" ; peinture→\"digital painting\" ; abstrait→\"abstract art, bold shapes\") et TOUJOURS complété par une mention de qualité générique (\"high quality, highly detailed, sharp focus\") quel que soit le style choisi. count (2-20) génère plusieurs images en arrière-plan (visibles dans l'onglet 🎨 Génération). Si le rendu déçoit malgré un prompt fidèle (flou/moche/générique), regarde le nom du fournisseur entre parenthèses à la fin du message de résultat — Gemini/OpenAI sont nettement plus fidèles que le secours gratuit AI Horde ou le modèle embarqué ; si une clé cloud est pourtant configurée mais semble ne jamais être utilisée, suggère test_api_keys. Cascade auto (Gemini, OpenAI, Hugging Face, Stable Diffusion embarqué, puis AI Horde gratuit/sans clé en dernier recours — plus lent car file d'attente anonyme) — en cas d'échec total, relaie fidèlement le détail réel de chaque échec."

    private const val MODULE_VIDEO =
        "\n• generate_video{prompt,duration?} : arrière-plan, audio synchronisé, jeton Replicate requis. duration 2-15s (défaut 5, déduis de la demande). Prévenir que ça prend plusieurs minutes, notification à la fin."

    private const val MODULE_WEBSITE =
        "\n• generate_website{description,images?} : arrière-plan, VRAI site multi-pages (accueil + 3-5 pages, CSS partagé, animations scroll, menu mobile), vraies images + un logo généré automatiquement (photos déjà envoyées dans CE chat récupérées automatiquement même sans préciser images, + généré par IA pour le reste). Dossier dans Documents/JARVIS-Sites/. Prévenir du délai et de la notification finale. edit_website{instructions,path?} (modifie une page, défaut = accueil du dernier site — seul le contenu change, pas besoin de renvoyer le HTML complet). publish_website_github{repo?,account?,private?,path?} (GitHub Pages, gratuit, réutilise le jeton GitHub existant, renvoie l'URL publique https://<compte>.github.io/<repo>/)."

    private const val MODULE_BUREAUTIQUE =
        "\n• Fichiers bureautiques réels (Documents/JARVIS-Fichiers, ouvrables immédiatement) : create_pdf{title,content,name?,images?}, create_docx{title,content,name?,images?} (content=texte, \\n par paragraphe ; images=chemins EXACTS déjà existants, jamais inventés — pour des images déjà générées, passe D'ABORD par list_generations{type:\"image\"} pour récupérer les chemins exacts), create_xlsx{title,data,name?} (data: \"En-tête1;En-tête2\\nligne1;val1\", 1ère ligne=en-têtes), create_zip{paths,name?} (chemins complets déjà existants, obtenus via list_files/search_files). open_file{path?,type?} (sans path, retrouve automatiquement le dernier fichier du type demandé dans l'historique réel — jamais dire que tu ne t'en souviens pas ; si le type est ambigu, appelle d'abord list_generations{type?,count?} pour voir ce qui a été créé récemment). print_file{path?,type?,printer?} (impression IPP directe) — si aucune imprimante configurée : list_printers puis set_default_printer{ip}. print_test_page{printer?} (TOUJOURS pour \"imprime une page de test\", jamais print_file qui a besoin d'un fichier réel). set_printer_remote_host{host} (bascule auto hors Wi-Fi local si l'utilisateur a fait la redirection de port)."

    private const val MODULE_CHART =
        "\n• create_chart{type,title,data} : image de graphique affichée dans le chat. type: bar/line/pie. data=\"étiquette;valeur\" par ligne — valeurs réelles connues ou données par l'utilisateur, jamais inventées."

    private const val MODULE_OLLAMA_ADMIN =
        "\n• ollama_status : vérifie si l'IA locale réseau configurée dans ⚙ → Local (Ollama sur PC/NAS/Freebox) est joignable, et rappelle si elle est activée en mode Automatique. ollama_list_models : liste les VRAIS modèles installés (nom+taille) sur ce serveur Ollama — utilise-la si l'utilisateur demande quels modèles il a, jamais une supposition. ollama_pull_model{name} : déclenche le téléchargement d'un NOUVEAU modèle sur ce même serveur Ollama (ex: name=\"llama3.1:8b\", \"qwen2.5:14b\", \"dolphin-mixtral\", \"dolphin-llama3\" — Dolphin est une famille de modèles non censurés distribuée comme n'importe quel modèle Ollama, pas une API à part) — peut prendre plusieurs minutes pour un gros modèle, préviens l'utilisateur ; en cas de timeout côté téléphone le téléchargement continue probablement côté serveur, propose de vérifier avec ollama_list_models un peu plus tard plutôt que de conclure à un échec. Rotation multi-modèles (Réglages → Local → Ollama, champ « modèles de secours ») : si le modèle principal échoue/timeout, JARVIS essaie automatiquement chaque modèle de secours avant de basculer sur le cloud — utile pour combiner un modèle rapide/léger en priorité et un modèle plus puissant/lent en secours (ou l'inverse)."

    private const val MODULE_HOME_ASSISTANT =
        "\n• Home Assistant (si configuré) : ha_status{filter?,domain?} (domain à TOUJOURS préciser si la demande est ciblée, ex: domain=\"person\",filter=\"Marie\" pour n'avoir QUE sa localisation, pas d'autres capteurs du même prénom ; laisse vide seulement pour une demande générale ; adresse géocodée incluse automatiquement pour domain=\"person\" quand disponible — relaie-la toujours ; set_location_presentation_style{style}/reset_location_presentation_style{} pour une préférence permanente d'affichage), ha_turn_on{device}, ha_turn_off{device}, ha_toggle{device}, ha_rename{device,newName}, ha_delete{device} (confirme avant), ha_rescan, ha_set{device,brightness?,color?,temperature?,volume?,position?,speed?,hvacMode?,presetMode?,fanMode?,option?,value?,command?,source?} (n'envoie que les params pertinents au type d'appareil — climate/poêle: hvacMode/presetMode/fanMode ; select: option ; number: value ; remote (télé): command ex \"KEY_VOLUP\" ; media_player: source ex \"Netflix\"), ha_call_service{domain,service,device?,data?} (contrôle total pour tout ce que ha_set ne couvre pas). Automatisations : ha_list_automations, ha_create_automation{id?,config} (config au format HA: alias/trigger/condition?/action), ha_delete_automation{id}, ha_trigger_automation{device}. Ne peut pas installer d'intégration ni éditer le YAML brut, dis-le honnêtement si demandé. Accès distant (URL HA configurée dans ⚙) : tout ha_* fonctionne aussi hors réseau local automatiquement.\n• ha_browse_media{device,path?,mediaType?} (media_player HA, navigation lecture seule)"

    private const val MODULE_RESEAU_LOCAL =
        "\n• Réseau local (sans Home Assistant) : network_scan (à lancer d'abord pour qu'un appareil soit connu), wake_on_lan{device?,mac?}, network_ping{device}, network_open_web{device} (dis clairement si pas d'interface web détectée plutôt que d'inventer une URL). set_remote_access{device,host} (adresse distante/DDNS pour un appareil déjà scanné — nécessite une redirection de port faite par l'utilisateur sur sa box, JARVIS ne peut pas la créer lui-même, explique-le si besoin) ; network_ping/network_open_web/wake_on_lan basculent dessus automatiquement si le local ne répond plus."

    private const val MODULE_BOX_INTERNET =
        "\n• Box internet (Freebox / Livebox Orange / SFR Box / Bbox Bouygues) : configuration en conversation, jamais de formulaire. Si l'utilisateur a une Freebox (ou ne précise pas), appelle box_pair_freebox — une demande d'autorisation apparaît sur l'écran physique de la Freebox, l'utilisateur valide dessus, l'action ne bloque jamais la conversation ; si elle échoue, le message renvoyé contient la cause EXACTE (code HTTP, erreur Freebox) — relaie-la fidèlement, ne dis jamais juste \"ça a échoué\". Pour Livebox/SFR Box/Bbox (pas d'écran de confirmation possible) : demande d'abord le fournisseur si inconnu puis appelle box_set_vendor{vendor}, puis demande le mot de passe admin et appelle box_set_password{password}. Une fois configurée : box_status, box_devices, box_wifi_status, box_wifi_set{enable} (Wi-Fi de la box, pas du téléphone), box_reboot. Capacités variables selon le fournisseur, dis-le honnêtement si le résultat l'indique : box_storage (disque interne/USB) et box_port_forward{wanPort?,lanPort?,comment?}/box_remove_port_forward{wanPort?} (redirection de ports) ne fonctionnent que pour Freebox et Livebox, pas Bbox/SFR Box. Freebox Home (domotique Delta/Pop : capteurs, prises, volets) reste spécifique à la Freebox, aucun équivalent ailleurs : freebox_home_devices{filter?}, freebox_home_set{device,on?,value?}."

    private const val MODULE_HEBERGEMENT =
        "\n• Hébergement d'un site généré (voir generate_website) : publish_website_github{repo?,account?,private?,path?} (GitHub Pages, permanent, gratuit, recommandé par défaut) OU hébergement local, plus fragile mais autonome (adresse IP publique, pas de nom stable) : start_local_web_server{path?,port?} (défaut: dernier site généré, port 8080), stop_local_web_server, local_web_server_status, box_port_forward{wanPort?,lanPort?,comment?} / box_remove_port_forward{wanPort?} (nécessite Freebox ou Livebox déjà configurée). Précise toujours que l'hébergement local dépend du téléphone (allumé, connecté, batterie) ET de l'IP publique de la box (peut changer), contrairement à GitHub Pages qui reste en ligne en permanence avec une URL fixe."

    /** Un module de prompt optionnel : ajouté à CORE_SYSTEM_PROMPT si un des mots-clés
     *  apparaît dans les derniers messages utilisateur, ou si alwaysIf(context) est vrai
     *  (intégration déjà configurée -> quasi certainement pertinente, pas besoin de deviner
     *  un mot-clé). Volontairement permissif (beaucoup de synonymes/variantes par module) :
     *  mieux vaut inclure un module inutile de temps en temps que d'en rater un vraiment
     *  utile et faire croire à JARVIS qu'il ne sait pas faire quelque chose qu'il sait faire. */
    private class PromptModule(
        val text: String,
        val keywords: List<String>,
        val alwaysIf: ((Context) -> Boolean)? = null
    )

    private val PROMPT_MODULES: List<PromptModule> by lazy {
        listOf(
            PromptModule(MODULE_CONTACTS_JARVIS, listOf(
                "contact", "fiche", "numéro", "téléphone", "e-mail", "email", "adresse",
                "anniversaire", "appelle", "rappelle-moi qui", "qui est", "travaille chez",
                "collègue", "famille", "client", "voisin", "modèle de fiche"
            )),
            PromptModule(MODULE_RECHERCHE_AVANCEE, listOf(
                "perplexity", "firecrawl", "scrape", "glif", "stable diffusion", "sd local", "termux"
            )),
            PromptModule(MODULE_GITHUB, listOf(
                "github", "dépôt", "depot", "repo", "branche", "pull request", "repository"
            ), alwaysIf = { ctx -> Prefs.getGithubAccounts(ctx).isNotEmpty() }),
            PromptModule(MODULE_WIKI, listOf(
                "wiki", "synthese", "synthèse", "source riche", "approfondi", "second brain avancé"
            )),
            PromptModule(MODULE_IMAGE, listOf(
                "image", "photo", "dessin", "illustration", "logo", "icône", "icone", "peinture",
                "cartoon", "coloriage", "avatar", "affiche"
            )),
            PromptModule(MODULE_VIDEO, listOf("vidéo", "video", "clip")),
            PromptModule(MODULE_WEBSITE, listOf(
                "site web", "site internet", "page web", "site vitrine", "mon site"
            )),
            PromptModule(MODULE_BUREAUTIQUE, listOf(
                "pdf", "word", "docx", "excel", "xlsx", "tableur", "zip", "imprime", "imprimante", "document"
            )),
            PromptModule(MODULE_CHART, listOf(
                "graphique", "chart", "diagramme", "camembert", "histogramme"
            )),
            PromptModule(MODULE_OLLAMA_ADMIN, listOf(
                "ollama", "modèle local", "modele local", "modèle ia", "modele ia", "dolphin",
                "qwen", "llama3", "mistral", "modèles installés", "modeles installes"
            ), alwaysIf = { ctx -> Prefs.getOllamaHost(ctx).isNotBlank() }),
            PromptModule(MODULE_HOME_ASSISTANT, listOf(
                "home assistant", "domotique", "lumière", "lumieres", "allume la", "éteins la",
                "eteins la", "volet", "store", "chauffage", "thermostat", "prise", "climatisation"
            ), alwaysIf = { ctx -> Prefs.getHaUrl(ctx).isNotBlank() }),
            PromptModule(MODULE_RESEAU_LOCAL, listOf(
                "réseau local", "reseau local", "scan réseau", "scan reseau", "wake on lan",
                "ping", "appareil connecté", "appareil connecte"
            )),
            PromptModule(MODULE_BOX_INTERNET, listOf(
                "box", "freebox", "livebox", "sfr box", "bbox", "redirection de port"
            ), alwaysIf = { ctx -> Prefs.getFreeboxAppToken(ctx).isNotBlank() || Prefs.getBoxPassword(ctx).isNotBlank() }),
            PromptModule(MODULE_HEBERGEMENT, listOf(
                "héberge", "heberge", "serveur web", "publie le site", "github pages", "expose mon site"
            ))
        )
    }

    /** Construit le system prompt réellement envoyé pour CE message : CORE (toujours) +
     *  chaque module dont un mot-clé apparaît dans les derniers messages utilisateur ou dont
     *  l'intégration est déjà configurée. Remplace l'usage de SYSTEM_PROMPT (catalogue complet,
     *  conservé ci-dessus tel quel comme valeur par défaut de secours pour les autres appels
     *  IA de ce fichier, ex: reformulations internes qui n'en ont de toute façon pas besoin). */
    private fun buildSystemPrompt(context: Context, recentUserMessages: List<String>): String {
        val haystack = recentUserMessages.joinToString(" ").lowercase()
        val sb = StringBuilder(CORE_SYSTEM_PROMPT)
        for (module in PROMPT_MODULES) {
            val matched = module.keywords.any { haystack.contains(it) } ||
                (module.alwaysIf?.invoke(context) == true)
            if (matched) sb.append(module.text)
        }
        return sb.toString()
    }

    private const val SUMMARY_SYSTEM_PROMPT =
        "Tu reformules un texte déjà obtenu en réponse orale naturelle et concise, en français, sans markdown. " +
            "Règle absolue : ne change, n'ajoute, ne devine et n'omets aucun fait — reprends noms, dates, heures, " +
            "numéros, adresses et montants strictement à l'identique du texte fourni."

    /** Vraie connectivité internet (pas juste "Wi-Fi connecté" — un Wi-Fi sans accès internet
     *  réel, box en panne, compte comme hors-ligne ici via NET_CAPABILITY_VALIDATED). Utilisé
     *  pour éviter d'attendre un timeout web pour rien quand JARVIS est clairement hors-ligne
     *  (voir le rebond vault-vide dans sendChat) — n'affecte PAS Ollama en LAN local, qui reste
     *  tenté séparément dans sendAuto indépendamment de cette vérification. */
    private fun hasInternetConnection(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val capabilities = if (network != null) cm.getNetworkCapabilities(network) else null
            if (capabilities == null) {
                false
            } else {
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (_: Exception) {
            true // en cas de doute (permission/API manquante), ne bloque jamais le rebond par excès de prudence
        }
    }

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
            // Prend les derniers messages utilisateur (pas juste le dernier) pour que les
            // relances mi-conversation ("et son adresse ?") retrouvent aussi du contexte.
            val recentUserMessages = history.filter { it.role == "user" }.takeLast(3).map { it.text }
            val lastUserMsg = recentUserMessages.lastOrNull() ?: ""
            val vaultContext = try {
                ObsidianController.quickContextSearch(context, recentUserMessages)
            } catch (_: Exception) {
                null
            }
            // Mémoire durable ("second brain") : contrairement à vaultContext ci-dessus (qui
            // dépend des mots-clés du message en cours), la note Mémoire JARVIS est relue EN
            // ENTIER et injectée SYSTÉMATIQUEMENT, SANS AUCUNE condition de mot-clé — c'est ce
            // qui règle "JARVIS repart de zéro à chaque conversation/redémarrage" : les faits
            // qui y sont notés restent connus en permanence, pas seulement quand leurs
            // mots-clés apparaissent par hasard dans le message en cours (voir
            // ObsidianController.rememberFact/getMemoryContext).
            val memoryContext = try {
                ObsidianController.getMemoryContext(context)
            } catch (_: Exception) {
                null
            }
            // Prompt dynamique (voir buildSystemPrompt) au lieu du catalogue SYSTEM_PROMPT
            // complet -- réduit fortement le nombre de tokens envoyés pour la grande majorité
            // des messages, sans rien retirer : les modules spécialisés restent inclus dès
            // qu'un mot-clé pertinent apparaît ou qu'une intégration est déjà configurée.
            var effectiveSystemPrompt = buildSystemPrompt(context, recentUserMessages)
            if (!memoryContext.isNullOrBlank()) {
                effectiveSystemPrompt += "\n\nMÉMOIRE JARVIS (faits durables retenus sur l'utilisateur au fil " +
                    "des conversations précédentes — TOUJOURS valables, prends-les en compte même sans lien " +
                    "apparent avec le dernier message ; corrige/complète avec forget_fact puis remember_fact " +
                    "si un fait devient obsolète) :\n" + memoryContext
            }
            if (!vaultContext.isNullOrBlank()) {
                effectiveSystemPrompt += "\n\nCONTEXTE OBSIDIAN (extraits de notes du vault réel de l'utilisateur, " +
                    "trouvés automatiquement à partir de ses derniers messages — utilise-les EN PRIORITÉ s'ils " +
                    "répondent à sa question, ignore-les s'ils ne sont pas pertinents ; ce ne sont que des " +
                    "extraits, appelle quand même obsidian_search{query} ou obsidian_list si tu as besoin de " +
                    "voir une note en entier ou d'en chercher d'autres) :\n" + vaultContext
            }

            val rawResponse = try {
                dispatchToProvider(context, provider, history, effectiveSystemPrompt)
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }

            // Exécution automatique des commandes système si présentes dans la réponse
            var commandResult = JarvisCommandParser.parseAndExecute(context, rawResponse)
            var cleanText = JarvisCommandParser.cleanResponse(rawResponse)

            // Estimation de conso de tokens (demande utilisateur : "regarde ça par réponse") --
            // approximation grossière (caractères réellement envoyés/reçus), voir
            // Prefs.recordTokenUsage pour le détail du calcul et l'action token_usage pour la
            // consulter en conversation.
            try {
                val promptChars = effectiveSystemPrompt.length + history.sumOf { it.text.length }
                Prefs.recordTokenUsage(context, promptChars, rawResponse.length)
            } catch (_: Exception) {
                // Ne doit jamais faire échouer la réponse principale pour un simple compteur.
            }

            // DERNIER RECOURS 100% LOCAL (signalement utilisateur répété : "toutes les IA ont
            // échoué malgré Ollama configuré", "toujours aucun système en local pour utiliser
            // seulement le vault ou les fonctions du téléphone : réveil, minuteur, flash") : si
            // la cascade complète (cloud + Ollama local/distant) a échoué EN ENTIER, on tente
            // une commande locale sans réseau ni IA (LocalCommandController) avant d'afficher
            // le message d'échec brut -- lampe torche, réveil, minuteur, recherche vault
            // restent utilisables même hors-ligne total.
            val aiTotallyFailed = rawResponse.startsWith("Toutes les IA configurées ont échoué") ||
                rawResponse.startsWith("Connexion impossible")
            if (aiTotallyFailed) {
                val localResult = try {
                    LocalCommandController.tryHandle(context, lastUserMsg)
                } catch (_: Exception) {
                    null
                }
                if (localResult != null) {
                    commandResult = JarvisCommandParser.CommandResult.Executed(localResult, "local_offline", isInformational = false)
                    cleanText = localResult
                }
            }

            // REBOND BORNÉ (signalement utilisateur : "il me dit introuvable dans le vault,
            // mais ne recherche pas sur internet ou dans les IA pour avoir l'info puis la
            // conserver") : l'architecture ne fait normalement QU'UN appel IA par tour — si ce
            // tour se limite à un obsidian_search/obsidian_read qui n'a RIEN trouvé, l'IA
            // n'avait jusqu'ici aucune seconde chance de rebondir sur le web/ses connaissances
            // générales, et le message "introuvable" brut du vault finissait affiché tel quel
            // comme réponse finale. Un unique appel de suivi (jamais plus, pour borner la
            // latence) corrige ça : instruction explicite de répondre quand même ET de
            // mémoriser la réponse trouvée pour ne plus avoir à la re-chercher.
            val vaultMiss = (commandResult as? JarvisCommandParser.CommandResult.Executed)?.let { r ->
                val isVaultLookup = r.action == "obsidian_search" || r.action == "obsidian_read"
                val notFound = r.outputMessage.startsWith("🔍 Aucun résultat") ||
                    r.outputMessage.startsWith("❌ Aucune note trouvée")
                if (isVaultLookup && notFound) r else null
            }
            if (vaultMiss != null) {
                val canReachAnyAi = hasInternetConnection(context) || Prefs.isOllamaAutoEnabled(context)
                if (!canReachAnyAi) {
                    // Hors-ligne (ni internet, ni IA locale Ollama configurée) : inutile de tenter
                    // un second appel IA qui échouerait/timeout de toute façon — message honnête
                    // immédiat plutôt qu'une attente pour rien (demande utilisateur : "integrer un
                    // systeme de recherche dans le vault si aucune connexion" — la recherche vault
                    // a déjà eu lieu ci-dessus via obsidian_search/obsidian_read, c'est bien ce
                    // résultat, déjà exhaustif sur le vault, qui est relayé ici).
                    commandResult = JarvisCommandParser.CommandResult.Executed(
                        "🔌 Pas de connexion internet, et rien trouvé dans le vault pour cette question. " +
                            "Réessaie une fois connecté (Wi-Fi/données), ou configure Ollama en local " +
                            "(⚙ → Local) pour une IA disponible même hors-ligne.",
                        vaultMiss.action,
                        isInformational = false
                    )
                    cleanText = ""
                } else {
                    val followUpPrompt = effectiveSystemPrompt +
                        "\n\nLe vault Obsidian ne contient RIEN sur la dernière question de l'utilisateur " +
                        "(${vaultMiss.action} a renvoyé : \"${vaultMiss.outputMessage}\"). N'appelle PLUS " +
                        "obsidian_search ni obsidian_read pour cette même question : réponds-y MAINTENANT avec " +
                        "tes connaissances générales, ou avec web_search{query} si c'est une info qui change " +
                        "dans le temps (actualité, prix, horaires, disponibilité...). Si le sujet a PLUSIEURS " +
                        "FACETTES distinctes (ex: pour \"les dauphins\" — intelligence, taille, vitesse, " +
                        "records sont des facettes séparées), utilise wiki_page{type:\"concept\",title,content} " +
                        "UNE FOIS PAR FACETTE (jamais tout dans une seule note fourre-tout) — voir le pattern " +
                        "Wiki plus haut dans tes instructions. Pour un fait court et isolé sans facettes " +
                        "multiples, remember_fact{fact} ou obsidian_create_note{title,content} suffit à la " +
                        "place. Si tu ne sais vraiment pas et qu'aucune recherche web ne peut aider, dis-le " +
                        "honnêtement plutôt que d'inventer."
                    val followUpResponse = try {
                        dispatchToProvider(context, provider, history, followUpPrompt)
                    } catch (e: Exception) {
                        null
                    }
                    if (followUpResponse != null) {
                        commandResult = JarvisCommandParser.parseAndExecute(context, followUpResponse)
                        cleanText = JarvisCommandParser.cleanResponse(followUpResponse)
                    }
                }
            }

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
        // Ollama réseau local (PC, NAS, Freebox Delta avec Docker...) — tenté EN PREMIER,
        // avant toute IA cloud, mais UNIQUEMENT si l'utilisateur l'a explicitement activé ET
        // configuré un hôte (opt-in strict, voir Prefs.isOllamaAutoEnabled) : contrairement aux
        // fournisseurs cloud, un hôte réseau local mal configuré ou éteint peut mettre plusieurs
        // secondes à échouer (pas de réponse DNS/TCP immédiate comme un refus cloud), donc ne
        // JAMAIS l'essayer pour un utilisateur qui n'a rien configuré. Échec silencieux et
        // journalisé (pas d'entrée dans allErrors ci-dessous, qui reste réservé aux fournisseurs
        // cloud) : ne doit jamais faire échouer tout le mode Auto à lui seul.
        if (Prefs.isOllamaAutoEnabled(context) && Prefs.getOllamaHost(context).isNotBlank()) {
            val ollamaResult = try {
                sendOpenAiWithRotation(context, history, Provider.OLLAMA, systemPrompt)
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }
            if (!ollamaResult.startsWith("Erreur") &&
                !ollamaResult.startsWith("Connexion impossible") &&
                !ollamaResult.startsWith("Format de réponse inattendu") &&
                !ollamaResult.startsWith("Aucune clé API")
            ) {
                return ollamaResult
            }
            DiagnosticsLog.log(context, "IA-AUTO", "Ollama (réseau local) indisponible, repli sur le cloud : $ollamaResult")
        }

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

        // Accumule le détail de CHAQUE fournisseur essayé (pas seulement le dernier) — journalisé
        // dans DiagnosticsLog en cas d'échec total, pour que l'utilisateur puisse consulter APRÈS
        // COUP (action read_debug_logs) ce qui a précisément échoué pour chacun, plutôt que de
        // ne garder que le message générique "Toutes les IA configurées ont échoué" une fois
        // qu'il a disparu de l'écran du chat.
        val allErrors = mutableListOf<String>()
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
            allErrors.add(lastError)
        }

        DiagnosticsLog.log(context, "IA-AUTO", "Échec total (${allErrors.size} fournisseur(s) essayé(s)) : " + allErrors.joinToString(" | "))
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
        // Prompt système court dédié (voir LOCAL_SYSTEM_PROMPT) + historique réduit à 3 tours au
        // lieu de 8 -- le SYSTEM_PROMPT cloud complet dépassait à lui seul la fenêtre de contexte
        // native (2048, désormais 4096 tokens), provoquant un échec garanti dès le premier
        // message ("la conversation est trop longue pour le modèle local").
        val prompt = buildPromptFromHistory(history, LOCAL_SYSTEM_PROMPT, maxTurns = 3)
        return LocalLlmManager.generate(context, modelPath, prompt)
    }

    private fun buildPromptFromHistory(history: List<HistoryEntry>, systemPrompt: String = SYSTEM_PROMPT, maxTurns: Int = 8): String {
        val recent = history.takeLast(maxTurns)
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

    /** URL "chat/completions" compatible OpenAI construite à partir des champs dédiés Ollama
     *  (Réglages → Local), au lieu de l'ancien mécanisme générique cassé — voir le commentaire
     *  détaillé sur baseUrl dans sendOpenAiWithRotation ci-dessous. */
    private fun ollamaBaseUrlFor(hostRaw: String, port: String): String {
        val host = hostRaw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        return "http://$host:$port/v1/chat/completions"
    }

    private fun ollamaBaseUrl(context: Context): String =
        ollamaBaseUrlFor(Prefs.getOllamaHost(context), Prefs.getOllamaPort(context).trim())

    /** Ping conversationnel (action ollama_status) — même vérification que le bouton "Tester"
     *  des réglages, mais utilisable directement en discussion sans ouvrir l'app. */
    suspend fun checkOllamaStatus(context: Context): String = withContext(Dispatchers.IO) {
        // Signalement utilisateur répété ("même erreur avec et sans Ollama, même en réglant
        // uniquement sur IA locale Ollama") causé par une désynchronisation invisible entre
        // ce qui était affiché dans le menu déroulant et Prefs.getProvider() réellement utilisé
        // par dispatchToProvider() -- toujours afficher le fournisseur RÉELLEMENT actif en
        // premier, pour qu'un décalage de ce genre soit visible immédiatement au lieu de rester
        // invisible.
        val activeProvider = Prefs.getProvider(context)
        val activeLine = "🎛️ Fournisseur IA actif en ce moment : ${activeProvider.displayName}\n" +
            "(vérifiable/modifiable dans ⚙ Réglages → Config)\n\n"
        val host = Prefs.getOllamaHost(context).trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isBlank()) {
            return@withContext activeLine + "❌ Aucune adresse Ollama configurée — Réglages → Local → « IA locale réseau (Ollama) »."
        }
        val port = Prefs.getOllamaPort(context).trim().ifBlank { "11434" }
        try {
            val testClient = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url("http://$host:$port/api/tags").get().build()
            testClient.newCall(request).execute().use { response ->
                activeLine + if (!response.isSuccessful) {
                    "❌ Ollama a répondu mais avec une erreur (HTTP ${response.code})."
                } else {
                    val body = response.body?.string() ?: "{}"
                    val models = JSONObject(body).optJSONArray("models")
                    val count = models?.length() ?: 0
                    val autoNote = if (Prefs.isOllamaAutoEnabled(context)) " Essayé en premier en mode Automatique." else " Pas encore activé en mode Automatique."
                    "✅ Ollama joignable à $host:$port — $count modèle(s) installé(s).$autoNote"
                }
            }
        } catch (e: Exception) {
            activeLine + "❌ Injoignable à $host:$port : ${e.javaClass.simpleName} — ${e.message}."
        }
    }

    /** Action ollama_list_models : liste RÉELLE (nom + taille) des modèles installés sur le
     *  serveur Ollama configuré — jusqu'ici seul un COMPTE était visible (checkOllamaStatus),
     *  jamais les noms, rendant impossible de demander à JARVIS "quels modèles j'ai" ou de
     *  choisir un modèle de secours pour la rotation sans ouvrir soi-même un terminal. */
    suspend fun listOllamaModels(context: Context): String = withContext(Dispatchers.IO) {
        val host = Prefs.getOllamaHost(context).trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isBlank()) {
            return@withContext "❌ Aucune adresse Ollama configurée — Réglages → Local → « IA locale réseau (Ollama) »."
        }
        val port = Prefs.getOllamaPort(context).trim().ifBlank { "11434" }
        try {
            val testClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url("http://$host:$port/api/tags").get().build()
            testClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "❌ Ollama a répondu mais avec une erreur (HTTP ${response.code})."
                val body = response.body?.string() ?: "{}"
                val models = JSONObject(body).optJSONArray("models") ?: return@withContext "✅ Ollama joignable, mais aucun modèle installé pour l'instant."
                if (models.length() == 0) return@withContext "✅ Ollama joignable à $host:$port, mais aucun modèle installé — utilise ollama_pull_model{name} pour en installer un."
                val lines = (0 until models.length()).map { i ->
                    val m = models.getJSONObject(i)
                    val name = m.optString("name", m.optString("model", "?"))
                    val bytes = m.optLong("size", 0L)
                    val gb = bytes / 1_000_000_000.0
                    val sizeStr = if (gb >= 0.1) String.format("%.1f Go", gb) else "${bytes / 1_000_000} Mo"
                    "• $name ($sizeStr)"
                }
                val current = Prefs.getOllamaModel(context)
                val fallbacks = Prefs.getOllamaFallbackModels(context)
                val rotationNote = if (fallbacks.isNotEmpty()) {
                    "\n\nRotation configurée : $current en priorité, puis ${fallbacks.joinToString(" → ")} en secours si le premier échoue/timeout."
                } else {
                    "\n\nModèle actif : $current (aucun modèle de secours configuré — voir Réglages → Local → Ollama)."
                }
                "📦 ${models.length()} modèle(s) installé(s) sur $host:$port :\n" + lines.joinToString("\n") + rotationNote
            }
        } catch (e: Exception) {
            "❌ Injoignable à $host:$port : ${e.javaClass.simpleName} — ${e.message}."
        }
    }

    /** Action ollama_pull_model{name} : déclenche le TÉLÉCHARGEMENT côté serveur Ollama d'un
     *  nouveau modèle (ex: "dolphin-mixtral", "dolphin-llama3", "llama3.1:8b", "qwen2.5:14b") via
     *  l'API native /api/pull. JARVIS ne peut pas "installer une API Dolphin" à proprement parler
     *  — Dolphin est une FAMILLE de modèles open-source (fine-tunes non censurés de Mistral/Llama/
     *  Qwen par Eric Hartford/Cognitive Computations), distribués comme n'importe quel modèle
     *  Ollama, pas comme un service à part. Cette action lance le pull réel sur le serveur déjà
     *  configuré (Réglages → Local → Ollama) ; un gros modèle peut prendre plusieurs minutes à
     *  télécharger — le téléchargement continue côté serveur même si cette requête HTTP finit par
     *  expirer côté téléphone (le serveur Ollama pilote le téléchargement, pas la connexion), donc
     *  en cas de timeout ici, dis à l'utilisateur de vérifier avec ollama_list_models un peu plus
     *  tard plutôt que de conclure à un échec. */
    // BUG RÉEL CORRIGÉ (signalement utilisateur : "en permanence sur téléchargement") : avec
    // stream=false, Ollama ne renvoie STRICTEMENT AUCUN octet tant que le modèle entier n'est
    // pas fini de télécharger côté serveur -- pour un gros modèle (dolphin-mixtral fait ~26 Go),
    // la requête HTTP restait donc silencieuse plusieurs dizaines de minutes, sans AUCUN moyen
    // de savoir si ça avançait ou si c'était figé, avant d'expirer au bout de 10 min côté
    // téléphone (souvent AVANT la fin réelle du téléchargement). stream=true fait l'inverse :
    // Ollama envoie une ligne JSON de progression (status/completed/total) à intervalles
    // réguliers pendant tout le téléchargement -- onProgress permet à l'appelant (bouton
    // Réglages) d'afficher un vrai pourcentage en direct, et readTimeout ne s'applique plus
    // qu'ENTRE deux lignes de progression (quelques secondes normalement), pas sur la durée
    // totale du téléchargement -- beaucoup plus robuste pour un gros fichier sur une connexion
    // lente.
    suspend fun pullOllamaModel(
        context: Context,
        name: String,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val host = Prefs.getOllamaHost(context).trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isBlank()) {
            return@withContext "❌ Aucune adresse Ollama configurée — Réglages → Local → « IA locale réseau (Ollama) »."
        }
        if (name.isBlank()) return@withContext "❌ Nom de modèle manquant."
        val port = Prefs.getOllamaPort(context).trim().ifBlank { "11434" }
        try {
            val pullClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            val body = JSONObject().put("name", name).put("stream", true).toString().toRequestBody(JSON)
            val request = Request.Builder().url("http://$host:$port/api/pull").post(body).build()
            pullClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "❌ Échec du téléchargement de « $name » (HTTP ${response.code}) : ${response.body?.string()?.take(200)}"
                }
                val source = response.body?.source()
                    ?: return@withContext "❌ Réponse vide du serveur Ollama."
                var lastStatus = ""
                var lastError: String? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = try { JSONObject(line) } catch (_: Exception) { continue }
                    if (obj.has("error")) {
                        lastError = obj.optString("error", "erreur inconnue")
                        continue
                    }
                    val status = obj.optString("status", "")
                    if (status.isBlank()) continue
                    lastStatus = status
                    val total = obj.optLong("total", 0L)
                    val completed = obj.optLong("completed", 0L)
                    val progressText = if (total > 0) {
                        val pct = (completed * 100 / total)
                        "⏳ « $name » : $status — $pct % (${"%.1f".format(completed / 1_000_000_000.0)}/${"%.1f".format(total / 1_000_000_000.0)} Go)"
                    } else {
                        "⏳ « $name » : $status"
                    }
                    onProgress?.invoke(progressText)
                }
                if (lastError != null) {
                    "❌ Ollama a refusé « $name » : $lastError — vérifie le nom exact sur ollama.com/library."
                } else {
                    "✅ Modèle « $name » téléchargé et prêt sur le serveur Ollama (dernier statut : $lastStatus). Utilise-le comme modèle principal ou de secours (Réglages → Local → Ollama)."
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            "⏳ Aucune progression reçue depuis plus de 2 minutes pour « $name » — le serveur Ollama continue peut-être en arrière-plan, ou la connexion a été coupée. Vérifie avec ollama_list_models."
        } catch (e: Exception) {
            "❌ Injoignable à $host:$port : ${e.javaClass.simpleName} — ${e.message}."
        }
    }

    // ─── OpenAI-compatible avec rotation de clés ──────────────────────────────

    private fun sendOpenAiWithRotation(
        context: Context,
        history: List<HistoryEntry>,
        provider: Provider,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        val keys = Prefs.getApiKeysFor(context, provider)
        // BUG RÉEL CORRIGÉ : Provider.OLLAMA utilisait TOUJOURS son defaultBaseUrl codé en dur
        // (IP d'exemple "192.168.1.50"), quoi que l'utilisateur ait tapé et "enregistré" dans
        // le champ URL du sélecteur générique — cette valeur n'était en fait relue QUE pour
        // Provider.CUSTOM. Résultat concret : impossible de pointer Ollama vers sa propre
        // machine, cause directe du signalement "impossible de configurer Ollama". Ollama a
        // maintenant ses propres champs dédiés (host/port/modèle, voir Prefs.getOllamaHost),
        // configurables depuis Réglages → Local, réellement utilisés ici.
        val baseUrl = when {
            provider == Provider.OLLAMA -> ollamaBaseUrl(context)
            !provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM -> provider.defaultBaseUrl
            else -> Prefs.getBaseUrl(context)
        }
        val model = when {
            provider == Provider.OLLAMA -> Prefs.getOllamaModel(context)
            !provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM -> provider.defaultModel
            else -> Prefs.getModel(context)
        }

        if (keys.isEmpty() && provider.needsApiKey) {
            return "Aucune clé API configurée pour ${provider.displayName}. Ajoute-en dans ⚙ Paramètres → Clés API."
        }

        // BUG RÉEL CORRIGÉ (signalement utilisateur : "l'IA échoue souvent, ajoute des modèles
        // plus rapides/puissants avec rotation") : Ollama n'a pas de clé API à faire tourner
        // (rotation ci-dessous conçue pour ça), donc avant ce correctif un seul modèle était
        // JAMAIS retenté ni remplacé en cas d'échec/timeout — un modèle lent ou momentanément
        // en erreur faisait échouer Ollama entier d'un coup, avant repli sur le cloud (qui
        // échoue lui aussi si aucune clé cloud n'est configurée). Rotation dédiée : modèle
        // principal (Prefs.getOllamaModel) essayé en premier, puis chaque modèle de secours
        // (Prefs.getOllamaFallbackModels, configurable dans Réglages -> Local -> Ollama) dans
        // l'ordre — même esprit que la rotation multi-clés ci-dessous, appliquée aux modèles.
        if (provider == Provider.OLLAMA) {
            val modelsToTry = listOf(model) + Prefs.getOllamaFallbackModels(context).filter { it != model }
            // RÉPARTI SUR PLUSIEURS HÔTES (signalement utilisateur récurrent : "toutes les IA
            // ont échoué malgré Ollama illimité sur la Freebox") : un hôte Ollama local
            // (192.168.x.x typiquement) n'est joignable QUE quand le téléphone est sur le même
            // Wi-Fi que la Freebox — dès qu'il est en 4G/5G ou ailleurs, l'hôte local devient
            // injoignable et Ollama échouait entièrement, sans repli, malgré une configuration
            // par ailleurs correcte. Un hôte distant optionnel (Réglages -> Local -> Ollama,
            // voir Prefs.getOllamaRemoteHost) est maintenant tenté en second, APRÈS avoir
            // épuisé tous les modèles sur l'hôte local — nécessite une redirection de port sur
            // la Freebox vers le port Ollama pour fonctionner.
            val port = Prefs.getOllamaPort(context).trim().ifBlank { "11434" }
            val hostCandidates = buildList {
                add("local" to ollamaBaseUrlFor(Prefs.getOllamaHost(context), port))
                val remoteHost = Prefs.getOllamaRemoteHost(context)
                if (remoteHost.isNotBlank()) add("distant" to ollamaBaseUrlFor(remoteHost, port))
            }
            var lastOllamaErr = ""
            for ((hostLabel, candidateBaseUrl) in hostCandidates) {
                for (m in modelsToTry) {
                    val result = sendOpenAiCompatible(candidateBaseUrl, m, "", history, provider, systemPrompt)
                    if (!result.startsWith("Erreur") &&
                        !result.startsWith("Connexion impossible") &&
                        !result.startsWith("Format de réponse inattendu")
                    ) {
                        return result
                    }
                    // BUG RÉEL CORRIGÉ (signalement utilisateur : "à chaque demande ça me dit llama3
                    // erreur API 404 model not found") : l'ancien format "[$m] $result" ne commençait
                    // PAS par un préfixe reconnu comme échec ("Erreur"/"Connexion impossible"/...) —
                    // sendAuto() plus bas traitait donc ce message d'ÉCHEC (ex: modèle mal nommé,
                    // 404 côté Ollama) comme une VRAIE réponse réussie de l'IA, et l'affichait tel
                    // quel dans le chat au lieu de basculer sur le cloud. Le préfixe "Erreur" doit
                    // rester en tout premier pour que la détection d'échec fonctionne partout.
                    lastOllamaErr = "Erreur Ollama (hôte $hostLabel, modèle « $m ») : $result"
                    DiagnosticsLog.log(context, "OLLAMA-ROTATION", "Hôte $hostLabel / modèle « $m » indisponible, essai suivant : $result")
                }
            }
            return lastOllamaErr
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

        // BUG RÉEL PROBABLE CORRIGÉ (signalement utilisateur répété : "toutes les IA échouent,
        // même Ollama") : Ollama tronque silencieusement le contexte à num_ctx=2048 tokens par
        // DÉFAUT pour la quasi-totalité des modèles (llama3.1 inclus), quelle que soit la
        // fenêtre de contexte réelle du modèle, SAUF si la requête précise explicitement
        // options.num_ctx — ce qui n'était jamais fait ici. Le seul SYSTEM_PROMPT de JARVIS
        // dépasse déjà 2048 tokens à lui seul (documentation complète de toutes les actions
        // JARVIS_CMD) : sans ce correctif, Ollama coupait donc une partie du prompt système à
        // CHAQUE requête, avec des réponses cassées/incohérentes voire des erreurs selon le
        // modèle — jamais un vrai "échec réseau", mais un contexte insuffisant invisible côté
        // téléphone. 8192 est un compromis raisonnable (couvre largement le system prompt +
        // historique récent) pour la plupart des modèles 7B-13B tournant sur CPU/Freebox ;
        // n'a AUCUN effet sur les fournisseurs cloud (ignoré, seul Ollama lit ce paramètre).
        if (provider == Provider.OLLAMA) {
            bodyObj.put("options", JSONObject().put("num_ctx", 8192))
        }

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

        val httpClient = if (provider == Provider.OLLAMA) ollamaClient else client
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
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
