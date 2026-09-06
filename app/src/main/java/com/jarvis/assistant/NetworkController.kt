package com.jarvis.assistant

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * NetworkController — découverte et contrôle des appareils connectés sur le
 * même réseau Wi-Fi que le téléphone (sans passer par Home Assistant).
 *
 * Ce qu'Android autorise réellement à une application classique (sans root) :
 *  • Balayer les IP du sous-réseau local et détecter les hôtes actifs
 *    (ping applicatif via connexion TCP courte sur des ports usuels — plus
 *    fiable que l'ICMP `InetAddress.isReachable`, souvent bloqué par les box).
 *  • Deviner un nom lisible : DNS inverse, puis requête NetBIOS (fonctionne
 *    pour la plupart des PC Windows / NAS Samba), sinon repli sur l'IP.
 *  • Retrouver l'adresse MAC via la table ARP du téléphone
 *    (`/proc/net/arp`) quand elle est accessible — uniquement pour les
 *    appareils que le téléphone a déjà contactés (donc juste après le
 *    scan). C'est un accès en lecture "best effort" : certains
 *    fabricants/versions Android le bloquent par sécurité, auquel cas
 *    l'adresse MAC reste vide et doit être saisie manuellement pour le
 *    Wake-on-LAN.
 *  • Réveiller un appareil éteint via Wake-on-LAN (paquet magique UDP), à
 *    condition de connaître son adresse MAC.
 *  • Ouvrir l'interface web d'un appareil détecté si un port web est ouvert.
 *
 * Android ne permet PAS (sans root ni app système) de couper le Wi-Fi d'un
 * autre appareil ou d'agir sur des protocoles propriétaires fermés — pour
 * un contrôle plus fin, passe par Home Assistant (HomeAssistantController).
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
    private const val NETBIOS_TIMEOUT_MS = 300

    /** Retourne le préfixe /24 du sous-réseau Wi-Fi courant, ex: "192.168.1." */
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
     * Balaie les 254 adresses du sous-réseau /24 courant en parallèle
     * (hôtes ET ports sondés concurremment) et retourne les appareils qui
     * répondent, avec nom deviné et MAC si trouvable. Prend généralement
     * 2 à 6 secondes sur un réseau domestique.
     */
    suspend fun scanNetwork(context: Context, onProgress: ((Int, Int) -> Unit)? = null): List<Device> =
        withContext(Dispatchers.IO) {
            val prefix = getLocalSubnetPrefix(context)
                ?: return@withContext emptyList()

            val results = java.util.concurrent.ConcurrentHashMap<String, Device>()
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val range = 1..254
            // Chunks de MAX_CONCURRENCY hôtes (pas l'inverse) pour sonder ~48 hôtes en parallèle.
            val chunks = range.chunked(MAX_CONCURRENCY)

            chunks.forEach { chunk ->
                chunk.map { host ->
                    async {
                        val ip = "$prefix$host"

                        // Sonde tous les ports d'un même hôte EN PARALLÈLE (pas séquentiellement)
                        // pour rester rapide même sur un hôte qui ne répond sur aucun port.
                        val portResults = PROBE_PORTS.map { port ->
                            async { port to isPortOpen(ip, port) }
                        }.awaitAll()
                        val openPorts = portResults.filter { it.second }.map { it.first }

                        val reachable = openPorts.isNotEmpty() || isIcmpReachable(ip)
                        if (reachable) {
                            val hostname = resolveHostname(ip)
                            results[ip] = Device(ip = ip, hostname = hostname, openPorts = openPorts)
                        }
                        onProgress?.invoke(done.incrementAndGet(), 254)
                    }
                }.awaitAll()
            }

            // Table ARP lue APRÈS le scan : les hôtes locaux viennent d'être contactés en TCP,
            // donc leur MAC est normalement fraîche dans le cache ARP du téléphone à ce stade.
            val arpTable = readArpTable()
            results.values.forEach { device ->
                arpTable[device.ip]?.let { device.mac = it }
            }

            results.values.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
        }

    /** DNS inverse puis, à défaut, requête NetBIOS (PC Windows / NAS Samba). */
    private fun resolveHostname(ip: String): String? {
        val dnsName = try {
            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
        } catch (_: Exception) { null }
        if (!dnsName.isNullOrBlank()) return dnsName

        return try {
            queryNetbiosName(ip)
        } catch (_: Exception) {
            null
        }
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
     * Requête NetBIOS Name Service (NBSTAT, UDP/137) — donne le nom
     * "d'ordinateur" tel qu'annoncé par Windows/Samba, souvent bien plus
     * parlant qu'une IP quand la résolution DNS inverse échoue (le cas le
     * plus fréquent sur un réseau domestique).
     */
    private fun queryNetbiosName(ip: String): String? {
        DatagramSocket().use { socket ->
            socket.soTimeout = NETBIOS_TIMEOUT_MS

            // En-tête NBNS : ID transaction, flags "requête standard", 1 question, 0 réponses.
            val query = ByteArray(50)
            query[0] = 0x13; query[1] = 0x37 // transaction ID arbitraire
            query[2] = 0x00; query[3] = 0x00 // flags
            query[4] = 0x00; query[5] = 0x01 // QDCOUNT = 1
            // ANCOUNT, NSCOUNT, ARCOUNT = 0 (déjà à 0 par défaut)

            // QNAME "*" encodé en "first-level encoding" NetBIOS (16 octets → 32 nibbles + 'A').
            var idx = 12
            query[idx++] = 32 // longueur du nom encodé
            val rawName = "*".padEnd(16, ' ').toByteArray(Charsets.US_ASCII)
            for (b in rawName) {
                val v = b.toInt() and 0xFF
                query[idx++] = ('A'.code + (v shr 4)).toByte()
                query[idx++] = ('A'.code + (v and 0x0F)).toByte()
            }
            query[idx++] = 0x00 // fin du nom (longueur 0)
            query[idx++] = 0x00; query[idx++] = 0x21 // QTYPE = NBSTAT (0x0021)
            query[idx++] = 0x00; query[idx] = 0x01   // QCLASS = IN (0x0001)

            val packet = DatagramPacket(query, query.size, InetAddress.getByName(ip), 137)
            socket.send(packet)

            val responseBuf = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
            try {
                socket.receive(responsePacket)
            } catch (_: SocketTimeoutException) {
                return null
            }

            val data = responsePacket.data
            if (responsePacket.length < 14) return null

            // Nom de la réponse : soit répété en clair (34 octets), soit un pointeur de
            // compression DNS (2 octets, motif 0xC0..) — on gère les deux pour rester robuste
            // face aux implémentations NBNS variées (Windows/Samba/routeurs).
            var pos = 12
            pos += if ((data[pos].toInt() and 0xC0) == 0xC0) 2 else 34

            // TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2) = 10 octets avant le RDATA.
            val numNamesPos = pos + 10
            if (responsePacket.length <= numNamesPos) return null

            val nameCount = data[numNamesPos].toInt() and 0xFF
            if (nameCount <= 0) return null

            val firstNameStart = numNamesPos + 1
            if (responsePacket.length < firstNameStart + 15) return null
            val nameBytes = data.copyOfRange(firstNameStart, firstNameStart + 15)
            val name = String(nameBytes, Charsets.US_ASCII).trim()
            return name.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        }
    }

    /**
     * Lecture "best effort" de la table ARP du téléphone (/proc/net/arp) pour
     * retrouver les adresses MAC des hôtes récemment contactés. Certains
     * appareils/versions Android restreignent cet accès par sécurité : dans
     * ce cas la lecture échoue silencieusement et on continue sans MAC
     * (l'utilisateur peut toujours la saisir manuellement pour le
     * Wake-on-LAN).
     */
    private fun readArpTable(): Map<String, String> {
        return try {
            val lines = File("/proc/net/arp").readLines()
            val map = mutableMapOf<String, String>()
            for (line in lines.drop(1)) { // ignore l'en-tête de colonnes
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4) {
                    val ip = cols[0]
                    val mac = cols[3]
                    if (mac.isNotBlank() && mac != "00:00:00:00:00:00") {
                        map[ip] = mac.uppercase()
                    }
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
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
            val macPart = d.mac?.let { " — MAC $it" } ?: ""
            sb.append("• ${d.label} (${d.ip})$macPart — ${d.guessedType}\n")
        }
        return sb.toString().trim()
    }
}
