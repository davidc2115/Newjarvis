package com.jarvis.assistant

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * NetworkController — découverte et contrôle des appareils connectés sur le
 * même réseau Wi-Fi que le téléphone (sans passer par Home Assistant).
 *
 * Ce qu'Android autorise réellement à une application classique (sans root) :
 *  • Balayer les IP du sous-réseau local et détecter les hôtes actifs
 *    (ping applicatif via connexion TCP courte sur des ports usuels — plus
 *    fiable que l'ICMP `InetAddress.isReachable`, souvent bloqué par les box).
 *  • Résoudre un nom d'hôte (mDNS/DNS inverse) quand l'appareil l'expose.
 *  • Réveiller un appareil éteint via Wake-on-LAN (paquet magique UDP), à
 *    condition de connaître son adresse MAC (les appareils compatibles WoL
 *    l'affichent dans leurs paramètres réseau — TV, PC, NAS...).
 *  • Ouvrir l'interface web d'un appareil détecté (routeur, NAS, imprimante,
 *    Freebox/box internet...) si un port web est ouvert.
 *
 * Android ne permet PAS (sans root ni app système) de couper le Wi-Fi d'un
 * autre appareil ou d'agir sur des protocoles propriétaires fermés — pour
 * un contrôle plus fin (extinction à distance, etc.), passe par Home
 * Assistant (HomeAssistantController) qui sait parler à ces intégrations.
 */
object NetworkController {

    data class Device(
        val ip: String,
        var hostname: String? = null,
        var openPorts: List<Int> = emptyList(),
        var mac: String? = null
    ) {
        val label: String get() = hostname?.takeIf { it.isNotBlank() && it != ip } ?: ip
        val guessedType: String get() = when {
            openPorts.contains(9100) || openPorts.contains(631) -> "🖨️ Imprimante"
            openPorts.contains(8123) -> "🏠 Home Assistant"
            openPorts.contains(554) || openPorts.contains(8554) -> "📷 Caméra IP"
            openPorts.contains(445) || openPorts.contains(139) -> "💾 NAS / Partage fichiers"
            openPorts.contains(53) || openPorts.contains(1900) -> "📡 Routeur / Box"
            openPorts.contains(22) -> "🖥️ Serveur / PC (SSH)"
            openPorts.contains(3389) -> "🖥️ PC Windows (RDP)"
            openPorts.contains(80) || openPorts.contains(443) || openPorts.contains(8080) -> "🌐 Appareil avec interface web"
            else -> "📶 Appareil réseau"
        }
    }

    // Ports usuels à tester pour détecter un hôte vivant + deviner son type.
    private val PROBE_PORTS = listOf(80, 443, 8080, 8123, 22, 445, 139, 554, 8554, 9100, 631, 3389, 62078)
    private const val CONNECT_TIMEOUT_MS = 250
    private const val MAX_CONCURRENCY = 48

    /** Retourne l'adresse IP locale + le masque, ex: "192.168.1.42" -> préfixe "192.168.1." */
    fun getLocalSubnetPrefix(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        val bytes = byteArrayOf(
            (ip and 0xFF).toByte(),
            (ip shr 8 and 0xFF).toByte(),
            (ip shr 16 and 0xFF).toByte(),
            (ip shr 24 and 0xFF).toByte()
        )
        return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}."
    }

    fun getLocalIp(context: Context): String? {
        val prefix = getLocalSubnetPrefix(context) ?: return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo?.ipAddress ?: return null
        val last = ip shr 24 and 0xFF
        return "$prefix$last"
    }

    /**
     * Balaie les 254 adresses du sous-réseau /24 courant en parallèle (essaie
     * une poignée de ports par hôte) et retourne les appareils qui répondent.
     * Prend généralement 3 à 8 secondes sur un réseau domestique.
     */
    suspend fun scanNetwork(context: Context, onProgress: ((Int, Int) -> Unit)? = null): List<Device> =
        withContext(Dispatchers.IO) {
            val prefix = getLocalSubnetPrefix(context)
                ?: return@withContext emptyList()

            val results = java.util.concurrent.ConcurrentHashMap<String, Device>()
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val range = 1..254
            val chunks = range.chunked((range.count() / MAX_CONCURRENCY).coerceAtLeast(1))

            chunks.forEach { chunk ->
                chunk.map { host ->
                    async {
                        val ip = "$prefix$host"
                        val openPorts = mutableListOf<Int>()
                        for (port in PROBE_PORTS) {
                            if (isPortOpen(ip, port)) openPorts.add(port)
                        }
                        val reachable = openPorts.isNotEmpty() || isIcmpReachable(ip)
                        if (reachable) {
                            val hostname = try {
                                InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
                            } catch (_: Exception) { null }
                            results[ip] = Device(ip = ip, hostname = hostname, openPorts = openPorts)
                        }
                        onProgress?.invoke(done.incrementAndGet(), 254)
                    }
                }.awaitAll()
            }

            results.values.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
        }

    private fun isPortOpen(ip: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun isIcmpReachable(ip: String): Boolean = try {
        InetAddress.getByName(ip).isReachable(CONNECT_TIMEOUT_MS)
    } catch (_: Exception) {
        false
    }

    /**
     * Envoie un paquet magique Wake-on-LAN pour réveiller un appareil éteint
     * (doit avoir le WoL activé dans son BIOS/UEFI ou ses paramètres réseau).
     * @param mac adresse MAC au format "AA:BB:CC:DD:EE:FF" ou "AA-BB-CC-DD-EE-FF"
     */
    suspend fun sendWakeOnLan(context: Context, mac: String): String = withContext(Dispatchers.IO) {
        val cleanMac = mac.trim().replace("-", ":").replace(".", ":")
        val macBytes = try {
            cleanMac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return@withContext "❌ Adresse MAC invalide : « $mac ». Format attendu : AA:BB:CC:DD:EE:FF."
        }
        if (macBytes.size != 6) return@withContext "❌ Adresse MAC invalide : « $mac »."

        return@withContext try {
            val magicPacket = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) magicPacket[i] = 0xFF.toByte()
            for (i in 6 until magicPacket.size step 6) {
                System.arraycopy(macBytes, 0, magicPacket, i, 6)
            }

            val broadcast = getBroadcastAddress(context)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val packet = DatagramPacket(magicPacket, magicPacket.size, broadcast, 9)
                socket.send(packet)
            }
            "⚡ Paquet Wake-on-LAN envoyé à $mac. L'appareil devrait démarrer dans quelques secondes s'il est bien configuré."
        } catch (e: Exception) {
            "❌ Échec de l'envoi Wake-on-LAN : ${e.message}"
        }
    }

    private fun getBroadcastAddress(context: Context): InetAddress {
        val prefix = getLocalSubnetPrefix(context)
        if (prefix != null) {
            return InetAddress.getByName("${prefix}255")
        }
        // Repli : cherche l'adresse de broadcast de l'interface Wi-Fi active.
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            iface.interfaceAddresses?.forEach { addr ->
                addr.broadcast?.let { return it }
            }
        }
        return InetAddress.getByName("255.255.255.255")
    }

    /** Formatte les résultats d'un scan pour la voix / le chat IA. */
    fun formatScanResult(devices: List<Device>): String {
        if (devices.isEmpty()) return "📡 Aucun appareil détecté sur le réseau local (ou le balayage a échoué)."
        val sb = StringBuilder("📡 **${devices.size} appareil(s) détecté(s) sur le réseau Wi-Fi** :\n\n")
        devices.forEach { d ->
            sb.append("• ${d.label} (${d.ip}) — ${d.guessedType}\n")
        }
        return sb.toString().trim()
    }
}
