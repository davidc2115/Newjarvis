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
    private lateinit var customModelUrlInput: EditText
    private lateinit var localModelPathText: TextView
    private lateinit var downloadProgressText: TextView


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
            val label = TextView(this).apply {
                text = "🔑 ${provider.displayName}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 16, 0, 4)
            }
            apiKeysContainer.addView(label)

            val field = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.input_height)
                ).also { it.bottomMargin = 4 }
                background = getDrawable(R.drawable.bg_input)
                setPadding(40, 0, 40, 0)
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Clé API ${provider.displayName}..."
                setText(Prefs.getApiKeyFor(this@SettingsActivity, provider))
            }
            apiKeysContainer.addView(field)
            apiKeyFields[provider] = field
        }
    }

    private fun loadSavedValues() {
        hfTokenInput.setText(Prefs.getHfToken(this))
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
            val keys = apiKeyFields.mapValues { (_, field) -> field.text.toString().trim() }
            Prefs.saveApiKeys(this, keys)
            Toast.makeText(this, "✅ Toutes les clés API enregistrées", Toast.LENGTH_SHORT).show()
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
