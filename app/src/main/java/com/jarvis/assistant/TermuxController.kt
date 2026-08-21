package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stable Diffusion WebUI (AUTOMATIC1111) piloté via Termux, en local sur le téléphone.
 *
 * Objectif : proposer une génération d'image 100% locale (pas de clé API cloud, pas de limite,
 * fidélité totale au prompt) pour les utilisateurs prêts à faire l'installation manuelle requise
 * côté Termux — JARVIS ne peut PAS rendre ça "totalement autonome" malgré la demande initiale en
 * ce sens : Android interdit à une app tierce d'écrire dans le sandbox d'une autre app (impossible
 * d'éditer termux.properties depuis JARVIS), et l'utilisateur doit accorder la permission
 * RUN_COMMAND lui-même (Android ne permet pas de l'auto-accorder). Ce contrôleur fait donc tout ce
 * qui est techniquement possible depuis l'extérieur, et guide explicitement l'utilisateur pour le
 * reste — jamais de simulation silencieuse d'une étape qui n'a pas réellement eu lieu.
 *
 * Prérequis, TOUS côté utilisateur (voir setupInstructions()) :
 *   1. Termux installé (F-Droid uniquement — la version Play Store est abandonnée/cassée).
 *   2. Dans Termux : `echo "allow-external-apps=true" >> ~/.termux/termux.properties` puis
 *      `termux-reload-settings` — JARVIS ne peut pas écrire ce fichier lui-même.
 *   3. Permission Android "Exécuter des commandes" accordée à Termux pour JARVIS (déclenchée par
 *      requestPermission(), mais la validation finale se fait dans les réglages Android).
 *
 * Une fois ces 3 prérequis remplis, setupAndLaunch() envoie un script d'installation +
 * lancement du WebUI via l'intent RUN_COMMAND (com.termux.RUN_COMMAND) EN SESSION VISIBLE
 * (EXTRA_COMMAND_BACKGROUND=false) plutôt qu'en arrière-plan pur : signalement utilisateur
 * "1 task en cours mais rien ne s'affiche dans Termux" — un envoi en RUN_COMMAND_BACKGROUND=true
 * (comme codé initialement) ne crée AUCUNE session Termux visible par conception (doc officielle
 * github.com/termux/termux-app/wiki/RUN_COMMAND-Intent), donc rien à voir en ouvrant Termux
 * n'était PAS un bug côté JARVIS mais le comportement documenté d'un envoi "background" — juste
 * inexploitable pour un utilisateur qui veut une preuve visuelle que ça tourne (et un diagnostic
 * en cas d'échec réseau/install pendant le téléchargement du modèle ~4 Go). En session visible,
 * Termux s'ouvre et affiche le script s'exécuter en direct ; sur Android >= 10 ceci peut être
 * bloqué tant que l'utilisateur n'a pas tapé sur la notification Termux (restriction Android de
 * démarrage d'activité depuis l'arrière-plan, documentée par Termux) — relayé explicitement dans
 * le message de succès plus bas plutôt que supposé acquis.
 * checkWebuiStatus() / generateImage() dialoguent ensuite en HTTP pur avec l'API REST du WebUI
 * (127.0.0.1:7860) — ce sont les 2 seuls mécanismes 100% documentés et fiables ; le canal de
 * retour de résultat de RUN_COMMAND (PendingIntent) a été délibérément écarté car ses clés
 * d'extras exactes ne sont pas publiquement documentées avec certitude (voir décision dans le
 * suivi de tâche #73 — mieux vaut ce contrôle HTTP fiable qu'un parsing de résultat qui pourrait
 * silencieusement échouer).
 *
 * CAUSE RÉELLE TROUVÉE ET CORRIGÉE : "WebUI toujours injoignable" malgré les correctifs
 * précédents (session visible, dpkg résilient) — le vrai blocage était ~/webui.log :
 * "Could not find a version that satisfies the requirement torch==2.1.2" / "No matching
 * distribution found". Ce n'est PAS un souci de version de Python (le message officiel de
 * launch.py qui suggère Python 3.10 est trompeur ici) : Termux tourne sur la libc Android
 * (bionic), alors que TOUS les wheels PyTorch publiés sur PyPI/download.pytorch.org sont
 * compilés pour glibc (manylinux) — aucune combinaison de version de Python ne peut réconcilier
 * les deux, le wheel n'existe simplement pas pour cette plateforme. Le paquet natif Termux
 * `python-torch` existe mais reste connu pour être instable/cassé au fil des mises à jour de
 * Termux (voir issues termux/termux-packages #20158, #21188, #25996 — non fiable). La solution
 * qui fonctionne réellement, confirmée par plusieurs guides communautaires à jour (ex: RVC/Applio
 * sur Termux) : installer un VRAI environnement Ubuntu (glibc) à l'intérieur de Termux via
 * `proot-distro` (github.com/termux/proot-distro, doc officielle vérifiée directement), et faire
 * tourner AUTOMATIC1111 entièrement à l'intérieur — là, pip installe les wheels PyTorch standard
 * sans problème. proot n'isole PAS le réseau (pas de vrai network namespace sans root), donc
 * 127.0.0.1:7860 à l'intérieur du conteneur Ubuntu reste exactement le même socket que celui vu
 * par Termux et par JARVIS — checkWebuiStatus()/generateImage() n'ont donc besoin d'AUCUN
 * changement. Limite honnête à annoncer : sur un téléphone (pas un PC), le WebUI seul consomme
 * déjà environ 2 Go de RAM rien que pour démarrer, avant même de charger un modèle — performances
 * et faisabilité dépendent fortement du téléphone (RAM/CPU/stockage libre).
 *
 * DEUXIÈME CAUSE RÉELLE TROUVÉE ET CORRIGÉE (signalement utilisateur : installation Termux/Ubuntu
 * terminée sans erreur visible, mais WebUI toujours injoignable, "Failed to connect to
 * /127.0.0.1:7860") : le script précédent laissait `python3 launch.py` comme toute dernière
 * commande sous `set -e` — quand le processus était tué par l'OOM killer d'Android pendant le
 * chargement du modèle (précision complète = 4 à 8 Go de RAM, très facile à dépasser sur
 * téléphone), le trap ERR imprimait juste une ligne générique "ECHEC ligne N" avant que le script
 * ne se termine, indiscernable pour l'utilisateur d'une vraie erreur d'installation — d'où
 * l'impression que "tout s'est bien passé" alors que le serveur n'avait en réalité jamais fini de
 * démarrer. Le script capture désormais explicitement le code de sortie de launch.py et, en cas
 * de code 137 (SIGKILL — signature typique d'un OOM kill), imprime un diagnostic explicite et
 * actionnable au lieu du message générique. Ajout aussi de `--nowebui` (API REST uniquement, pas
 * d'interface Gradio) : JARVIS n'appelle jamais l'UI HTML, seulement `/sdapi/v1/...`, donc ce
 * flag réduit la mémoire/temps de démarrage sans rien retirer d'utile.
 *
 * TROISIÈME CAUSE RÉELLE TROUVÉE ET CORRIGÉE (signalement utilisateur : "~/jarvis_sd_install.log
 * PERMISSION DENIED") : le script redirigeait toute sa sortie vers ce fichier via `tee -a` (mode
 * AJOUT) dès sa toute première commande — si un fichier de ce nom existait déjà avec des
 * permissions incompatibles (reliquat d'un essai antérieur à une version plus ancienne du script,
 * ou touché depuis un autre contexte), `tee -a` échouait immédiatement et TOUT le reste du script
 * ne s'exécutait jamais, sans qu'aucun message utile n'apparaisse (la redirection elle-même est ce
 * qui échoue). Le script supprime désormais ce fichier avant de rediriger dessus, repartant à
 * chaque installation d'un fichier neuf appartenant forcément au bon processus.
 *
 * QUATRIÈME CAUSE RÉELLE TROUVÉE ET CORRIGÉE (signalement utilisateur, log : "error:
 * externally-managed-environment ... Couldn't install torch") : Ubuntu 24.04 (utilisé dans
 * proot-distro) embarque Python 3.12, qui applique par défaut la politique PEP 668 — pip refuse
 * tout install système (pip install torch...) tant qu'un venv n'est pas utilisé OU que
 * --break-system-packages n'est pas passé explicitement. launch.py de stable-diffusion-webui
 * installe torch via pip system-wide en interne (pas de venv actif dans ce trace), donc bloqué
 * immédiatement, avant même de tenter le téléchargement de PyTorch. Le script exporte désormais
 * PIP_BREAK_SYSTEM_PACKAGES=1 avant de lancer launch.py — pip reconnaît cette variable
 * d'environnement comme équivalent exact de --break-system-packages pour TOUS ses appels internes
 * (y compris ceux faits par launch.py lui-même, qu'on ne peut pas modifier directement), sans
 * effet sur les systèmes où PEP 668 n'est pas appliqué (no-op inoffensif).
 */
object TermuxController {

    data class Result(val success: Boolean, val message: String)

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    // internal (pas private) : SettingsActivity en a besoin pour déclencher la VRAIE demande
    // de permission au runtime via ActivityResultContracts.RequestPermission() — voir le bug
    // réel corrigé documenté sur termuxPermissionLauncher dans SettingsActivity.kt.
    internal const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val WEBUI_BASE_URL = "http://127.0.0.1:7860"
    private const val NEGATIVE_PROMPT_DEFAULT =
        "low quality, blurry, deformed, bad anatomy, watermark, text, signature"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // une génération txt2img peut prendre 1-2 min sur mobile
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Script bash idempotent : réinstallable sans effet de bord si déjà en place. Le choix du
    // modèle (SD 1.5 pruned-emaonly, ~4 Go) est fixe et son nom de fichier vérifié par existence
    // plutôt qu'un pattern glob fragile, pour ne jamais retélécharger inutilement.
    // Rendu résilient suite aux échecs réels signalés en session (verrou dpkg tenu par un
    // apt bloqué, dpkg laissé "interrupted" après un force-stop, "l'installation démarre puis
    // s'arrête" sans que le message d'erreur reste visible une fois le terminal fermé/scrollé) :
    //   - dpkg --configure -a en tout premier : se répare seul si une install précédente a été
    //     interrompue, au lieu d'exiger que l'utilisateur le fasse manuellement à chaque fois.
    //   - toute la sortie est dupliquée vers ~/jarvis_sd_install.log (tee) : même si le
    //     terminal se ferme ou scrolle, `cat ~/jarvis_sd_install.log` dans Termux retrouve
    //     TOUJOURS le détail complet, y compris après le fait.
    //   - trap ERR : imprime une ligne de résumé explicite (numéro de ligne + commande exacte
    //     qui a échoué) juste avant que `set -e` n'arrête le script, plutôt que de laisser
    //     l'utilisateur deviner où ça s'est arrêté.
    //   - wget --tries=3 --continue : reprend un téléchargement interrompu du modèle (~4 Go)
    //     au lieu de tout reperdre sur un simple accroc réseau.
    // RÉÉCRITURE (proot-distro Ubuntu) suite au vrai log d'échec obtenu de l'utilisateur : voir
    // le commentaire de classe plus haut pour la cause exacte (torch n'a aucun wheel compatible
    // avec la libc Android de Termux, quelle que soit la version de Python). Le script installe
    // désormais un vrai Ubuntu (glibc) via proot-distro puis y installe/lance AUTOMATIC1111
    // ENTIÈREMENT à l'intérieur — c'est là, et seulement là, que "pip install torch" trouve un
    // wheel compatible. Étapes visibles dans l'ordre (le terminal reste occupé tant que le
    // WebUI tourne, c'est volontaire — voir le message de succès plus bas) :
    //   1. pkg install proot-distro (Termux) puis proot-distro install ubuntu:24.04 (une seule
    //      fois, ~700 Mo — `|| true` pour rester idempotent si déjà installé, même logique que
    //      dpkg --configure -a plus haut).
    //   2. À L'INTÉRIEUR du conteneur (proot-distro login ubuntu -- bash -c '...') : paquets
    //      apt (python3/venv/pip/git/wget/libgl1 — libgl1 requis par opencv-python, dépendance
    //      de stable-diffusion-webui, sinon ImportError: libGL.so.1 au lancement), clonage du
    //      dépôt, téléchargement du modèle, puis lancement de launch.py EN PREMIER PLAN (pas de
    //      nohup/background ici : sous proot, un process détaché du shell qui l'a lancé peut être
    //      tué avec lui selon comment le shell parent se termine — le pattern documenté qui
    //      fonctionne réellement, y compris dans les guides communautaires vérifiés, est de
    //      laisser tourner au premier plan et garder Termux ouvert).
    // Sous-script interne entre guillemets SIMPLES (pas doubles) : aucune expansion de variable
    // n'est nécessaire dedans, donc aucun risque que le bash EXTÉRIEUR (Termux) interprète un
    // $ avant que ça n'atteigne le bash INTÉRIEUR (Ubuntu) — évite tout un niveau d'échappement
    // imbriqué fragile.
    private const val SETUP_SCRIPT = """
set -e
rm -f ~/jarvis_sd_install.log 2>/dev/null || true
touch ~/jarvis_sd_install.log
exec > >(tee -a ~/jarvis_sd_install.log) 2>&1
trap 'echo "=== ECHEC ligne ${'$'}LINENO : ${'$'}BASH_COMMAND — detail complet dans ~/jarvis_sd_install.log ==="' ERR
echo "=== Debut installation : $(date) ==="
dpkg --configure -a || true
pkg update -y
pkg install -y proot-distro
echo "=== Installation environnement Ubuntu (proot-distro), une seule fois si pas deja fait (~700 Mo) : $(date) ==="
proot-distro install ubuntu:24.04 || true
echo "=== Ubuntu pret. Installation Python/PyTorch + WebUI a l'interieur (peut prendre longtemps au 1er lancement) : $(date) ==="
proot-distro login ubuntu -- bash -c 'set -e
apt-get update -y
apt-get install -y python3 python3-venv python3-pip git wget libgl1 libglib2.0-0
export PIP_BREAK_SYSTEM_PACKAGES=1
cd ~
if [ ! -d stable-diffusion-webui ]; then
  git clone --depth 1 https://github.com/AUTOMATIC1111/stable-diffusion-webui.git
fi
cd stable-diffusion-webui
mkdir -p models/Stable-diffusion
if [ ! -f models/Stable-diffusion/v1-5-pruned-emaonly.safetensors ]; then
  wget --tries=3 --continue -O models/Stable-diffusion/v1-5-pruned-emaonly.safetensors https://huggingface.co/runwayml/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors
fi
echo "=== Lancement du WebUI (1er lancement seulement : installation de PyTorch et dependances Python, peut prendre 10 a 30+ minutes) ==="
set +e
python3 launch.py --api --nowebui --listen --port 7860 --skip-torch-cuda-test --no-half --precision full
CODE=${'$'}?
if [ ${'$'}CODE -ne 0 ]; then
  echo "=== ARRET DU WEBUI (code sortie ${'$'}CODE), le terminal va revenir a l invite. ==="
  if [ ${'$'}CODE -eq 137 ]; then
    echo "=== CAUSE PROBABLE : memoire insuffisante (OOM), le systeme Android a tue le processus pendant le chargement du modele. Limite materielle du telephone, pas un bug JARVIS : precision complete = 4 a 8 Go de RAM necessaires. A essayer : fermer toutes les autres applications avant de relancer, redemarrer le telephone pour liberer de la RAM, ou utiliser un appareil avec plus de RAM (8 Go recommandes). ==="
  else
    echo "=== Voir le detail complet dans ~/jarvis_sd_install.log (dernieres lignes avant cet arret) pour la cause exacte. ==="
  fi
fi
exit ${'$'}CODE'
"""

    /** true si le paquet Termux (F-Droid) est installé sur l'appareil. */
    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** true si JARVIS a déjà obtenu la permission Android d'envoyer des commandes à Termux. */
    fun hasRunCommandPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Texte explicatif complet des 3 étapes manuelles requises côté utilisateur, affiché dans les
     * Réglages et renvoyé par setupAndLaunch()/termux_sd_setup quand un prérequis manque encore —
     * jamais de tentative silencieuse de contourner une étape non remplie.
     */
    fun setupInstructions(): String = """
🔧 Configuration Stable Diffusion local (via Termux) — 3 étapes, à faire une seule fois :

1️⃣ Installe **Termux** depuis F-Droid (PAS le Play Store, version abandonnée) : f-droid.org/packages/com.termux

2️⃣ Ouvre Termux et tape :
`echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`
(JARVIS ne peut pas écrire ce fichier lui-même — c'est une protection Android entre apps.)

3️⃣ Autorise JARVIS à envoyer des commandes à Termux : Réglages Android → Apps → JARVIS → Autorisations → Autorisations supplémentaires → « Exécuter des commandes ». Le bouton ci-dessous ouvre cet écran directement.

Une fois les 3 étapes faites, appuie sur « Configurer Stable Diffusion (Termux) » : JARVIS installe un vrai environnement Ubuntu à l'intérieur de Termux (proot-distro) puis y installe et lance AUTOMATIC1111 — seule méthode qui fonctionne réellement sur Android (Termux seul ne peut pas installer PyTorch, incompatibilité de bibliothèque système, pas un bug JARVIS). Prévoir : au moins 8 Go d'espace de stockage libre, une connexion Wi-Fi, et de la patience (Ubuntu + dépendances + modèle ~4 Go + PyTorch au premier lancement : facilement 15 à 40 minutes selon le téléphone et la connexion). Le terminal Termux doit rester ouvert tant que tu veux que le serveur reste disponible.
    """.trimIndent()

    /** Ouvre l'écran Android des permissions de l'app, pour l'étape 3 de setupInstructions(). */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Envoie le script d'installation + lancement du WebUI à Termux, en arrière-plan (pas de
     * session interactive ouverte). Vérifie d'abord les prérequis un par un pour renvoyer un
     * message d'erreur précis plutôt qu'un échec silencieux du RUN_COMMAND.
     */
    fun setupAndLaunch(context: Context): Result {
        if (!isTermuxInstalled(context)) {
            DiagnosticsLog.log(context, "TERMUX_SD", "setupAndLaunch refusé : Termux non installé.")
            return Result(false, "❌ Termux n'est pas installé.\n\n${setupInstructions()}")
        }
        if (!hasRunCommandPermission(context)) {
            DiagnosticsLog.log(context, "TERMUX_SD", "setupAndLaunch refusé : permission RUN_COMMAND absente côté Android.")
            return Result(
                false,
                "❌ JARVIS n'a pas encore la permission « Exécuter des commandes » pour Termux.\n\n${setupInstructions()}"
            )
        }

        return try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
                putExtra(EXTRA_COMMAND_ARGUMENTS, arrayOf("-c", SETUP_SCRIPT))
                putExtra(EXTRA_COMMAND_BACKGROUND, false)
                putExtra(EXTRA_COMMAND_SESSION_ACTION, "0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            DiagnosticsLog.log(context, "TERMUX_SD", "Commande d'installation envoyée à Termux (RUN_COMMAND accepté par Android, pas de garantie que Termux l'exécute réellement si allow-external-apps=false côté termux.properties).")
            Result(
                true,
                "✅ Installation envoyée à Termux. Termux devrait s'ouvrir automatiquement et afficher " +
                    "le script s'exécuter en direct, en plusieurs étapes visibles dans cet ordre : " +
                    "mise à jour Termux, installation d'un environnement Ubuntu complet (proot-distro, " +
                    "~700 Mo, uniquement au tout premier lancement), puis À L'INTÉRIEUR de cet Ubuntu : " +
                    "paquets Python, clonage du dépôt, téléchargement du modèle (~4 Go), puis " +
                    "installation de PyTorch et démarrage du serveur. Si RIEN ne s'ouvre, une " +
                    "notification « Termux » est probablement apparue dans le volet de notifications " +
                    "Android (restriction Android empêchant l'ouverture automatique depuis " +
                    "l'arrière-plan) — appuie dessus pour voir la session. IMPORTANT (différent " +
                    "d'avant) : contrairement à une installation classique, le terminal Termux NE " +
                    "reviendra PAS à l'invite de commande une fois prêt — le WebUI tourne au premier " +
                    "plan et le terminal doit rester ouvert tant qu'il doit répondre ; le signal de " +
                    "succès est la ligne « Running on local URL » qui apparaît dans le terminal, PAS " +
                    "un retour d'invite. Premier lancement particulièrement long (Ubuntu + PyTorch : " +
                    "15 à 40 minutes selon le téléphone et la connexion), les suivants seront " +
                    "beaucoup plus rapides. Utilise « Vérifier le statut » (test réseau réel) pour " +
                    "confirmer que le WebUI répond, plutôt que de te fier à l'état du terminal. Si une " +
                    "erreur s'affiche dans Termux avant la fin, c'est la cause exacte du souci — " +
                    "relis-la directement, ou si le terminal s'est fermé/scrollé, tape « cat " +
                    "~/jarvis_sd_install.log » dans Termux pour retrouver le détail complet même " +
                    "après coup."
            )
        } catch (e: Exception) {
            DiagnosticsLog.log(context, "TERMUX_SD", "Échec de l'envoi RUN_COMMAND : ${e.javaClass.simpleName} — ${e.message}")
            Result(false, "❌ Échec de l'envoi de la commande à Termux : ${e.javaClass.simpleName} — ${e.message}\n\n${setupInstructions()}")
        }
    }

    /** Ping l'API REST du WebUI en local — seul moyen fiable de savoir si le serveur tourne. */
    suspend fun checkWebuiStatus(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$WEBUI_BASE_URL/sdapi/v1/sd-models").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result(false, "⏳ WebUI pas encore prêt (HTTP ${response.code}). Réessaie dans quelques instants.")
                }
                val bodyStr = response.body?.string() ?: "[]"
                val models = JSONArray(bodyStr)
                if (models.length() == 0) {
                    Result(false, "⚠️ WebUI répond mais aucun modèle chargé.")
                } else {
                    Result(true, "✅ Stable Diffusion local est prêt (${models.length()} modèle(s) disponible(s)).")
                }
            }
        } catch (e: Exception) {
            Result(
                false,
                "⏳ WebUI injoignable (${e.message}). Vérifie qu'il a été lancé via « Configurer Stable Diffusion » " +
                    "et attends la fin du premier démarrage. Si le terminal Termux est revenu à l'invite de " +
                    "commande ($) au lieu de rester bloqué sur le serveur en cours d'exécution, le processus " +
                    "s'est arrêté — cause la plus fréquente sur téléphone : mémoire insuffisante (OOM) pendant " +
                    "le chargement du modèle. Tape « cat ~/jarvis_sd_install.log » dans Termux et regarde les " +
                    "toutes dernières lignes : si tu vois « ARRET DU WEBUI » avec un diagnostic OOM, ferme " +
                    "toutes les autres applications puis relance l'installation."
            )
        }
    }

    /**
     * Résultat brut d'une génération txt2img : soit les octets PNG/JPEG décodés (succès), soit un
     * message d'erreur — délibérément séparé du type ImageGenController.Result (qui embarque déjà
     * la sauvegarde galerie + le message final formaté) pour laisser ImageGenController.tryTermuxWebui
     * décider lui-même du wording et de l'ajout aux diagnostics partagés de la cascade, exactement
     * comme les autres fournisseurs (tryGemini, tryOpenAI, ...).
     */
    data class RawImageResult(val success: Boolean, val bytes: ByteArray?, val error: String?)

    /** Génération txt2img via l'API REST locale du WebUI (127.0.0.1:7860/sdapi/v1/txt2img). */
    suspend fun generateImage(context: Context, prompt: String, format: String): RawImageResult =
        withContext(Dispatchers.IO) {
            val (width, height) = dimsFor(format)
            try {
                val body = JSONObject()
                    .put("prompt", prompt)
                    .put("negative_prompt", NEGATIVE_PROMPT_DEFAULT)
                    .put("width", width)
                    .put("height", height)
                    .put("steps", 25)
                    .put("cfg_scale", 7)
                    .toString()
                    .toRequestBody(JSON)

                val request = Request.Builder()
                    .url("$WEBUI_BASE_URL/sdapi/v1/txt2img")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext RawImageResult(
                            false, null,
                            "HTTP ${response.code} — ${response.body?.string()?.take(200)}"
                        )
                    }
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val images = json.optJSONArray("images")
                    val b64 = images?.optString(0)
                    if (b64.isNullOrBlank()) {
                        return@withContext RawImageResult(false, null, "réponse sans image exploitable")
                    }
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    RawImageResult(true, bytes, null)
                }
            } catch (e: Exception) {
                RawImageResult(false, null, "exception réseau — ${e.message}")
            }
        }

    private fun dimsFor(format: String): Pair<Int, Int> = when (format.lowercase()) {
        "portrait" -> 512 to 768
        "paysage" -> 768 to 512
        else -> 512 to 512
    }
}
