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

    /** DM-API: laedt die KOMPLETTE Geraeteliste (Pagination via dm:deviceLoadProgress). */
    suspend fun dmLoadDevices(instance: String, timeoutMs: Long = 20000): JSONObject? {
        if (socket == null) return null
        return withTimeoutOrNull(timeoutMs) {
            val merged = org.json.JSONArray()
            var total = -1
            var res = dmCall(instance, "dm:loadDevices", null) ?: return@withTimeoutOrNull null
            var guard = 0
            while (guard < 50) {
                val add = res.optJSONArray("add")
                if (add != null) {
                    for (i in 0 until add.length()) merged.put(add.get(i))
                }
                if (total < 0) total = res.optInt("total", -1)
                val origin = res.optJSONObject("next")?.opt("origin") ?: break
                res = dmCall(instance, "dm:deviceLoadProgress", org.json.JSONObject().put("origin", origin)) ?: break
                guard++
            }
            org.json.JSONObject().put("add", merged).put("total", total)
        }
    }

    /** Ein einzelner sendTo-Aufruf; gibt das Ergebnis-JSON zurueck. */
    private suspend fun dmCall(instance: String, command: String, message: Any?): JSONObject? {
        val s = socket ?: return null
        return suspendCancellableCoroutine { cont ->
            s.emit("sendTo", instance, command, message, Ack { args ->
                val r = args?.getOrNull(0)
                if (cont.isActive) cont.resume(r as? JSONObject)
            })
        }
    }

    /** Liest alle States unter dem Muster (id -> stateObj). */
    suspend fun getStates(pattern: String, timeoutMs: Long = 8000): JSONObject? {
        val s = socket ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<JSONObject?> { cont ->
                s.emit("getStates", pattern, Ack { args ->
                    val states = args?.getOrNull(1)
                    if (cont.isActive) cont.resume(states as? JSONObject)
                })
            }
        }
    }

    /** Liest State-Objekte im id-Bereich (Metadaten). Rueckgabe: {rows:[{id, value:{common...}}]}. */
    suspend fun getStateObjects(startkey: String, endkey: String, timeoutMs: Long = 8000): JSONObject? {
        val s = socket ?: return null
        val params = JSONObject().put("startkey", startkey).put("endkey", endkey)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<JSONObject?> { cont ->
                s.emit("getObjectView", "system", "state", params, Ack { args ->
                    val doc = args?.getOrNull(1)
                    if (cont.isActive) cont.resume(doc as? JSONObject)
                })
            }
        }
    }

    /** Setzt einen State (Steuerung). true bei Erfolg. */
    suspend fun setState(id: String, value: Any?, timeoutMs: Long = 6000): Boolean {
        val s = socket ?: return false
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                s.emit("setState", id, value, Ack { args ->
                    val err = args?.getOrNull(0)
                    if (cont.isActive) cont.resume(err == null)
                })
            }
        } ?: false
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
