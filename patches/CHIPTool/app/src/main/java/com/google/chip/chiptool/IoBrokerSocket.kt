package com.google.chip.chiptool

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URISyntaxException
import kotlin.coroutines.resume

/**
 * Schlanke WebSocket-Anbindung an ioBroker ueber den web-Adapter (socket.io v2).
 *
 * Ersetzt den Umweg ueber simple-api (REST) + JS-Skript: die App kann damit
 * direkt States lesen (getState) und Adapter-Befehle als Message schicken (sendTo,
 * z.B. controllerCommissionDevice am Matter-Adapter).
 *
 * Coroutine-basiert: alle Aufrufe sind suspend und liefern null/false bei Timeout/Fehler.
 *
 * TODO Authentifizierung: aktuell OHNE Login (web-Adapter steht auf auth:false).
 *      Spaeter nachruesten, z.B. ueber IO.Options.query = "user=<u>&pass=<p>".
 */
class IoBrokerSocket(private val ip: String, private val port: String) {

    private var socket: Socket? = null

    /** Baut die Verbindung auf. true, wenn innerhalb [timeoutMs] verbunden. */
    suspend fun connect(timeoutMs: Long = 8000): Boolean {
        val s = try {
            val opts = IO.Options()
            opts.transports = arrayOf("websocket")
            opts.reconnection = false
            opts.timeout = timeoutMs
            // TODO Auth: opts.query = "user=" + user + "&pass=" + pass
            IO.socket("http://$ip:$port", opts)
        } catch (e: URISyntaxException) {
            return false
        }
        socket = s

        return withTimeoutOrNull(timeoutMs + 1000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                s.on(Socket.EVENT_CONNECT) { if (cont.isActive) cont.resume(true) }
                s.on(Socket.EVENT_CONNECT_ERROR) { if (cont.isActive) cont.resume(false) }
                cont.invokeOnCancellation { s.off() }
                s.connect()
            }
        } ?: false
    }

    /**
     * Liest einen State. ioBroker antwortet ueber den Socket mit (error, state).
     * Rueckgabe: das State-JSON (z.B. {"val":true,...}) oder null.
     */
    suspend fun getState(id: String, timeoutMs: Long = 6000): JSONObject? {
        val s = socket ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<JSONObject?> { cont ->
                s.emit("getState", id, Ack { args ->
                    val state = args?.getOrNull(1)
                    if (cont.isActive) cont.resume(state as? JSONObject)
                })
            }
        }
    }

    /**
     * Schickt einen Adapter-Befehl ueber die Message-API (wie sendTo im JS-Adapter).
     * Der Socket-Callback liefert das Ergebnis direkt als einziges Argument.
     *
     * Beispiele:
     *   sendTo("matter.0", "controllerThreadBorderRouters", JSONObject())
     *   sendTo("matter.0", "controllerCommissionDevice", JSONObject().put("manualCode", code))
     *
     * Rueckgabe: das Ergebnis-JSON (z.B. {"result":...} oder {"error":...}) oder null bei Timeout.
     */
    suspend fun sendTo(
        instance: String,
        command: String,
        message: JSONObject,
        timeoutMs: Long = 70000
    ): JSONObject? {
        val s = socket ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<JSONObject?> { cont ->
                s.emit("sendTo", instance, command, message, Ack { args ->
                    val res = args?.getOrNull(0)
                    if (cont.isActive) cont.resume(res as? JSONObject)
                })
            }
        }
    }

    /** Verbindung sauber schliessen. Immer im finally aufrufen. */
    fun close() {
        socket?.let {
            it.off()
            it.disconnect()
            it.close()
        }
        socket = null
    }
}
