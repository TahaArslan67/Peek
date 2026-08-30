package com.peek.app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.peek.app.PeekApplication
import com.peek.app.data.models.Session
import com.peek.app.pairing.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/**
 * SignalingClient + WebRtcClient + PairingManager'i koordine eden orchestrator.
 *
 * Aşama 4: Görüntü istek mekanizması. Eşleşince kamera KAPALI kalır.
 * Görüntü isteğe bağlı:
 *  - sendViewRequest(): karşı taraftan görüntü iste
 *  - acceptViewRequest(): kendi kamera aç, createOffer gönder (CALLER rolü)
 *  - rejectViewRequest(): reddet
 *  - stopViewing(): WebRTC bağlantıyı kes, kamera kapat, eşleşme korunsun
 *
 * Signaling onMessage:
 *  - "joined"            -> rol belirle (peerCount==1 CALLER, ==2 CALLEE)
 *  - "peer-status:joined" -> sadece listener'a bildir (otomatik offer YOK)
 *  - "view-request"      -> listener.onViewRequest
 *  - "view-accept"       -> listener.onViewAccepted (offer beklenir)
 *  - "view-reject"       -> listener.onViewRejected
 *  - "view-stop"         -> WebRTC release + listener.onViewStopped
 *  - "offer"             -> handleOffer (görüntü isteyen taraf, receiver only)
 *  - "answer"            -> handleAnswer
 *  - "ice-candidate"     -> handleIceCandidate
 *  - "peer-status:left"  -> bağlantıyı temizle, listener'a bildir
 *
 * Tüm callback'ler main thread'e post edilir (UI thread).
 */
