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
import kotlinx.coroutines.withContext
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
    private lateinit var firecrawlKeyInput: EditText
    private lateinit var glifTokenInput: EditText
    private lateinit var customModelUrlInput: EditText
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

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri)
    }

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
        firecrawlKeyInput     = findViewById(R.id.firecrawlKeyInput)
        glifTokenInput        = findViewById(R.id.glifTokenInput)
        customModelUrlInput   = findViewById(R.id.customModelUrlInput)
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
        hfTokenInput.setText(Prefs.getHfToken(this))
        firecrawlKeyInput.setText(Prefs.getFirecrawlApiKey(this))
        glifTokenInput.setText(Prefs.getGlifApiToken(this))
        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        val initialProvider = Prefs.getProvider(this)
        apiKeyInput.setText(Prefs.getApiKeyFor(this, initialProvider).ifBlank { Prefs.getApiKey(this) })
        updateLocalModelLabel()
    }

    private fun setupButtons() {
        val pickModelButton      = findViewById<TextView>(R.id.pickModelButton)
        val downloadCustomButton = findViewById<TextView>(R.id.downloadCustomButton)
        val saveButton           = findViewById<TextView>(R.id.saveButton)
        val saveApiKeysButton    = findViewById<TextView>(R.id.saveApiKeysButton)
        val modelCardsContainer  = findViewById<LinearLayout>(R.id.modelCardsContainer)
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

        // ── Cartes dynamiques de modèles ──────────────────────────────────────
        modelCardsContainer.removeAllViews()
        ModelDownloader.MODEL_CATALOG.forEachIndexed { index, entry ->
            buildModelCard(modelCardsContainer, entry, index)
        }

        // ── Import fichier local ───────────────────────────────────────────────
        pickModelButton.setOnClickListener { pickModelLauncher.launch(arrayOf("*/*")) }
        findViewById<TextView>(R.id.pickSdModelButton).setOnClickListener {
            pickSdModelLauncher.launch(arrayOf("*/*"))
        }
        updateSdModelLabel()
        findViewById<TextView>(R.id.deleteLocalModelButton).setOnClickListener { deleteLocalTextModel() }
        findViewById<TextView>(R.id.deleteSdModelButton).setOnClickListener { deleteLocalSdModel() }

        // ── URL personnalisée ──────────────────────────────────────────────────
        downloadCustomButton.setOnClickListener {
            val url = customModelUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Entrez une URL de modèle", Toast.LENGTH_SHORT).show()
            } else {
                val format = when {
                    url.endsWith(".task", ignoreCase = true) -> LocalLlmManager.LocalModelFormat.TASK
                    url.endsWith(".onnx", ignoreCase = true) -> LocalLlmManager.LocalModelFormat.ONNX
                    else -> LocalLlmManager.LocalModelFormat.TASK
                }
                startDownload(url, format, useToken = true)
            }
        }

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
        setupOllamaSection()
    }

    /**
     * Section "IA locale réseau (Ollama)" de l'onglet Local — champs dédiés (host/port/modèle)
     * réellement lus par ApiClient.ollamaBaseUrl/Prefs.getOllamaModel, contrairement à l'ancien
     * mécanisme générique du sélecteur Cloud dont l'édition n'avait aucun effet réel pour
     * Provider.OLLAMA (voir le commentaire dans ApiClient.sendOpenAiWithRotation). Host prérempli
     * avec l'hôte Freebox déjà connu si l'utilisateur n'a rien configuré — l'hostname
     * mafreebox.freebox.fr se résout sur tout le réseau local d'une Freebox, pas seulement une
     * fois appairée, donc c'est un point de départ raisonnable pour qui héberge Ollama dessus.
     */
    private fun setupOllamaSection() {
        val hostInput = findViewById<EditText>(R.id.ollamaHostInput)
        val portInput = findViewById<EditText>(R.id.ollamaPortInput)
        val modelInputOllama = findViewById<EditText>(R.id.ollamaModelInput)
        val fallbackModelsInput = findViewById<EditText>(R.id.ollamaFallbackModelsInput)
        val remoteHostInput = findViewById<EditText>(R.id.ollamaRemoteHostInput)
        val toggleButton = findViewById<TextView>(R.id.toggleOllamaAutoButton)
        val saveButtonOllama = findViewById<TextView>(R.id.saveOllamaButton)
        val testButton = findViewById<TextView>(R.id.testOllamaButton)
        val installDolphinButton = findViewById<TextView>(R.id.installDolphinButton)
        val statusText = findViewById<TextView>(R.id.ollamaStatusText)

        val savedHost = Prefs.getOllamaHost(this)
        hostInput.setText(
            savedHost.ifBlank {
                Prefs.getFreeboxHost(this).removePrefix("https://").removePrefix("http://").trimEnd('/')
            }
        )
        portInput.setText(Prefs.getOllamaPort(this))
        modelInputOllama.setText(Prefs.getOllamaModel(this))
        fallbackModelsInput.setText(Prefs.getOllamaFallbackModels(this).joinToString(", "))
        remoteHostInput.setText(Prefs.getOllamaRemoteHost(this))

        fun refreshToggleLabel() {
            toggleButton.text = if (Prefs.isOllamaAutoEnabled(this)) {
                "✅ ACTIVÉ dans le mode Automatique — essayé en premier, avant les IA cloud"
            } else {
                "⬜ DÉSACTIVÉ dans le mode Automatique — appuie pour activer"
            }
        }
        refreshToggleLabel()

        toggleButton.setOnClickListener {
            Prefs.setOllamaAutoEnabled(this, !Prefs.isOllamaAutoEnabled(this))
            refreshToggleLabel()
        }

        saveButtonOllama.setOnClickListener {
            Prefs.saveOllamaHost(this, hostInput.text.toString())
            Prefs.saveOllamaPort(this, portInput.text.toString())
            Prefs.saveOllamaModel(this, modelInputOllama.text.toString())
            Prefs.saveOllamaFallbackModels(this, fallbackModelsInput.text.toString())
            Prefs.saveOllamaRemoteHost(this, remoteHostInput.text.toString())
            Toast.makeText(this, "✅ Configuration Ollama enregistrée", Toast.LENGTH_SHORT).show()
        }

        testButton.setOnClickListener {
            // Teste directement les valeurs des champs (pas besoin d'avoir déjà enregistré) —
            // utilise l'endpoint natif Ollama /api/tags (liste des modèles installés), plus
            // simple et plus rapide qu'un vrai appel de génération pour un simple ping.
            val host = hostInput.text.toString().trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            val port = portInput.text.toString().trim().ifBlank { "11434" }
            if (host.isBlank()) {
                statusText.text = "❌ Renseigne d'abord une adresse (IP ou nom d'hôte)."
                return@setOnClickListener
            }
            testButton.isEnabled = false
            statusText.text = "⏳ Test en cours…"
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder().url("http://$host:$port/api/tags").get().build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                "❌ Ollama a répondu mais avec une erreur (HTTP ${response.code}) — vérifie qu'il tourne bien sur ce port."
                            } else {
                                val body = response.body?.string() ?: "{}"
                                val models = org.json.JSONObject(body).optJSONArray("models")
                                val count = models?.length() ?: 0
                                "✅ Ollama joignable à $host:$port — $count modèle(s) installé(s)."
                            }
                        }
                    } catch (e: Exception) {
                        "❌ Injoignable : ${e.javaClass.simpleName} — ${e.message}. Vérifie l'adresse, le port, et que ce téléphone est bien sur le même réseau."
                    }
                }
                statusText.text = result
                testButton.isEnabled = true
            }
        }

        // Déclenche l'installation de Dolphin (non censuré) directement depuis Réglages --
        // signalement utilisateur : "ajoute Dolphin uncensored sur la Freebox dans Ollama" --
        // indépendant d'une conversation IA fonctionnelle, contrairement à demander à JARVIS
        // de le faire via ollama_pull_model (inutile si c'est justement la cascade IA qui est
        // en panne). Enregistre d'abord les champs affichés (pullOllamaModel lit host/port
        // depuis Prefs, pas depuis les EditText) pour ne jamais viser un serveur périmé.
        installDolphinButton.setOnClickListener {
            Prefs.saveOllamaHost(this, hostInput.text.toString())
            Prefs.saveOllamaPort(this, portInput.text.toString())
            installDolphinButton.isEnabled = false
            statusText.text = "⏳ Téléchargement de dolphin-mixtral lancé sur le serveur Ollama… peut prendre plusieurs minutes."
            CoroutineScope(Dispatchers.Main).launch {
                val result = ApiClient.pullOllamaModel(this@SettingsActivity, "dolphin-mixtral")
                statusText.text = result
                installDolphinButton.isEnabled = true
            }
        }
    }

    /**
     * Bouton "Voir / copier le journal" — même schéma que showCrashReportIfAny() dans
     * MainActivity (AlertDialog copiable), mais pour DiagnosticsLog (échecs de cascade
     * IA/image/commandes, PAS des plantages) : la seule façon honnête de "voir les logs" côté
     * JARVIS, qui n'a aucun accès distant au téléphone de l'utilisateur.
     */
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
                startDownload(entry.url, entry.format, useToken = entry.needsHfToken)
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

    private fun startDownload(url: String, format: LocalLlmManager.LocalModelFormat, useToken: Boolean) {
        if (isDownloading) {
            Toast.makeText(this, "Un téléchargement est déjà en cours…", Toast.LENGTH_SHORT).show()
            return
        }
        val hfToken = if (useToken) hfTokenInput.text.toString().trim() else ""
        isDownloading = true
        downloadProgressText.text = "⬇ Démarrage du téléchargement…"

        CoroutineScope(Dispatchers.Main).launch {
            ModelDownloader.download(this@SettingsActivity, url, hfToken, format) { progress ->
                runOnUiThread {
                    when (progress) {
                        is ModelDownloader.Progress.Percent -> downloadProgressText.text = "⬇ Téléchargement… ${progress.value}%"
                        is ModelDownloader.Progress.Done -> {
                            isDownloading = false
                            downloadProgressText.text = "✅ Modèle téléchargé et actif sur le téléphone !"

                            if (format == LocalLlmManager.LocalModelFormat.STABLE_DIFFUSION) {
                                updateSdModelLabel()
                                Toast.makeText(this@SettingsActivity, "Modèle Stable Diffusion enregistré ✅", Toast.LENGTH_SHORT).show()
                            } else {
                                // Activer automatiquement le mode local
                                val targetProvider = if (format == LocalLlmManager.LocalModelFormat.TASK) Provider.ON_DEVICE else Provider.LOCAL_GGUF
                                selectedProvider = targetProvider
                                providerSpinner.setSelection(Provider.entries.indexOf(targetProvider))
                                Prefs.save(this@SettingsActivity, targetProvider, "", "", "")

                                updateLocalModelLabel()
                                Toast.makeText(this@SettingsActivity, "Modèle enregistré et activé ✅", Toast.LENGTH_SHORT).show()
                            }
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

    private fun importModelFile(uri: Uri) {
        Toast.makeText(this, "Import du modèle en cours…", Toast.LENGTH_LONG).show()
        val format = LocalLlmManager.LocalModelFormat.GGUF
        val ext = "gguf"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val destFile = File(filesDir, "local_model.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                }
                Prefs.saveLocalModelPath(this@SettingsActivity, destFile.absolutePath)
                Prefs.saveLocalModelFormat(this@SettingsActivity, format.name)
                LocalLlmManager.unload()

                runOnUiThread {
                    // Activer automatiquement le mode local
                    selectedProvider = Provider.LOCAL_GGUF
                    providerSpinner.setSelection(Provider.entries.indexOf(Provider.LOCAL_GGUF))
                    Prefs.save(this@SettingsActivity, Provider.LOCAL_GGUF, "", "", "")

                    updateLocalModelLabel()
                    Toast.makeText(this@SettingsActivity, "Modèle importé et activé ✅", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Échec de l'import : ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun updateLocalModelLabel() {
        val path = Prefs.getLocalModelPath(this)
        localModelPathText.text = if (path.isBlank()) {
            "Modèle actif : Aucun"
        } else {
            val file = File(path)
            val sizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0
            "Modèle actif sur l'appareil : ${file.name} (${selectedProvider.displayName}, ~${sizeMb} Mo)"
        }
    }

    private fun deleteLocalTextModel() {
        val path = Prefs.getLocalModelPath(this)
        if (path.isBlank()) {
            Toast.makeText(this, "Aucun modèle de texte local à supprimer.", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Supprimer ce modèle ?")
            .setMessage("${File(path).name} sera effacé du téléphone. Tu pourras le retélécharger plus tard si besoin.")
            .setPositiveButton("Supprimer") { _, _ ->
                LocalLlmManager.unload()
                File(path).delete()
                Prefs.saveLocalModelPath(this, "")
                updateLocalModelLabel()
                Toast.makeText(this, "🗑️ Modèle supprimé.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
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
}
