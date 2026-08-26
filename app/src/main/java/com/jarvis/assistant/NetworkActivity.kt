package com.jarvis.assistant

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkActivity : AppCompatActivity() {

    private lateinit var emptyText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var savedDevicesText: TextView
    private lateinit var nameInput: EditText
    private lateinit var macInput: EditText
    private var lastScan: List<NetworkController.Device> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        emptyText = findViewById(R.id.networkEmptyText)
        devicesContainer = findViewById(R.id.networkDevicesContainer)
        savedDevicesText = findViewById(R.id.savedDevicesText)
        nameInput = findViewById(R.id.wolNameInput)
        macInput = findViewById(R.id.wolMacInput)

        findViewById<TextView>(R.id.btnNetworkScan).setOnClickListener { runScan() }

        findViewById<TextView>(R.id.btnWolSave).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val mac = macInput.text.toString().trim()
            if (name.isBlank() || mac.isBlank()) {
                Toast.makeText(this, "Nom et adresse MAC requis.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveNetworkDevice(this, Prefs.SavedDevice(name, mac))
            Toast.makeText(this, "✅ « $name » enregistré.", Toast.LENGTH_SHORT).show()
            refreshSavedDevices()
        }

        findViewById<TextView>(R.id.btnWolWake).setOnClickListener {
            val mac = macInput.text.toString().trim()
            if (mac.isBlank()) {
                Toast.makeText(this, "Indique une adresse MAC.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "⏳ Envoi du signal de réveil...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { NetworkController.sendWakeOnLan(this@NetworkActivity, mac) }
                Toast.makeText(this@NetworkActivity, result, Toast.LENGTH_LONG).show()
            }
        }

        refreshSavedDevices()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun runScan() {
        emptyText.visibility = View.VISIBLE
        emptyText.text = "🔍 Scan en cours (quelques secondes)..."
        devicesContainer.removeAllViews()
        CoroutineScope(Dispatchers.Main).launch {
            val devices = withContext(Dispatchers.IO) { NetworkController.scanNetwork(this@NetworkActivity) }
            lastScan = devices
            withContext(Dispatchers.IO) { Prefs.saveScannedDevices(this@NetworkActivity, devices) }
            refreshDeviceList()
            refreshSavedDevices()
        }
    }

    /**
     * Reconstruit la liste des appareils détectés lors du dernier scan : une carte
     * cliquable par appareil, ouvrant un menu d'actions (ouvrir l'interface web,
     * enregistrer pour Wake-on-LAN, retester). Répond directement au besoin de
     * pouvoir se connecter à une imprimante ou voir son interface web depuis l'appli.
     */
    private fun refreshDeviceList() {
        devicesContainer.removeAllViews()

        if (lastScan.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = "Touche « Scanner le réseau » pour lister les appareils connectés à ton Wi-Fi. Touche ensuite un appareil trouvé (imprimante, box, NAS...) pour ouvrir son interface web, le réveiller ou le retester."
            return
        }
        emptyText.visibility = View.GONE

        lastScan.forEach { device ->
            val webUrl = NetworkController.guessWebUrl(device)
            val webPart = if (webUrl != null) "\n🌐 Interface web : $webUrl" else ""
            val portsPart = if (device.openPorts.isNotEmpty()) "\n🔓 Ports : ${device.openPorts.joinToString(", ")}" else ""

            val row = TextView(this).apply {
                text = "${device.guessedType}\n${device.label}$portsPart$webPart\n👉 Toucher pour les options"
                setTextColor(Color.parseColor("#E6EAF2"))
                textSize = 11f
                setLineSpacing(dp(2).toFloat(), 1f)
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setOnClickListener { showDeviceOptions(device) }
            }
            devicesContainer.addView(row)
        }
    }

    private fun showDeviceOptions(device: NetworkController.Device) {
        val hasWeb = NetworkController.guessWebUrl(device) != null
        val options = mutableListOf<String>()
        if (hasWeb) options.add("🌐 Ouvrir l'interface web")
        options.add("🔁 Retester (ping)")
        options.add("⚡ Enregistrer pour Wake-on-LAN")

        AlertDialog.Builder(this)
            .setTitle(device.label)
            .setItems(options.toTypedArray()) { dialog, which ->
                when (options[which]) {
                    "🌐 Ouvrir l'interface web" -> openDeviceWeb(device)
                    "🔁 Retester (ping)" -> pingDeviceNow(device)
                    "⚡ Enregistrer pour Wake-on-LAN" -> promptSaveForWol(device)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun openDeviceWeb(device: NetworkController.Device) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { NetworkController.openWebInterface(this@NetworkActivity, device.ip) }
            Toast.makeText(this@NetworkActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    private fun pingDeviceNow(device: NetworkController.Device) {
        Toast.makeText(this, "⏳ Test de « ${device.label} »...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { NetworkController.pingDevice(this@NetworkActivity, device.ip) }
            Toast.makeText(this@NetworkActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptSaveForWol(device: NetworkController.Device) {
        val input = EditText(this).apply {
            hint = "Nom (ex: Imprimante Bureau)"
            setText(device.hostname?.takeIf { it.isNotBlank() && it != device.ip } ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Enregistrer « ${device.label} »")
            .setView(input)
            .setPositiveButton("Enregistrer") { dialog, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Précise un nom.", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.saveNetworkDevice(this, Prefs.SavedDevice(name, device.mac ?: "", device.ip))
                    Toast.makeText(this, "✅ « $name » enregistré.", Toast.LENGTH_SHORT).show()
                    refreshSavedDevices()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Annuler") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun refreshSavedDevices() {
        val devices = Prefs.getSavedNetworkDevices(this)
        savedDevicesText.text = if (devices.isEmpty()) {
            "Aucun appareil enregistré. Lance un scan, ou ajoute une adresse MAC ci-dessus pour le Wake-on-LAN."
        } else {
            devices.joinToString("\n") { d ->
                val macPart = if (d.mac.isNotBlank()) " — MAC ${d.mac}" else " — pas de MAC (réveil impossible)"
                val ipPart = if (d.ip.isNotBlank()) " (${d.ip})" else ""
                "• ${d.name}$ipPart$macPart"
            }
        }
    }
}
