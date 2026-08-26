package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File

/**
 * LocalWebServerController — point d'entrée JARVIS_CMD pour démarrer/arrêter le
 * serveur web local (LocalWebServerService) qui sert un site généré directement
 * depuis le téléphone. Combiné à une redirection de port sur la box (voir
 * RouterController, box_port_forward — Freebox/Livebox uniquement) pour exposer
 * le site à internet via son IP publique, gratuitement, sans hébergeur tiers — au
 * prix d'une disponibilité qui dépend du téléphone (allumé, sur le même réseau,
 * chargé) et d'une IP publique qui peut changer (préférer publish_website_github
 * pour un hébergement permanent avec nom de domaine stable).
 */
object LocalWebServerController {

    private const val DEFAULT_PORT = 8080

    /** Démarre le serveur local pour [siteDir] (dossier contenant index.html). */
    fun start(context: Context, siteDir: File, port: Int = DEFAULT_PORT): String {
        if (!siteDir.exists() || !siteDir.isDirectory) {
            return "❌ Dossier de site introuvable : ${siteDir.absolutePath}"
        }
        if (!File(siteDir, "index.html").exists()) {
            return "❌ Ce dossier ne contient pas de site généré (index.html manquant)."
        }
        val intent = Intent(context, LocalWebServerService::class.java).apply {
            putExtra(LocalWebServerService.EXTRA_ROOT_DIR, siteDir.absolutePath)
            putExtra(LocalWebServerService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(context, intent)

        val localUrl = "http://${localIpAddress(context) ?: "<IP-de-ton-téléphone>"}:$port/"
        val sb = StringBuilder("🌐 Serveur local démarré : « ${siteDir.name} » servi sur le port $port.\n")
        sb.append("📶 Accessible sur ton réseau Wi-Fi local : $localUrl\n")
        sb.append("\n💡 Pour un accès depuis l'extérieur (pas seulement ton Wi-Fi), configure une redirection de port sur ta box (box_port_forward, Freebox/Livebox uniquement).")
        return sb.toString()
    }

    /** Arrête le serveur local s'il tourne. */
    fun stop(context: Context): String {
        if (!LocalWebServerService.isRunning) return "ℹ️ Le serveur local n'est pas actif."
        context.stopService(Intent(context, LocalWebServerService::class.java))
        return "🛑 Serveur local arrêté."
    }

    /** État actuel du serveur local, sans effet de bord. */
    fun status(context: Context): String {
        if (!LocalWebServerService.isRunning) return "ℹ️ Le serveur local JARVIS n'est pas actif. Utilise start_local_web_server pour en démarrer un."
        val port = LocalWebServerService.currentPort
        val root = LocalWebServerService.currentRootPath?.let { File(it).name } ?: "?"
        val localUrl = "http://${localIpAddress(context) ?: "<IP-de-ton-téléphone>"}:$port/"
        val sb = StringBuilder("🌐 Serveur local actif : « $root » sur le port $port (${LocalWebServerService.requestCount} requête(s) servies).\n")
        sb.append("📶 $localUrl")
        return sb.toString()
    }

    /** Adresse IPv4 locale du téléphone sur le Wi-Fi actuel, ou null si indisponible. */
    private fun localIpAddress(context: Context): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
