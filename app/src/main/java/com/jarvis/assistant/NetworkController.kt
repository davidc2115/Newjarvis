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
    suspend fun sendWakeOnLan(context: Context, mac: String, deviceName: String? = null): String = withContext(Dispatchers.IO) {
        val cleanMac = mac.trim().replace("-", ":").replace(".", ":")
        val macBytes = try {
            cleanMac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return@withContext "❌ Adresse MAC invalide : « $mac ». Format attendu : AA:BB:CC:DD:EE:FF."
        }
        if (macBytes.size != 6) return@withContext "❌ Adresse MAC invalide : « $mac »."

        val magicPacket = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) magicPacket[i] = 0xFF.toByte()
        for (i in 6 until magicPacket.size step 6) {
            System.arraycopy(macBytes, 0, magicPacket, i, 6)
        }

        var localOk = false
        var localError: String? = null
        try {
            val broadcast = getBroadcastAddress(context)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val packet = DatagramPacket(magicPacket, magicPacket.size, broadcast, 9)
                socket.send(packet)
            }
            localOk = true
        } catch (e: Exception) {
            localError = e.message
        }

        // Réveil à distance (hors Wi-Fi local) : nécessite que l'utilisateur ait redirigé
        // un port UDP (souvent 9) de sa box vers son réseau local pour cet appareil — JARVIS
        // ne peut pas créer cette redirection lui-même, seulement l'utiliser si configurée
        // via set_remote_access. Le paquet magique est alors envoyé en UNICAST direct à cet
        // hôte plutôt qu'en broadcast (le broadcast ne traverse jamais Internet).
        var remoteOk = false
        val remoteHost = deviceName?.let { resolveDeviceRemoteHost(context, it) }
        if (!remoteHost.isNullOrBlank()) {
            try {
                val (host, port) = parseHostPort(remoteHost, 9)
                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(magicPacket, magicPacket.size, InetAddress.getByName(host), port)
                    socket.send(packet)
                }
                remoteOk = true
            } catch (_: Exception) { /* remoteOk reste false, reflété dans le message ci-dessous */ }
        }

        return@withContext when {
            localOk && remoteOk -> "⚡ Paquet Wake-on-LAN envoyé à $mac en local ET vers l'accès distant ($remoteHost). L'appareil devrait démarrer dans quelques secondes s'il est bien configuré."
            localOk -> "⚡ Paquet Wake-on-LAN envoyé à $mac (réseau local). L'appareil devrait démarrer dans quelques secondes s'il est bien configuré."
            remoteOk -> "⚡ Paquet Wake-on-LAN envoyé à $mac via l'accès distant ($remoteHost) — le réseau local n'a pas pu être utilisé directement."
            else -> "❌ Échec de l'envoi Wake-on-LAN : ${localError ?: "erreur inconnue"}."
        }
    }

    /** Sépare un "host" ou "host:port" ; retourne [defaultPort] si aucun port n'est précisé. */
    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = hostPort.trim()
        val idx = trimmed.lastIndexOf(':')
        return if (idx > 0 && trimmed.substring(idx + 1).toIntOrNull() != null) {
            trimmed.substring(0, idx) to trimmed.substring(idx + 1).toInt()
        } else {
            trimmed to defaultPort
        }
    }

    /** Adresse distante (publique/DDNS) enregistrée pour un appareil, ou null si aucune. */
    fun resolveDeviceRemoteHost(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isBlank()) return null
        val saved = Prefs.getSavedNetworkDevices(context)
        return saved.firstOrNull { it.name.equals(q, ignoreCase = true) && it.remoteHost.isNotBlank() }?.remoteHost
            ?: saved.firstOrNull { it.name.contains(q, ignoreCase = true) && it.remoteHost.isNotBlank() }?.remoteHost
    }

    /**
     * Récupère l'IP publique (WAN) du réseau actuel — best effort, via un service HTTP
     * public qui renvoie juste l'IP en texte brut, sans clé ni compte. Best effort
     * volontaire : un échec (pas de réseau, service indisponible...) renvoie simplement
     * null, ne doit jamais faire échouer l'action appelante (ping/ouverture web) pour
     * laquelle cette capture n'est qu'un bonus.
     */
    suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("https://api.ipify.org")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            conn.disconnect()
            ip.takeIf { it.isNotBlank() && it.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Demandé explicitement : capture automatiquement l'IP publique courante comme accès
     * distant pour [deviceName] dès qu'une connexion LOCALE à cet appareil réussit — pour
     * que JARVIS puisse ensuite retenter cet accès depuis l'extérieur du réseau, sans que
     * l'utilisateur ait à chercher et saisir lui-même son IP publique via set_remote_access.
     *
     * Limites honnêtes, assumées dans le message renvoyé : (1) ne s'applique qu'aux
     * appareils déjà enregistrés par leur NOM (📡 Réseau local) — une IP tapée directement
     * n'a pas de fiche où stocker l'info ; (2) NE REMPLACE JAMAIS un accès distant déjà
     * configuré (ex: un DDNS+port mis en place manuellement) ; (3) la plupart des
     * connexions grand public ont une IP dynamique qui peut changer — ce n'est pas un
     * DDNS, juste un point de départ pratique, capturé à nouveau à chaque connexion locale
     * réussie tant qu'aucun accès distant explicite n'a été défini ; (4) fonctionne
     * seulement si le port nécessaire est déjà redirigé vers l'appareil sur la box/routeur
     * — JARVIS ne peut pas créer cette redirection lui-même.
     */
    private suspend fun autoCaptureRemoteHost(context: Context, deviceName: String): String? {
        val existing = resolveDeviceRemoteHost(context, deviceName)
        if (!existing.isNullOrBlank()) return null // ne jamais écraser un réglage déjà en place
        val saved = Prefs.getSavedNetworkDevices(context).firstOrNull { it.name.equals(deviceName, ignoreCase = true) }
            ?: return null
        val publicIp = fetchPublicIp() ?: return null
        Prefs.setDeviceRemoteHost(context, saved.name, publicIp)
        return publicIp
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

    // Ports qui servent effectivement une interface web consultable dans un navigateur
    // (contrairement à 9100, port d'impression brut sans interface HTTP).
    private val WEB_PORTS = listOf(443, 80, 8080, 631)

    /** Meilleure URL web devinée pour un appareil, ou null si aucun port web n'est ouvert. */
    fun guessWebUrl(device: Device): String? {
        val port = WEB_PORTS.firstOrNull { device.openPorts.contains(it) } ?: return null
        val scheme = if (port == 443) "https" else "http"
        val portSuffix = if (port == 80 || port == 443) "" else ":$port"
        return "$scheme://${device.ip}$portSuffix"
    }

    /**
     * Retrouve l'IP d'un appareil à partir d'un nom (recherche insensible à la casse
     * dans les appareils déjà scannés/enregistrés) ou directement d'une IP fournie.
     */
    private fun resolveDeviceIp(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isBlank()) return null
        // L'utilisateur a peut-être donné directement une IP.
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(q)) return q
        val saved = Prefs.getSavedNetworkDevices(context)
        return saved.firstOrNull { it.name.equals(q, ignoreCase = true) && it.ip.isNotBlank() }?.ip
            ?: saved.firstOrNull { it.name.contains(q, ignoreCase = true) && it.ip.isNotBlank() }?.ip
    }

    /**
     * "Ping" applicatif d'un appareil précis (par nom déjà connu ou IP directe) : reteste
     * sa joignabilité et ses ports ouverts EN DIRECT (contrairement au scan complet, ne
     * sonde qu'un seul hôte donc quasi instantané). Utilisé pour la commande vocale/chat
     * "ping l'imprimante" ou "est-ce que le NAS répond".
     */
    suspend fun pingDevice(context: Context, query: String): String = withContext(Dispatchers.IO) {
        val ip = resolveDeviceIp(context, query)
            ?: return@withContext "❌ Aucun appareil connu pour « $query ». Lance d'abord un scan réseau (📡 Réseau local) pour qu'il soit repéré."

        val start = System.currentTimeMillis()
        val portResults = PROBE_PORTS.map { port -> async { port to isPortOpen(ip, port) } }.awaitAll()
        val elapsedMs = System.currentTimeMillis() - start
        val openPorts = portResults.filter { it.second }.map { it.first }
        val reachable = openPorts.isNotEmpty() || isIcmpReachable(ip)

        if (reachable) {
            val device = Device(ip = ip, openPorts = openPorts)
            val webPart = guessWebUrl(device)?.let { "\n🌐 Interface web disponible : $it" } ?: ""
            val portsPart = if (openPorts.isNotEmpty()) "\n🔓 Ports ouverts : ${openPorts.joinToString(", ")}" else ""
            // Capture automatique de l'IP publique pour un accès distant ultérieur — voir
            // autoCaptureRemoteHost (n'écrase jamais un accès distant déjà configuré).
            val capturedIp = autoCaptureRemoteHost(context, query)
            val capturePart = if (capturedIp != null) {
                "\n🌍 IP publique capturée pour un accès distant futur : $capturedIp (fonctionnera seulement si le port " +
                    "nécessaire est redirigé vers cet appareil sur ta box — sinon utilise set_remote_access pour préciser " +
                    "un DDNS+port fiable)."
            } else ""
            return@withContext "✅ « $query » ($ip) répond en ${elapsedMs}ms — ${device.guessedType}.$portsPart$webPart$capturePart"
        }

        // Injoignable en local (donc éventuellement hors Wi-Fi domestique) — bascule sur
        // l'accès distant s'il a été configuré via set_remote_access pour cet appareil.
        val remoteHost = resolveDeviceRemoteHost(context, query)
        if (!remoteHost.isNullOrBlank()) {
            val (remoteIp, _) = parseHostPort(remoteHost, 0)
            val remoteStart = System.currentTimeMillis()
            val remotePortResults = PROBE_PORTS.map { port -> async { port to isPortOpen(remoteIp, port) } }.awaitAll()
            val remoteElapsed = System.currentTimeMillis() - remoteStart
            val remoteOpenPorts = remotePortResults.filter { it.second }.map { it.first }
            if (remoteOpenPorts.isNotEmpty()) {
                val device = Device(ip = remoteIp, openPorts = remoteOpenPorts)
                return@withContext "✅ « $query » ($remoteHost, accès distant) répond en ${remoteElapsed}ms — ${device.guessedType}."
            }
            return@withContext "❌ « $query » ne répond ni en local ($ip) ni via l'accès distant configuré ($remoteHost)."
        }

        "❌ « $query » ($ip) ne répond pas — éteint, hors de portée Wi-Fi, ou pare-feu qui bloque le sondage." +
            (if (isOffLocalNetwork(context)) " Tu sembles être hors de ton réseau local : configure un accès distant avec set_remote_access si tu veux le joindre de l'extérieur." else "")
    }

    /** Vrai si le téléphone n'est actuellement pas connecté au Wi-Fi (donc probablement hors réseau local). */
    private fun isOffLocalNetwork(context: Context): Boolean = getLocalSubnetPrefix(context) == null

    /**
     * Ouvre l'interface web d'un appareil (nom connu ou IP) dans le navigateur du
     * téléphone, si un port web est détecté. Reteste en direct plutôt que de se fier à
     * un ancien scan, au cas où l'appareil aurait changé d'état depuis.
     */
    suspend fun openWebInterface(context: Context, query: String): String {
        val ip = resolveDeviceIp(context, query)
            ?: return "❌ Aucun appareil connu pour « $query ». Lance d'abord un scan réseau (📡 Réseau local) pour qu'il soit repéré."

        val openPorts = withContext(Dispatchers.IO) {
            WEB_PORTS.map { port -> async { port to isPortOpen(ip, port) } }.awaitAll()
        }.filter { it.second }.map { it.first }
        val reachedLocally = openPorts.isNotEmpty()

        var url = guessWebUrl(Device(ip = ip, openPorts = openPorts))
        if (url == null) {
            // Injoignable/pas d'interface web en local — bascule sur l'accès distant si configuré.
            val remoteHost = resolveDeviceRemoteHost(context, query)
            if (!remoteHost.isNullOrBlank()) {
                val (remoteIp, remotePort) = parseHostPort(remoteHost, 0)
                val remoteOpenPorts = withContext(Dispatchers.IO) {
                    WEB_PORTS.map { port -> async { port to isPortOpen(remoteIp, port) } }.awaitAll()
                }.filter { it.second }.map { it.first }
                url = guessWebUrl(Device(ip = remoteIp, openPorts = remoteOpenPorts))
                    ?: if (remotePort > 0) "http://$remoteIp:$remotePort" else null
            }
        }
        if (url == null) {
            return "❌ « $query » ($ip) ne semble pas exposer d'interface web accessible (aucun des ports 80/443/8080/631 n'est ouvert). Pour une imprimante, vérifie que le serveur web intégré est activé dans ses réglages."
        }

        // Capture automatique de l'IP publique pour un accès distant ultérieur — uniquement
        // quand l'appareil a bien été joint EN LOCAL cette fois-ci (pas via un accès
        // distant déjà en place, ce qui n'apporterait rien).
        val capturePart = if (reachedLocally) {
            autoCaptureRemoteHost(context, query)?.let {
                "\n🌍 IP publique capturée pour un accès distant futur : $it (nécessite que le port soit redirigé sur ta box)."
            } ?: ""
        } else ""

        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "🌐 Ouverture de l'interface web de « $query » : $url$capturePart"
        } catch (e: Exception) {
            "❌ Impossible d'ouvrir le navigateur : ${e.message}"
        }
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
