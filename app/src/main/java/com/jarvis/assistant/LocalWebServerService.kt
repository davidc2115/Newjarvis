package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * LocalWebServerService — serveur HTTP minimal (hand-rolled, `java.net.ServerSocket`,
 * AUCUNE dépendance externe) qui sert les fichiers d'un site généré par JARVIS
 * directement depuis le stockage du téléphone, sur le réseau local (et au-delà si
 * combiné à une redirection de port Freebox — voir FreeboxController).
 *
 * Tourne en foreground service (obligatoire : Android tuerait un simple thread dès
 * que l'app passe en arrière-plan) — une notification persistante indique que le
 * serveur est actif, avec le port utilisé, tant qu'il tourne.
 *
 * Volontairement minimal : GET uniquement, pas de HTTPS (TLS embarqué serait
 * disproportionné pour un usage local/DuckDNS ; le trafic reste en clair — à ne pas
 * utiliser pour du contenu sensible), pas de mise en cache, pas de compression.
 * Suffisant pour servir un site statique généré (HTML/CSS/JS/images).
 */
class LocalWebServerService : Service() {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false

    companion object {
        const val EXTRA_ROOT_DIR = "rootDir"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "jarvis_local_web_server"
        private const val NOTIF_ID = 701
        private const val TAG = "LocalWebServerService"

        /** État exposé pour LocalWebServerController — évite d'avoir à binder au service juste pour un statut. */
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var currentPort: Int = 0
            private set
        @Volatile var currentRootPath: String? = null
            private set
        @Volatile var requestCount: Int = 0

        private val MIME_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "txt" to "text/plain; charset=utf-8"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Serveur web local JARVIS", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rootPath = intent?.getStringExtra(EXTRA_ROOT_DIR)
        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080

        if (rootPath.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(port, rootDir.name))
        startServer(rootDir, port)
        return START_STICKY
    }

    private fun buildNotification(port: Int, siteName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌐 Serveur local JARVIS actif")
            .setContentText("« $siteName » servi sur le port $port")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startServer(rootDir: File, port: Int) {
        if (running) return
        running = true
        executor.execute {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                isRunning = true
                currentPort = port
                currentRootPath = rootDir.absolutePath
                requestCount = 0
                while (running) {
                    try {
                        val client = socket.accept()
                        executor.execute { handleClient(client, rootDir) }
                    } catch (e: SocketException) {
                        // socket fermé volontairement (stopServer) — sortie normale de la boucle
                        break
                    } catch (e: IOException) {
                        // erreur transitoire sur une connexion précise — on continue d'accepter
                    }
                }
            } catch (e: IOException) {
                // port déjà utilisé ou autre échec au démarrage — le service s'arrête proprement
                stopServer()
                stopSelf()
            }
        }
    }

    private fun handleClient(client: Socket, rootDir: File) {
        try {
            client.soTimeout = 10_000
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            // Consomme le reste des en-têtes de la requête (on ne les utilise pas, mais il faut
            // vider le flux pour que le client considère la requête bien reçue).
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeResponse(client, 405, "text/plain; charset=utf-8", "405 Method Not Allowed".toByteArray())
                return
            }

            var path = parts[1].substringBefore("?").let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (path.isBlank() || path == "/") path = "/index.html"

            // Protection anti-traversée de chemin : le fichier résolu doit rester DANS rootDir.
            val requested = File(rootDir, path.removePrefix("/")).canonicalFile
            val rootCanonical = rootDir.canonicalFile
            if (!requested.path.startsWith(rootCanonical.path)) {
                writeResponse(client, 403, "text/plain; charset=utf-8", "403 Forbidden".toByteArray())
                return
            }

            val file = if (requested.isDirectory) File(requested, "index.html") else requested
            if (!file.exists() || !file.isFile) {
                writeResponse(client, 404, "text/html; charset=utf-8", "<h1>404 - Page introuvable</h1>".toByteArray())
                return
            }

            requestCount++
            val mime = MIME_TYPES[file.extension.lowercase()] ?: "application/octet-stream"
            writeResponse(client, 200, mime, file.readBytes())
        } catch (e: Exception) {
            // client déconnecté brutalement ou requête malformée — pas bloquant pour le serveur
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(client: Socket, code: Int, contentType: String, body: ByteArray) {
        val statusText = when (code) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "Error"
        }
        val out = BufferedOutputStream(client.getOutputStream())
        val header = "HTTP/1.1 $code $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Server: JARVIS-LocalWebServer\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun stopServer() {
        running = false
        isRunning = false
        currentPort = 0
        currentRootPath = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    override fun onDestroy() {
        stopServer()
        executor.shutdownNow()
        super.onDestroy()
    }
}
