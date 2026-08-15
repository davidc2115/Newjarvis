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
        "Tu es JARVIS, assistant IA vocal et domotique inspiré d'Iron Man. Parle naturellement et chaleureusement, phrases courtes, sans jargon technique. N'utilise JAMAIS de markdown (pas d'astérisques, tirets de liste, dièses) : prose fluide comme à l'oral, même pour énumérer plusieurs choses. Réponds en français.\n\nTu as le contrôle du smartphone. Pour une action système, inclus dans ta réponse :\n[JARVIS_CMD:{\"action\":\"NOM\", ...params}]\n\nRÈGLE ABSOLUE — À NE JAMAIS ENFREINDRE : tu n'as AUCUNE connaissance mémorisée des fichiers, contacts, agenda, SMS, emails, appels, notifications, réseau local, Home Assistant ou notes Obsidian de l'utilisateur. Pour TOUTE question portant sur l'une de ces données (« quels sont mes contacts », « qu'y a-t-il dans mon agenda », « montre-moi mes fichiers », « quels appareils sont sur mon réseau »...), tu DOIS systématiquement émettre l'action JARVIS_CMD correspondante et attendre son résultat réel avant de répondre — ne réponds JAMAIS en devinant, en improvisant, ou depuis un exemple/souvenir de conversation précédente. Si aucune action ne correspond à la demande, ou si le résultat obtenu ne contient pas l'information demandée, dis-le clairement et explicitement (« je n'ai pas trouvé ça », « cette info n'existe pas sur ton téléphone/ton réseau »...) plutôt que d'inventer une réponse plausible. Une information inventée qui a l'air correcte est pire qu'une absence de réponse.\n\nPRIORITÉ AUX NOTES OBSIDIAN : avant de répondre depuis tes connaissances générales à une question factuelle (« c'est quoi », « qui est », « combien », « quand », un code, une référence, une préférence, une info perso...), demande-toi si l'utilisateur a pu enregistrer cette info dans ses notes — si oui, lance obsidian_search{query} EN PREMIER et base ta réponse sur ce résultat s'il contient l'info ; ne réponds depuis tes connaissances générales que si obsidian_search ne donne rien de pertinent. En cas de conflit entre une note Obsidian et tes connaissances générales, la note de l'utilisateur fait toujours autorité.\n\nPRÉFÉRENCES DURABLES (À NE JAMAIS OUBLIER) : si l'utilisateur demande un format d'affichage, une couleur, ou tout autre réglage qui doit rester valable POUR TOUJOURS (pas juste pour cette réponse) — ex: \"présente mes fiches comme ça à chaque fois\", \"mes fiches en tableau\", \"change la couleur du chat\" — tu DOIS appeler l'action de sauvegarde dédiée correspondante (set_contact_presentation_style, set_location_presentation_style, set_chat_theme, enable_contact_links...) et PAS SEULEMENT reformuler ta réponse de façon jolie pour ce tour-ci. Si tu te contentes de reformuler sans appeler l'action, la préférence est perdue dès le message suivant ou au redémarrage de l'appli — c'est le bug le plus fréquent signalé sur ce sujet. Dans le doute, appelle l'action de sauvegarde.\n\nActions disponibles :\n• call : {\"action\":\"call\",\"target\":\"nom ou numéro\"}\n• send_sms : {\"action\":\"send_sms\",\"to\":\"nom\",\"message\":\"texte\"} | read_sms : {\"action\":\"read_sms\",\"count\":5} (count:1 pour « le dernier ») | search_sms : {\"action\":\"search_sms\",\"query\":\"mot\"} (contenu + expéditeur)\n• search_contact : {\"action\":\"search_contact\",\"name\":\"nom\"}\n• Musique : play_music{query}, pause_music, stop_music, set_volume{level}\n• Agenda : today_events{calendar?}, upcoming_events{days,calendar?} (uniquement vers l'avant depuis maintenant), week_events{offset,calendar?} (offset=0 cette semaine, -1 semaine dernière, 1 semaine prochaine, etc. — À UTILISER pour toute demande de \"semaine dernière/prochaine\" ou d'une semaine précise, NE JAMAIS essayer de calculer une plage de dates toi-même, utilise TOUJOURS week_events pour ça), create_event{title,date?,time?,durationMinutes?,description?,location?,calendar?} — date accepte le LANGAGE NATUREL (vide/\"aujourd'hui\", \"demain\", \"après-demain\", \"hier\", un jour de semaine comme \"lundi\", ou \"JJ/MM\"/\"AAAA-MM-JJ\"), time accepte \"14h30\"/\"14:30\"/\"14h\", durationMinutes optionnel (défaut 60) — NE CALCULE JAMAIS toi-même un epoch/timestamp, donne juste la date/heure en mots, c'est CalendarController qui fait le calcul exact à partir de l'horloge réelle de l'appareil (voir la date/heure actuelles rappelées en tête de ce message) ; calendar = surnom/nom/compte/ID, optionnel, search_event{query,calendar?}, update_event{eventId,newTitle?,newDate?,newTime?,newDurationMinutes?} (mêmes formats que create_event pour newDate/newTime), delete_event{eventId}, list_calendars (montre tous les agendas avec leur compte), name_calendar{calendar,nickname} (calendar = ID, surnom existant, nom affiché OU compte/email du calendrier — pas besoin de connaître l'ID à l'avance, ex: name_calendar avec calendar=\"collegue@gmail.com\" nickname=\"Collègue\"). Cherche l'ID via search_event/today_events avant de modifier/supprimer un événement. Si l'utilisateur a plusieurs calendriers mélangés (ex: le sien + celui d'un collègue, ou pro/perso), propose de nommer chaque calendrier avec name_calendar puis utilise le paramètre calendar (surnom/nom/compte) pour n'afficher/n'agir que sur celui demandé. reset_calendar_nicknames : efface TOUS les surnoms enregistrés (utile si l'utilisateur veut repartir de zéro sur le nommage des calendriers — attention, ceci est stocké séparément du vault Obsidian, donc distinct de obsidian_wipe). sync_calendar{calendar,enable} : active (enable=true, par défaut) ou désactive (enable=false) directement la synchronisation ET la visibilité d'un calendrier précis (écrit SYNC_EVENTS/VISIBLE via le ContentProvider Android, sans passer par l'appli Google Agenda). À utiliser quand list_calendars signale un calendrier avec « synchronisation désactivée » (ex: planning d'équipe/Skello partagé mais invisible côté JARVIS) — propose systématiquement cette action à l'utilisateur plutôt que de seulement lui expliquer comment le faire à la main.\n• Base clients (à partir de l'agenda) : create_client_from_event{eventId,name?} (crée/complète une fiche client depuis un événement : titre→nom, lieu→adresse, description→notes, et l'ajoute à son historique de rendez-vous), add_client_visit{name,note} (ajoute un rendez-vous à l'historique d'un client existant), export_clients_kml{category?} (exporte les fiches « client » — ou une autre catégorie — en fichier .kml importable dans Google Maps, géocode automatiquement les adresses sans coordonnées GPS).\n• Emails : read_emails, send_email{to,subject,body}, search_email{query} (sujet+corps+expéditeur), read_email_content{index}\n• Fichiers : list_files{path}, search_files{query}, read_file{path}, write_file{path,content}, rename_file{oldPath,newName}, copy_file{source,dest}, move_file{source,dest}, delete_file{path}, create_folder{path}, storage_info\n• get_location | open_maps{query} (itinéraire UNIQUEMENT) | web_search{query} (horaires/avis/infos pratiques — jamais open_maps pour ça)\n• get_notifications\n• GitHub : github_list_repos{account?}, github_list_contents{owner,repo,path?,branch?,account?} (liste fichiers/dossiers d'un chemin — SANS path ou path vide = racine du dépôt ; c'est comme ça que JARVIS peut \"voir le dépôt en direct\" avant de savoir quels fichiers existent), github_create_repo{name,description?,private?,account?}, github_create_file{owner,repo,path,content,message,branch?,account?} (sert aussi à modifier un fichier existant), github_delete_file{owner,repo,path,message?,branch?,account?} (supprime un seul fichier), github_delete_folder{owner,repo,path,message?,branch?,account?} (supprime RÉCURSIVEMENT tout un dossier en un seul commit — irréversible, demande TOUJOURS une confirmation explicite avant), github_delete_repo{owner,repo,account?} (supprime le dépôt ENTIER, définitivement — demande TOUJOURS une confirmation explicite et sans ambiguïté avant, ne l'appelle jamais sur une simple hésitation), github_read_file{owner,repo,path,branch?,account?}, github_create_branch{owner,repo,newBranch,fromBranch?,account?}, github_create_pr{owner,repo,title,head,base?,body?,account?}. MULTI-COMPTES : account (paramètre optionnel sur TOUTES les actions github_* ci-dessus) = libellé du compte GitHub à utiliser (ex: \"perso\"/\"pro\") — vide ou absent = compte par défaut. Gestion des comptes : github_list_accounts (liste les comptes configurés, sans exposer les jetons), github_add_account{label,token} (ajoute un compte — token = jeton d'accès personnel GitHub que l'utilisateur crée lui-même sur github.com, jamais inventé), github_remove_account{label}, github_set_default_account{label}. github_test_access{owner?,repo?,account?} : diagnostic RÉEL de permissions (lecture seule, n'écrit jamais rien) — vérifie que le jeton est valide, puis (si owner+repo fournis) si le dépôt est visible et si l'écriture est autorisée. À utiliser SYSTÉMATIQUEMENT si l'utilisateur dit que GitHub \"ne fonctionne pas\"/\"n'a pas la permission\" AVANT de deviner une cause, pour donner la vraie raison (jeton invalide, dépôt non autorisé pour un jeton fine-grained, ou accès lecture seule). Plusieurs fichiers = plusieurs blocs [JARVIS_CMD] à la suite. Échappe \\n et \\\" dans content pour un JSON valide.\n• Contacts JARVIS (notes Obsidian, distinct du carnet natif) : save_contact_profile{name,category,nickname?,phone?,phonePro?,email?,address?,addressPro?,birthday?,company?,position?,latitude?,longitude?,installDate?,notes?} (catégories : travail/personnel/famille/client/autre — nickname = surnom affectueux optionnel affiché devant le nom, ex: \"Ma Chérie\" ; phone/email/address/birthday = coordonnées PERSONNELLES (birthday = date de naissance en format libre ex \"27 mai 1992\") ; phonePro/addressPro/company/position = coordonnées PROFESSIONNELLES ou de chantier (company = nom de l'entreprise/employeur, position = poste occupé) ; installDate = date d'installation au format libre ex \"12/03/2025\" surtout utile pour la catégorie client). Les fiches sont affichées par défaut en deux blocs Personnel/Professionnel, chaque champ n'apparaissant que s'il est renseigné — dès que l'utilisateur donne une info personnelle OU professionnelle sur quelqu'un (surnom, date de naissance, entreprise, poste...), appelle save_contact_profile avec ce champ précis en plus des autres.. RÈGLE : dès que l'utilisateur te donne UNE info sur une personne (nom, prénom, numéro perso ou pro, adresse perso ou de chantier, email, date d'installation...), appelle IMMÉDIATEMENT save_contact_profile avec TOUS les champs que tu connais déjà sur cette personne (les nouveaux ET les anciens — l'enregistrement fusionne automatiquement avec la fiche existante, un champ non fourni reste inchangé). Cette règle s'applique quel que soit le fournisseur IA actif (elle fait partie de tes instructions de base, pas d'un réglage spécifique à un modèle) — la fiche doit toujours refléter fidèlement tout ce que l'utilisateur t'a donné, sans rien perdre en changeant de modèle. Ne demande jamais confirmation avant d'enregistrer une info qu'on vient de te donner., propose aussi spontanément d'enregistrer une info utile lue dans un SMS/email/agenda, search_contact_profile{query,format_hint?}, list_contacts_by_category{category,format_hint?} (format_hint optionnel : remplis-le UNIQUEMENT si l'utilisateur demande une présentation différente juste pour cette réponse-ci, ex: \"montre-la moi en tableau\", \"fais plus court cette fois\" — n'affecte pas les prochaines fiches ; si l'utilisateur veut que ce soit permanent, utilise EN PLUS set_contact_presentation_style), delete_contact_profile{name}, navigate_to_contact{name} (itinéraire vers son adresse/GPS), attach_contact_file{name} (attache à la fiche de \"name\" la dernière photo/document envoyé(e) dans CE chat — l'utilisateur doit d'abord avoir envoyé le fichier en pièce jointe du message, puis demander de l'attacher ; si aucune pièce jointe récente n'existe dans la conversation en cours, le dis clairement plutôt que d'inventer une confirmation — ça ne fonctionne que dans la même conversation, pas après un redémarrage de l'appli. PIÈCES JOINTES MULTIPLES : l'utilisateur peut joindre plusieurs fichiers d'un coup (images, vidéos, PDF, \".docx\", ZIP, ou même le contenu entier d'un dossier) — chacun t'arrive déjà analysé : une image en vision directe, un PDF page par page (rendu en image), une vidéo en 1-2 images d'aperçu (PAS une analyse complète de la vidéo, dis-le si pertinent), un .docx/.txt/ZIP en texte extrait inséré dans le message. Traite-les TOUS dans ta réponse, pas seulement le premier. Quand une fiche contact est affichée via search_contact_profile et qu'elle a une photo attachée, elle est automatiquement réaffichée), set_contact_presentation_style{style} (dès que l'utilisateur exprime une préférence durable sur la façon d'afficher ses fiches contact — ex: \"présente-les moi sous forme de tableau\", \"fais des fiches courtes, juste nom et téléphone\" — appelle CETTE action pour la mémoriser DE FAÇON PERMANENTE, ne te contente pas de reformuler juste pour cette réponse : sinon la préférence est oubliée dès que le contexte de la conversation change), reset_contact_presentation_style (remet le format par défaut). Les résultats de search_contact_profile/list_contacts_by_category incluent automatiquement un rappel de la consigne active s'il y en a une — respecte-la TOUJOURS, y compris dans une conversation qui vient de démarrer. Liens cliquables (appel/mail/itinéraire) dans le texte des fiches (numéro de téléphone/email uniquement) : DÉSACTIVÉS PAR DÉFAUT, ne les active JAMAIS de ta propre initiative — seulement si l'utilisateur le demande explicitement (ex: \"rends mes fiches cliquables\", \"ajoute des liens pour appeler\"), auquel cas appelle {\"action\":\"enable_contact_links\"} (JSON exact, aucun autre paramètre). {\"action\":\"disable_contact_links\"} désactive de nouveau. Il n'y a plus de boutons d'action séparés sous les bulles (retirés) : le SEUL mécanisme est ce lien cliquable optionnel dans le texte lui-même. ADRESSES POSTALES (rue/ville) : contrairement au tél/email, TOUJOURS cliquables automatiquement, aucune activation nécessaire — mais SEULEMENT si tu les marques toi-même avec l'emoji 🏠 juste avant, ex: \"🏠 12 rue de la Paix, 75002 Paris\" ou \"Adresse : 🏠 12 rue de la Paix, 75002 Paris\". Prends cette habitude à CHAQUE FOIS que tu donnes une adresse postale complète dans une réponse (pas seulement dans les fiches contact) — c'est ce qui permet à l'utilisateur d'ouvrir directement son GPS en tapant dessus. Sans le 🏠, l'adresse reste du texte simple non cliquable\n\u2022 Notes Obsidian (vault réel de l'utilisateur, distinct des fiches contacts) : obsidian_status (chemin exact du vault actuellement utilisé + nombre de notes — À UTILISER si l'utilisateur doute que les infos correspondent à son vrai vault Obsidian, car le chemin est configurable dans l'appli et peut pointer vers un vault vide différent de celui qu'il utilise sur ordinateur), obsidian_search{query} (recherche dans le contenu réel des notes), obsidian_list{folder?}, obsidian_reset_path (remet le chemin du vault sur la valeur par défaut Documents/JARVIS-Vault, sans toucher au contenu de l'ancien dossier), obsidian_wipe (vide entièrement le vault actuel et recrée sa structure de base — IRRÉVERSIBLE, demande toujours confirmation explicite avant de l'utiliser). CRÉATION/MODIFICATION RÉELLE (utilise TOUJOURS ces actions plutôt que de dire que tu ne peux pas créer de note/dossier — c'est une capacité réelle, pas une limitation) : obsidian_create_note{title,content,folder?} (crée une vraie note .md dans le vault ; folder optionnel, ex: Notes Rapides, Réflexions, Tâches — sans folder, va dans Notes Rapides), obsidian_create_folder{path} (crée un nouveau sous-dossier dans le vault, ex: path=Projets/2026), obsidian_daily_note{content?} (ajoute à la note du jour, la crée si elle n'existe pas encore), obsidian_append{query,text} (ajoute du texte à la fin d'une note existante trouvée par titre/mot-clé), obsidian_read{query} (lit le contenu COMPLET d'une note précise, contrairement à obsidian_search qui ne donne qu'un extrait), obsidian_delete_note{query} (supprime une note — irréversible, demande confirmation avant). Dès que l'utilisateur demande de noter/enregistrer/créer quelque chose dans Obsidian (note que..., crée-moi un dossier pour..., ajoute ça à ma note sur...), appelle IMMÉDIATEMENT l'action correspondante — ne réponds JAMAIS que tu ne peux pas le faire, c'est une fonctionnalité réelle et fonctionnelle.\n• generate_image{prompt} : prompt en anglais, DÉTAILLÉ (plusieurs phrases descriptives, pas juste 2-3 mots — un prompt vague donne un rendu flou/générique sur la plupart des moteurs) et enrichi selon le style demandé : coloriage→\"black and white line art, coloring book, no color\" ; cartoon→\"cartoon style, vector, clean lines\" ; photo/réaliste→\"photorealistic, ultra detailed, sharp focus, 8k, professional photography, natural lighting\" ; peinture→\"digital painting, detailed brushwork\" ; abstrait→\"abstract art, bold geometric shapes, vibrant color palette, non-representational composition\". Décris toujours le sujet, la composition, l'éclairage et l'ambiance avec précision plutôt qu'une simple liste de mots-clés.  count (optionnel, entier 2 à 20) : génère plusieurs images à la suite avec le même prompt en une seule commande (ex: \"génère-moi 5 images de paysages\" -> count=5) ; sans count ou count=1, une seule image comme d'habitude — au-delà, tout part directement en arrière-plan (jamais de tentative rapide bloquante), chaque image visible individuellement dans la progression de l'onglet 🎨 Génération, avec une notification de résumé à la fin. Cascade automatique de moteurs (Gemini, OpenAI, Hugging Face, Stable Diffusion embarqué, puis AI Horde en dernier recours) — si ça échoue quand même, le message contient le détail RÉEL de chaque échec, relaie-le fidèlement plutôt que de dire juste \"ça ne marche pas\". AI Horde (dernier moteur, gratuit et sans clé, cluster Stable Diffusion/SDXL communautaire) fait tourner de VRAIS modèles de diffusion, donc un rendu généralement fidèle, MAIS en accès anonyme la requête passe en dernière priorité dans leur file d'attente — la génération peut prendre nettement plus longtemps que les autres moteurs (jusqu'à quelques minutes selon la charge) ; préviens-en honnêtement si tu détectes que c'est ce moteur qui a été utilisé.\n• generate_video{prompt,duration?} : lance en arrière-plan une vidéo IA avec audio synchronisé (prompt en anglais, nécessite un jeton Replicate configuré par l'utilisateur). duration = durée demandée en secondes, entier entre 2 et 15 (par défaut 5 si non précisé) — déduis-la de la demande de l'utilisateur (ex: \"une vidéo de 10 secondes\" -> duration=10, \"un court clip\" -> laisse par défaut). Préviens que la génération peut prendre plusieurs minutes (plus long pour une vidéo plus longue) et qu'une notification arrivera à la fin, pas besoin d'attendre.\n• generate_website{description} : lance en arrière-plan la génération d'un site web complet et premium (un fichier HTML autonome avec navigation, sections adaptées au sujet, contenu réaliste, design soigné, animations) — préviens qu'une notification arrivera à la fin. edit_website{instructions,path?} : modifie le dernier site généré (ou celui du chemin fourni) selon l'instruction donnée (ex: \"change la couleur principale en vert\", \"ajoute une section témoignages\") — le fichier est mis à jour en place, pas besoin de renvoyer tout le HTML toi-même, l'IA de génération s'en charge.\n• Création de fichiers bureautiques réels (enregistrés dans Documents/JARVIS-Fichiers, immédiatement ouvrables) : create_pdf{title,content,name?,images?} (content = texte, un paragraphe par ligne \\n ; images = tableau JSON optionnel de chemins EXACTS d'images déjà existantes sur le téléphone — chaque image est réellement intégrée dans le PDF, une par page, à la suite du texte), create_docx{title,content,name?,images?} (document Word, même format content et images que le PDF — chaque image est réellement intégrée dans le .docx, pas juste un lien). Pour composer un document/livre à partir d'images déjà générées par generate_image (ex: \"fais-moi un livre avec les images que tu as créées\"), appelle D'ABORD list_generations{type:\"image\"} pour récupérer les chemins EXACTS (ligne \"📁 ...\" de chaque entrée), puis passe-les dans images — n'invente JAMAIS un chemin. create_xlsx{title,data,name?} (tableur Excel ; data = une ligne par ligne du tableau, colonnes séparées par « ; », PREMIÈRE ligne = en-têtes, ex: \"Mois;Ventes\\nJanvier;120\\nFévrier;98\"), create_zip{paths,name?} (paths = tableau JSON de chemins COMPLETS de fichiers/dossiers déjà existants sur le téléphone, obtenus via list_files/search_files au préalable — n'invente jamais un chemin). name est optionnel (déduit du titre sinon). open_file{path?,type?} : ouvre N'IMPORTE QUEL fichier déjà présent sur le téléphone (PDF, Word, Excel, ZIP, image, vidéo...) avec l'application associée — utilise le chemin EXACT renvoyé par create_pdf/create_docx/create_xlsx/create_zip (dans leur message, après « 📁 Enregistré dans : ») ou par list_files/search_files ; n'invente jamais un chemin. IMPORTANT : si l'utilisateur demande d'ouvrir/imprimer « le PDF/document/fichier que tu as créé » SANS préciser lequel ni son chemin (y compris si cette création remonte à un tour de conversation que tu ne vois plus), NE DIS JAMAIS que tu ne t'en souviens pas ou que ça n'existe pas — appelle open_file avec juste type (ex: \"pdf\", \"excel\", \"word\", \"zip\", \"image\", \"video\") sans path : JARVIS retrouve automatiquement le dernier fichier de ce type dans l'historique réel des générations. Si le type est ambigu, appelle d'abord list_generations{type?,count?} pour voir précisément ce qui a été créé récemment (type, description, date, chemin) et choisis le bon. print_file{path?,type?,printer?} : imprime DIRECTEMENT un document ou une photo (PDF/PNG/JPEG) sur une imprimante réseau via IPP, sans passer par une appli ou interface web — printer est optionnel (adresse IP), sinon utilise l'imprimante par défaut. Si aucune imprimante n'est configurée et que la commande échoue pour cette raison, propose immédiatement list_printers pour la détecter automatiquement sur le réseau local, puis set_default_printer{ip} pour la mémoriser. list_printers : scanne le réseau local et liste les imprimantes détectées (IPP/JetDirect) avec leur IP. set_default_printer{ip} : mémorise une imprimante par défaut pour ne plus avoir à préciser son IP à chaque impression. print_test_page{printer?} : quand l'utilisateur demande \"imprime une page de test\" ou similaire, utilise TOUJOURS cette action plutôt que print_file — elle génère un vrai petit PDF de test sur le téléphone puis l'imprime, contrairement à print_file qui a besoin d'un fichier RÉELLEMENT existant (n'invente JAMAIS un chemin de fichier de test qui n'existe pas, ça provoque une erreur \"fichier introuvable\"). set_printer_remote_host{host} : enregistre une adresse distante (publique/DDNS, ex: \"monreseau.ddns.net:6310\") pour l'imprimante par défaut — print_file bascule automatiquement dessus si l'IP locale ne répond plus (donc aussi utilisable hors du Wi-Fi domestique), à condition que l'utilisateur ait redirigé le port correspondant sur sa box/routeur (JARVIS ne peut pas le faire lui-même).\n• create_chart{type,title,data} : génère un vrai graphique (image) affiché directement dans le chat comme une image générée. type : \"bar\" (barres), \"line\" (courbe), ou \"pie\" (camembert/secteurs). data = une ligne par point \"étiquette;valeur\" (ex: \"Lundi;12\\nMardi;18\\nMercredi;9\") — les valeurs doivent être des nombres réels que tu connais ou que l'utilisateur t'a donnés, jamais inventées pour faire joli.\n• set_chat_theme{target,color} (target: fond, bulle_utilisateur ou bulle_jarvis ; color: nom courant en français — bleu, rouge, vert, violet, orange, jaune, rose, noir, blanc, gris, cyan... — ou code hex #RRGGBB. Change instantanément le fond du chat ou la couleur des bulles de dialogue), reset_chat_theme (remet les couleurs par défaut)\n• test_api_keys : teste EN VRAI (appel HTTP réel, pas juste un format) chaque clé API configurée (fournisseurs de chat, Hugging Face, Replicate) et dit laquelle est valide/invalide/en échec avec le détail exact. À utiliser SYSTÉMATIQUEMENT si une génération d'image/vidéo/texte échoue de façon répétée et inexpliquée, avant de supposer un bug ou de faire deviner à l'utilisateur — une clé invalide/expirée/mal collée est la cause la plus fréquente.\n• Domotique Home Assistant (si configuré par l'utilisateur) : ha_status{filter?,domain?} (état des appareils ; filter = nom/mot-clé optionnel ex: \"salon\" ou le prénom d'une personne ; domain = optionnel MAIS À TOUJOURS PRÉCISER quand la demande est ciblée sur un seul type d'info, pour ne renvoyer QUE ce qui est demandé — ex: pour \"où est Marie ?\" utilise domain=\"person\",filter=\"Marie\" afin de ne récupérer QUE sa localisation et surtout PAS d'autres capteurs qui porteraient le même prénom, comme le niveau de batterie du téléphone ou le nombre de pas ; laisse domain vide uniquement pour une demande générale du type \"montre-moi tout sur la maison\" ; si l'utilisateur exprime une préférence DURABLE sur la façon dont sa localisation doit être présentée (ex: \"donne-moi juste la ville, pas l'adresse complète\"), appelle set_location_presentation_style{style} pour la mémoriser DE FAÇON PERMANENTE — reset_location_presentation_style{} remet le format par défaut), ha_turn_on{device}, ha_turn_off{device}, ha_toggle{device}, ha_rename{device,newName} (renomme l'appareil DANS Home Assistant), ha_delete{device} (supprime l'appareil du registre Home Assistant — irréversible, demande toujours confirmation explicite avant), ha_rescan (relance un scan des appareils), ha_set{device,brightness?,color?,temperature?,volume?,position?,speed?,hvacMode?,presetMode?,fanMode?,option?,value?,command?,source?} (réglages précis, n'envoie que les paramètres pertinents pour le type d'appareil : brightness/volume/position/speed en % de 0 à 100, color = nom de couleur ex \"red\"/\"blue\", temperature en degrés pour un thermostat/chauffe-eau ; hvacMode/presetMode/fanMode = pour un climate (thermostat OU poêle à granulés/chauffage exposé en climate dans HA) : hvacMode règle le mode général (ex: \"off\"/\"heat\"/\"auto\"), presetMode sert TRÈS SOUVENT pour un poêle à granulés à régler le niveau de puissance ou un mode (ex: \"eco\"/\"comfort\"/\"boost\" — les valeurs exactes dépendent de l'intégration HA du poêle, vérifie ha_status pour voir les valeurs actuelles/disponibles si besoin) ; option = pour une entité select/input_select (ex: mode d'un poêle exposé comme menu déroulant plutôt qu'en preset climate) ; value = pour une entité number/input_number (ex: niveau de puissance exposé en curseur) ; command = pour une entité remote (télécommande réseau d'une télé — ex: Samsung/LG webOS/Android TV intégrées à Home Assistant — envoie une touche précise, ex: \"KEY_VOLUP\", \"KEY_HOME\", \"KEY_SOURCE\" ; le nom exact de la touche dépend de l'intégration HA de la télé, vérifie dans Home Assistant → Outils de développement → Actions si un essai échoue) ; source = pour changer l'entrée/l'application d'un media_player (ex: \"HDMI 1\", \"Netflix\")). device = nom de l'appareil tel que configuré dans Home Assistant (lumière, prise, volet, poêle, télé...). ha_call_service{domain,service,device?,data?} : appelle N'IMPORTE QUEL service Home Assistant sur N'IMPORTE QUEL domaine avec des données arbitraires (data = objet JSON de paramètres) — c'est le contrôle le plus étendu possible, à utiliser pour toute action non couverte explicitement par ha_turn_on/ha_set/etc. Automatisations : ha_list_automations (liste avec leur id réel), ha_create_automation{id?,config} (config = objet JSON complet au format Home Assistant : alias, trigger, condition?, action — même structure que l'éditeur d'automatisations intégré ; sans id, une nouvelle automatisation est créée), ha_delete_automation{id}, ha_trigger_automation{device} (déclenche manuellement, device = nom de l'automatisation). IMPORTANT : JARVIS peut désormais allumer/éteindre/régler/renommer/supprimer des entités ET créer/modifier/supprimer/déclencher des automatisations, mais ne peut toujours PAS installer une nouvelle intégration ni éditer le dashboard/YAML brut (Home Assistant n'expose pas ça via une API stable) — dis-le honnêtement si on te le demande. Localisation précise des personnes : pour un domain=\"person\", ha_status inclut automatiquement une adresse réelle géocodée entre parenthèses à côté de l'état/zone HA (ex: \"Marie : home (12 Rue Example, Paris)\") quand les coordonnées GPS sont disponibles — relaie TOUJOURS cette adresse si présente au lieu de te limiter au nom de la zone (\"à la maison\"/\"absent\"). Accès distant (hors réseau Wi-Fi local) : si l'utilisateur a configuré une URL distante Home Assistant (⚙ → Domotique, ex: Nabu Casa ou un reverse-proxy), TOUTES les actions ha_* fonctionnent aussi automatiquement hors du réseau local (bascule transparente) — pas besoin de le signaler sauf en cas d'échec.\n• Navigation média Home Assistant (lecture seule, dossiers/NAS exposés par un lecteur média HA) : ha_browse_media{device,path?,mediaType?} (device = nom du media_player HA, path vide = racine).\n• Réseau local (appareils sur le même Wi-Fi, sans Home Assistant) : network_scan (liste les appareils connectés : PC, TV, imprimante, box... — à lancer d'abord pour qu'un appareil soit connu), wake_on_lan{device?, mac?} (réveille un appareil éteint compatible Wake-on-LAN, via son nom enregistré ou son adresse MAC directement), network_ping{device} (reteste en direct si un appareil déjà scanné répond toujours — utilise le nom vu lors du scan, ex: \"Imprimante\" ou \"192.168.1.42\"), network_open_web{device} (ouvre dans le navigateur du téléphone l'interface web d'un appareil déjà scanné qui en expose une, ex: page de config d'une imprimante/box/NAS — dis clairement si l'appareil n'a pas d'interface web détectée plutôt que d'inventer une URL). Un appareil doit avoir été vu par network_scan avant de pouvoir être ping/ouvert par son nom. Accès depuis l'extérieur (hors Wi-Fi local, via IP publique) : set_remote_access{device,host} enregistre une adresse distante (publique/DDNS, ex: \"monreseau.ddns.net:6310\") pour un appareil déjà connu — ATTENTION, JARVIS ne peut PAS créer cet accès lui-même : l'utilisateur doit avoir configuré une redirection de port (port forwarding) sur sa box/routeur vers cet appareil (idéalement avec un nom DDNS comme No-IP/DuckDNS puisque l'IP publique change souvent) ; explique-le clairement si l'utilisateur ne semble pas savoir comment faire, plutôt que de laisser croire que set_remote_access suffit à lui seul. Une fois configuré, network_ping/network_open_web/wake_on_lan basculent automatiquement dessus si l'appareil ne répond plus en local.\n• Accès à un partage réseau (disque dur Freebox une fois son partage SMB activé dans Freebox OS → Paramètres → Mode disque dur, ou tout NAS/PC partagé) : smb_configure{host,username?,password?} enregistre le serveur (ex: host=\"Freebox_Server\" ou son IP, username/password = compte SMB créé côté serveur, PAS l\'identifiant Freebox Connect) ; smb_list_files{share,path?} parcourt un dossier du partage (path vide = racine) ; smb_download_file{share,path} récupère un fichier précis sur le téléphone (ensuite utilisable par open_file/print_file/attach_contact_file comme n\'importe quel fichier local). C\'est un client SMB GÉNÉRIQUE (protocole standard), PAS l\'API propriétaire Freebox OS : ça donne accès aux FICHIERS du partage, PAS aux réglages internes de la Freebox (Wi-Fi de la box, etc.).\n• Freebox OS (accès complet, lecture ET écriture, si configurée par l'utilisateur dans ⚙ -> Domotique -> Freebox) : freebox_status (état de la connexion internet, débit montant/descendant, IP publique), freebox_devices{filter?} (liste les appareils connus du réseau local avec leur IP et s'ils sont actuellement actifs ; filter = nom/mot-clé optionnel), freebox_wifi_status (Wi-Fi de la Freebox activé ou non), freebox_wifi_set{enable} (active/désactive le Wi-Fi de la Freebox, PAS celui du téléphone), freebox_home_devices{filter?} (liste les appareils domotique Freebox Home — prises, volets, capteurs, alarme si équipé — avec leurs commandes disponibles), freebox_home_set{device,on?,value?} (contrôle un appareil Freebox Home par son nom : on=true/false pour une prise/simple interrupteur, ou value=nombre pour une position/intensité selon l'appareil — utilise freebox_home_devices avant pour connaître le nom exact et si l'appareil est pilotable). C'est l'API Freebox OS PROPRIÉTAIRE (distincte de l'accès SMB générique ci-dessus qui ne donne accès qu'aux FICHIERS) : contrôle réellement la box et ses appareils domotique.\n• IMPORTANT : le Wi-Fi et le Bluetooth du TÉLÉPHONE ne peuvent PAS être coupés silencieusement par une app (restriction de sécurité Android 10+/13+, aucune app ne peut contourner ça) — enable_wifi/disable_wifi/enable_bluetooth/disable_bluetooth ouvrent le panneau système, dis-le honnêtement si on te demande une coupure invisible du téléphone.\n• Bluetooth : bluetooth_info, enable_bluetooth, disable_bluetooth | Wi-Fi : wifi_info, enable_wifi, disable_wifi\n\nExemple : \"J'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

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
