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
 * lancement du WebUI en arrière-plan via l'intent RUN_COMMAND (com.termux.RUN_COMMAND), et
 * checkWebuiStatus() / generateImage() dialoguent ensuite en HTTP pur avec l'API REST du WebUI
 * (127.0.0.1:7860) — ce sont les 2 seuls mécanismes 100% documentés et fiables ; le canal de
 * retour de résultat de RUN_COMMAND (PendingIntent) a été délibérément écarté car ses clés
 * d'extras exactes ne sont pas publiquement documentées avec certitude (voir décision dans le
 * suivi de tâche #73 — mieux vaut ce contrôle HTTP fiable qu'un parsing de résultat qui pourrait
 * silencieusement échouer).
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
    private const val SETUP_SCRIPT = """
set -e
pkg update -y
pkg install -y python git wget libjpeg-turbo
cd ~
if [ ! -d stable-diffusion-webui ]; then
  git clone --depth 1 https://github.com/AUTOMATIC1111/stable-diffusion-webui.git
fi
cd stable-diffusion-webui
mkdir -p models/Stable-diffusion
if [ ! -f models/Stable-diffusion/v1-5-pruned-emaonly.safetensors ]; then
  wget -O models/Stable-diffusion/v1-5-pruned-emaonly.safetensors \
    https://huggingface.co/runwayml/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors
fi
nohup python launch.py --api --listen --port 7860 --skip-torch-cuda-test --no-half --lowvram > ~/webui.log 2>&1 &
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

Une fois les 3 étapes faites, appuie sur « Configurer Stable Diffusion (Termux) » : JARVIS installera et lancera automatiquement le serveur (premier lancement plus long : téléchargement d'un modèle ~4 Go).
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
            return Result(false, "❌ Termux n'est pas installé.\n\n${setupInstructions()}")
        }
        if (!hasRunCommandPermission(context)) {
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
                putExtra(EXTRA_COMMAND_BACKGROUND, true)
                putExtra(EXTRA_COMMAND_SESSION_ACTION, "0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Result(
                true,
                "✅ Installation lancée dans Termux (arrière-plan). Premier lancement : plusieurs " +
                    "minutes (téléchargement du modèle ~4 Go selon ta connexion). Utilise « Vérifier " +
                    "le statut » pour savoir quand c'est prêt."
            )
        } catch (e: Exception) {
            Result(false, "❌ Échec de l'envoi de la commande à Termux : ${e.message}\n\n${setupInstructions()}")
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
            Result(false, "⏳ WebUI injoignable (${e.message}). Vérifie qu'il a été lancé via « Configurer Stable Diffusion » et attends la fin du premier démarrage.")
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