class ConnectionManager(
    private val context: Context,
    private val listener: ConnectionListener,
) {

    interface ConnectionListener {
        fun onRoomJoined(roomCode: String, role: Session.Role, peerCount: Int)
        fun onPeerJoined()
        fun onPeerLeft()
        fun onConnectionStateChanged(state: PeerConnection.IceConnectionState)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onLocalVideoTrack(track: VideoTrack)
        fun onError(error: Throwable)
        // Aşama 4 — görüntü istek callback'leri
        fun onViewRequest() {}
        fun onViewAccepted() {}
        fun onViewRejected() {}
        fun onViewStopped() {}
    }

    private val app = context.applicationContext as PeekApplication
    private val preferences = app.preferences
    private val eglBase = app.eglBase
    private val factory = app.factory

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var signalingClient: SignalingClient? = null
    private var webRtcClient: WebRtcClient? = null
    private var session: Session? = null

    /**
     * Mevcut görüntü oturumunda bu cihazın rolü.
     *  - VIEWER: görüntü isteyen (receiver only, kamera kapalı)
     *  - BROADCASTER: görüntü veren (kamera açık, offer gönderir)
     *  - NONE: görüntü aktif değil
     */
    private enum class ViewRole { NONE, VIEWER, BROADCASTER }
    private var viewRole: ViewRole = ViewRole.NONE

    /** Eşleşme kodu üret ve signaling'e bağlanıp odaya katıl. */
    fun generateAndJoin() {
        val code = PairingManager.generateCode()
        scope.launch {
            val url = preferences.signalingServerUrl.first()
            connectAndJoin(url, code)
        }
    }

    /** Verilen kod ile signaling'e bağlanıp odaya katıl. */
    fun joinWithCode(code: String) {
        if (!PairingManager.isValidCode(code)) {
            mainHandler.post { listener.onError(IllegalArgumentException("Geçersiz eşleşme kodu")) }
            return
        }
        scope.launch {
            val url = preferences.signalingServerUrl.first()
            connectAndJoin(url, code)
        }
    }

    private suspend fun connectAndJoin(serverUrl: String, roomCode: String) {
        // Önceki bağlantıyı temizle
        cleanupInternal()

        val signaling = SignalingClient(serverUrl, signalingListener)
        signalingClient = signaling

        val webRtc = WebRtcClient(
            context = context,
            eglBase = eglBase,
            factory = factory,
            signalingClient = signaling,
            listener = webRtcListener,
        )
        webRtcClient = webRtc

        pendingRoomCode = roomCode
        signaling.connect()
    }

    private var pendingRoomCode: String? = null

    // ---- Aşama 4: Görüntü istek metotları ----

    /** Karşı taraftan görüntü iste (view-request gönder). */
    fun sendViewRequest() {
        signalingClient?.sendViewRequest()
    }

    /**
     * Görüntü isteğini kabul et: kamera aç, PeerConnection kur (BROADCASTER),
     * createOffer gönder. Bu taraf CALLER/offer gönderen olur.
     */
    fun acceptViewRequest() {
        viewRole = ViewRole.BROADCASTER
        scope.launch {
            val iceServers = preferences.getIceServers()
            // Görüntü veren taraf: kamera açık (includeLocalVideo = true)
            val ok = webRtcClient?.createPeerConnection(iceServers, includeLocalVideo = true) ?: false
            peerConnectionCreated = ok
            if (ok) {
                webRtcClient?.createOffer()
            } else {
                mainHandler.post { listener.onError(RuntimeException("PeerConnection oluşturulamadı")) }
            }
        }
    }

    /** Görüntü isteğini reddet (view-reject gönder). */
    fun rejectViewRequest() {
        signalingClient?.sendViewReject()
    }

    /**
     * Görüntüyü durdur: WebRTC bağlantıyı kes, kamera kapat.
     * Eşleşme (signaling + oda) KORUNSUN — tekrar istek gönderilebilsin.
     */
    fun stopViewing() {
        signalingClient?.sendViewStop()
        releaseWebRtcOnly()
        mainHandler.post { listener.onViewStopped() }
    }

    // ---- Signaling listener ----

    private val signalingListener = object : SignalingClient.SignalingListener {
        override fun onConnected() {
            Log.d(TAG, "signaling connected")
            pendingRoomCode?.let { code ->
                signalingClient?.joinRoom(code)
            }
        }

        override fun onMessage(message: JSONObject) {
            handleSignalingMessage(message)
        }

        override fun onDisconnected(reason: String) {
            Log.d(TAG, "signaling disconnected: $reason")
            mainHandler.post { listener.onPeerLeft() }
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "signaling error", error)
            mainHandler.post { listener.onError(error) }
        }
    }

    private fun handleSignalingMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "joined" -> {
                val roomCode = msg.optString("roomCode")
                val peerCount = msg.optInt("peerCount")
                val role = if (peerCount <= 1) Session.Role.CALLER else Session.Role.CALLEE
                session = Session(roomCode = roomCode, role = role, isConnected = false)
                mainHandler.post { listener.onRoomJoined(roomCode, role, peerCount) }
                // Aşama 4: otomatik PeerConnection/offer YOK. Görüntü iste beklenir.
            }

            "peer-status" -> {
                when (msg.optString("status")) {
                    "joined" -> {
                        mainHandler.post { listener.onPeerJoined() }
                        // Aşama 4: otomatik createOffer YAPILMAZ
                    }

                    "left" -> {
                        mainHandler.post { listener.onPeerLeft() }
                        cleanupInternal()
                    }
                }
            }

            // ---- Aşama 4: görüntü istek mesajları ----
            "view-request" -> {
                // Karşı taraf bizden görüntü istiyor
                mainHandler.post { listener.onViewRequest() }
            }

            "view-accept" -> {
                // Karşı taraf kabul etti; onlar offer gönderecek (BROADCASTER)
                // Biz VIEWER'ız, offer'ı bekleyeceğiz
                viewRole = ViewRole.VIEWER
                mainHandler.post { listener.onViewAccepted() }
            }

            "view-reject" -> {
                viewRole = ViewRole.NONE
                mainHandler.post { listener.onViewRejected() }
            }

            "view-stop" -> {
                // Karşı taraf görüntüyü durdurdu
                releaseWebRtcOnly()
                viewRole = ViewRole.NONE
                mainHandler.post { listener.onViewStopped() }
            }

            "offer" -> {
                // Görüntü isteyen taraf (VIEWER): kamera açmadan PeerConnection kur
                if (peerConnectionCreated.not()) {
                    scope.launch {
                        val iceServers = preferences.getIceServers()
                        // VIEWER: kamera kapalı (includeLocalVideo = false)
                        val ok = webRtcClient?.createPeerConnection(
                            iceServers, includeLocalVideo = false
                        ) ?: false
                        peerConnectionCreated = ok
                        if (ok) {
                            webRtcClient?.handleOffer(msg.optString("sdp"))
                        } else {
                            mainHandler.post { listener.onError(RuntimeException("PeerConnection oluşturulamadı")) }
                        }
                    }
                } else {
                    webRtcClient?.handleOffer(msg.optString("sdp"))
                }
            }

            "answer" -> {
                webRtcClient?.handleAnswer(msg.optString("sdp"))
            }

            "ice-candidate" -> {
                val candidateObj = msg.optJSONObject("candidate")
                if (candidateObj != null) {
                    val candidate = candidateObj.optString("candidate")
                    val sdpMid = candidateObj.optString("sdpMid")
                    val sdpMLineIndex = candidateObj.optInt("sdpMLineIndex")
                    webRtcClient?.handleIceCandidate(candidate, sdpMid, sdpMLineIndex)
                } else {
                    val candidate = msg.optString("candidate")
                    val sdpMid = msg.optString("sdpMid")
                    val sdpMLineIndex = msg.optInt("sdpMLineIndex")
                    if (candidate.isNotBlank()) {
                        webRtcClient?.handleIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    }
                }
            }

            "left" -> {
                mainHandler.post { listener.onPeerLeft() }
                cleanupInternal()
            }

            "error" -> {
                val message = msg.optString("message", "Bilinmeyen signaling hatası")
                mainHandler.post { listener.onError(RuntimeException(message)) }
            }
        }
    }

    private var peerConnectionCreated = false

    private val webRtcListener = object : WebRtcClient.WebRtcListener {
        override fun onRemoteVideoTrack(track: VideoTrack) {
            mainHandler.post { listener.onRemoteVideoTrack(track) }
        }

        override fun onRemoteAudioTrack(track: AudioTrack) {
            Log.d(TAG, "remote audio track")
        }

        override fun onConnectionState(state: PeerConnection.IceConnectionState) {
            mainHandler.post { listener.onConnectionStateChanged(state) }
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                session = session?.copy(isConnected = true)
            }
        }

        override fun onLocalVideoTrack(track: VideoTrack) {
            mainHandler.post { listener.onLocalVideoTrack(track) }
        }

        override fun onError(error: Throwable) {
            mainHandler.post { listener.onError(error) }
        }
    }

    /** Ön/arka kamera geçişi. */
    fun switchCamera(): Boolean = webRtcClient?.switchCamera() ?: false

    /** Ses açık/kapalı. */
    fun setAudioEnabled(enabled: Boolean) = webRtcClient?.setAudioEnabled(enabled)

    /**
     * Sadece WebRTC katmanını serbest bırak (kamera + peer connection).
     * Signaling bağlantısı ve oda KORUNSUN (stopViewing sonrası).
     */
    private fun releaseWebRtcOnly() {
        peerConnectionCreated = false
        viewRole = ViewRole.NONE
        webRtcClient?.release()
        // webRtcClient'ı null yapma; aynı instance tekrar kullanılsın
    }

    /** Bağlantıyı ve tüm kaynakları temizle (signaling dahil). */
    fun disconnect() {
        signalingClient?.leave()
        cleanupInternal()
    }

    private fun cleanupInternal() {
        peerConnectionCreated = false
        viewRole = ViewRole.NONE
        session = null
        webRtcClient?.release()
        webRtcClient = null
        signalingClient?.disconnect()
        signalingClient = null
        pendingRoomCode = null
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ConnectionManager"
    }
}
