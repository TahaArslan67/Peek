package com.peek.app.webrtc

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Signaling sunucusuyla WebSocket üzerinden haberleşir.
 *
 * Mesaj tipleri (server ile ortak):
 *  - join      : { type, roomCode }
 *  - offer     : { type, sdp }
 *  - answer    : { type, sdp }
 *  - ice-candidate : { type, candidate, sdpMid, sdpMLineIndex }
 *  - leave     : { type }
 *  - peer-status : { type, status: "joined"|"left" }
 *
 * Sunucu URL'si AppPreferences'tan alınır (varsayılan: lokal/render adresi).
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener,
) {
    interface SignalingListener {
        fun onConnected()
        fun onMessage(message: JSONObject)
        fun onDisconnected(reason: String)
        fun onError(error: Throwable)
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    /** WebSocket bağlantısını açar. */
    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    listener.onMessage(JSONObject(text))
                } catch (e: Exception) {
                    listener.onError(e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t)
            }
        })
    }

    fun joinRoom(roomCode: String) = send(JSONObject().apply {
        put("type", "join")
        put("roomCode", roomCode)
    })

    fun sendOffer(sdp: String) = send(JSONObject().apply {
        put("type", "offer")
        put("sdp", sdp)
    })

    fun sendAnswer(sdp: String) = send(JSONObject().apply {
        put("type", "answer")
        put("sdp", sdp)
    })

    fun sendIceCandidate(candidate: JSONObject) = send(JSONObject().apply {
        put("type", "ice-candidate")
        put("candidate", candidate)
    })

    fun leave() = send(JSONObject().apply { put("type", "leave") })

    // ---- Aşama 4: görüntü istek mesajları ----

    fun sendViewRequest() = send(JSONObject().apply { put("type", "view-request") })

    fun sendViewAccept() = send(JSONObject().apply { put("type", "view-accept") })

    fun sendViewReject() = send(JSONObject().apply { put("type", "view-reject") })

    fun sendViewStop() = send(JSONObject().apply { put("type", "view-stop") })

    private fun send(message: JSONObject): Boolean {
        Log.d(TAG, "send: $message")
        return webSocket?.send(message.toString()) ?: false
    }

    fun disconnect() {
        webSocket?.close(1000, "client closed")
        webSocket = null
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
