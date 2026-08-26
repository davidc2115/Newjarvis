package com.jarvis.assistant

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * HomeAssistantWsClient — client WebSocket minimal pour les commandes
 * d'administration qui n'existent QUE via l'API WebSocket de Home Assistant
 * (pas de route REST équivalente) : renommer ou supprimer une entité du
 * registre. https://developers.home-assistant.io/docs/api/websocket/
 *
 * Ouvre une connexion, s'authentifie avec le même jeton à long terme que la
 * partie REST, envoie UNE commande, attend le résultat, puis referme —
 * volontairement sans connexion persistante pour rester simple et fiable
 * (ces actions sont ponctuelles, pas un flux temps réel).
 */
object HomeAssistantWsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // pas de timeout de lecture sur un WebSocket
        .build()

    /** Envoie une commande (sans "id", ajouté automatiquement) et retourne le message "result" du serveur. */
    suspend fun sendCommand(context: android.content.Context, command: JSONObject): JSONObject =
        suspendCancellableCoroutine { cont ->
            if (!HomeAssistantController.isConfigured(context)) {
                cont.resume(JSONObject().put("success", false).put("error", "Home Assistant non configuré."))
                return@suspendCancellableCoroutine
            }

            val wsUrl = Prefs.getHaUrl(context).trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/api/websocket"

            var resumed = false
            fun finish(result: JSONObject, socket: WebSocket?) {
                if (!resumed) {
                    resumed = true
                    cont.resume(result)
                }
                socket?.close(1000, null)
            }

            val request = Request.Builder().url(wsUrl).build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "auth_required" -> {
                                webSocket.send(
                                    JSONObject()
                                        .put("type", "auth")
                                        .put("access_token", Prefs.getHaToken(context))
                                        .toString()
                                )
                            }
                            "auth_invalid" -> finish(
                                JSONObject().put("success", false).put("error", "Jeton Home Assistant invalide ou expiré."),
                                webSocket
                            )
                            "auth_ok" -> {
                                val toSend = JSONObject(command.toString()).put("id", 1)
                                webSocket.send(toSend.toString())
                            }
                            "result" -> finish(json, webSocket)
                        }
                    } catch (_: Exception) {
                        // ignore les messages non exploitables (ping/pong etc.)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    finish(JSONObject().put("success", false).put("error", t.message ?: "Erreur de connexion WebSocket."), null)
                }
            })

            cont.invokeOnCancellation { socket.cancel() }
        }
}
