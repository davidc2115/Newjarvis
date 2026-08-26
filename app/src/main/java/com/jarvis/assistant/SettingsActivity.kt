package com.jarvis.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.jarvis.assistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/** Réglages : choix de la couleur d'accent du thème (bulles utilisateur, bouton d'envoi...). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var swatches: List<SwatchEntry>

    private data class SwatchEntry(val color: Int, val circle: View, val check: View, val container: View)

    // Écran de consentement système pour l'autorisation Gmail/Agenda (voir
    // GoogleAccountController.requestAuthorization) -- distinct du sélecteur de compte
    // Credential Manager. On persiste ici le jeton d'accès obtenu (voir Prefs.setGoogleAccessToken)
    // pour que MainActivity puisse l'utiliser immédiatement sans redemander l'autorisation.
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val accessToken = GoogleAccountController.handleAuthorizationResult(this, result.data)
        if (accessToken != null) {
            Prefs.setGoogleAccessToken(this, accessToken)
            Toast.makeText(this, "✅ Accès Gmail/Agenda autorisé.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                getString(R.string.google_authorization_error, "consentement refusé ou annulé"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Écran de sélection de compte via l'ancienne API GoogleSignInClient (voir
    // GoogleAccountController.getLegacySignInIntent) -- remplace le Credential Manager suspendu
    // qui restait bloqué indéfiniment sur certains téléphones Xiaomi/MIUI (voir signalement
    // utilisateur : timeout 20s déclenché malgré compte Google + Play Services en ordre).
    private val googleLegacySignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onLegacySignInResult(result.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Voir MainActivity.onCreate pour pourquoi cet appel explicite est nécessaire.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        swatches = listOf(
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_cyan), binding.swatchCyanCircle, binding.swatchCyanCheck, binding.swatchCyan),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_violet), binding.swatchVioletCircle, binding.swatchVioletCheck, binding.swatchViolet),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_rose), binding.swatchRoseCircle, binding.swatchRoseCheck, binding.swatchRose),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_vert), binding.swatchVertCircle, binding.swatchVertCheck, binding.swatchVert),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_orange), binding.swatchOrangeCircle, binding.swatchOrangeCheck, binding.swatchOrange),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_rouge), binding.swatchRougeCircle, binding.swatchRougeCheck, binding.swatchRouge),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_bleu), binding.swatchBleuCircle, binding.swatchBleuCheck, binding.swatchBleu),
            SwatchEntry(ContextCompat.getColor(this, R.color.accent_blanc), binding.swatchBlancCircle, binding.swatchBlancCheck, binding.swatchBlanc)
        )

        swatches.forEach { entry ->
            val bg = entry.circle.background?.mutate()
            if (bg is GradientDrawable) bg.setColor(entry.color)
            entry.container.setOnClickListener { selectColor(entry.color) }
        }

        updateSelection(Prefs.getAccentColor(this))

        binding.backButton.setOnClickListener { finish() }

        setupPersonalizationSection()
        setupModelSelection()
        setupGoogleAccountSection()
        setupObsidianSection()
    }

    /** Prénom utilisé par JARVIS dans ses réponses conversationnelles (voir
     *  MainActivity.buildConversationalPrompt) -- même schéma de sauvegarde que
     *  googleWebClientIdInput ci-dessous : on écrit dans Prefs à la perte du focus, pas à
     *  chaque frappe (évite d'écrire sur le disque à chaque caractère tapé). */
    private fun setupPersonalizationSection() {
        binding.userNameInput.setText(Prefs.getUserName(this).orEmpty())
        binding.userNameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                Prefs.setUserName(this, binding.userNameInput.text?.toString().orEmpty())
            }
        }
    }

    private fun selectColor(color: Int) {
        Prefs.setAccentColor(this, color)
        updateSelection(color)
    }

    private fun updateSelection(current: Int) {
        swatches.forEach { it.check.visibility = if (it.color == current) View.VISIBLE else View.GONE }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.settingsTopBar.setPadding(
                binding.settingsTopBar.paddingLeft, bars.top,
                binding.settingsTopBar.paddingRight, binding.settingsTopBar.paddingBottom
            )
            insets
        }
        // Voir MainActivity.applyWindowInsets pour pourquoi cet appel est nécessaire (cas où
        // le tout premier passage d'insets a lieu avant l'attachement du listener).
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * Choix du backend IA (Gemini Nano via AICore, ou un modèle local via LiteRT-LM -- voir
     * LocalLlmController). Les modèles locaux (Qwen3/Qwen2.5) sont publics sur Hugging Face
     * (licence Apache 2.0) : aucun compte ni jeton requis, contrairement à l'ancien Gemma qui
     * imposait une licence acceptée manuellement. La liste des modèles disponibles n'apparaît
     * que quand le backend "IA locale" est sélectionné.
     */
    private fun setupModelSelection() {
        binding.modelGeminiNanoRow.setOnClickListener { selectModel(Prefs.MODEL_GEMINI_NANO) }
        binding.modelGemmaRow.setOnClickListener { selectModel(Prefs.MODEL_LOCAL_LLM) }

        refreshModelSelectionUi()
    }

    private fun selectModel(model: String) {
        Prefs.setSelectedModel(this, model)
        refreshModelSelectionUi()
    }

    private fun refreshModelSelectionUi() {
        val selected = Prefs.getSelectedModel(this)
        val isLocalLlm = selected == Prefs.MODEL_LOCAL_LLM

        binding.modelGeminiNanoRow.background = ContextCompat.getDrawable(
            this, if (isLocalLlm) R.drawable.bg_model_row else R.drawable.bg_model_row_selected
        )
        binding.modelGeminiNanoCheck.visibility = if (isLocalLlm) View.GONE else View.VISIBLE

        binding.modelGemmaRow.background = ContextCompat.getDrawable(
            this, if (isLocalLlm) R.drawable.bg_model_row_selected else R.drawable.bg_model_row
        )
        binding.modelGemmaCheck.visibility = if (isLocalLlm) View.VISIBLE else View.GONE

        val localSectionVisibility = if (isLocalLlm) View.VISIBLE else View.GONE
        binding.gemmaConfigSection.visibility = localSectionVisibility
        binding.localModelsContainer.visibility = localSectionVisibility
        refreshLocalModelsList()
    }

    /**
     * Rend une rangée par modèle local disponible (voir LocalLlmController.AVAILABLE_MODELS),
     * même schéma dynamique que refreshGoogleAccountsList : un modèle non téléchargé affiche
     * un bouton "Télécharger" + une barre de progression pendant le transfert, un modèle déjà
     * téléchargé affiche "Supprimer". Le modèle actif (Prefs.getLocalLlmModelId) est marqué.
     */
    private fun refreshLocalModelsList() {
        if (Prefs.getSelectedModel(this) != Prefs.MODEL_LOCAL_LLM) return
        binding.localModelsContainer.removeAllViews()
        val activeModelId = Prefs.getLocalLlmModelId(this)

        LocalLlmController.AVAILABLE_MODELS.forEach { model ->
            val downloaded = LocalLlmController.isDownloaded(this, model)
            val isActive = model.id == activeModelId

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(if (isActive) R.drawable.bg_model_row_selected else R.drawable.bg_model_row)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dpToPx(16), 0, dpToPx(16), dpToPx(8)) }
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = "${if (isActive) "✅ " else ""}${model.displayName}\n${model.description}"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerRow.addView(label)
            row.addView(headerRow)

            val statusText = TextView(this).apply {
                text = getString(
                    if (downloaded) R.string.model_downloaded_status else R.string.model_not_downloaded_status
                )
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, dpToPx(6), 0, 0)
            }
            row.addView(statusText)

            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(6) }
            }
            row.addView(progress)

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dpToPx(8), 0, 0)
            }
            if (!isActive) {
                val useButton = TextView(this).apply {
                    text = "Utiliser"
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_default))
                    textSize = 12f
                    setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                    setOnClickListener {
                        Prefs.setLocalLlmModelId(this@SettingsActivity, model.id)
                        refreshLocalModelsList()
                    }
                }
                actionsRow.addView(useButton)
            }
            if (downloaded) {
                val deleteButton = TextView(this).apply {
                    text = getString(R.string.delete_model_button)
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_rouge))
                    textSize = 12f
                    setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                    setOnClickListener {
                        LocalLlmController.deleteModel(this@SettingsActivity, model)
                        refreshLocalModelsList()
                    }
                }
                actionsRow.addView(deleteButton)
            } else {
                val downloadButton = TextView(this).apply {
                    text = getString(R.string.download_model_button)
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_default))
                    textSize = 12f
                    setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                    setOnClickListener { confirmAndDownloadLocalModel(model, this, progress) }
                }
                actionsRow.addView(downloadButton)
            }
            row.addView(actionsRow)

            binding.localModelsContainer.addView(row)
        }
    }

    private fun confirmAndDownloadLocalModel(
        model: LocalLlmController.LocalModel,
        downloadButton: TextView,
        progress: ProgressBar
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.model_download_confirm_title)
            .setMessage(R.string.model_download_confirm_message)
            .setPositiveButton(R.string.download_model_button) { _, _ -> startLocalModelDownload(model, downloadButton, progress) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startLocalModelDownload(
        model: LocalLlmController.LocalModel,
        downloadButton: TextView,
        progress: ProgressBar
    ) {
        downloadButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0

        lifecycleScope.launch {
            try {
                LocalLlmController.download(this@SettingsActivity, model) { downloaded, total ->
                    if (total > 0) {
                        val percent = ((downloaded * 100) / total).toInt()
                        runOnUiThread { progress.progress = percent }
                    }
                }
                refreshLocalModelsList()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                downloadButton.visibility = View.VISIBLE
                Toast.makeText(this@SettingsActivity, "❌ ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    /**
     * Connexion Google directement depuis l'appli, multi-comptes (voir GoogleAccountController
     * pour le pourquoi des deux étapes authentification/autorisation, et pour l'ID client Web
     * indispensable -- saisi ici, jamais codé en dur). Utilisé par Agenda (GoogleCalendarApiController)
     * et Mail (GmailApiController), demande explicite de l'utilisateur de repasser sur l'API
     * OAuth officielle plutôt que CalendarContract/IMAP.
     */
    // Vault Obsidian (voir ObsidianController) -- OpenDocumentTree() est le seul moyen sous
    // scoped storage (Android 10+) de laisser l'utilisateur choisir un dossier ARBITRAIRE
    // (pas juste un fichier) avec un acces lecture/ecriture qui survit au redemarrage de
    // l'appli -- d'ou takePersistableUriPermission juste apres, sans quoi l'acces expirerait
    // a la fin de ce processus.
    private val obsidianVaultPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onObsidianVaultChosen(uri) }

    private fun onObsidianVaultChosen(uri: android.net.Uri?) {
        if (uri == null) return // l'utilisateur a annule le selecteur, rien a faire
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            showCopyableErrorDialog(
                "Impossible de garder l'accès à ce dossier",
                "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            )
            return
        }
        Prefs.setObsidianVaultUri(this, uri.toString())
        refreshObsidianVaultStatus()
        Toast.makeText(this, "✅ Vault Obsidian sélectionné.", Toast.LENGTH_SHORT).show()
    }

    private fun setupObsidianSection() {
        binding.chooseObsidianVaultButton.setOnClickListener { obsidianVaultPickerLauncher.launch(null) }
        refreshObsidianVaultStatus()
    }

    private fun refreshObsidianVaultStatus() {
        val root = ObsidianController.getVaultRoot(this)
        if (root != null) {
            val label = root.name ?: root.uri.lastPathSegment ?: root.uri.toString()
            binding.obsidianVaultStatus.text = getString(R.string.obsidian_vault_selected, label)
            binding.chooseObsidianVaultButton.text = getString(R.string.obsidian_change_vault_button)
        } else {
            binding.obsidianVaultStatus.text = getString(R.string.obsidian_no_vault)
            binding.chooseObsidianVaultButton.text = getString(R.string.obsidian_choose_vault_button)
        }
    }

    private fun setupGoogleAccountSection() {
        binding.googleWebClientIdInput.setText(Prefs.getGoogleWebClientId(this).orEmpty())
        binding.googleWebClientIdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                Prefs.setGoogleWebClientId(this, binding.googleWebClientIdInput.text?.toString().orEmpty().trim())
            }
        }
        binding.addGoogleAccountButton.setOnClickListener { addGoogleAccount() }
        refreshGoogleAccountsList()
    }

    private fun addGoogleAccount() {
        // Diagnostic conserve : confirme que le clic est bien recu (utile si un futur probleme
        // similaire refait surface). Voir GoogleAccountController.getLegacySignInIntent pour le
        // pourquoi du passage a l'ancienne API GoogleSignInClient (Credential Manager restait
        // bloque indefiniment sur Xiaomi/MIUI -- confirme par l'utilisateur, timeout 20s
        // declenche alors que compte Google + Play Services etaient en ordre).
        Log.d("JarvisGoogleAuth", "addGoogleAccount() déclenché par un clic")
        Toast.makeText(this, "Tentative de connexion Google...", Toast.LENGTH_SHORT).show()

        val webClientId = binding.googleWebClientIdInput.text?.toString()?.trim().orEmpty()
        if (webClientId.isBlank()) {
            Toast.makeText(this, getString(R.string.google_web_client_id_missing), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setGoogleWebClientId(this, webClientId)

        try {
            // signOutThenGetLegacySignInIntent (pas getLegacySignInIntent directement) : sans
            // ça, le sélecteur de compte peut être sauté et reconnecter silencieusement le
            // dernier compte utilisé, empêchant d'en ajouter un 2e/3e -- voir le commentaire de
            // cette fonction dans GoogleAccountController.
            GoogleAccountController.signOutThenGetLegacySignInIntent(this, webClientId) { intent ->
                googleLegacySignInLauncher.launch(intent)
            }
        } catch (e: Exception) {
            Log.e("JarvisGoogleAuth", "Impossible de lancer l'écran de connexion Google", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            )
        }
    }

    /**
     * Callback de googleLegacySignInLauncher (voir sa déclaration) une fois l'écran de
     * sélection de compte système refermé -- succès ou échec/annulation.
     */
    private fun onLegacySignInResult(data: android.content.Intent?) {
        try {
            val account = GoogleAccountController.handleLegacySignInResult(data)
            val email = account.email ?: account.id ?: "?"
            val displayName = account.displayName ?: email

            val accounts = Prefs.loadGoogleAccounts(this)
            if (accounts.none { it.email == email }) {
                accounts.add(GoogleAccountController.LinkedAccount(email, displayName))
                Prefs.saveGoogleAccounts(this, accounts)
                refreshGoogleAccountsList()
            }

            // Demande immédiatement l'autorisation Gmail/Agenda pour ce compte -- demande
            // explicite de l'utilisateur : "accès complet" au compte, pas juste l'identité.
            // Le jeton obtenu est mis en cache (Prefs.setGoogleAccessToken) pour que
            // MainActivity puisse l'utiliser directement au prochain message du chat.
            GoogleAccountController.requestAuthorization(
                activity = this,
                pendingIntentLauncher = googleAuthorizationLauncher,
                onGranted = { accessToken ->
                    if (accessToken != null) {
                        Prefs.setGoogleAccessToken(this, accessToken)
                        // Voir Prefs.setGoogleAccessTokenForAccount -- en plus du jeton "actif"
                        // unique ci-dessus (utilisé pour les écritures : créer/supprimer un
                        // événement, envoyer un mail), on retient CE jeton pour CE compte
                        // précisément, ce qui permet de lire l'agenda/les mails de plusieurs
                        // comptes en même temps (voir MainActivity.ensureGoogleTokensForAllAccounts)
                        // tant qu'aucun des jetons obtenus n'a expiré (~55 min).
                        Prefs.setGoogleAccessTokenForAccount(this, email, accessToken)
                    }
                    // Voir Prefs.getActiveGoogleAccountEmail -- un seul jeton "actif" à la fois
                    // pour les écritures (contrainte de l'API Google elle-même, pas de JARVIS) --
                    // ce compte devient celui utilisé pour créer/supprimer un événement ou
                    // envoyer un mail jusqu'au prochain changement (les LECTURES, elles, utilisent
                    // tous les comptes autorisés simultanément, voir ci-dessus).
                    Prefs.setActiveGoogleAccountEmail(this, email)
                    refreshGoogleAccountsList()
                    Toast.makeText(
                        this,
                        getString(R.string.google_account_linked, email),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { e ->
                    Log.e("JarvisGoogleAuth", "requestAuthorization a échoué", e)
                    showCopyableErrorDialog(
                        getString(R.string.google_authorization_error, ""),
                        "${e.javaClass.simpleName}: ${e.message ?: "?"}"
                    )
                }
            )
        } catch (e: ApiException) {
            // Codes fréquents : 12501 = annulé par l'utilisateur (pas une vraie erreur, message
            // adapté) ; 10 = DEVELOPER_ERROR (SHA-1/package non enregistré comme client OAuth
            // "Android" côté Cloud Console) ; 7 = erreur réseau.
            if (e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                Log.d("JarvisGoogleAuth", "Connexion Google annulée par l'utilisateur")
                return
            }
            Log.e("JarvisGoogleAuth", "Legacy signIn a échoué (code ${e.statusCode})", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "ApiException (code ${e.statusCode}): " +
                    "${GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)}\n\n" +
                    "Si le code est 10 (DEVELOPER_ERROR), le SHA-1/package de l'appli n'est " +
                    "probablement pas enregistré comme identifiant OAuth de type \"Android\" " +
                    "dans Google Cloud Console."
            )
        } catch (e: Exception) {
            Log.e("JarvisGoogleAuth", "Legacy signIn a échoué", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            )
        }
    }

    /**
     * Les Toast disparaissent en ~3s -- trop court pour lire/recopier une erreur technique
     * (vécu : l'utilisateur ne pouvait recopier que des bribes du message). Une AlertDialog
     * reste affichée et le texte peut être copié dans le presse-papier pour être recollé
     * tel quel dans le chat JARVIS ou envoyé en support.
     */
    private fun showCopyableErrorDialog(title: String, detail: String) {
        // Si l'Activity a été détruite/recréée entre-temps (voir CancellationException
        // ci-dessus), afficher une AlertDialog planterait avec BadTokenException -- on
        // vérifie et on se contente du log dans ce cas (déjà écrit avant cet appel).
        if (isFinishing || isDestroyed) {
            Log.w("JarvisGoogleAuth", "Dialogue d'erreur ignoré (Activity finissante/détruite): $detail")
            return
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(title.trim().ifBlank { "Erreur" })
                .setMessage(detail)
                .setPositiveButton("Copier") { dialog, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Erreur JARVIS", detail))
                    Toast.makeText(this, "Copié.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Fermer", null)
                .show()
        } catch (e: Exception) {
            Log.e("JarvisGoogleAuth", "Impossible d'afficher le dialogue d'erreur", e)
        }
    }

    private fun refreshGoogleAccountsList() {
        binding.googleAccountsContainer.removeAllViews()
        val accounts = Prefs.loadGoogleAccounts(this)
        if (accounts.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.google_no_accounts)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                textSize = 13f
                setPadding(dpToPx(20), 0, dpToPx(20), 0)
            }
            binding.googleAccountsContainer.addView(empty)
            return
        }

        // Voir Prefs.getActiveGoogleAccountEmail -- l'API Google n'autorise qu'un seul jeton
        // "par défaut" à la fois (voir developer.android.com/identity/authorization,
        // "Authorization from a non-default account"), donc parmi les comptes LIÉS, un seul
        // est réellement utilisé pour Agenda/Mail à un instant donné -- ce label rend ça visible
        // au lieu de laisser croire que tous les comptes sont interrogés simultanément.
        val activeEmail = Prefs.getActiveGoogleAccountEmail(this)
        val readableAccounts = Prefs.getAllValidGoogleAccountTokens(this).keys
        accounts.forEach { account ->
            val isActive = account.email == activeEmail
            val isReadable = account.email in readableAccounts
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_model_row)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dpToPx(16), 0, dpToPx(16), dpToPx(8)) }
            }
            val label = TextView(this).apply {
                val identity = if (account.displayName.isNotBlank() && account.displayName != account.email) {
                    "${account.displayName}\n${account.email}"
                } else {
                    account.email
                }
                text = when {
                    isActive -> "✅ $identity\n(actif -- création/suppression/envoi)"
                    isReadable -> "🔓 $identity\n(lecture Agenda/Mail active)"
                    else -> identity
                }
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (!isActive) {
                val activate = TextView(this).apply {
                    text = "Activer"
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_default))
                    textSize = 12f
                    setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                    setOnClickListener { activateGoogleAccount(account) }
                }
                buttons.addView(activate)
            }
            val unlink = TextView(this).apply {
                text = getString(R.string.google_unlink_button)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_rouge))
                textSize = 12f
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                setOnClickListener { unlinkGoogleAccount(account) }
            }
            buttons.addView(unlink)
            row.addView(label)
            row.addView(buttons)
            binding.googleAccountsContainer.addView(row)
        }
    }

    /**
     * Relance le sélecteur de compte système pour que l'utilisateur choisisse [account] --
     * l'API Google (AuthorizationClient) ne permet pas de "basculer" silencieusement vers un
     * compte déjà lié sans repasser par ce sélecteur (voir commentaire de refreshGoogleAccountsList
     * ci-dessus). Réutilise exactement le même mécanisme que l'ajout d'un nouveau compte.
     */
    private fun activateGoogleAccount(account: GoogleAccountController.LinkedAccount) {
        val webClientId = Prefs.getGoogleWebClientId(this).orEmpty()
        if (webClientId.isBlank()) {
            Toast.makeText(this, getString(R.string.google_web_client_id_missing), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Choisis « ${account.email} » dans le sélecteur pour l'activer.", Toast.LENGTH_LONG).show()
        try {
            GoogleAccountController.signOutThenGetLegacySignInIntent(this, webClientId) { intent ->
                googleLegacySignInLauncher.launch(intent)
            }
        } catch (e: Exception) {
            Log.e("JarvisGoogleAuth", "Impossible de lancer l'écran de connexion Google", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            )
        }
    }

    private fun unlinkGoogleAccount(account: GoogleAccountController.LinkedAccount) {
        val remaining = Prefs.loadGoogleAccounts(this).filterNot { it.email == account.email }
        Prefs.saveGoogleAccounts(this, remaining)
        refreshGoogleAccountsList()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
