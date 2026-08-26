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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var providerSpinner: Spinner
    private lateinit var modelCardsContainer: LinearLayout
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

    private lateinit var firecrawlKeyInput: EditText
    private lateinit var glifTokenInput: EditText
    private lateinit var localModelPathText: TextView
    private lateinit var downloadProgressText: TextView

    // Stable Diffusion local via Termux (voir TermuxController) — vues chargées à la volée
    // dans setupTermuxSdSection() plutôt qu'en lateinit ici : section optionnelle isolée,
    // pas de risque de crash si un id venait à manquer lors d'une future refonte de l'onglet.


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

    // Une liste de champs EditText PAR provider (pas un seul) -- necessaire pour
    // le bouton "+" (signalement utilisateur : "un bouton + a cote de chaque champ
    // cles API afin de pouvoir en creer une deuxieme, puis troisieme... sans
    // supprimer les autres") : chaque clé a maintenant son propre champ, ajoute/
    // retire dynamiquement sans jamais toucher aux autres champs deja remplis.
    private val apiKeyFields = mutableMapOf<Provider, MutableList<EditText>>()

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

    // BUG RÉEL CORRIGÉ : TermuxController ne demandait JAMAIS la permission RUN_COMMAND au
    // runtime (protectionLevel="dangerous" côté Termux, donc PAS auto-accordée à l'installation
    // malgré la déclaration <uses-permission> dans le manifest) — seul openAppSettings() ouvrait
    // l'écran générique "Infos sur l'application", où la permission n'apparaît dans
    // "Autorisations supplémentaires" QUE si elle a déjà été demandée au moins une fois via
    // l'API Android standard. Résultat concret signalé : l'utilisateur ne voyait AUCUNE
    // autorisation "Exécuter des commandes" à accorder, quoi qu'il fasse. Ce launcher déclenche
    // la vraie popup système.
    private val termuxPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val statusText = findViewById<TextView>(R.id.termuxStatusText)
        if (granted) {
            statusText.text = "✅ Permission accordée. Appuie maintenant sur « Configurer Stable Diffusion (Termux) »."
        } else {
            statusText.text = "❌ Permission refusée. Sans elle, JARVIS ne peut pas envoyer de commande à Termux — réessaie, ou accorde-la manuellement dans Réglages Android → Apps → JARVIS → Autorisations."
        }
    }

    // Ecran de consentement systeme pour l'autorisation Gmail/Agenda (voir
    // GoogleAccountController.requestAuthorization) -- distinct du selecteur de compte
    // Credential Manager. On persiste ici le jeton d'acces obtenu (voir Prefs.setGoogleAccessToken)
    // pour un usage immediat par JarvisCommandParser sans redemander l'autorisation.
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val accessToken = GoogleAccountController.handleAuthorizationResult(this, result.data)
        if (accessToken != null) {
            Prefs.setGoogleAccessToken(this, accessToken)
            Toast.makeText(this, "\u2705 Acces Gmail/Agenda autorise.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                getString(R.string.google_authorization_error, "consentement refuse ou annule"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Ecran de selection de compte via l'ancienne API GoogleSignInClient (voir
    // GoogleAccountController.getLegacySignInIntent) -- plus fiable que Credential Manager sur
    // certains telephones (voir historique de l'appli reecrite, taches #217-222).
    private val googleLegacySignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onLegacySignInResult(result.data) }

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

        firecrawlKeyInput     = findViewById(R.id.firecrawlKeyInput)
        glifTokenInput        = findViewById(R.id.glifTokenInput)
        localModelPathText    = findViewById(R.id.localModelPathText)
        downloadProgressText  = findViewById(R.id.downloadProgressText)


        colorCarousel         = findViewById(R.id.colorCarousel)
        orbStyleCarousel      = findViewById(R.id.orbStyleCarousel)

        setupTabs()
        setupProviderSpinner()
        setupColorAndStyleCarousels()
        buildApiKeyFields()
        loadSavedValues()
        setupButtons()
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
                // sans être passé par le bouton ENREGISTRER de l'onglet Config. Seul Custom
                // garde l'exception (URL à saisir avant que la sauvegarde ait un sens).
                if (provider != Provider.CUSTOM) {
                    Prefs.save(
                        this@SettingsActivity,
                        provider,
                        baseUrlInput.text.toString().trim(),
                        modelInput.text.toString().trim(),
                        apiKeyInput.text.toString().trim()
                    )
                    Toast.makeText(
                        this@SettingsActivity,
                        "✅ IA active : ${provider.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateCloudSection(provider: Provider) {
        autoInfoText.visibility  = if (provider.isAuto) View.VISIBLE else View.GONE
        // Seul Custom nécessite de préciser une URL/modèle manuellement.
        // Pour tous les autres, l'onglet Config se limite au choix de l'IA.
        val needsAdvanced = provider == Provider.CUSTOM
        advancedConfigSection.visibility = if (needsAdvanced) View.VISIBLE else View.GONE
        val showCloud = !provider.isLocal && !provider.isAuto
        baseUrlInput.isEnabled = showCloud
        modelInput.isEnabled   = showCloud
        apiKeyInput.isEnabled  = showCloud && provider.needsApiKey
    }

    private fun buildApiKeyFields() {
        apiKeysContainer.removeAllViews()
        apiKeyFields.clear()

        for (provider in Provider.CLOUD_KEY_PROVIDERS) {
            // Ligne de titre + bouton "+" (signalement utilisateur : pouvoir ajouter une 2e,
            // 3e... clé pour un même provider SANS jamais écraser celles déjà saisies -- avant,
            // un seul champ existait par provider, forçant à tout retaper séparé par virgule).
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = "🔑 ${provider.displayName}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 16, 0, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleRow.addView(label)

            // Conteneur des champs de CE provider (une ligne par clé) -- rempli plus bas avec
            // une ligne par clé déjà enregistrée, ou une ligne vide si aucune.
            val fieldsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            apiKeyFields[provider] = mutableListOf()

            fun addKeyRow(initialValue: String) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 4 }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val field = EditText(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        resources.getDimensionPixelSize(R.dimen.input_height),
                        1f
                    ).also { it.marginEnd = 8 }
                    background = getDrawable(R.drawable.bg_input)
                    setPadding(40, 0, 40, 0)
                    setTextColor(getColor(R.color.text_primary))
                    setHintTextColor(getColor(R.color.text_secondary))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = "Clé API ${provider.displayName}…"
                    setText(initialValue)
                }
                val removeButton = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(36, 36).also {
                        it.width = (36 * resources.displayMetrics.density).toInt()
                        it.height = (36 * resources.displayMetrics.density).toInt()
                    }
                    text = "✕"
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(getColor(R.color.text_secondary))
                    background = getDrawable(R.drawable.bg_icon_button)
                    setOnClickListener {
                        fieldsContainer.removeView(row)
                        apiKeyFields[provider]?.remove(field)
                    }
                }
                row.addView(field)
                row.addView(removeButton)
                fieldsContainer.addView(row)
                apiKeyFields.getValue(provider).add(field)
            }

            val addButton = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(36, 36).also {
                    it.width = (36 * resources.displayMetrics.density).toInt()
                    it.height = (36 * resources.displayMetrics.density).toInt()
                }
                text = "+"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.cyan_accent))
                background = getDrawable(R.drawable.bg_icon_button)
                // Ajoute toujours une ligne VIDE en plus -- ne touche jamais aux champs déjà
                // remplis (donc jamais de perte de clé existante en cliquant sur +).
                setOnClickListener { addKeyRow("") }
            }
            titleRow.addView(addButton)
            apiKeysContainer.addView(titleRow)
            apiKeysContainer.addView(fieldsContainer)

            val existingKeys = Prefs.getApiKeysFor(this@SettingsActivity, provider)
            if (existingKeys.isEmpty()) {
                addKeyRow("")
            } else {
                existingKeys.forEach { addKeyRow(it) }
            }
        }
    }

    private fun loadSavedValues() {
        firecrawlKeyInput.setText(Prefs.getFirecrawlApiKey(this))
        glifTokenInput.setText(Prefs.getGlifApiToken(this))
        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        val initialProvider = Prefs.getProvider(this)
        apiKeyInput.setText(Prefs.getApiKeyFor(this, initialProvider).ifBlank { Prefs.getApiKey(this) })
        updateLocalModelLabel()
    }

    private fun setupButtons() {
        val saveButton           = findViewById<TextView>(R.id.saveButton)
        val saveApiKeysButton    = findViewById<TextView>(R.id.saveApiKeysButton)
        modelCardsContainer         = findViewById(R.id.modelCardsContainer)
        val wakeWordInput        = findViewById<EditText>(R.id.wakeWordInput)
        val toggleWakeWordButton = findViewById<TextView>(R.id.toggleWakeWordButton)
        val requestBatteryExemptionButton = findViewById<TextView>(R.id.requestBatteryExemptionButton)

        wakeWordInput.setText(Prefs.getWakeWord(this))
        updateWakeWordButtonLabel(toggleWakeWordButton)

        // ── Box internet (voir RouterController). Pour la Freebox : appairage direct
        // depuis ce bouton (demande d'autorisation affichée sur l'écran de la Freebox). Pour
        // les autres fournisseurs (Livebox/SFR Box/Bbox, pas d'écran de confirmation possible),
        // configuration en conversation avec JARVIS (précise le fournisseur puis son mot de
        // passe admin). Cette section affiche l'état actuel.
        val boxStatusText = findViewById<TextView>(R.id.boxStatusText)
        val pairFreeboxButton = findViewById<TextView>(R.id.rescanBoxButton)

        fun refreshBoxStatus() {
            val vendor = RouterController.vendorLabel(this)
            boxStatusText.text = if (RouterController.isConfigured(this)) {
                "✅ $vendor configurée et opérationnelle."
            } else {
                "ℹ️ Aucune box configurée pour l'instant. Si tu as une Freebox, touche le bouton ci-dessous. Sinon, dis à JARVIS quel fournisseur tu utilises (Livebox/SFR Box/Bbox)."
            }
        }
        refreshBoxStatus()

        pairFreeboxButton.setOnClickListener {
            pairFreeboxButton.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val message = withContext(Dispatchers.IO) {
                    FreeboxController.startPairing(applicationContext)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                refreshBoxStatus()
                pairFreeboxButton.isEnabled = true
            }
        }

        // ── Backends IA on-device (Gemini Nano / Qwen local via LiteRT-LM) ─────
        // Remplace l'ancien catalogue GGUF/ONNX/MediaPipe multi-format + import de fichier
        // personnalisé, retiré avec les modules natifs (llama.cpp/stable-diffusion.cpp, taches
        // #247/#248 -- demande explicite : garder les modeles IA on-device ACTUELS de l'appli
        // reecrite, pas l'ancien systeme natif).
        setupOnDeviceAiSection()

        // ── Sauvegarde paramètres cloud ───────────────────────────────────────
        saveButton.setOnClickListener {
            Prefs.save(
                this,
                selectedProvider,
                baseUrlInput.text.toString().trim(),
                modelInput.text.toString().trim(),
                apiKeyInput.text.toString().trim()
            )
            Prefs.saveAccentColor(this, selectedAccentColor)
            Prefs.saveOrbStyle(this, selectedOrbStyle)
            Toast.makeText(this, "✅ Paramètres enregistrés", Toast.LENGTH_SHORT).show()
        }

        saveApiKeysButton.setOnClickListener {
            // Une liste de clés PAR provider (une par champ, dans l'ordre affiché) --
            // Prefs.saveAllApiKeys écrase la liste de CE provider avec exactement ce qui est
            // affiché, ce qui est maintenant sûr : tous les champs (existants + ajoutés via
            // "+") sont bien présents à l'écran, plus besoin de découpage par virgule.
            val keysMap = apiKeyFields.mapValues { (_, fields) ->
                fields.map { it.text.toString().trim() }.filter { it.isNotBlank() }
            }
            Prefs.saveAllApiKeys(this, keysMap)
            Prefs.saveFirecrawlApiKey(this, firecrawlKeyInput.text.toString().trim())
            Prefs.saveGlifApiToken(this, glifTokenInput.text.toString().trim())
            Toast.makeText(this, "✅ Toutes les clés API enregistrées", Toast.LENGTH_SHORT).show()
        }

        toggleWakeWordButton.setOnClickListener {
            Prefs.saveWakeWord(this, wakeWordInput.text.toString().trim())
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

        // BUG REEL CORRIGE (signalement utilisateur : "l'ecoute ne fonctionne toujours pas") :
        // sur beaucoup de telephones (Xiaomi/MIUI en tete, mais Samsung/OnePlus/Huawei ont des
        // equivalents), Android tue ou throttle agressivement un service en arriere-plan comme
        // WakeWordService des que l'ecran est eteint depuis un moment, SAUF si l'app est
        // explicitement exemptee des optimisations de batterie. Avant, seul un texte
        // informatif mentionnait cette etape a faire manuellement dans les reglages systeme,
        // jamais retrouvable facilement -- ce bouton declenche directement la VRAIE demande
        // systeme (popup Android standard), un seul appui.
        requestBatteryExemptionButton.setOnClickListener {
            try {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                    Toast.makeText(this, "✅ Deja exempte des optimisations de batterie", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                // Repli : certains constructeurs (Xiaomi/MIUI notamment) bloquent cette action
                // standard Android -- on ouvre au moins l'ecran general des parametres app.
                TermuxController.openAppSettings(this)
                Toast.makeText(this, "Ouvre les parametres batterie de l'app depuis cet ecran (⚠️ ${e.message})", Toast.LENGTH_LONG).show()
            }
        }

        setupTermuxSdSection()
        setupDebugLogsButton()
        setupGoogleAccountSection()
    }

    private fun setupDebugLogsButton() {
        val button = findViewById<TextView>(R.id.viewDebugLogsButton)
        button.setOnClickListener {
            val content = DiagnosticsLog.readRecent(this, 200)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🩺 Journal de diagnostics")
                .setMessage(content)
                .setPositiveButton("Copier") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Journal JARVIS", content))
                    Toast.makeText(this, "Journal copié dans le presse-papier", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Vider") { _, _ ->
                    DiagnosticsLog.clear(this)
                    Toast.makeText(this, "Journal vidé", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun updateWakeWordButtonLabel(button: TextView) {
        button.text = if (Prefs.isWakeWordEnabled(this)) "DÉSACTIVER L'ÉCOUTE PERMANENTE" else "ACTIVER L'ÉCOUTE PERMANENTE"
    }

    /**
     * Section "Stable Diffusion local via Termux" de l'onglet Local — isolée dans sa propre
     * fonction (plutôt que mêlée au bloc onCreate déjà dense) car elle combine 3 responsabilités
     * propres à cette intégration : toggle d'activation (Prefs.isTermuxSdEnabled), raccourci vers
     * l'écran de permissions Android (étape manuelle obligatoire, voir TermuxController), et 2
     * actions réseau/RUN_COMMAND asynchrones (configurer, vérifier le statut).
     */
    private fun setupTermuxSdSection() {
        val copyStep2Button = findViewById<TextView>(R.id.termuxCopyStep2Button)
        val toggleButton = findViewById<TextView>(R.id.toggleTermuxSdButton)
        val appSettingsButton = findViewById<TextView>(R.id.termuxAppSettingsButton)
        val setupButton = findViewById<TextView>(R.id.termuxSetupButton)
        val statusButton = findViewById<TextView>(R.id.termuxStatusButton)
        val statusText = findViewById<TextView>(R.id.termuxStatusText)

        // Étape 2 taper une commande avec quotes + && à la main sur un clavier mobile est une
        // source d'erreur fréquente (autocorrection des guillemets, && mal saisi...) — cause
        // probable la plus concrète d'un signalement "j'ai tapé la commande mais ça ne marche
        // toujours pas". Copier-coller élimine cette classe d'erreur entièrement.
        copyStep2Button.setOnClickListener {
            val command = "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Commande Termux", command))
            Toast.makeText(this, "📋 Commande copiée — colle-la dans Termux (appui long > Coller) puis appuie sur Entrée", Toast.LENGTH_LONG).show()
        }

        fun refreshToggleLabel() {
            toggleButton.text = if (Prefs.isTermuxSdEnabled(this)) {
                "✅ ACTIVÉ — utilisé en priorité pour générer les images"
            } else {
                "⬜ DÉSACTIVÉ — appuie pour activer"
            }
        }
        refreshToggleLabel()

        toggleButton.setOnClickListener {
            Prefs.setTermuxSdEnabled(this, !Prefs.isTermuxSdEnabled(this))
            refreshToggleLabel()
        }

        // Demande la VRAIE permission au runtime (popup système) plutôt que de se contenter
        // d'ouvrir l'écran générique "Infos sur l'application" en espérant que l'utilisateur
        // la trouve lui-même — voir le commentaire sur termuxPermissionLauncher pour le bug
        // réel que ça corrige (la permission n'apparaissait nulle part tant qu'elle n'avait
        // jamais été demandée au moins une fois via cette API).
        appSettingsButton.setOnClickListener {
            when {
                !TermuxController.isTermuxInstalled(this) ->
                    Toast.makeText(this, "Installe d'abord Termux (F-Droid) — voir les instructions ci-dessus", Toast.LENGTH_LONG).show()
                TermuxController.hasRunCommandPermission(this) ->
                    Toast.makeText(this, "✅ Permission déjà accordée", Toast.LENGTH_SHORT).show()
                else -> termuxPermissionLauncher.launch(TermuxController.RUN_COMMAND_PERMISSION)
            }
        }

        setupButton.setOnClickListener {
            if (TermuxController.isTermuxInstalled(this) && !TermuxController.hasRunCommandPermission(this)) {
                termuxPermissionLauncher.launch(TermuxController.RUN_COMMAND_PERMISSION)
                return@setOnClickListener
            }
            setupButton.isEnabled = false
            val result = TermuxController.setupAndLaunch(this)
            statusText.text = result.message
            Toast.makeText(this, if (result.success) "✅ Lancé, voir le statut ci-dessous" else "❌ Voir le détail ci-dessous", Toast.LENGTH_SHORT).show()
            setupButton.isEnabled = true
        }

        statusButton.setOnClickListener {
            statusButton.isEnabled = false
            statusText.text = "⏳ Vérification en cours…"
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    TermuxController.checkWebuiStatus(applicationContext)
                }
                statusText.text = result.message
                statusButton.isEnabled = true
            }
        }
    }

    /** Bascule l'IA on-device sur Gemini Nano (AICore) -- aucun telechargement de modele a
     *  gerer ici, GeminiNanoController s'en charge (voir MainActivity pour le flux complet
     *  disponible/telechargeable/en telechargement). */
    private fun selectGeminiNano() {
        selectedProvider = Provider.GEMINI_NANO
        providerSpinner.setSelection(Provider.entries.indexOf(Provider.GEMINI_NANO))
        Prefs.save(this, Provider.GEMINI_NANO, "", "", "")
        updateLocalModelLabel()
        Toast.makeText(this, "\u2705 Gemini Nano activ\u00e9.", Toast.LENGTH_SHORT).show()
    }

    /** Bascule l'IA on-device sur le modele Qwen local deja telecharge (voir buildLocalModelCard
     *  pour le declenchement du telechargement s'il ne l'est pas encore). */
    private fun selectLocalLitert(model: LocalLlmController.LocalModel) {
        Prefs.setLocalLlmModelId(this, model.id)
        selectedProvider = Provider.LOCAL_LITERT
        providerSpinner.setSelection(Provider.entries.indexOf(Provider.LOCAL_LITERT))
        Prefs.save(this, Provider.LOCAL_LITERT, "", "", "")
        updateLocalModelLabel()
        rebuildModelCatalogUI()
        Toast.makeText(this, "\u2705 Mod\u00e8le activ\u00e9 : ${model.displayName}", Toast.LENGTH_SHORT).show()
    }

    /** Cable les deux lignes fixes (Gemini Nano / Qwen local) + reconstruit les cartes de
     *  telechargement des modeles Qwen disponibles. A l'inverse de l'ancien catalogue
     *  multi-format, il n'y a plus qu'une seule famille de modele local (LiteRT-LM). */
    private fun setupOnDeviceAiSection() {
        findViewById<TextView>(R.id.geminiNanoRow).setOnClickListener { selectGeminiNano() }
        findViewById<TextView>(R.id.localLitertRow).setOnClickListener {
            val model = LocalLlmController.modelById(Prefs.getLocalLlmModelId(this))
            if (LocalLlmController.isDownloaded(this, model)) {
                selectLocalLitert(model)
            } else {
                Toast.makeText(this, "T\u00e9l\u00e9charge d'abord un mod\u00e8le Qwen ci-dessous.", Toast.LENGTH_SHORT).show()
            }
        }
        rebuildModelCatalogUI()
    }

    /** Cree une carte visuelle pour un modele Qwen du registre LocalLlmController.AVAILABLE_MODELS
     *  -- indique s'il est deja telecharge et propose de le telecharger/l'activer. */
    private fun buildLocalModelCard(container: LinearLayout, model: LocalLlmController.LocalModel) {
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

        val isDownloaded = LocalLlmController.isDownloaded(this, model)
        val isActive = isDownloaded && selectedProvider == Provider.LOCAL_LITERT &&
            Prefs.getLocalLlmModelId(this) == model.id

        val titleText = TextView(this).apply {
            text = model.displayName
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        card.addView(titleText)

        val descText = TextView(this).apply {
            text = model.description
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt())
        }
        card.addView(descText)

        if (isDownloaded) {
            val badge = TextView(this).apply {
                text = if (isActive) "\u2705 T\u00e9l\u00e9charg\u00e9 \u2014 mod\u00e8le actif en ce moment" else "\u2705 T\u00e9l\u00e9charg\u00e9 (non actif)"
                setTextColor(getColor(R.color.cyan_accent))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            card.addView(badge)
        }

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        if (isDownloaded && !isActive) {
            val btnActivate = TextView(this).apply {
                text = "\u2b50 UTILISER CE MOD\u00c8LE"
                setTextColor(getColor(R.color.background_dark))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                background = getDrawable(R.drawable.bg_mic_button)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectLocalLitert(model) }
            }
            buttonRow.addView(btnActivate)
        } else if (!isDownloaded) {
            val btnDownload = TextView(this).apply {
                text = "\u2b07 T\u00c9L\u00c9CHARGER SUR LE T\u00c9L\u00c9PHONE"
                setTextColor(getColor(R.color.background_dark))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                background = getDrawable(R.drawable.bg_mic_button)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { startLocalModelDownload(model) }
            }
            buttonRow.addView(btnDownload)
        }
        if (isDownloaded) {
            val btnDelete = TextView(this).apply {
                text = "\ud83d\uddd1\ufe0f"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                background = getDrawable(R.drawable.bg_bubble_ai)
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (8 * dp).toInt() }
                setOnClickListener { deleteLocalTextModel(model) }
            }
            buttonRow.addView(btnDelete)
        }
        card.addView(buttonRow)

        container.addView(card)
    }

    /** Reconstruit les cartes du catalogue Qwen -- necessaire apres un telechargement ou une
     *  activation pour que la coche "telecharge/actif" se mette a jour immediatement. */
    private fun rebuildModelCatalogUI() {
        modelCardsContainer.removeAllViews()
        LocalLlmController.AVAILABLE_MODELS.forEach { model ->
            buildLocalModelCard(modelCardsContainer, model)
        }
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

        val styleOptions = listOf("PULSE" to "Orbe pulsante", "NETWORK_SPHERE" to "Sphère réseau", "OBSIDIAN_WEB" to "Toile Obsidian", "NEURAL_CORE" to "Neural Core (HUD)")
        orbStyleCarouselAdapter = OrbStyleCarouselAdapter(this, styleOptions, selectedOrbStyle, selectedAccentColor) { styleId ->
            selectedOrbStyle = styleId
            Prefs.saveOrbStyle(this, styleId)
            Toast.makeText(this, "✅ Style d'orbe enregistré — relance le mode vocal pour le voir.", Toast.LENGTH_SHORT).show()
        }
        orbStyleCarousel.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        orbStyleCarousel.adapter = orbStyleCarouselAdapter
        LinearSnapHelper().attachToRecyclerView(orbStyleCarousel)
    }

    /** Demarre le telechargement d'un modele Qwen (voir LocalLlmController.download) --
     *  progression affichee dans downloadProgressText, active automatiquement le modele une
     *  fois termine (comportement identique a l'ancien systeme). */
    private fun startLocalModelDownload(model: LocalLlmController.LocalModel) {
        if (isDownloading) {
            Toast.makeText(this, "Un t\u00e9l\u00e9chargement est d\u00e9j\u00e0 en cours\u2026", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        downloadProgressText.text = "\u2b07 D\u00e9marrage du t\u00e9l\u00e9chargement \u2014 ${model.displayName}\u2026"

        CoroutineScope(Dispatchers.Main).launch {
            try {
                LocalLlmController.download(this@SettingsActivity, model) { downloaded, total ->
                    runOnUiThread {
                        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        downloadProgressText.text = "\u2b07 T\u00e9l\u00e9chargement\u2026 $pct%"
                    }
                }
                isDownloading = false
                downloadProgressText.text = "\u2705 Mod\u00e8le t\u00e9l\u00e9charg\u00e9 et actif sur le t\u00e9l\u00e9phone !"
                selectLocalLitert(model)
            } catch (e: Exception) {
                isDownloading = false
                downloadProgressText.text = ""
                Toast.makeText(this@SettingsActivity, "\u274c \u00c9chec : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateLocalModelLabel() {
        localModelPathText.text = when (selectedProvider) {
            Provider.GEMINI_NANO -> "Mod\u00e8le actif : Gemini Nano (Google AICore)"
            Provider.LOCAL_LITERT -> {
                val model = LocalLlmController.modelById(Prefs.getLocalLlmModelId(this))
                if (LocalLlmController.isDownloaded(this, model)) {
                    "Mod\u00e8le actif sur l'appareil : ${model.displayName}"
                } else {
                    "Mod\u00e8le actif : Aucun (t\u00e9l\u00e9charge un mod\u00e8le Qwen ci-dessous)"
                }
            }
            else -> "Mod\u00e8le actif : Aucun"
        }
    }

    private fun deleteLocalTextModel(model: LocalLlmController.LocalModel) {
        if (!LocalLlmController.isDownloaded(this, model)) {
            Toast.makeText(this, "Aucun mod\u00e8le local \u00e0 supprimer.", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Supprimer ce mod\u00e8le ?")
            .setMessage("${model.displayName} sera effac\u00e9 du t\u00e9l\u00e9phone. Tu pourras le retélécharger plus tard si besoin.")
            .setPositiveButton("Supprimer") { _, _ ->
                LocalLlmController.deleteModel(this, model)
                updateLocalModelLabel()
                rebuildModelCatalogUI()
                Toast.makeText(this, "\ud83d\uddd1\ufe0f Mod\u00e8le supprim\u00e9.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ─── OAuth Google (Agenda/Mail) ───────────────────────────────────────────────────
    // Connexion Google directement depuis l'appli, multi-comptes (voir GoogleAccountController
    // pour le pourquoi des deux etapes authentification/autorisation, et pour l'ID client Web
    // indispensable -- saisi ici, jamais code en dur). Vient EN PLUS du calendrier local
    // (CalendarController) et de l'IMAP/SMTP existants de cette base (greffe taches #247-249,
    // demande explicite de l'utilisateur de garder l'integration OAuth actuelle).

    private fun setupGoogleAccountSection() {
        val webClientIdInput = findViewById<EditText>(R.id.googleWebClientIdInput)
        webClientIdInput.setText(Prefs.getGoogleWebClientId(this).orEmpty())
        webClientIdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                Prefs.setGoogleWebClientId(this, webClientIdInput.text?.toString().orEmpty().trim())
            }
        }
        findViewById<TextView>(R.id.addGoogleAccountButton).setOnClickListener { addGoogleAccount() }
        refreshGoogleAccountsList()
    }

    private fun addGoogleAccount() {
        val webClientId = findViewById<EditText>(R.id.googleWebClientIdInput).text?.toString()?.trim().orEmpty()
        if (webClientId.isBlank()) {
            Toast.makeText(this, getString(R.string.google_web_client_id_missing), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setGoogleWebClientId(this, webClientId)

        try {
            // signOutThenGetLegacySignInIntent (pas getLegacySignInIntent directement) : sans
            // ca, le selecteur de compte peut etre saute et reconnecter silencieusement le
            // dernier compte utilise, empechant d'en ajouter un 2e/3e.
            GoogleAccountController.signOutThenGetLegacySignInIntent(this, webClientId) { intent ->
                googleLegacySignInLauncher.launch(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("JarvisGoogleAuth", "Impossible de lancer l'ecran de connexion Google", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            )
        }
    }

    /**
     * Callback de googleLegacySignInLauncher une fois l'ecran de selection de compte systeme
     * refermee -- succes ou echec/annulation.
     */
    private fun onLegacySignInResult(data: Intent?) {
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

            // Demande immediatement l'autorisation Gmail/Agenda pour ce compte. Le jeton
            // obtenu est mis en cache (Prefs.setGoogleAccessToken) pour un usage immediat.
            GoogleAccountController.requestAuthorization(
                activity = this,
                pendingIntentLauncher = googleAuthorizationLauncher,
                onGranted = { accessToken ->
                    if (accessToken != null) {
                        Prefs.setGoogleAccessToken(this, accessToken)
                        Prefs.setGoogleAccessTokenForAccount(this, email, accessToken)
                    }
                    Prefs.setActiveGoogleAccountEmail(this, email)
                    refreshGoogleAccountsList()
                    Toast.makeText(this, getString(R.string.google_account_linked, email), Toast.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    android.util.Log.e("JarvisGoogleAuth", "requestAuthorization a echoue", e)
                    showCopyableErrorDialog(
                        getString(R.string.google_authorization_error, ""),
                        "${e.javaClass.simpleName}: ${e.message ?: "?"}"
                    )
                }
            )
        } catch (e: com.google.android.gms.common.api.ApiException) {
            // Codes frequents : 12501 = annule par l'utilisateur ; 10 = DEVELOPER_ERROR
            // (SHA-1/package non enregistre comme client OAuth "Android" cote Cloud Console) ;
            // 7 = erreur reseau.
            if (e.statusCode == com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                android.util.Log.d("JarvisGoogleAuth", "Connexion Google annulee par l'utilisateur")
                return
            }
            android.util.Log.e("JarvisGoogleAuth", "Legacy signIn a echoue (code ${e.statusCode})", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "ApiException (code ${e.statusCode}): " +
                    "${com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)}\n\n" +
                    "Si le code est 10 (DEVELOPER_ERROR), le SHA-1/package de l'appli n'est " +
                    "probablement pas enregistre comme identifiant OAuth de type \"Android\" " +
                    "dans Google Cloud Console."
            )
        } catch (e: Exception) {
            android.util.Log.e("JarvisGoogleAuth", "Legacy signIn a echoue", e)
            showCopyableErrorDialog(
                getString(R.string.google_signin_error, ""),
                "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            )
        }
    }

    /**
     * Les Toast disparaissent en ~3s -- trop court pour lire/recopier une erreur technique.
     * Une AlertDialog reste affichee et le texte peut etre copie dans le presse-papier.
     */
    private fun showCopyableErrorDialog(title: String, detail: String) {
        if (isFinishing || isDestroyed) {
            android.util.Log.w("JarvisGoogleAuth", "Dialogue d'erreur ignore (Activity finissante/detruite): $detail")
            return
        }
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title.trim().ifBlank { "Erreur" })
                .setMessage(detail)
                .setPositiveButton("Copier") { dialog, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Erreur JARVIS", detail))
                    Toast.makeText(this, "Copie.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Fermer", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("JarvisGoogleAuth", "Impossible d'afficher le dialogue d'erreur", e)
        }
    }

    private fun refreshGoogleAccountsList() {
        val container = findViewById<LinearLayout>(R.id.googleAccountsContainer)
        container.removeAllViews()
        val accounts = Prefs.loadGoogleAccounts(this)
        if (accounts.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.google_no_accounts)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(dpToPx(20), 0, dpToPx(20), 0)
            }
            container.addView(empty)
            return
        }

        val activeEmail = Prefs.getActiveGoogleAccountEmail(this)
        val readableAccounts = Prefs.getAllValidGoogleAccountTokens(this).keys
        accounts.forEach { account ->
            val isActive = account.email == activeEmail
            val isReadable = account.email in readableAccounts
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_bubble_ai)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(8) }
            }
            val label = TextView(this).apply {
                val identity = if (account.displayName.isNotBlank() && account.displayName != account.email) {
                    "${account.displayName}\n${account.email}"
                } else {
                    account.email
                }
                text = when {
                    isActive -> "\u2705 $identity\n(actif -- creation/suppression/envoi)"
                    isReadable -> "\ud83d\udd13 $identity\n(lecture Agenda/Mail active)"
                    else -> identity
                }
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (!isActive) {
                val activate = TextView(this).apply {
                    text = "Activer"
                    setTextColor(getColor(R.color.cyan_accent))
                    textSize = 12f
                    setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                    setOnClickListener { activateGoogleAccount(account) }
                }
                buttons.addView(activate)
            }
            val unlink = TextView(this).apply {
                text = getString(R.string.google_unlink_button)
                setTextColor(getColor(R.color.error_glow))
                textSize = 12f
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                setOnClickListener { unlinkGoogleAccount(account) }
            }
            buttons.addView(unlink)
            row.addView(label)
            row.addView(buttons)
            container.addView(row)
        }
    }

    /**
     * Relance le selecteur de compte systeme pour que l'utilisateur choisisse [account] --
     * l'API Google (AuthorizationClient) ne permet pas de "basculer" silencieusement vers un
     * compte deja lie sans repasser par ce selecteur.
     */
    private fun activateGoogleAccount(account: GoogleAccountController.LinkedAccount) {
        val webClientId = Prefs.getGoogleWebClientId(this).orEmpty()
        if (webClientId.isBlank()) {
            Toast.makeText(this, getString(R.string.google_web_client_id_missing), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Choisis \u00ab ${account.email} \u00bb dans le s\u00e9lecteur pour l'activer.", Toast.LENGTH_LONG).show()
        try {
            GoogleAccountController.signOutThenGetLegacySignInIntent(this, webClientId) { intent ->
                googleLegacySignInLauncher.launch(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("JarvisGoogleAuth", "Impossible de lancer l'ecran de connexion Google", e)
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
