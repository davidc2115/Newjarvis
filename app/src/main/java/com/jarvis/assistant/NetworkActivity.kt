package com.jarvis.assistant

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private lateinit var savedDevicesText: TextView
    private lateinit var nameInput: EditText
    private lateinit var macInput: EditText
    private var lastScan: List<NetworkController.Device> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        outputText = findViewById(R.id.networkOutputText)
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
            outputText.text = "⏳ Envoi du signal de réveil..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { NetworkController.sendWakeOnLan(this@NetworkActivity, mac) }
                outputText.text = result
            }
        }

        refreshSavedDevices()
    }

    private fun runScan() {
        outputText.text = "🔍 Scan en cours (quelques secondes)..."
        CoroutineScope(Dispatchers.Main).launch {
            val devices = withContext(Dispatchers.IO) { NetworkController.scanNetwork(this@NetworkActivity) }
            lastScan = devices
            outputText.text = NetworkController.formatScanResult(devices)
        }
    }

    private fun refreshSavedDevices() {
        val devices = Prefs.getSavedNetworkDevices(this)
        savedDevicesText.text = if (devices.isEmpty()) {
            "Aucun appareil enregistré."
        } else {
            devices.joinToString("\n") { "• ${it.name} — ${it.mac}" }
        }
    }
}
