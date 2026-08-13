package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * VideoGenController — génération vidéo IA via l'API Replicate
 * (https://replicate.com/docs/reference/http).
 *
 * Contrairement à la génération d'image (plusieurs fournisseurs gratuits
 * disponibles), il n'existe pas encore d'API de génération vidéo IA
 * simple, fiable et gratuite. Replicate est le choix le plus honnête :
 * API HTTP ouverte, facturée à l'usage par le fournisseur (quelques
 * centimes à ~1$ par vidéo selon le modèle), jeton gratuit à créer sur
 * replicate.com/account/api-tokens (carte bancaire requise par Replicate
 * pour activer la facturation, mais aucun abonnement).
 *
 * Modèle : "wan-video/wan-2.7-t2v" (Alibaba Wan 2.7, texte → vidéo avec audio
 * synchronisé, jusqu'à 1080p) — choisi spécifiquement parce qu'il accepte un
 * VRAI paramètre "duration" en secondes côté API (contrairement à l'ancien
 * modèle minimax/video-01, qui produisait toujours ~6s fixes quoi qu'on
 * demande — c'était un plafond du modèle, pas un bug de l'appli). Durée
 * demandée bornée à [MIN_DURATION_S, MAX_DURATION_S] secondes, la vidéo
 * réelle peut prendre plusieurs minutes à générer selon la durée choisie.
 */
object VideoGenController {

    data class Result(val success: Boolean, val message: String, val localPath: String? = null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    private const val DEFAULT_MODEL = "wan-video/wan-2.7-t2v"
    private const val MAX_POLL_ATTEMPTS = 60      // 60 x 5s = 5min max (vidéos plus longues = génération plus longue)
    private const val POLL_INTERVAL_MS = 5000L
    const val MIN_DURATION_S = 2
    const val MAX_DURATION_S = 15
    const val DEFAULT_DURATION_S = 5

    suspend fun generateVideo(context: Context, prompt: String, durationSeconds: Int = DEFAULT_DURATION_S): Result {
        if (prompt.isBlank()) return Result(false, "❌ Aucune description de vidéo fournie.")

        val token = Prefs.getReplicateToken(context)
        if (token.isBlank()) {
            return Result(
                false,
                "❌ La génération vidéo nécessite un jeton API Replicate. " +
                    "Crée un compte gratuit sur replicate.com, génère un jeton dans " +
                    "Account → API tokens, puis colle-le dans 🎨 Génération (champ jeton Replicate, section vidéo)."
            )
        }

        val duration = durationSeconds.coerceIn(MIN_DURATION_S, MAX_DURATION_S)

        return try {
            val model = DEFAULT_MODEL
            val body = JSONObject()
                .put("input", JSONObject().put("prompt", prompt).put("duration", duration))
                .toString()
                .toRequestBody(JSON)

            val createRequest = Request.Builder()
                .url("https://api.replicate.com/v1/models/$model/predictions")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "wait=1") // attend jusqu'à ~60s de calcul synchrone si possible
                .post(body)
                .build()

            var predictionUrl: String
            var status: String
            var outputVal: Any?

            var lastErrorDetail: String? = null

            client.newCall(createRequest).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result(false, "❌ Replicate a refusé la demande (HTTP ${response.code}) : ${bodyStr.take(300)}")
                }
                val json = JSONObject(bodyStr)
                predictionUrl = json.optJSONObject("urls")?.optString("get") ?: ""
                status = json.optString("status", "starting")
                outputVal = json.opt("output")
                if (status == "failed") {
                    lastErrorDetail = json.optString("error", bodyStr.take(300))
                }
            }

            if (predictionUrl.isBlank()) return Result(false, "❌ Réponse Replicate invalide (pas d'URL de suivi). Vérifie que le modèle « $model » existe toujours sur Replicate, ou que le jeton a bien les droits nécessaires.")

            // Poll jusqu'à ce que la prédiction soit terminée si elle ne l'était pas déjà.
            // Utilise delay() (suspension coroutine) plutôt que Thread.sleep() pour ne pas
            // bloquer inutilement le thread du dispatcher IO pendant l'attente.
            var attempts = 0
            while (status != "succeeded" && status != "failed" && status != "canceled" && attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++
                val pollRequest = Request.Builder()
                    .url(predictionUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                client.newCall(pollRequest).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = JSONObject(bodyStr)
                        status = json.optString("status", status)
                        outputVal = json.opt("output")
                        if (status == "failed") {
                            lastErrorDetail = json.optString("error", bodyStr.take(300))
                        }
                    } else {
                        lastErrorDetail = "HTTP ${response.code} lors du suivi — ${bodyStr.take(200)}"
                    }
                }
            }

            if (status != "succeeded") {
                val reason = lastErrorDetail?.let { " Détail : $it" } ?: ""
                return Result(false, "❌ Génération vidéo échouée ou trop longue (statut : $status).$reason Réessaie avec une description plus simple ou une durée plus courte.")
            }

            val videoUrl = when (outputVal) {
                is String -> outputVal as String
                is org.json.JSONArray -> (outputVal as org.json.JSONArray).let { if (it.length() > 0) it.getString(0) else null }
                else -> null
            } ?: return Result(false, "❌ Réponse Replicate sans URL vidéo exploitable.")

            val downloadRequest = Request.Builder().url(videoUrl).build()
            val videoBytes = client.newCall(downloadRequest).execute().use { resp ->
                if (!resp.isSuccessful) return Result(false, "❌ Échec du téléchargement de la vidéo générée.")
                resp.body?.bytes()
            } ?: return Result(false, "❌ Vidéo vide reçue depuis Replicate.")

            val savedPath = saveVideo(context, videoBytes, prompt)

            // On ne fait JAMAIS confiance aveuglément à la durée demandée pour le message
            // affiché : Replicate a déjà été observé accepter le paramètre "duration" sans
            // erreur tout en renvoyant une vidéo plus courte que demandé (limite réelle du
            // modèle, pas un bug de l'appli). On mesure donc la durée RÉELLE directement
            // dans le conteneur MP4 téléchargé (atome mvhd) et on l'affiche à la place —
            // et on prévient explicitement l'utilisateur si le modèle n'a pas respecté sa demande.
            val actualDuration = extractMp4DurationSeconds(videoBytes)
            val message = if (actualDuration != null) {
                val rounded = Math.round(actualDuration).toInt()
                if (kotlin.math.abs(actualDuration - duration) > 1.5) {
                    "🎬 Vidéo générée pour « $prompt » — durée réelle : ${rounded}s (le modèle n'a pas respecté la durée demandée de ${duration}s ; " +
                        "c'est une limite du modèle « $model » côté Replicate, pas un bug de JARVIS — réessaie ou raccourcis ta demande si besoin).\n📁 Enregistrée dans : $savedPath"
                } else {
                    "🎬 Vidéo de ${rounded}s générée pour « $prompt ».\n📁 Enregistrée dans : $savedPath"
                }
            } else {
                "🎬 Vidéo générée pour « $prompt » (durée demandée : ${duration}s, durée réelle non détectable automatiquement).\n📁 Enregistrée dans : $savedPath"
            }
            Result(true, message, savedPath)
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de la génération vidéo : ${e.message}")
        }
    }

    /**
     * Lit la durée réelle d'un fichier MP4 directement depuis son atome "mvhd" (Movie Header
     * Box), sans dépendance externe (MediaMetadataRetriever nécessiterait un fichier déjà
     * écrit sur disque ; ici on lit directement les octets déjà en mémoire). Retourne null si
     * le format n'est pas reconnu plutôt que de faire une supposition fausse.
     */
    private fun extractMp4DurationSeconds(bytes: ByteArray): Double? {
        try {
            fun u32(off: Int): Long =
                ((bytes[off].toLong() and 0xFF) shl 24) or ((bytes[off + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[off + 2].toLong() and 0xFF) shl 8) or (bytes[off + 3].toLong() and 0xFF)
            fun u64(off: Int): Long {
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (bytes[off + i].toLong() and 0xFF)
                return v
            }

            // Cherche récursivement l'atome "moov" puis "mvhd" à l'intérieur.
            fun findBox(start: Int, end: Int, type: String): Pair<Int, Int>? {
                var pos = start
                while (pos + 8 <= end) {
                    var size = u32(pos)
                    val boxType = String(bytes, pos + 4, 4, Charsets.US_ASCII)
                    var headerSize = 8
                    if (size == 1L) {
                        size = u64(pos + 8)
                        headerSize = 16
                    }
                    if (size <= 0) break
                    if (boxType == type) return Pair(pos + headerSize, (pos + size).toInt())
                    pos += size.toInt()
                }
                return null
            }

            val moov = findBox(0, bytes.size, "moov") ?: return null
            val mvhd = findBox(moov.first, moov.second, "mvhd") ?: return null
            val mvhdStart = mvhd.first
            val version = bytes[mvhdStart].toInt() and 0xFF
            return if (version == 1) {
                val timescale = u32(mvhdStart + 4 + 8 + 8)
                val duration = u64(mvhdStart + 4 + 8 + 8 + 4)
                if (timescale == 0L) null else duration.toDouble() / timescale.toDouble()
            } else {
                val timescale = u32(mvhdStart + 4 + 4 + 4)
                val duration = u32(mvhdStart + 4 + 4 + 4 + 4)
                if (timescale == 0L) null else duration.toDouble() / timescale.toDouble()
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun saveVideo(context: Context, bytes: ByteArray, prompt: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "JARVIS-Generated"
        ).also { it.mkdirs() }

        val safePrompt = prompt.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
        val fileName = "${fileDateFormat.format(Date())}_$safePrompt.mp4"
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
