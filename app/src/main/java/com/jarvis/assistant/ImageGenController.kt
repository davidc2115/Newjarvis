package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Génération d'images IA — essaie plusieurs fournisseurs en cascade pour
 * maximiser la fiabilité (si l'un échoue, essaie automatiquement le suivant) :
 *
 * 1. Google Gemini (Nano Banana) — si une clé Gemini est configurée.
 * 2. OpenAI gpt-image-1 — si une clé OpenAI est configurée. (DALL-E 2/3 ont été
 *    RETIRÉS de l'API OpenAI le 12 mai 2026 — tout appel avec l'ancien modèle
 *    échouait systématiquement ; c'était une cause réelle des échecs signalés.)
 * 3. Stable Diffusion (via la passerelle Hugging Face "Inference Providers",
 *    router.huggingface.co — l'ancien endpoint api-inference.huggingface.co
 *    n'est plus supporté par Hugging Face) — si un jeton Hugging Face est
 *    configuré (celui déjà utilisé pour les modèles locaux).
 * 4. Stable Diffusion EMBARQUÉ sur le téléphone (stable-diffusion.cpp compilé
 *    nativement, aucun réseau) — si un modèle a été importé dans les
 *    paramètres. ⚠️ Sans GPU dédié, compte plusieurs MINUTES par image sur
 *    CPU de téléphone, et peut échouer par manque de mémoire sur des appareils
 *    avec peu de RAM disponible — c'est la réalité du calcul de diffusion sur
 *    mobile, pas un défaut de l'intégration.
 * 5. AI Horde (aihorde.net, anciennement Stable Horde) — gratuit, SANS clé
 *    (accès anonyme officiel avec la clé publique documentée "0000000000"),
 *    DERNIER filet de secours uniquement. REMPLACE Pollinations (retiré à la
 *    demande de l'utilisateur suite à des rendus jugés trop flous) : AI Horde
 *    fait tourner de VRAIS modèles Stable Diffusion/SDXL sur des GPU de
 *    volontaires (pas un modèle dégradé propriétaire), donc un rendu
 *    généralement plus fidèle. ⚠️ HONNÊTETÉ, contrepartie réelle : les
 *    requêtes anonymes ont la PRIORITÉ LA PLUS BASSE dans leur file d'attente
 *    communautaire — la génération peut prendre de quelques secondes à
 *    plusieurs minutes selon la charge du moment, contrairement à Pollinations
 *    qui répondait quasi instantanément (mais avec un rendu régulièrement
 *    flou). Ce n'est utilisé qu'en tout dernier recours de toute façon, donc
 *    ce compromis vitesse/fiabilité est acceptable ici.
 *
 * Chaque échec (y compris ceux du SD embarqué) alimente un diagnostic partagé
 * affiché en cas d'échec total — un échec du SD local ne doit JAMAIS écraser
 * silencieusement les diagnostics des fournisseurs essayés avant lui (bug
 * réel corrigé : l'utilisateur ne voyait que "mémoire insuffisante" même
 * quand sa clé Gemini, pourtant valide, avait échoué pour une autre raison).
 *
 * ⚠️ Microsoft Copilot n'a PAS d'API publique de génération d'image
 * accessible aux applications tierces — impossible à intégrer honnêtement.
 *
 * L'image est sauvegardée dans Pictures/JARVIS-Generated et affichée
 * directement dans le chat.
 */
object ImageGenController {

    data class Result(val message: String, val base64: String?, val mime: String?, val savedPath: String? = null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // la génération d'image peut prendre du temps
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    // Prompt négatif standard, appliqué aux moteurs Stable Diffusion (Hugging Face, AI Horde)
    // qui l'acceptent — corrige la cause principale des rendus "abstraits/flous/déformés"
    // signalés : sans prompt négatif, un modèle SD de base part sans aucune contrainte
    // contre les artefacts classiques (anatomie ratée, mains difformes, flou, basse
    // résolution...). Gemini et OpenAI (meilleure qualité générale, en tête de la cascade)
    // n'ont pas ce paramètre — cette liste ne s'applique qu'aux fournisseurs SD.
    private const val NEGATIVE_PROMPT =
        "blurry, out of focus, low quality, low resolution, worst quality, jpeg artifacts, " +
            "deformed, disfigured, distorted, mutated, bad anatomy, extra limbs, missing limbs, " +
            "malformed hands, poorly drawn face, ugly, duplicate, watermark, signature, text, " +
            "cropped, abstract blob, noise"

    /** Normalise les synonymes FR/EN vers l'une des 3 valeurs canoniques utilisées ci-dessous. */
    private fun normalizeFormat(raw: String): String {
        val f = raw.lowercase().trim()
        return when {
            f.contains("portrait") || f.contains("vertical") || f.contains("story") || f.contains("9:16") || f.contains("3:4") -> "portrait"
            f.contains("paysage") || f.contains("landscape") || f.contains("horizontal") || f.contains("16:9") || f.contains("4:3") -> "paysage"
            else -> "carre"
        }
    }

    /** Gemini : ratio "l:h" — 1:1/3:4/4:3 confirmés supportés par gemini-3.1-flash-image. */
    private fun geminiAspectRatio(format: String): String = when (format) {
        "portrait" -> "3:4"
        "paysage" -> "4:3"
        else -> "1:1"
    }

    /** OpenAI gpt-image-1 : seules tailles acceptées par l'API — pas de valeurs arbitraires. */
    private fun openAiSize(format: String): String = when (format) {
        "portrait" -> "1024x1536"
        "paysage" -> "1536x1024"
        else -> "1024x1024"
    }

    /** Hugging Face SDXL : résolutions natives ~1MP, multiples de 64 (recommandation SDXL). */
    private fun hfDims(format: String): Pair<Int, Int> = when (format) {
        "portrait" -> 832 to 1216
        "paysage" -> 1216 to 832
        else -> 1024 to 1024
    }

    /** Stable Diffusion embarqué (CPU mobile) : dimensions modestes, multiples de 64. */
    private fun localSdDims(format: String): Pair<Int, Int> = when (format) {
        "portrait" -> 448 to 640
        "paysage" -> 640 to 448
        else -> 512 to 512
    }

    /** AI Horde : dimensions multiples de 64 (contrainte de l'API). */
    private fun aiHordeDims(format: String): Pair<Int, Int> = when (format) {
        "portrait" -> 512 to 768
        "paysage" -> 768 to 512
        else -> 512 to 512
    }

    /**
     * [format] choisit l'orientation de l'image parmi "carre" (défaut), "portrait" ou
     * "paysage" — accepte aussi des synonymes courants (vertical/horizontal/story/square...)
     * via normalizeFormat(). Avant ce paramètre, TOUS les fournisseurs généraient
     * uniquement en carré (512/1024x1024) quoi que l'utilisateur demande : le SYSTEM_PROMPT
     * incitait déjà l'IA à demander "portrait, paysage ou carré ?" mais la réponse n'avait
     * ensuite aucun effet réel — cause directe du signalement "impossible de choisir le
     * format de l'image". Chaque fournisseur reçoit désormais des dimensions/aspectRatio
     * dédiés, dans les limites qu'il accepte réellement.
     */
    suspend fun generateImage(context: Context, prompt: String, format: String = "carre"): Result {
        if (prompt.isBlank()) {
            return Result("❌ Aucune description d'image fournie.", null, null)
        }
        val fmt = normalizeFormat(format)

        // Diagnostic collecté au fil des tentatives — auparavant, un échec HTTP sur un
        // provider CONFIGURÉ (mauvaise clé, quota, erreur serveur...) était avalé
        // silencieusement pour passer au suivant ; si tous échouaient, l'utilisateur ne
        // voyait qu'un message générique "configure une clé" même quand une clé était
        // bel et bien configurée mais rejetée pour une raison précise (ex: HTTP 400/403).
        val diagnostics = mutableListOf<String>()

        // 1. Google Gemini, si une clé est configurée.
        tryGemini(context, prompt, fmt, diagnostics)?.let { return it }

        // 2. OpenAI DALL-E 3, si une clé est configurée.
        tryOpenAI(context, prompt, fmt, diagnostics)?.let { return it }

        // 3. Stable Diffusion via Hugging Face, si un jeton est configuré.
        tryHuggingFace(context, prompt, fmt, diagnostics)?.let { return it }

        // 4. Stable Diffusion embarqué sur le téléphone, si un modèle est importé.
        tryOnDeviceStableDiffusion(context, prompt, fmt, diagnostics)?.let { return it }

        // 5. AI Horde (gratuit, sans clé — accès anonyme officiel) — en tout dernier
        // recours seulement : c'est un cluster communautaire, les requêtes anonymes
        // passent en dernière priorité et peuvent prendre plusieurs minutes selon la
        // charge. Remplace Pollinations (qualité jugée insuffisante par l'utilisateur).
        tryAiHorde(context, prompt, fmt, diagnostics)?.let { return it }

        val detail = if (diagnostics.isNotEmpty()) {
            "\n\nDétail des échecs :\n" + diagnostics.joinToString("\n") { "• $it" }
        } else ""

        return Result(
            "❌ Échec de la génération d'image sur tous les moteurs disponibles " +
                "(Gemini, OpenAI, Hugging Face, Stable Diffusion embarqué, AI Horde). " +
                "Configure au moins une clé API dans ⚙ → Clés API, ou importe un modèle " +
                "Stable Diffusion local dans ⚙ → Modèles Locaux.$detail",
            null, null
        )
    }

    // ─── 4. Stable Diffusion EMBARQUÉ (stable-diffusion.cpp natif) ─────────────

    private fun tryOnDeviceStableDiffusion(context: Context, prompt: String, format: String, diagnostics: MutableList<String>): Result? {
        val modelPath = Prefs.getLocalSdModelPath(context)
        if (modelPath.isBlank()) return null

        // IMPORTANT : chaque échec ici ajoute au diagnostic PARTAGÉ et renvoie null (continue
        // vers le fournisseur suivant), au lieu de renvoyer directement un Result — avant ce
        // correctif, un échec du SD local écrasait silencieusement les diagnostics Gemini/
        // OpenAI/Hugging Face déjà collectés : l'utilisateur ne voyait QUE l'erreur du SD
        // local (ex: "mémoire insuffisante"), même quand une clé Gemini pourtant valide avait
        // échoué pour une tout autre raison plus haut dans la cascade — cause réelle du
        // symptôme "la génération échoue toujours sans qu'on comprenne pourquoi".
        if (!NativeStableDiffusion.isAvailable()) {
            diagnostics.add("Stable Diffusion embarqué : moteur non chargé sur cet appareil (${NativeStableDiffusion.getLoadError() ?: "bibliothèque native introuvable"})")
            return null
        }

        return try {
            val loaded = NativeStableDiffusion.loadModel(modelPath)
            if (!loaded) {
                diagnostics.add("Stable Diffusion embarqué : échec du chargement du modèle (vérifie qu'il est compatible .safetensors/.ckpt/.gguf)")
                return null
            }

            // Résolution modeste pour rester dans un temps raisonnable sur CPU mobile ; steps
            // relevé de 20 à 26 (compromis qualité/temps — le prompt négatif est géré côté
            // natif dans jarvis_sd_jni.cpp, voir ce fichier pour le détail). Dimensions selon
            // le format demandé (voir localSdDims) au lieu d'un carré fixe.
            val (width, height) = localSdDims(format)
            val steps = 26

            val rgbBytes = NativeStableDiffusion.generate(prompt, width, height, steps)
            if (rgbBytes == null) {
                diagnostics.add("Stable Diffusion embarqué : échec de la génération (mémoire insuffisante ou erreur interne)")
                return null
            }

            val channels = NativeStableDiffusion.getChannelCount()
            val bitmap = rgbBytesToBitmap(rgbBytes, width, height, channels)

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val pngBytes = out.toByteArray()

            val savedPath = saveToGallery(context, pngBytes, prompt)
            val base64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)

            Result(
                "🎨 Image générée pour « $prompt » (Stable Diffusion embarqué, 100% hors-ligne).\n📁 Enregistrée dans : $savedPath",
                base64,
                "image/png",
                savedPath
            )
        } catch (e: Exception) {
            diagnostics.add("Stable Diffusion embarqué : erreur — ${e.message}")
            null
        }
    }

    // ─── 5. AI Horde (gratuit, sans clé, dernier recours) ─────────────────────
    // Cluster communautaire de VRAIS modèles Stable Diffusion/SDXL — accès anonyme
    // officiel et documenté (clé publique "0000000000", aucune inscription requise).
    // Fonctionnement asynchrone : on soumet la demande, on interroge périodiquement
    // son statut jusqu'à ce qu'elle soit prête, puis on récupère le résultat — contrairement
    // aux autres fournisseurs de cette cascade qui répondent en un seul appel HTTP direct.
    // Documentation officielle : https://aihorde.net/api (endpoints generate/async,
    // generate/check/{id}, generate/status/{id}), vérifiée disponible et fonctionnelle.

    private suspend fun tryAiHorde(context: Context, prompt: String, format: String, diagnostics: MutableList<String>): Result? {
        return try {
            // Convention officielle AI Horde pour le prompt négatif : concaténé au prompt
            // positif via le séparateur " ### " (pas un champ JSON séparé — confirmé contre
            // l'implémentation de référence SillyTavern, un des plus gros clients AI Horde).
            // steps 30 (au lieu de 20) + karras=true (meilleur ordonnancement du bruit) :
            // corrige la principale cause des rendus flous/déformés en dernier recours.
            val submitBody = JSONObject()
                .put("prompt", "$prompt ### $NEGATIVE_PROMPT")
                .put(
                    "params",
                    JSONObject()
                        .put("width", aiHordeDims(format).first)
                        .put("height", aiHordeDims(format).second)
                        .put("steps", 30)
                        .put("cfg_scale", 7)
                        .put("sampler_name", "k_euler_a")
                        .put("karras", true)
                        .put("n", 1)
                )
                .put("nsfw", false)
                // r2=false : demande le résultat directement encodé en base64 dans la réponse
                // de statut, sans passer par un second téléchargement depuis un lien externe.
                .put("r2", false)
                .toString()
                .toRequestBody(JSON)

            val submitRequest = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/async")
                .addHeader("apikey", "0000000000") // accès anonyme officiel documenté par AI Horde
                .addHeader("Content-Type", "application/json")
                .post(submitBody)
                .build()

            val jobId = client.newCall(submitRequest).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    diagnostics.add("AI Horde : HTTP ${response.code} lors de la soumission — ${bodyStr.take(200)}")
                    return null
                }
                val id = JSONObject(bodyStr).optString("id")
                if (id.isBlank()) {
                    diagnostics.add("AI Horde : demande non acceptée — ${bodyStr.take(200)}")
                    return null
                }
                id
            }

            // Sondage périodique (la horde recommande d'éviter plus d'1 requête/seconde ; on
            // espace davantage par courtoisie) — budget total ~2 minutes, cohérent avec une
            // file d'attente anonyme (priorité la plus basse) qui peut être lente sans pour
            // autant faire attendre l'utilisateur indéfiniment sur un dernier recours gratuit.
            var done = false
            var faulted = false
            var attempts = 0
            while (attempts < 40 && !done && !faulted) {
                delay(3000)
                val checkRequest = Request.Builder()
                    .url("https://aihorde.net/api/v2/generate/check/$jobId")
                    .get().build()
                client.newCall(checkRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val checkJson = JSONObject(response.body?.string() ?: "")
                        done = checkJson.optBoolean("done", false)
                        faulted = checkJson.optBoolean("faulted", false)
                    }
                }
                attempts++
            }

            if (faulted) {
                diagnostics.add("AI Horde : la génération a échoué côté worker communautaire")
                return null
            }
            if (!done) {
                diagnostics.add(
                    "AI Horde : délai d'attente dépassé (file d'attente anonyme surchargée) — " +
                        "réessaie plus tard, ou crée un compte gratuit sur aihorde.net pour une priorité plus élevée"
                )
                return null
            }

            val statusRequest = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/status/$jobId")
                .get().build()

            client.newCall(statusRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    diagnostics.add("AI Horde : HTTP ${response.code} lors de la récupération du résultat")
                    return null
                }
                val statusJson = JSONObject(response.body?.string() ?: "")
                val first = statusJson.optJSONArray("generations")?.optJSONObject(0)
                val b64 = first?.optString("img")
                if (b64.isNullOrBlank()) {
                    diagnostics.add("AI Horde : réponse terminée mais sans image exploitable")
                    return null
                }
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val savedPath = saveToGallery(context, bytes, prompt)
                val model = first.optString("model", "Stable Diffusion")
                Result(
                    "🎨 Image générée pour « $prompt » (AI Horde — $model, cluster Stable Diffusion communautaire gratuit).\n📁 Enregistrée dans : $savedPath",
                    b64,
                    "image/png",
                    savedPath
                )
            }
        } catch (e: Exception) {
            diagnostics.add("AI Horde : exception réseau — ${e.message}")
            null
        }
    }

    private fun rgbBytesToBitmap(rgbBytes: ByteArray, width: Int, height: Int, channels: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val offset = i * channels
            val r = rgbBytes[offset].toInt() and 0xFF
            val g = rgbBytes[offset + 1].toInt() and 0xFF
            val b = rgbBytes[offset + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    // ─── 1. Google Gemini (Nano Banana) ────────────────────────────────────────

    private fun tryGemini(context: Context, prompt: String, format: String, diagnostics: MutableList<String>): Result? {
        val keys = Prefs.getApiKeysFor(context, Provider.GEMINI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val body = JSONObject()
                    .put(
                        "contents",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "parts",
                                org.json.JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put(
                                "responseModalities",
                                org.json.JSONArray().put("TEXT").put("IMAGE")
                            )
                            .put(
                                "imageConfig",
                                JSONObject().put("aspectRatio", geminiAspectRatio(format))
                            )
                    )
                    .toString()
                    .toRequestBody(JSON)

                val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "gemini-3.1-flash-image:generateContent?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code == 401) {
                            Prefs.markKeyFailed(context, Provider.GEMINI, apiKey)
                        }
                        diagnostics.add("Gemini : HTTP ${response.code} — ${bodyStr.take(200)}")
                        return@use // essaie la clé suivante s'il y en a une
                    }

                    val json = JSONObject(bodyStr)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        diagnostics.add("Gemini : réponse HTTP 200 sans « candidates » exploitable — ${bodyStr.take(200)}")
                        return@use
                    }
                    // L'API Gemini répond en camelCase ("inlineData"/"mimeType"), mais on
                    // vérifie aussi le snake_case par sécurité : une réponse HTTP 200 sans
                    // aucune image détectée à cause d'un nom de champ inattendu se traduisait
                    // auparavant par un échec silencieux, impossible à distinguer d'un vrai
                    // manque d'image dans la réponse.
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    if (parts == null) {
                        diagnostics.add("Gemini : réponse sans contenu exploitable — ${bodyStr.take(200)}")
                        return@use
                    }

                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                        val b64 = inlineData?.optString("data")
                        if (!b64.isNullOrBlank()) {
                            val mime = inlineData.optString("mimeType", inlineData.optString("mime_type", "image/png"))
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val savedPath = saveToGallery(context, bytes, prompt)
                            return Result(
                                "🎨 Image générée pour « $prompt » (Google Gemini).\n📁 Enregistrée dans : $savedPath",
                                b64,
                                mime,
                                savedPath
                            )
                        }
                    }
                    diagnostics.add("Gemini : réponse reçue mais aucune image dans les parts (texte seul renvoyé ?) — ${bodyStr.take(200)}")
                }
            } catch (e: Exception) {
                diagnostics.add("Gemini : exception réseau — ${e.message}")
            }
        }
        return null
    }

    // ─── 2. OpenAI (gpt-image-1) ────────────────────────────────────────────────
    // DALL-E 2 et DALL-E 3 ont été retirés de l'API OpenAI le 12 mai 2026 — tout appel
    // avec model="dall-e-3" échoue désormais systématiquement (404/erreur de modèle
    // inconnu). C'était une cause RÉELLE et vérifiée des échecs de génération d'image
    // signalés à répétition, pas un problème côté app. Migré vers "gpt-image-1", le
    // modèle actuel recommandé par OpenAI (gpt-image-2 existe aussi mais gpt-image-1
    // reste supporté jusqu'à fin 2026 ; le paramètre response_format n'existe plus sur
    // ces modèles — ils renvoient TOUJOURS du base64 dans data[0].b64_json).

    private fun tryOpenAI(context: Context, prompt: String, format: String, diagnostics: MutableList<String>): Result? {
        val keys = Prefs.getApiKeysFor(context, Provider.OPENAI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val body = JSONObject()
                    .put("model", "gpt-image-1")
                    .put("prompt", prompt)
                    .put("n", 1)
                    .put("size", openAiSize(format))
                    .toString()
                    .toRequestBody(JSON)

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/images/generations")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code == 401) {
                            Prefs.markKeyFailed(context, Provider.OPENAI, apiKey)
                        }
                        diagnostics.add("OpenAI : HTTP ${response.code} — ${bodyStr.take(200)}")
                        return@use // essaie la clé OpenAI suivante s'il y en a une
                    }

                    val json = JSONObject(bodyStr)
                    val dataArr = json.optJSONArray("data")
                    val b64 = dataArr?.optJSONObject(0)?.optString("b64_json")
                    if (b64.isNullOrBlank()) {
                        diagnostics.add("OpenAI : réponse HTTP 200 sans image encodée — ${bodyStr.take(200)}")
                        return@use
                    }

                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val savedPath = saveToGallery(context, bytes, prompt)
                    return Result(
                        "🎨 Image générée pour « $prompt » (OpenAI gpt-image-1).\n📁 Enregistrée dans : $savedPath",
                        b64,
                        "image/png",
                        savedPath
                    )
                }
            } catch (e: Exception) {
                diagnostics.add("OpenAI : exception réseau — ${e.message}")
            }
        }
        return null
    }

    // ─── 3. Stable Diffusion via Hugging Face Inference Providers ─────────────
    // L'ancien endpoint "api-inference.huggingface.co" n'est PLUS supporté par
    // Hugging Face (confirmé : renvoie une erreur invitant à migrer) — remplacé par
    // leur passerelle "Inference Providers" sur router.huggingface.co. C'était la
    // deuxième cause réelle et vérifiée des échecs de génération d'image en cascade.

    private fun tryHuggingFace(context: Context, prompt: String, format: String, diagnostics: MutableList<String>): Result? {
        val token = Prefs.getHfToken(context)
        if (token.isBlank()) return null

        return try {
            // Paramètres explicites (guidance_scale, num_inference_steps, negative_prompt,
            // résolution native SDXL 1024x1024) plutôt que de laisser la passerelle sur ses
            // valeurs par défaut — c'était la cause principale des rendus "abstraits/flous/
            // déformés" signalés : sans prompt négatif ni nombre d'étapes suffisant, un modèle
            // SDXL de base produit facilement ce type d'artefact. Schéma confirmé par la doc
            // officielle Hugging Face Inference Providers (task text-to-image).
            val body = JSONObject()
                .put("inputs", prompt)
                .put(
                    "parameters",
                    JSONObject()
                        .put("negative_prompt", NEGATIVE_PROMPT)
                        .put("num_inference_steps", 30)
                        .put("guidance_scale", 7.5)
                        .put("width", hfDims(format).first)
                        .put("height", hfDims(format).second)
                )
                .toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("https://router.huggingface.co/hf-inference/models/stabilityai/stable-diffusion-xl-base-1.0")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""
                if (!response.isSuccessful || !contentType.startsWith("image/")) {
                    val bodyPreview = if (!response.isSuccessful) response.body?.string()?.take(200) else "Content-Type inattendu: $contentType"
                    diagnostics.add("Hugging Face : HTTP ${response.code} — $bodyPreview")
                    return null
                }

                val bytes = response.body?.bytes() ?: return null
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val savedPath = saveToGallery(context, bytes, prompt)
                Result(
                    "🎨 Image générée pour « $prompt » (Stable Diffusion XL).\n📁 Enregistrée dans : $savedPath",
                    base64,
                    "image/jpeg",
                    savedPath
                )
            }
        } catch (e: Exception) {
            diagnostics.add("Hugging Face : exception réseau — ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToGallery(context: Context, bytes: ByteArray, prompt: String): String {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "JARVIS-Generated"
            ).also { it.mkdirs() }

            val safePrompt = prompt.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val fileName = "${fileDateFormat.format(Date())}_$safePrompt.png"
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            // BUG REEL CORRIGE : ecrire via File/writeBytes n'informe pas MediaStore -- l'image
            // existe bien sur le disque mais restait invisible dans Galerie/Photos tant qu'un
            // scan media spontane n'avait pas lieu (meme correctif applique dans
            // JarvisCommandParser.logFileRecord pour les fichiers bureautiques). On declenche
            // l'indexation tout de suite, sans attendre.
            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            } catch (e: Exception) { /* non bloquant */ }
            file.absolutePath
        } catch (e: Exception) {
            "(échec de la sauvegarde locale : ${e.message})"
        }
    }
}
