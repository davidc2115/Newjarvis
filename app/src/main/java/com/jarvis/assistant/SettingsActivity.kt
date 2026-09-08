package com.jarvis.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var providerSpinner: Spinner
    private lateinit var tabCloud: TextView
    private lateinit var tabApiKeys: TextView
    private lateinit var tabLocal: TextView
    private lateinit var tabSystem: TextView
    private lateinit var panelCloud: View
    private lateinit var panelApiKeys: View
    private lateinit var panelLocal: View
    private lateinit var panelSystem: View

    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var autoInfoText: View
    private lateinit var advancedConfigSection: View
    private lateinit var apiKeysContainer: LinearLayout

    private lateinit var hfTokenInput: EditText
    private lateinit var downloadProgressText: TextView
    private lateinit var aiCoreStatusDot: View
    private lateinit var aiCoreStatusText: TextView
    private lateinit var aiCoreProgressText: TextView
    private lateinit var aiCoreActionButton: TextView


    private lateinit var colorCarousel: RecyclerView
    private lateinit var orbStyleCarousel: RecyclerView
    private lateinit var colorCarouselAdapter: ColorCarouselAdapter
    private lateinit var orbStyleCarouselAdapter: OrbStyleCarouselAdapter
    private val carouselColors = listOf(
        Color.parseColor("#00E5FF"), Color.parseColor("#FF3B30"), Color.parseColor("#2979FF"),
        Color.parseColor("#B388FF"), Color.parseColor("#FFC400"), Color.parseColor("#00E676")
    )

    private var selectedProvider: Provider = Provider.GROQ
    private var selectedAccentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
    private var selectedOrbStyle: String = "PULSE"
    private var isDownloading = false

    private val apiKeyFields = mutableMapOf<Provider, EditText>()

    private val pickSdModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importSdModelFile(uri)
    }

    // Cause réelle trouvée du bug "l'écoute permanente ne fonctionne pas" : le bouton
    // ACTIVER se contentait de vérifier la permission micro et abandonnait avec un Toast
    // si elle manquait, sans jamais afficher la popup de demande d'autorisation Android —
    // seul le bouton micro du chat/mode vocal la déclenchait. Un utilisateur qui active
    // l'écoute permanente en premier, avant d'avoir jamais utilisé le mode vocal manuel,
    // ne pouvait donc JAMAIS l'activer tant qu'il n'allait pas cocher la permission dans
    // les réglages système Android lui-même.
    private val wakeWordMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startWakeWordServiceNow()
        else {
            Prefs.saveWakeWordEnabled(this, false)
            Toast.makeText(this, "❌ Permission micro refusée — l'écoute permanente reste désactivée", Toast.LENGTH_LONG).show()
        }
        updateWakeWordButtonLabel(findViewById(R.id.toggleWakeWordButton))
    }

    private fun startWakeWordServiceNow() {
        val serviceIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        Toast.makeText(this, "✅ Écoute permanente activée", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        BottomNav.setup(this, NavDestination.SETTINGS)
        EdgeToEdgeHelper.applyTopInset(findViewById(R.id.rootLayout))
        EdgeToEdgeHelper.applyBottomInset(findViewById(R.id.bottomNavRoot))

        findViewById<TextView>(R.id.subNavDashboard).setOnClickListener {
            startActivity(Intent(this, PhoneControlActivity::class.java))
        }
        findViewById<TextView>(R.id.subNavObsidian).setOnClickListener {
            startActivity(Intent(this, ObsidianActivity::class.java))
        }
        // subNavParams : déjà sur cet écran, pas de navigation nécessaire.

        providerSpinner       = findViewById(R.id.providerSpinner)
        tabCloud              = findViewById(R.id.tabCloud)
        tabApiKeys            = findViewById(R.id.tabApiKeys)
        tabLocal               = findViewById(R.id.tabLocal)
        tabSystem             = findViewById(R.id.tabSystem)
        panelCloud            = findViewById(R.id.panelCloud)
        panelApiKeys          = findViewById(R.id.panelApiKeys)
        panelLocal            = findViewById(R.id.panelLocal)
        panelSystem           = findViewById(R.id.panelSystem)

        baseUrlInput          = findViewById(R.id.baseUrlInput)
        modelInput            = findViewById(R.id.modelInput)
        apiKeyInput           = findViewById(R.id.apiKeyInput)
        autoInfoText          = findViewById(R.id.autoInfoText)
        advancedConfigSection = findViewById(R.id.advancedConfigSection)
        apiKeysContainer      = findViewById(R.id.apiKeysContainer)

        hfTokenInput          = findViewById(R.id.hfTokenInput)
        downloadProgressText  = findViewById(R.id.downloadProgressText)
        aiCoreStatusDot       = findViewById(R.id.aiCoreStatusDot)
        aiCoreStatusText      = findViewById(R.id.aiCoreStatusText)
        aiCoreProgressText    = findViewById(R.id.aiCoreProgressText)
        aiCoreActionButton    = findViewById(R.id.aiCoreActionButton)


        colorCarousel         = findViewById(R.id.colorCarousel)
        orbStyleCarousel      = findViewById(R.id.orbStyleCarousel)

        setupTabs()
        setupProviderSpinner()
        setupColorAndStyleCarousels()
        buildApiKeyFields()
        loadSavedValues()
        setupButtons()
        setupAiCoreSection()
    }

    private fun setupTabs() {
        showTab("cloud")
        tabCloud.setOnClickListener  { showTab("cloud") }
        tabApiKeys.setOnClickListener { showTab("apikeys") }
        tabLocal.setOnClickListener  { showTab("local") }
        tabSystem.setOnClickListener { showTab("system") }
    }

    private fun showTab(tab: String) {
        panelCloud.visibility   = if (tab == "cloud")   View.VISIBLE else View.GONE
        panelApiKeys.visibility = if (tab == "apikeys") View.VISIBLE else View.GONE
        panelLocal.visibility   = if (tab == "local")   View.VISIBLE else View.GONE
        panelSystem.visibility  = if (tab == "system")  View.VISIBLE else View.GONE

        tabCloud.alpha   = if (tab == "cloud")   1f else 0.45f
        tabApiKeys.alpha = if (tab == "apikeys") 1f else 0.45f
        tabLocal.alpha   = if (tab == "local")   1f else 0.45f
        tabSystem.alpha  = if (tab == "system")  1f else 0.45f
    }

    private fun setupProviderSpinner() {
        val currentProvider = Prefs.getProvider(this)
        selectedProvider = currentProvider

        val names = Provider.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter
        providerSpinner.setSelection(Provider.entries.indexOf(currentProvider))
        updateCloudSection(currentProvider)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = Provider.entries[position]
                selectedProvider = provider
                updateCloudSection(provider)
                if (!provider.isLocal && !provider.isAuto) {
                    baseUrlInput.setText(provider.defaultBaseUrl)
                    modelInput.setText(provider.defaultModel)
                    apiKeyInput.setText(Prefs.getApiKeyFor(this@SettingsActivity, provider))
                }

                // Sauvegarde immédiate du choix, quelle que soit la page où l'utilisateur
                // navigue ensuite — évite de perdre la sélection en changeant d'onglet
                // sans être passé par le bouton ENREGISTRER de l'onglet Config.
                // Exception : Ollama/Custom nécessitent une URL saisie manuellement,
                // donc on attend le clic explicite sur ENREGISTRER pour ceux-là.
                if (provider != Provider.OLLAMA && provider != Provider.CUSTOM) {
                    Prefs.save(
                        this@SettingsActivity,
                        provider,
                        baseUrlInput.text.toString().trim(),
                        modelInput.text.toString().trim(),
                        apiKeyInput.text.toString().trim()
                    )
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateCloudSection(provider: Provider) {
        autoInfoText.visibility  = if (provider.isAuto) View.VISIBLE else View.GONE
        // Seuls Ollama et Custom nécessitent de préciser une URL/modèle manuellement.
        // Pour tous les autres, l'onglet Config se limite au choix de l'IA.
        val needsAdvanced = provider == Provider.OLLAMA || provider == Provider.CUSTOM
        advancedConfigSection.visibility = if (needsAdvanced) View.VISIBLE else View.GONE
        val showCloud = !provider.isLocal && !provider.isAuto
        baseUrlInput.isEnabled = showCloud
        modelInput.isEnabled   = showCloud
        apiKeyInput.isEnabled  = showCloud && provider.needsApiKey
    }

    // Courte description + domaine où obtenir une clé, affichés sous chaque fournisseur pour
    // que l'onglet Clés API soit compréhensible sans devoir deviner lequel choisir. Ordre
    // volontairement identique à Provider.CLOUD_KEY_PROVIDERS (= l'ordre de repli du mode
    // Automatique) : le premier configuré et joignable répond en premier.
    private data class ApiKeyInfo(val description: String, val getKeyDomain: String)

    private val apiKeyInfo: Map<Provider, ApiKeyInfo> = mapOf(
        Provider.GROQ to ApiKeyInfo(
            "Gratuit, très rapide. Quota limité (8 000 tokens/min, 30 requêtes/min) — idéal en premier essai, moins pour de longues conversations.",
            "console.groq.com"
        ),
        Provider.OPENAI to ApiKeyInfo(
            "ChatGPT (GPT-4o mini). Payant après le petit crédit d'essai offert à l'inscription.",
            "platform.openai.com"
        ),
        Provider.CLAUDE to ApiKeyInfo(
            "Anthropic Claude. Payant après le petit crédit d'essai offert à l'inscription — très fiable pour le code et les tâches complexes.",
            "console.anthropic.com"
        ),
        Provider.GEMINI to ApiKeyInfo(
            "Google Gemini. Gratuit avec un quota nettement plus généreux que Groq (~250 000 tokens/min) — recommandé comme repli principal.",
            "aistudio.google.com"
        ),
        Provider.MISTRAL to ApiKeyInfo(
            "IA française. Offre gratuite disponible, quota limité.",
            "console.mistral.ai"
        ),
        Provider.DEEPSEEK to ApiKeyInfo(
            "Très économique. Petit quota gratuit à l'inscription.",
            "platform.deepseek.com"
        ),
        Provider.PERPLEXITY to ApiKeyInfo(
            "Orienté recherche web à jour. Payant.",
            "perplexity.ai"
        ),
        Provider.TOGETHER to ApiKeyInfo(
            "Quelques dollars de crédit gratuit offerts à l'inscription.",
            "api.together.ai"
        ),
        Provider.OPENROUTER to ApiKeyInfo(
            "Accès à de nombreux modèles (dont certains gratuits) via une seule clé.",
            "openrouter.ai"
        ),
        Provider.SERPAPI to ApiKeyInfo(
            "Recherche Google en direct — sert à web_search, pas au chat. Payant après l'essai gratuit.",
            "serpapi.com"
        )
    )

    // Petite icône distinctive par fournisseur, purement décorative (pas de vraie iconographie
    // de marque disponible hors-ligne) — aide à repérer un fournisseur d'un coup d'œil dans la
    // liste plutôt que de devoir lire chaque nom.
    private val providerIcon: Map<Provider, String> = mapOf(
        Provider.GROQ to "⚡",
        Provider.OPENAI to "🟢",
        Provider.CLAUDE to "🟣",
        Provider.GEMINI to "✨",
        Provider.MISTRAL to "🌀",
        Provider.DEEPSEEK to "🐳",
        Provider.PERPLEXITY to "🔎",
        Provider.TOGETHER to "🤝",
        Provider.OPENROUTER to "🌐",
        Provider.SERPAPI to "🔍"
    )

    private fun buildApiKeyFields() {
        apiKeysContainer.removeAllViews()
        apiKeyFields.clear()
        val dp = resources.displayMetrics.density

        for (provider in Provider.CLOUD_KEY_PROVIDERS) {
            // SerpAPI a un rôle différent (recherche web, pas génération de réponse) : on le
            // sépare visuellement du reste (même style d'étiquette de section que "🏠 DOMOTIQUE
            // & RÉSEAU" dans activity_smart_home.xml) pour éviter la confusion "pourquoi cette
            // clé-là ne répond jamais dans le chat ?".
            if (provider == Provider.SERPAPI) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                        it.topMargin = (8 * dp).toInt(); it.bottomMargin = (16 * dp).toInt()
                    }
                    setBackgroundColor(getColor(R.color.text_secondary))
                    alpha = 0.15f
                }
                apiKeysContainer.addView(divider)

                val sectionLabel = TextView(this).apply {
                    text = "🔍 RECHERCHE WEB — FONCTION DIFFÉRENTE DU CHAT"
                    setTextColor(getColor(R.color.violet_accent))
                    textSize = 11f
                    setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    letterSpacing = 0.06f
                    setPadding(2, 0, 0, (10 * dp).toInt())
                }
                apiKeysContainer.addView(sectionLabel)
            }

            val info = apiKeyInfo[provider]
            val hasKey = Prefs.getApiKeyFor(this, provider).isNotBlank()

            // ── Carte fournisseur ────────────────────────────────────────────────
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_settings_card)
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (14 * dp).toInt() }
            }

            // Ligne d'en-tête : icône + nom + badge de statut
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (10 * dp).toInt() }
            }
            val iconChip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                gravity = android.view.Gravity.CENTER
                text = providerIcon[provider] ?: "🔑"
                textSize = 15f
                background = getDrawable(R.drawable.bg_icon_chip)
            }
            val nameText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginStart = (12 * dp).toInt(); it.marginEnd = (8 * dp).toInt()
                }
                text = provider.displayName
                setTextColor(getColor(R.color.text_primary))
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val statusBadge = TextView(this).apply {
                text = if (hasKey) "✓ Configurée" else "Non configurée"
                textSize = 9.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((9 * dp).toInt(), (4 * dp).toInt(), (9 * dp).toInt(), (4 * dp).toInt())
                setTextColor(getColor(if (hasKey) R.color.success_glow else R.color.text_secondary))
                background = getDrawable(R.drawable.bg_pill_badge)?.mutate()?.apply {
                    setTint(if (hasKey) Color.parseColor("#334ADE80") else Color.parseColor("#268D8A82"))
                }
            }
            headerRow.addView(iconChip)
            headerRow.addView(nameText)
            headerRow.addView(statusBadge)
            card.addView(headerRow)

            if (info != null) {
                val description = TextView(this).apply {
                    text = info.description
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 10.5f
                    setLineSpacing(2f, 1f)
                    setPadding(0, 0, 0, (10 * dp).toInt())
                }
                card.addView(description)
            }

            val field = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.input_height)
                ).also { it.bottomMargin = (8 * dp).toInt() }
                background = getDrawable(R.drawable.bg_input)
                setPadding(40, 0, 40, 0)
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Clé API ${provider.displayName}..."
                setText(Prefs.getApiKeyFor(this@SettingsActivity, provider))
                // Le badge "✓ Configurée" ne se met à jour qu'au prochain rebuild de la liste
                // (après ENREGISTRER) — pas besoin de watcher ici, juste une info au chargement.
            }
            card.addView(field)
            apiKeyFields[provider] = field

            if (info != null) {
                val getKeyLink = TextView(this).apply {
                    text = "🔗 Obtenir une clé sur ${info.getKeyDomain}"
                    setTextColor(getColor(R.color.cyan_accent))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    background = getDrawable(R.drawable.bg_quick_action_chip)
                    setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${info.getKeyDomain}")))
                        } catch (_: Exception) {
                            Toast.makeText(this@SettingsActivity, "Impossible d'ouvrir le lien.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                card.addView(getKeyLink)
            }

            apiKeysContainer.addView(card)
        }
    }


    private fun loadSavedValues() {
        hfTokenInput.setText(Prefs.getHfToken(this))
        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        val initialProvider = Prefs.getProvider(this)
        apiKeyInput.setText(Prefs.getApiKeyFor(this, initialProvider).ifBlank { Prefs.getApiKey(this) })
    }

    private fun setupButtons() {
        val saveButton           = findViewById<TextView>(R.id.saveButton)
        val saveApiKeysButton    = findViewById<TextView>(R.id.saveApiKeysButton)
        val modelCardsContainer  = findViewById<LinearLayout>(R.id.modelCardsContainer)
        val wakeWordInput        = findViewById<EditText>(R.id.wakeWordInput)
        val toggleWakeWordButton = findViewById<TextView>(R.id.toggleWakeWordButton)
        val picovoiceKeyInput    = findViewById<EditText>(R.id.picovoiceKeyInput)

        wakeWordInput.setText(Prefs.getWakeWord(this))
        picovoiceKeyInput.setText(Prefs.getPicovoiceKey(this))
        updateWakeWordButtonLabel(toggleWakeWordButton)

        // ── Accès SMB (voir SmbController) — demandé explicitement, absent des Paramètres
        // jusqu'ici (seule la commande chat smb_configure existait pour le régler).
        val smbHostInput     = findViewById<EditText>(R.id.smbHostInput)
        val smbUsernameInput = findViewById<EditText>(R.id.smbUsernameInput)
        val smbPasswordInput = findViewById<EditText>(R.id.smbPasswordInput)
        val saveSmbButton    = findViewById<TextView>(R.id.saveSmbButton)

        smbHostInput.setText(Prefs.getSmbHost(this))
        smbUsernameInput.setText(Prefs.getSmbUsername(this))
        smbPasswordInput.setText(Prefs.getSmbPassword(this))

        saveSmbButton.setOnClickListener {
            val message = SmbController.configure(
                this,
                smbHostInput.text.toString().trim(),
                smbUsernameInput.text.toString().trim(),
                smbPasswordInput.text.toString()
            )
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        // ── Freebox OS (voir FreeboxController) — accès complet lecture/écriture,
        // distinct du partage SMB ci-dessus qui ne donne accès qu'aux fichiers.
        val freeboxHostInput     = findViewById<EditText>(R.id.freeboxHostInput)
        val freeboxAppIdInput    = findViewById<EditText>(R.id.freeboxAppIdInput)
        val freeboxAppTokenInput = findViewById<EditText>(R.id.freeboxAppTokenInput)
        val saveFreeboxButton    = findViewById<TextView>(R.id.saveFreeboxButton)

        freeboxHostInput.setText(Prefs.getFreeboxHost(this))
        freeboxAppIdInput.setText(Prefs.getFreeboxAppId(this))
        freeboxAppTokenInput.setText(Prefs.getFreeboxAppToken(this))

        saveFreeboxButton.setOnClickListener {
            val host = freeboxHostInput.text.toString().trim()
            Prefs.saveFreeboxHost(this, if (host.isBlank()) "http://mafreebox.freebox.fr" else host)
            Prefs.saveFreeboxAppId(this, freeboxAppIdInput.text.toString().trim())
            Prefs.saveFreeboxAppToken(this, freeboxAppTokenInput.text.toString().trim())
            Toast.makeText(this, "✅ Freebox enregistrée.", Toast.LENGTH_LONG).show()
        }

        // ── DuckDNS (voir DuckDnsController) — nom de domaine gratuit pour héberger
        // un site JARVIS directement depuis ce téléphone.
        val duckdnsDomainInput = findViewById<EditText>(R.id.duckdnsDomainInput)
        val duckdnsTokenInput  = findViewById<EditText>(R.id.duckdnsTokenInput)
        val saveDuckDnsButton  = findViewById<TextView>(R.id.saveDuckDnsButton)

        duckdnsDomainInput.setText(Prefs.getDuckDnsDomain(this))
        duckdnsTokenInput.setText(Prefs.getDuckDnsToken(this))

        saveDuckDnsButton.setOnClickListener {
            Prefs.saveDuckDnsDomain(this, duckdnsDomainInput.text.toString().trim())
            Prefs.saveDuckDnsToken(this, duckdnsTokenInput.text.toString().trim())
            Toast.makeText(this, "✅ DuckDNS enregistré.", Toast.LENGTH_LONG).show()
        }

        // ── Cartes dynamiques de modèles ──────────────────────────────────────
        modelCardsContainer.removeAllViews()
        ModelDownloader.MODEL_CATALOG.forEachIndexed { index, entry ->
            buildModelCard(modelCardsContainer, entry, index)
        }

        // ── Import fichier Stable Diffusion local ───────────────────────────────
        findViewById<TextView>(R.id.pickSdModelButton).setOnClickListener {
            pickSdModelLauncher.launch(arrayOf("*/*"))
        }
        updateSdModelLabel()
        findViewById<TextView>(R.id.deleteSdModelButton).setOnClickListener { deleteLocalSdModel() }

        // ── Sauvegarde paramètres cloud ───────────────────────────────────────
        saveButton.setOnClickListener {
            Prefs.save(
                this,
                selectedProvider,
                baseUrlInput.text.toString().trim(),
                modelInput.text.toString().trim(),
                apiKeyInput.text.toString().trim()
            )
            Prefs.saveHfToken(this, hfTokenInput.text.toString().trim())
            Prefs.saveAccentColor(this, selectedAccentColor)
            Prefs.saveOrbStyle(this, selectedOrbStyle)
            Toast.makeText(this, "✅ Paramètres enregistrés", Toast.LENGTH_SHORT).show()
        }

        saveApiKeysButton.setOnClickListener {
            val keys = apiKeyFields.mapValues { (_, field) -> field.text.toString().trim() }
            Prefs.saveApiKeys(this, keys)
            Toast.makeText(this, "✅ Toutes les clés API enregistrées", Toast.LENGTH_SHORT).show()
            // Reconstruit la liste pour rafraîchir les badges "✓ configurée" (les champs
            // gardent le texte déjà saisi car buildApiKeyFields relit Prefs, qui vient d'être
            // mis à jour ci-dessus).
            buildApiKeyFields()
        }

        toggleWakeWordButton.setOnClickListener {
            Prefs.saveWakeWord(this, wakeWordInput.text.toString().trim())
            Prefs.savePicovoiceKey(this, picovoiceKeyInput.text.toString().trim())
            val nowEnabled = !Prefs.isWakeWordEnabled(this)
            Prefs.saveWakeWordEnabled(this, nowEnabled)

            if (nowEnabled) {
                val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasMicPermission) {
                    startWakeWordServiceNow()
                } else {
                    // Avant : abandon silencieux avec juste un Toast, jamais de vraie demande
                    // de permission tant que l'utilisateur n'était pas passé par le mode vocal
                    // manuel — c'était la cause réelle du bug. On demande maintenant la
                    // permission directement ici ; le service démarre dans le callback
                    // ci-dessus si elle est accordée.
                    wakeWordMicPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            } else {
                stopService(Intent(this, WakeWordService::class.java))
                Toast.makeText(this, "Écoute permanente désactivée", Toast.LENGTH_SHORT).show()
            }
            updateWakeWordButtonLabel(toggleWakeWordButton)
        }
    }

    private fun updateWakeWordButtonLabel(button: TextView) {
        button.text = if (Prefs.isWakeWordEnabled(this)) "DÉSACTIVER L'ÉCOUTE PERMANENTE" else "ACTIVER L'ÉCOUTE PERMANENTE"
    }

    /** Crée une carte visuelle pour un modèle du catalogue. */
    private fun buildModelCard(container: LinearLayout, entry: ModelDownloader.ModelEntry, index: Int) {
        val dp = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            background = getDrawable(R.drawable.bg_bubble_ai)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        }

        // Nom du modèle + taille
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val titleText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text  = entry.label
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val sizeText = TextView(this).apply {
            text  = entry.sizeHint
            setTextColor(getColor(R.color.cyan_accent))
            textSize = 11f
        }
        titleRow.addView(titleText)
        titleRow.addView(sizeText)
        card.addView(titleRow)

        // Description
        val descText = TextView(this).apply {
            text = entry.description
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt())
        }
        card.addView(descText)

        // Badge "Jeton HF requis"
        if (entry.needsHfToken) {
            val badge = TextView(this).apply {
                text = "🔑 Jeton HuggingFace requis — entrez-le dans le champ ci-dessus"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 10f
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            card.addView(badge)
        }

        // Bouton télécharger
        val btnDownload = TextView(this).apply {
            text = "⬇ TÉLÉCHARGER SUR LE TÉLÉPHONE"
            setTextColor(getColor(R.color.background_dark))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            background = getDrawable(R.drawable.bg_mic_button)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startDownload(entry.url, useToken = entry.needsHfToken)
            }
        }
        card.addView(btnDownload)

        container.addView(card)
    }


    /**
     * Carrousels défilants pour la couleur et le style de l'orbe — remplacent les
     * anciennes rangées fixes de pastilles/boutons. Les deux restent synchronisés :
     * changer la couleur met immédiatement à jour l'aperçu live dans le carrousel
     * de styles, puisque chaque carte y affiche une vraie mini-instance d'OrbView.
     *
     * IMPORTANT : contrairement au reste de l'écran (qui n'est sauvegardé qu'au clic
     * sur ENREGISTRER, plus bas et facile à manquer), une sélection ici est enregistrée
     * IMMÉDIATEMENT — un choix de couleur/style qu'on oublie de "confirmer" via un
     * bouton lointain est l'explication la plus probable d'un orbe qui semble "ne
     * jamais changer" alors que le tapotement a bien été pris en compte à l'écran.
     */
    private fun setupColorAndStyleCarousels() {
        selectedAccentColor = Prefs.getAccentColor(this)
        selectedOrbStyle = Prefs.getOrbStyle(this)

        colorCarouselAdapter = ColorCarouselAdapter(this, carouselColors, selectedAccentColor) { color ->
            selectedAccentColor = color
            orbStyleCarouselAdapter.updateAccentColor(color)
            Prefs.saveAccentColor(this, color)
            Toast.makeText(this, "✅ Couleur enregistrée — relance le mode vocal pour la voir.", Toast.LENGTH_SHORT).show()
        }
        colorCarousel.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        colorCarousel.adapter = colorCarouselAdapter
        LinearSnapHelper().attachToRecyclerView(colorCarousel)

        val styleOptions = listOf("PULSE" to "Orbe pulsante", "NETWORK_SPHERE" to "Sphère réseau")
        orbStyleCarouselAdapter = OrbStyleCarouselAdapter(this, styleOptions, selectedOrbStyle, selectedAccentColor) { styleId ->
            selectedOrbStyle = styleId
            Prefs.saveOrbStyle(this, styleId)
            Toast.makeText(this, "✅ Style d'orbe enregistré — relance le mode vocal pour le voir.", Toast.LENGTH_SHORT).show()
        }
        orbStyleCarousel.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        orbStyleCarousel.adapter = orbStyleCarouselAdapter
        LinearSnapHelper().attachToRecyclerView(orbStyleCarousel)
    }

    private fun startDownload(url: String, useToken: Boolean) {
        if (isDownloading) {
            Toast.makeText(this, "Un téléchargement est déjà en cours…", Toast.LENGTH_SHORT).show()
            return
        }
        val hfToken = if (useToken) hfTokenInput.text.toString().trim() else ""
        isDownloading = true
        downloadProgressText.text = "⬇ Démarrage du téléchargement…"

        CoroutineScope(Dispatchers.Main).launch {
            ModelDownloader.download(this@SettingsActivity, url, hfToken) { progress ->
                runOnUiThread {
                    when (progress) {
                        is ModelDownloader.Progress.Percent -> downloadProgressText.text = "⬇ Téléchargement… ${progress.value}%"
                        is ModelDownloader.Progress.Done -> {
                            isDownloading = false
                            downloadProgressText.text = "✅ Modèle téléchargé et actif sur le téléphone !"
                            updateSdModelLabel()
                            Toast.makeText(this@SettingsActivity, "Modèle Stable Diffusion enregistré ✅", Toast.LENGTH_SHORT).show()
                        }
                        is ModelDownloader.Progress.Error -> {
                            isDownloading = false
                            downloadProgressText.text = ""
                            Toast.makeText(this@SettingsActivity, progress.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun importSdModelFile(uri: Uri) {
        Toast.makeText(this, "Import du modèle Stable Diffusion en cours… (peut prendre une minute, fichier volumineux)", Toast.LENGTH_LONG).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val destFile = File(filesDir, "local_sd_model.bin")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                }
                Prefs.saveLocalSdModelPath(this@SettingsActivity, destFile.absolutePath)
                NativeStableDiffusion.unload()

                runOnUiThread {
                    updateSdModelLabel()
                    Toast.makeText(this@SettingsActivity, "Modèle Stable Diffusion importé ✅", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Échec de l'import : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateSdModelLabel() {
        val path = Prefs.getLocalSdModelPath(this)
        val label = findViewById<TextView>(R.id.sdModelPathText)
        if (path.isBlank()) {
            label.text = "Aucun modèle importé"
        } else {
            val file = File(path)
            val sizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0
            label.text = "Modèle actif : ${file.name} (~${sizeMb} Mo)"
        }
    }

    private fun deleteLocalSdModel() {
        val path = Prefs.getLocalSdModelPath(this)
        if (path.isBlank()) {
            Toast.makeText(this, "Aucun modèle Stable Diffusion local à supprimer.", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Supprimer ce modèle ?")
            .setMessage("${File(path).name} sera effacé du téléphone. Tu pourras le retélécharger plus tard si besoin.")
            .setPositiveButton("Supprimer") { _, _ ->
                NativeStableDiffusion.unload()
                File(path).delete()
                Prefs.saveLocalSdModelPath(this, "")
                updateSdModelLabel()
                Toast.makeText(this, "🗑️ Modèle supprimé.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IA locale (AICore / Gemini Nano) — voir AiCoreManager.kt. Contrairement aux
    // anciens moteurs embarqués (MediaPipe/.gguf/.onnx), il n'y a plus de fichier de
    // modèle à télécharger/importer manuellement : Android gère lui-même la
    // distribution via AICore. Cette section se contente de vérifier la disponibilité
    // et de déclencher le téléchargement système si besoin.
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupAiCoreSection() {
        aiCoreActionButton.setOnClickListener { refreshAiCoreStatus(triggerDownloadIfNeeded = true) }
        refreshAiCoreStatus(triggerDownloadIfNeeded = false)
    }

    // Couleur du point de statut devant "GEMINI NANO" — même code couleur que les badges
    // "✓ Configurée" de l'onglet Clés API (vert=succès, ambre=action possible, rouge=erreur,
    // gris=vérification en cours), pour un langage visuel cohérent entre les deux onglets.
    private fun tintAiCoreStatusDot(colorHex: String) {
        aiCoreStatusDot.background = getDrawable(R.drawable.bg_status_dot)?.mutate()?.apply {
            setTint(Color.parseColor(colorHex))
        }
    }

    private fun refreshAiCoreStatus(triggerDownloadIfNeeded: Boolean) {
        aiCoreStatusText.text = "Statut : vérification…"
        aiCoreActionButton.isEnabled = false
        tintAiCoreStatusDot("#8D8A82")

        CoroutineScope(Dispatchers.Main).launch {
            when (val status = AiCoreManager.checkStatus()) {
                AiCoreManager.Status.AVAILABLE -> {
                    tintAiCoreStatusDot("#4ADE80")
                    aiCoreStatusText.text = "✅ Gemini Nano est disponible et prête à l'emploi sur cet appareil"
                    aiCoreProgressText.text = ""
                    aiCoreActionButton.text = "🔄 REVÉRIFIER"
                    aiCoreActionButton.isEnabled = true
                }
                AiCoreManager.Status.DOWNLOADABLE -> {
                    tintAiCoreStatusDot("#E8B84B")
                    aiCoreStatusText.text = "⬇️ Gemini Nano est téléchargeable — pas encore présente sur ce téléphone"
                    aiCoreActionButton.text = "⬇ TÉLÉCHARGER GEMINI NANO"
                    aiCoreActionButton.isEnabled = true
                    if (triggerDownloadIfNeeded) downloadAiCore()
                }
                AiCoreManager.Status.DOWNLOADING -> {
                    tintAiCoreStatusDot("#E8B84B")
                    aiCoreStatusText.text = "⬇ Téléchargement de Gemini Nano déjà en cours sur cet appareil…"
                    aiCoreActionButton.text = "🔄 REVÉRIFIER"
                    aiCoreActionButton.isEnabled = true
                }
                AiCoreManager.Status.UNAVAILABLE -> {
                    tintAiCoreStatusDot("#F87171")
                    aiCoreStatusText.text = "❌ Gemini Nano n'est pas disponible sur cet appareil (nécessite Android 14+ et un appareil de la liste officielle Google AICore — Pixel, Samsung Galaxy S24+, ou un flagship récent Xiaomi/POCO, entre autres)"
                    aiCoreProgressText.text = ""
                    aiCoreActionButton.text = "🔄 REVÉRIFIER"
                    aiCoreActionButton.isEnabled = true
                }
                AiCoreManager.Status.ERROR -> {
                    tintAiCoreStatusDot("#F87171")
                    aiCoreStatusText.text = "❌ Impossible de vérifier Gemini Nano — l'application système AICore est peut-être manquante ou à mettre à jour"
                    aiCoreProgressText.text = ""
                    aiCoreActionButton.text = "🔄 REVÉRIFIER"
                    aiCoreActionButton.isEnabled = true
                }
            }
        }
    }

    private fun downloadAiCore() {
        aiCoreActionButton.isEnabled = false
        aiCoreProgressText.text = "⬇ Démarrage du téléchargement…"

        CoroutineScope(Dispatchers.Main).launch {
            AiCoreManager.download { event ->
                runOnUiThread {
                    when (event) {
                        is AiCoreManager.DownloadEvent.Progress ->
                            aiCoreProgressText.text = "⬇ Téléchargement… ${event.bytesDownloaded / (1024 * 1024)} Mo reçus"
                        AiCoreManager.DownloadEvent.Completed -> {
                            aiCoreProgressText.text = "✅ Téléchargement terminé"
                            Toast.makeText(this@SettingsActivity, "✅ Gemini Nano téléchargé et prêt", Toast.LENGTH_SHORT).show()
                            CoroutineScope(Dispatchers.Main).launch { AiCoreManager.warmup() }
                            refreshAiCoreStatus(triggerDownloadIfNeeded = false)
                        }
                        is AiCoreManager.DownloadEvent.Failed -> {
                            aiCoreProgressText.text = ""
                            Toast.makeText(this@SettingsActivity, "❌ Échec du téléchargement : ${event.message}", Toast.LENGTH_LONG).show()
                            refreshAiCoreStatus(triggerDownloadIfNeeded = false)
                        }
                    }
                }
            }
        }
    }
}
