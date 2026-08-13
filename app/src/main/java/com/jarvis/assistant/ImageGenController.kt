package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Base64
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
 * 5. Pollinations AI (gratuit, sans clé) — réintégré à la demande de
 *    l'utilisateur comme DERNIER filet de secours uniquement. ⚠️ HONNÊTETÉ :
 *    leur service a déjà traversé une période de qualité dégradée reconnue
 *    par Pollinations eux-mêmes (issue GitHub #5372) — impossible de garantir
 *    un rendu ou une compréhension du prompt identiques à Gemini, ce n'est
 *    pas la même IA ni le même niveau de qualité, quel que soit le réglage
 *    côté JARVIS. Il vaut mieux que rien, pas un substitut équivalent.
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

    suspend fun generateImage(context: Context, prompt: String): Result {
        if (prompt.isBlank()) {
            return Result("❌ Aucune description d'image fournie.", null, null)
        }

        // Diagnostic collecté au fil des tentatives — auparavant, un échec HTTP sur un
        // provider CONFIGURÉ (mauvaise clé, quota, erreur serveur...) était avalé
        // silencieusement pour passer au suivant ; si tous échouaient, l'utilisateur ne
        // voyait qu'un message générique "configure une clé" même quand une clé était
        // bel et bien configurée mais rejetée pour une raison précise (ex: HTTP 400/403).
        val diagnostics = mutableListOf<String>()

        // 1. Google Gemini, si une clé est configurée.
        tryGemini(context, prompt, diagnostics)?.let { return it }

        // 2. OpenAI DALL-E 3, si une clé est configurée.
        tryOpenAI(context, prompt, diagnostics)?.let { return it }

        // 3. Stable Diffusion via Hugging Face, si un jeton est configuré.
        tryHuggingFace(context, prompt, diagnostics)?.let { return it }

        // 4. Stable Diffusion embarqué sur le téléphone, si un modèle est importé.
        tryOnDeviceStableDiffusion(context, prompt, diagnostics)?.let { return it }

        // 5. Pollinations AI (gratuit, sans clé) — en tout dernier recours seulement : leur
        // service a déjà traversé une période de qualité dégradée qu'ils reconnaissaient
        // eux-mêmes (issue GitHub #5372), donc ce n'est PAS un rendu garanti équivalent à
        // Gemini malgré la demande — on ne peut pas promettre une qualité qu'on ne contrôle
        // pas. Il sert juste de dernier filet gratuit quand tout le reste a échoué.
        tryPollinations(context, prompt, diagnostics)?.let { return it }

        val detail = if (diagnostics.isNotEmpty()) {
            "\n\nDétail des échecs :\n" + diagnostics.joinToString("\n") { "• $it" }
        } else ""

        return Result(
            "❌ Échec de la génération d'image sur tous les moteurs disponibles " +
                "(Gemini, OpenAI, Hugging Face, Stable Diffusion embarqué, Pollinations). " +
                "Configure au moins une clé API dans ⚙ → Clés API, ou importe un modèle " +
                "Stable Diffusion local dans ⚙ → Modèles Locaux.$detail",
            null, null
        )
    }

    // ─── 4. Stable Diffusion EMBARQUÉ (stable-diffusion.cpp natif) ─────────────

    private fun tryOnDeviceStableDiffusion(context: Context, prompt: String, diagnostics: MutableList<String>): Result? {
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

            // Résolution modeste et peu d'étapes pour rester dans un temps raisonnable sur CPU mobile.
            val width = 512
            val height = 512
            val steps = 20

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

    // ─── 5. Pollinations AI (gratuit, sans clé, dernier recours) ──────────────

    private fun tryPollinations(context: Context, prompt: String, diagnostics: MutableList<String>): Result? {
        return try {
            val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val request = Request.Builder()
                .url("https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true")
                .get().build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""
                if (!response.isSuccessful || !contentType.startsWith("image/")) {
                    diagnostics.add("Pollinations : HTTP ${response.code} — réponse inexploitable")
                    return null
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    diagnostics.add("Pollinations : réponse vide")
                    return null
                }
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val savedPath = saveToGallery(context, bytes, prompt)
                Result(
                    "🎨 Image générée pour « $prompt » (Pollinations AI — moteur gratuit de dernier recours, qualité variable selon leur service).\n📁 Enregistrée dans : $savedPath",
                    base64,
                    "image/jpeg",
                    savedPath
                )
            }
        } catch (e: Exception) {
            diagnostics.add("Pollinations : exception réseau — ${e.message}")
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

    private fun tryGemini(context: Context, prompt: String, diagnostics: MutableList<String>): Result? {
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
                        JSONObject().put(
                            "responseModalities",
                            org.json.JSONArray().put("TEXT").put("IMAGE")
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

    private fun tryOpenAI(context: Context, prompt: String, diagnostics: MutableList<String>): Result? {
        val keys = Prefs.getApiKeysFor(context, Provider.OPENAI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val body = JSONObject()
                    .put("model", "gpt-image-1")
                    .put("prompt", prompt)
                    .put("n", 1)
                    .put("size", "1024x1024")
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

    private fun tryHuggingFace(context: Context, prompt: String, diagnostics: MutableList<String>): Result? {
        val token = Prefs.getHfToken(context)
        if (token.isBlank()) return null

        return try {
            val body = JSONObject().put("inputs", prompt).toString().toRequestBody(JSON)
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
            file.absolutePath
        } catch (e: Exception) {
            "(échec de la sauvegarde locale : ${e.message})"
        }
    }
}
