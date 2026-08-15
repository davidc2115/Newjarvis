package com.jarvis.assistant

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Permet à JARVIS de créer/modifier/parcourir/supprimer des projets sur GitHub —
 * via l'API REST GitHub (pas de git embarqué : plus simple et fiable sur mobile,
 * et c'est ce que fait GitHub lui-même en coulisses pour toute édition faite
 * depuis son site web).
 *
 * MULTI-COMPTES : chaque fonction accepte un [accountLabel] optionnel (ex: "perso",
 * "pro", "client X") pour choisir quel compte GitHub utiliser parmi ceux enregistrés
 * (⚙ Paramètres → Clés API → Codage GitHub). Vide = compte par défaut (le premier
 * configuré, ou celui marqué par défaut). Si aucun compte ne correspond au libellé
 * demandé, un message d'erreur explicite liste les comptes réellement disponibles —
 * jamais un échec silencieux sur le mauvais compte.
 */
object GitHubController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val NO_TOKEN = "❌ Aucun jeton GitHub configuré. Ajoute-le dans ⚙ Paramètres → onglet « Clés API » → section Codage."

    /** Résout le jeton à utiliser pour [accountLabel] (vide = compte par défaut). */
    private fun resolveToken(context: Context, accountLabel: String): String? {
        val accounts = Prefs.getGithubAccounts(context)
        if (accounts.isEmpty()) return null
        val account = if (accountLabel.isBlank()) {
            Prefs.getDefaultGithubAccount(context)
        } else {
            Prefs.findGithubAccount(context, accountLabel)
        }
        return account?.token?.takeIf { it.isNotBlank() }
    }

    private fun noAccountMessage(context: Context, accountLabel: String): String {
        val accounts = Prefs.getGithubAccounts(context)
        if (accounts.isEmpty()) return NO_TOKEN
        return "❌ Aucun compte GitHub trouvé pour « $accountLabel ». Comptes configurés : " +
            accounts.joinToString(", ") { it.label.ifBlank { "(sans nom)" } } + "."
    }

    private fun authBuilder(context: Context, url: String, accountLabel: String = ""): Request.Builder? {
        val token = resolveToken(context, accountLabel) ?: return null
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
    }

    /** Liste les comptes GitHub configurés (libellés + compte par défaut), sans exposer les jetons. */
    fun listAccounts(context: Context): String {
        val accounts = Prefs.getGithubAccounts(context)
        if (accounts.isEmpty()) return NO_TOKEN
        val sb = StringBuilder("👤 **Comptes GitHub configurés** :\n\n")
        accounts.forEach { a ->
            val star = if (a.isDefault) " ⭐ par défaut" else ""
            sb.append("• ${a.label.ifBlank { "(sans nom)" }}$star\n")
        }
        return sb.toString().trim()
    }

    fun listRepos(context: Context, accountLabel: String = ""): String {
        if (Prefs.getGithubAccounts(context).isEmpty()) return NO_TOKEN
        val builder = authBuilder(context, "https://api.github.com/user/repos?sort=updated&per_page=15", accountLabel)
            ?: return noAccountMessage(context, accountLabel)
        return try {
            client.newCall(builder.get().build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Erreur GitHub (${resp.code}) : $body"
                val arr = JSONArray(body)
                if (arr.length() == 0) return "Aucun dépôt trouvé sur ce compte."
                val sb = StringBuilder("📦 Dépôts récents :\n\n")
                for (i in 0 until arr.length()) {
                    val repo = arr.getJSONObject(i)
                    val visibility = if (repo.optBoolean("private")) "privé" else "public"
                    sb.append("• ${repo.optString("full_name")} ($visibility)\n")
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    data class RepoInfo(val owner: String, val name: String, val isPrivate: Boolean) {
        val fullName: String get() = "$owner/$name"
    }

    /**
     * Version structurée de listRepos — utilisée par l'écran GitHub pour construire un
     * sélecteur de dépôts CLIQUABLE (demandé explicitement à la place de la saisie manuelle
     * du propriétaire/nom du dépôt). Renvoie une liste vide en cas d'échec (pas de compte,
     * erreur réseau...) plutôt que de faire planter l'appelant — l'écran affiche alors un
     * message adapté via listRepos (la version texte) pour connaître la cause exacte.
     */
    suspend fun listReposStructured(context: Context, accountLabel: String = ""): List<RepoInfo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = authBuilder(context, "https://api.github.com/user/repos?sort=updated&per_page=30", accountLabel)
                ?: return@withContext emptyList()
            try {
                client.newCall(builder.get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val body = resp.body?.string() ?: return@withContext emptyList()
                    val arr = JSONArray(body)
                    (0 until arr.length()).map { i ->
                        val repo = arr.getJSONObject(i)
                        val fullName = repo.optString("full_name")
                        RepoInfo(
                            owner = fullName.substringBefore("/", ""),
                            name = fullName.substringAfter("/", fullName),
                            isPrivate = repo.optBoolean("private")
                        )
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    fun createRepo(context: Context, name: String, description: String, isPrivate: Boolean, accountLabel: String = ""): String {
        val builder = authBuilder(context, "https://api.github.com/user/repos", accountLabel)
            ?: return if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)
        return try {
            val body = JSONObject()
                .put("name", name)
                .put("description", description)
                .put("private", isPrivate)
                .put("auto_init", true)
                .toString()
                .toRequestBody(JSON)
            client.newCall(builder.post(body).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de la création du dépôt (${resp.code}) : $respBody"
                val json = JSONObject(respBody)
                "✅ Dépôt créé : ${json.optString("html_url")}"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    sealed class ContentsResult {
        data class Success(val folders: List<String>, val files: List<Pair<String, Long>>) : ContentsResult()
        data class NotADirectory(val path: String, val sizeBytes: Long) : ContentsResult()
        data class Error(val message: String) : ContentsResult()
    }

    /**
     * Version structurée de listContents — utilisée par l'écran GitHub pour construire un
     * navigateur de dossiers CLIQUABLE (demandé explicitement à la place de la saisie
     * manuelle du chemin). listContents (texte) réutilise cette fonction pour ne pas
     * dupliquer l'appel réseau/parsing à deux endroits.
     */
    suspend fun listContentsStructured(context: Context, owner: String, repo: String, path: String, branch: String, accountLabel: String = ""): ContentsResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cleanPath = path.trim().trim('/')
            val url = "https://api.github.com/repos/$owner/$repo/contents/$cleanPath?ref=$branch"
            val builder = authBuilder(context, url, accountLabel)
                ?: return@withContext ContentsResult.Error(if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel))
            try {
                client.newCall(builder.get().build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) return@withContext ContentsResult.Error("❌ Impossible de lister « ${cleanPath.ifBlank { "/" }} » (${resp.code}) : $body")
                    val trimmedBody = body.trim()
                    if (!trimmedBody.startsWith("[")) {
                        val obj = JSONObject(trimmedBody)
                        return@withContext ContentsResult.NotADirectory(obj.optString("path"), obj.optLong("size"))
                    }
                    val arr = JSONArray(trimmedBody)
                    val folders = mutableListOf<String>()
                    val files = mutableListOf<Pair<String, Long>>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        if (item.optString("type") == "dir") folders.add(item.optString("name"))
                        else files.add(item.optString("name") to item.optLong("size"))
                    }
                    ContentsResult.Success(folders.sorted(), files.sortedBy { it.first })
                }
            } catch (e: Exception) {
                ContentsResult.Error("❌ Erreur réseau : ${e.message}")
            }
        }

    /**
     * Liste le contenu d'un dossier d'un dépôt (racine si [path] est vide) — distingue
     * fichiers et sous-dossiers, avec leur taille pour les fichiers. C'est ce qui permet
     * de "voir le dépôt en direct" depuis JARVIS sans connaître déjà les chemins exacts.
     */
    suspend fun listContents(context: Context, owner: String, repo: String, path: String, branch: String, accountLabel: String = ""): String {
        val cleanPath = path.trim().trim('/')
        return when (val result = listContentsStructured(context, owner, repo, path, branch, accountLabel)) {
            is ContentsResult.Error -> result.message
            is ContentsResult.NotADirectory -> "📄 « ${result.path} » est un fichier (${result.sizeBytes} octets), pas un dossier. Utilise github_read_file pour le lire."
            is ContentsResult.Success -> {
                if (result.folders.isEmpty() && result.files.isEmpty()) return "📂 « ${cleanPath.ifBlank { "/" }} » est vide."
                val sb = StringBuilder("📂 **$owner/$repo${if (cleanPath.isNotBlank()) "/$cleanPath" else ""}** (branche $branch) :\n\n")
                result.folders.forEach { sb.append("📁 $it/\n") }
                result.files.forEach { (n, size) -> sb.append("📄 $n (${size} octets)\n") }
                sb.toString().trim()
            }
        }
    }

    /** Crée un fichier, ou le met à jour s'il existe déjà (même endpoint GitHub pour les deux cas). */
    fun createOrUpdateFile(
        context: Context,
        owner: String,
        repo: String,
        path: String,
        content: String,
        commitMessage: String,
        branch: String,
        accountLabel: String = ""
    ): String {
        if (resolveToken(context, accountLabel) == null) {
            return if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)
        }

        // Récupère le sha existant si le fichier existe déjà (requis par l'API pour une mise à jour)
        var existingSha: String? = null
        try {
            val getBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch", accountLabel)
            if (getBuilder != null) {
                client.newCall(getBuilder.get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        existingSha = JSONObject(resp.body?.string() ?: "").optString("sha").ifBlank { null }
                    }
                }
            }
        } catch (_: Exception) {
            // Le fichier n'existe probablement pas encore — on continue en création.
        }

        val putBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path", accountLabel) ?: return NO_TOKEN
        return try {
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val bodyJson = JSONObject()
                .put("message", commitMessage)
                .put("content", encoded)
                .put("branch", branch)
            existingSha?.let { bodyJson.put("sha", it) }

            val body = bodyJson.toString().toRequestBody(JSON)
            client.newCall(putBuilder.put(body).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de l'écriture du fichier (${resp.code}) : $respBody"
                val json = JSONObject(respBody)
                val commitUrl = json.optJSONObject("commit")?.optString("html_url") ?: ""
                val verb = if (existingSha != null) "mis à jour" else "créé"
                "✅ Fichier $path $verb dans $owner/$repo.\n🔗 $commitUrl"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    /**
     * Supprime un fichier d'un dépôt (nécessite son sha actuel, récupéré automatiquement).
     * GitHub n'a pas de vraie notion de "dossier vide" — pour supprimer un dossier entier,
     * voir deleteFolder (qui supprime récursivement tous les fichiers qu'il contient).
     */
    fun deleteFile(context: Context, owner: String, repo: String, path: String, commitMessage: String, branch: String, accountLabel: String = ""): String {
        val getBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch", accountLabel)
            ?: return if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)
        val sha = try {
            client.newCall(getBuilder.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return "❌ Fichier introuvable, rien à supprimer (${resp.code}) : ${resp.body?.string()}"
                JSONObject(resp.body?.string() ?: "").optString("sha").ifBlank { null }
                    ?: return "❌ « $path » ne semble pas être un fichier (peut-être un dossier ? utilise deleteFolder)."
            }
        } catch (e: Exception) {
            return "❌ Erreur réseau : ${e.message}"
        }

        val deleteBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path", accountLabel) ?: return NO_TOKEN
        return try {
            val body = JSONObject()
                .put("message", commitMessage)
                .put("sha", sha)
                .put("branch", branch)
                .toString()
                .toRequestBody(JSON)
            client.newCall(deleteBuilder.delete(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) return "❌ Échec de la suppression (${resp.code}) : ${resp.body?.string()}"
                "✅ Fichier « $path » supprimé de $owner/$repo."
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    /**
     * Supprime récursivement un dossier entier (tous les fichiers qu'il contient), en UN
     * SEUL commit — via la Git Trees API : récupère l'arborescence complète de la branche,
     * retire toutes les entrées dont le chemin commence par [folderPath], construit un
     * nouvel arbre, un nouveau commit pointant dessus, puis avance la référence de branche.
     * C'est la seule façon fiable de "supprimer un dossier" sur GitHub (qui ne modélise pas
     * les dossiers comme des objets à part entière — seuls les fichiers existent réellement).
     */
    suspend fun deleteFolder(context: Context, owner: String, repo: String, folderPath: String, commitMessage: String, branch: String, accountLabel: String = ""): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cleanFolder = folderPath.trim().trim('/')
            if (cleanFolder.isBlank()) return@withContext "❌ Précise le chemin du dossier à supprimer (ne supprime jamais toute la racine par sécurité)."
            if (resolveToken(context, accountLabel) == null) {
                return@withContext if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)
            }
            try {
                // 1. SHA du commit courant de la branche.
                val refBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch", accountLabel)!!
                val commitSha = client.newCall(refBuilder.get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "❌ Branche « $branch » introuvable : ${resp.body?.string()}"
                    JSONObject(resp.body?.string() ?: "").getJSONObject("object").getString("sha")
                }
                // 2. SHA de l'arbre racine associé à ce commit.
                val commitBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/commits/$commitSha", accountLabel)!!
                val treeSha = client.newCall(commitBuilder.get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "❌ Impossible de lire le commit : ${resp.body?.string()}"
                    JSONObject(resp.body?.string() ?: "").getJSONObject("tree").getString("sha")
                }
                // 3. Arborescence complète récursive.
                val treeBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/trees/$treeSha?recursive=1", accountLabel)!!
                val fullTree = client.newCall(treeBuilder.get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "❌ Impossible de lire l'arborescence : ${resp.body?.string()}"
                    JSONObject(resp.body?.string() ?: "").getJSONArray("tree")
                }
                val remaining = JSONArray()
                var removedCount = 0
                for (i in 0 until fullTree.length()) {
                    val entry = fullTree.getJSONObject(i)
                    val entryPath = entry.optString("path")
                    if (entry.optString("type") == "blob" && (entryPath == cleanFolder || entryPath.startsWith("$cleanFolder/"))) {
                        removedCount++
                        continue
                    }
                    if (entry.optString("type") == "blob") {
                        remaining.put(JSONObject().apply {
                            put("path", entryPath); put("mode", entry.optString("mode"))
                            put("type", "blob"); put("sha", entry.optString("sha"))
                        })
                    }
                }
                if (removedCount == 0) return@withContext "❌ Aucun fichier trouvé sous « $cleanFolder » — dossier déjà vide ou inexistant."

                // 4. Nouvel arbre SANS les fichiers du dossier supprimé.
                val newTreeBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/trees", accountLabel)!!
                val newTreeBody = JSONObject().put("tree", remaining).toString().toRequestBody(JSON)
                val newTreeSha = client.newCall(newTreeBuilder.post(newTreeBody).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "❌ Échec de la création du nouvel arbre : ${resp.body?.string()}"
                    JSONObject(resp.body?.string() ?: "").getString("sha")
                }
                // 5. Nouveau commit pointant sur ce nouvel arbre.
                val newCommitBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/commits", accountLabel)!!
                val newCommitBody = JSONObject()
                    .put("message", commitMessage)
                    .put("tree", newTreeSha)
                    .put("parents", JSONArray().put(commitSha))
                    .toString().toRequestBody(JSON)
                val newCommitSha = client.newCall(newCommitBuilder.post(newCommitBody).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "❌ Échec de la création du commit : ${resp.body?.string()}"
                    JSONObject(resp.body?.string() ?: "").getString("sha")
                }
                // 6. Avance la référence de branche vers le nouveau commit.
                val updateRefBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch", accountLabel)!!
                val updateBody = JSONObject().put("sha", newCommitSha).toString().toRequestBody(JSON)
                client.newCall(updateRefBuilder.patch(updateBody).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "❌ Échec de la mise à jour de la branche : ${resp.body?.string()}"
                    "✅ Dossier « $cleanFolder » supprimé ($removedCount fichier(s)) de $owner/$repo, branche $branch."
                }
            } catch (e: Exception) {
                "❌ Erreur réseau : ${e.message}"
            }
        }

    /** Supprime le dépôt entier — IRRÉVERSIBLE, l'appelant doit avoir obtenu une confirmation explicite avant. */
    fun deleteRepo(context: Context, owner: String, repo: String, accountLabel: String = ""): String {
        val builder = authBuilder(context, "https://api.github.com/repos/$owner/$repo", accountLabel)
            ?: return if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)
        return try {
            client.newCall(builder.delete().build()).execute().use { resp ->
                if (resp.code == 204) "✅ Dépôt $owner/$repo supprimé définitivement."
                else "❌ Échec de la suppression du dépôt (${resp.code}) : ${resp.body?.string()}"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun readFile(context: Context, owner: String, repo: String, path: String, branch: String, accountLabel: String = ""): String {
        val builder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch", accountLabel)
            ?: return if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)
        return try {
            client.newCall(builder.get().build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Fichier introuvable (${resp.code}) : $body"
                val json = JSONObject(body)
                val content = json.optString("content", "")
                String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun createBranch(context: Context, owner: String, repo: String, newBranch: String, fromBranch: String, accountLabel: String = ""): String {
        val getRefBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/ref/heads/$fromBranch", accountLabel) ?: return NO_TOKEN
        val baseSha = try {
            client.newCall(getRefBuilder.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return "❌ Branche de base « $fromBranch » introuvable : ${resp.body?.string()}"
                JSONObject(resp.body?.string() ?: "").getJSONObject("object").getString("sha")
            }
        } catch (e: Exception) {
            return "❌ Erreur réseau : ${e.message}"
        }

        val postBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/refs", accountLabel) ?: return NO_TOKEN
        return try {
            val body = JSONObject()
                .put("ref", "refs/heads/$newBranch")
                .put("sha", baseSha)
                .toString()
                .toRequestBody(JSON)
            client.newCall(postBuilder.post(body).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de la création de branche (${resp.code}) : $respBody"
                "✅ Branche « $newBranch » créée à partir de « $fromBranch »."
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun createPullRequest(
        context: Context,
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String,
        accountLabel: String = ""
    ): String {
        val builder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/pulls", accountLabel) ?: return NO_TOKEN
        return try {
            val bodyJson = JSONObject()
                .put("title", title)
                .put("head", head)
                .put("base", base)
                .put("body", body)
                .toString()
                .toRequestBody(JSON)
            client.newCall(builder.post(bodyJson).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de la création de la pull request (${resp.code}) : $respBody"
                val json = JSONObject(respBody)
                "✅ Pull request créée : ${json.optString("html_url")}"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    /**
     * Diagnostic de permissions réel (lecture seule, n'écrit jamais rien) : vérifie que le
     * jeton est valide, puis, si owner/repo fournis, si le dépôt est visible et si le jeton
     * a le droit d'écrire dessus. Répond à la plainte la plus fréquente ("aucune permission
     * ?") avec la VRAIE cause plutôt qu'un échec silencieux :
     *  - jeton absent/invalide/expiré (401 sur /user)
     *  - dépôt introuvable (404) : soit le nom est faux, soit (cas le plus fréquent avec un
     *    jeton fine-grained) le dépôt n'a jamais été ajouté à la liste des dépôts autorisés
     *    pour ce jeton (GitHub renvoie volontairement 404 et jamais 403 dans ce cas précis,
     *    pour ne pas révéler l'existence d'un dépôt privé à un jeton non autorisé)
     *  - dépôt visible mais permissions.push = false : accès lecture seule (jeton classique
     *    sans le scope repo complet, ou jeton fine-grained avec seulement Contents: Read-only)
     */
    fun testAccess(context: Context, owner: String, repo: String, accountLabel: String = ""): String {
        val token = resolveToken(context, accountLabel)
            ?: return if (Prefs.getGithubAccounts(context).isEmpty()) NO_TOKEN else noAccountMessage(context, accountLabel)

        val sb = StringBuilder("🔍 Diagnostic GitHub :\n\n")

        // 1) Le jeton lui-même est-il valide ?
        val userReq = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        var login = ""
        try {
            client.newCall(userReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return sb.append("❌ Jeton invalide ou expiré (code ${resp.code}). Régénère-le sur github.com puis remplace-le dans ⚙ -> Clés API -> Codage GitHub.").toString()
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                login = json.optString("login", "?")
                val scopes = resp.header("X-OAuth-Scopes")
                sb.append("✅ Jeton valide, connecté en tant que $login")
                if (!scopes.isNullOrBlank()) sb.append(" (scopes classiques : $scopes)")
                sb.append(".\n")
            }
        } catch (e: Exception) {
            return sb.append("❌ Erreur réseau en vérifiant le jeton : ${e.message}").toString()
        }

        if (owner.isBlank() || repo.isBlank()) {
            sb.append("\nPrécise owner et repo pour vérifier l'accès à un dépôt précis.")
            return sb.toString()
        }

        // 2) Ce dépôt précis est-il visible ET pilotable en écriture par ce jeton ?
        val repoReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        try {
            client.newCall(repoReq).execute().use { resp ->
                when {
                    resp.code == 404 -> sb.append(
                        "\n❌ « $owner/$repo » : introuvable avec ce jeton (404). Soit le nom du dépôt est incorrect, " +
                            "soit, si c'est un jeton fine-grained, ce dépôt précis n'a jamais été coché dans sa " +
                            "liste de dépôts autorisés (github.com -> Settings -> Developer settings -> Fine-grained tokens " +
                            "-> ton jeton -> Repository access). GitHub renvoie volontairement 404, jamais 403, dans ce cas."
                    )
                    !resp.isSuccessful -> sb.append("\n❌ « $owner/$repo » : erreur ${resp.code} en le consultant : ${resp.body?.string()}")
                    else -> {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val perms = json.optJSONObject("permissions")
                        val canPush = perms?.optBoolean("push", false) ?: false
                        val canPull = perms?.optBoolean("pull", true) ?: true
                        val isPrivate = json.optBoolean("private", false)
                        sb.append("\n✅ « $owner/$repo » visible (${if (isPrivate) "privé" else "public"}). ")
                        sb.append(
                            if (canPush) "✅ Écriture confirmée (create_file/delete_file/branches/PR vont fonctionner)."
                            else if (canPull) "⚠️ Lecture seule : ce jeton peut lire ce dépôt mais pas y écrire. Jeton classique : il lui manque le scope repo complet (pas juste public_repo). Jeton fine-grained : régénère-le avec la permission Contents en Read and write pour ce dépôt."
                            else "❌ Ni lecture ni écriture claire sur ce dépôt avec ce jeton."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("\n❌ Erreur réseau en testant l'accès au dépôt : ${e.message}")
        }
        return sb.toString()
    }
}
