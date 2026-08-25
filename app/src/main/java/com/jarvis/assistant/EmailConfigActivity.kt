package com.jarvis.assistant

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityEmailConfigBinding
import kotlinx.coroutines.launch

/**
 * Écran de configuration des comptes email (IMAP/SMTP) -- porté depuis l'ancienne version de ce
 * projet à la demande explicite de l'utilisateur ("regarde comme c'était sur l'ancienne appli"),
 * simplifié pour ne garder QUE le chemin mot de passe d'application (voir EmailController.kt
 * pour le pourquoi -- aucune Console Cloud, contrairement à l'ancienne tentative OAuth2).
 *
 * Multi-fournisseurs dès le départ (pas seulement Gmail) : boutons de préréglage qui pré-
 * remplissent juste les serveurs IMAP/SMTP (voir Prefs.EmailAccount.preset), l'utilisateur peut
 * aussi les modifier manuellement pour un fournisseur non listé.
 */
class EmailConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.emailBackButton.setOnClickListener { finish() }

        binding.presetGmailButton.setOnClickListener { applyPreset("gmail") }
        binding.presetOutlookButton.setOnClickListener { applyPreset("outlook") }
        binding.presetYahooButton.setOnClickListener { applyPreset("yahoo") }
        binding.presetIcloudButton.setOnClickListener { applyPreset("icloud") }

        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.saveEmailAccountButton.setOnClickListener { saveAccount() }

        refreshAccountsList()
    }

    /** Pré-remplit les champs serveurs IMAP/SMTP pour un fournisseur connu (voir Prefs.EmailAccount.preset). */
    private fun applyPreset(service: String) {
        val email = binding.emailAddressInput.text?.toString().orEmpty()
        val preset = Prefs.EmailAccount.preset(service, email, "") ?: return
        if (binding.emailLabelInput.text.isNullOrBlank()) binding.emailLabelInput.setText(preset.label)
        binding.imapHostInput.setText(preset.imapHost)
        binding.imapPortInput.setText(preset.imapPort.toString())
        binding.smtpHostInput.setText(preset.smtpHost)
        binding.smtpPortInput.setText(preset.smtpPort.toString())
    }

    private fun buildAccountFromForm(): Prefs.EmailAccount? {
        val label = binding.emailLabelInput.text?.toString()?.trim().orEmpty()
        val email = binding.emailAddressInput.text?.toString()?.trim().orEmpty()
        // Les espaces dans un mot de passe d'application collé depuis Google (affiché en 4
        // groupes de 4 lettres) doivent être retirés -- IMAP/SMTP le refusent sinon.
        val password = binding.emailPasswordInput.text?.toString()?.trim()?.replace(" ", "").orEmpty()
        val imapHost = binding.imapHostInput.text?.toString()?.trim().orEmpty()
        val imapPort = binding.imapPortInput.text?.toString()?.trim()?.toIntOrNull() ?: 993
        val smtpHost = binding.smtpHostInput.text?.toString()?.trim().orEmpty()
        val smtpPort = binding.smtpPortInput.text?.toString()?.trim()?.toIntOrNull() ?: 587

        if (email.isBlank() || password.isBlank() || imapHost.isBlank() || smtpHost.isBlank()) return null

        return Prefs.EmailAccount(
            label = label.ifBlank { email },
            email = email,
            password = password,
            imapHost = imapHost,
            imapPort = imapPort,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpStartTls = true
        )
    }

    private fun testConnection() {
        val account = buildAccountFromForm()
        binding.emailTestResultText.visibility = android.view.View.VISIBLE
        if (account == null) {
            binding.emailTestResultText.text = getString(R.string.email_missing_fields)
            return
        }
        binding.emailTestResultText.text = "🔄 Test de connexion en cours…"
        lifecycleScope.launch {
            binding.emailTestResultText.text = EmailController.testConnection(account)
        }
    }

    private fun saveAccount() {
        val account = buildAccountFromForm()
        if (account == null) {
            binding.emailTestResultText.visibility = android.view.View.VISIBLE
            binding.emailTestResultText.text = getString(R.string.email_missing_fields)
            return
        }
        Prefs.addEmailAccount(this, account)
        refreshAccountsList()
        binding.emailTestResultText.visibility = android.view.View.VISIBLE
        binding.emailTestResultText.text = "✅ Compte ${account.email} enregistré."
    }

    private fun refreshAccountsList() {
        binding.emailAccountsContainer.removeAllViews()
        val accounts = Prefs.getEmailAccounts(this)
        if (accounts.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.email_no_accounts)
                setTextColor(ContextCompat.getColor(this@EmailConfigActivity, R.color.text_secondary))
                textSize = 13f
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            binding.emailAccountsContainer.addView(empty)
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
                ).apply { setMargins(0, 0, 0, dpToPx(8)) }
            }
            val label = TextView(this).apply {
                val badge = if (account.isDefault) getString(R.string.email_default_badge) else ""
                text = "${account.label}\n${account.email}$badge"
                setTextColor(ContextCompat.getColor(this@EmailConfigActivity, R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delete = TextView(this).apply {
                text = getString(R.string.email_delete_button)
                setTextColor(ContextCompat.getColor(this@EmailConfigActivity, R.color.accent_rouge))
                textSize = 12f
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                setOnClickListener {
                    Prefs.removeEmailAccount(this@EmailConfigActivity, account.id)
                    refreshAccountsList()
                }
            }
            row.addView(label)
            row.addView(delete)
            binding.emailAccountsContainer.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
