package com.jarvis.assistant

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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

        setupModelSelection()
        setupGoogleAccountSection()
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
     * Choix du backend IA (Gemini Nano via AICore, ou Gemma 3 1B en local via LiteRT-LM --
     * voir GemmaController). Gemma nécessite un jeton Hugging Face + le téléchargement du
     * modèle (~555 Mo), donc la section correspondante n'apparaît que quand Gemma est
     * sélectionné.
     */
    private fun setupModelSelection() {
        binding.hfTokenInput.setText(Prefs.getHfToken(this).orEmpty())
        binding.hfTokenInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setHfToken(this, binding.hfTokenInput.text?.toString().orEmpty().trim())
        }

        binding.modelGeminiNanoRow.setOnClickListener { selectModel(Prefs.MODEL_GEMINI_NANO) }
        binding.modelGemmaRow.setOnClickListener { selectModel(Prefs.MODEL_GEMMA) }

        binding.downloadModelButton.setOnClickListener { confirmAndDownloadGemma() }
        binding.deleteModelButton.setOnClickListener {
            GemmaController.deleteModel(this)
            refreshModelUi()
        }

        refreshModelSelectionUi()
        refreshModelUi()
    }

    private fun selectModel(model: String) {
        Prefs.setSelectedModel(this, model)
        refreshModelSelectionUi()
    }

    private fun refreshModelSelectionUi() {
        val selected = Prefs.getSelectedModel(this)
        val isGemma = selected == Prefs.MODEL_GEMMA

        binding.modelGeminiNanoRow.background = ContextCompat.getDrawable(
            this, if (isGemma) R.drawable.bg_model_row else R.drawable.bg_model_row_selected
        )
        binding.modelGeminiNanoCheck.visibility = if (isGemma) View.GONE else View.VISIBLE

        binding.modelGemmaRow.background = ContextCompat.getDrawable(
            this, if (isGemma) R.drawable.bg_model_row_selected else R.drawable.bg_model_row
        )
        binding.modelGemmaCheck.visibility = if (isGemma) View.VISIBLE else View.GONE

        val gemmaSectionVisibility = if (isGemma) View.VISIBLE else View.GONE
        binding.gemmaConfigSection.visibility = gemmaSectionVisibility
        binding.hfTokenInput.visibility = gemmaSectionVisibility
        binding.hfTokenHelpText.visibility = gemmaSectionVisibility
        binding.modelStatusText.visibility = gemmaSectionVisibility
        refreshModelUi()
    }

    private fun refreshModelUi() {
        if (Prefs.getSelectedModel(this) != Prefs.MODEL_GEMMA) return
        val downloaded = GemmaController.isDownloaded(this)
        binding.modelStatusText.text = getString(
            if (downloaded) R.string.model_downloaded_status else R.string.model_not_downloaded_status
        )
        binding.downloadModelButton.visibility = if (downloaded) View.GONE else View.VISIBLE
        binding.deleteModelButton.visibility = if (downloaded) View.VISIBLE else View.GONE
    }

    private fun confirmAndDownloadGemma() {
        val token = binding.hfTokenInput.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            binding.modelStatusText.text = getString(R.string.hf_token_missing)
            return
        }
        Prefs.setHfToken(this, token)

        AlertDialog.Builder(this)
            .setTitle(R.string.model_download_confirm_title)
            .setMessage(R.string.model_download_confirm_message)
            .setPositiveButton(R.string.download_model_button) { _, _ -> startGemmaDownload(token) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startGemmaDownload(token: String) {
        binding.downloadModelButton.visibility = View.GONE
        binding.modelDownloadProgress.visibility = View.VISIBLE
        binding.modelDownloadProgress.progress = 0

        lifecycleScope.launch {
            try {
                GemmaController.download(this@SettingsActivity, token) { downloaded, total ->
                    if (total > 0) {
                        val percent = ((downloaded * 100) / total).toInt()
                        runOnUiThread { binding.modelDownloadProgress.progress = percent }
                    }
                }
                binding.modelDownloadProgress.visibility = View.GONE
                refreshModelUi()
            } catch (e: Exception) {
                binding.modelDownloadProgress.visibility = View.GONE
                binding.downloadModelButton.visibility = View.VISIBLE
                binding.modelStatusText.text = "❌ ${e.message}"
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
        val webClientId = binding.googleWebClientIdInput.text?.toString()?.trim().orEmpty()
        if (webClientId.isBlank()) {
            Toast.makeText(this, getString(R.string.google_web_client_id_missing), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setGoogleWebClientId(this, webClientId)

        lifecycleScope.launch {
            try {
                // onlyAuthorized = false : montre TOUS les comptes Google du téléphone, pas
                // seulement ceux déjà liés -- c'est ce qui permet d'en ajouter un nouveau (voir
                // GoogleAccountController -- multi-comptes).
                val credential = GoogleAccountController.signIn(this@SettingsActivity, webClientId, onlyAuthorized = false)
                val email = credential.email ?: credential.id
                val displayName = credential.displayName ?: email

                val accounts = Prefs.loadGoogleAccounts(this@SettingsActivity)
                if (accounts.none { it.email == email }) {
                    accounts.add(GoogleAccountController.LinkedAccount(email, displayName))
                    Prefs.saveGoogleAccounts(this@SettingsActivity, accounts)
                    refreshGoogleAccountsList()
                }

                // Demande immédiatement l'autorisation Gmail/Agenda pour ce compte -- demande
                // explicite de l'utilisateur : "accès complet" au compte, pas juste l'identité.
                // Le jeton obtenu est mis en cache (Prefs.setGoogleAccessToken) pour que
                // MainActivity puisse l'utiliser directement au prochain message du chat.
                GoogleAccountController.requestAuthorization(
                    activity = this@SettingsActivity,
                    pendingIntentLauncher = googleAuthorizationLauncher,
                    onGranted = { accessToken ->
                        if (accessToken != null) Prefs.setGoogleAccessToken(this@SettingsActivity, accessToken)
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.google_account_linked, email),
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.google_authorization_error, e.message ?: "?"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.google_signin_error, e.message ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
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

        accounts.forEach { account ->
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
                text = if (account.displayName.isNotBlank() && account.displayName != account.email) {
                    "${account.displayName}\n${account.email}"
                } else {
                    account.email
                }
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val unlink = TextView(this).apply {
                text = getString(R.string.google_unlink_button)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_rouge))
                textSize = 12f
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                setOnClickListener { unlinkGoogleAccount(account) }
            }
            row.addView(label)
            row.addView(unlink)
            binding.googleAccountsContainer.addView(row)
        }
    }

    private fun unlinkGoogleAccount(account: GoogleAccountController.LinkedAccount) {
        val remaining = Prefs.loadGoogleAccounts(this).filterNot { it.email == account.email }
        Prefs.saveGoogleAccounts(this, remaining)
        refreshGoogleAccountsList()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
