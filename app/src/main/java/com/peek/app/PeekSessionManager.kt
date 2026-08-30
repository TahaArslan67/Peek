package com.peek.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.peek.app.data.models.Session
import com.peek.app.notifications.NotificationHelper
import com.peek.app.webrtc.ConnectionManager
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/**
 * ConnectionManager'ı Activity lifecycle'ından bağımsız tutan singleton.
 *
 * Kullanıcı MainActivity'yi kapatsa bile bağlantı ve overlay service
 * yaşamaya devam eder. Tüm WebRTC ve görüntü-istek olayları burada toplanır
 * ve kayıtlı listener'lara (Activity + OverlayWindowService) iletilir.
 *
 * VideoTrack'ler burada tutulur çünkü WebRTC native objesi Parcelable DEĞİL
 * — Service'e Intent ile pass edilemez. Service `PeekSessionManager.get().remoteVideoTrack`
 * üzerinden erişir.
 *
 * Aşama 4: ViewState ile görüntü istek durumu takip edilir.
 */
class PeekSessionManager private constructor(private val app: Application) {

    /**
     * Görüntü oturumu durumu.
     *  - IDLE: eşleşmiş ama görüntü yok
     *  - REQUEST_SENT: biz istek gönderdik, cevap bekliyoruz
     *  - REQUEST_RECEIVED: karşı taraf bizden istedi, biz cevap vereceğiz
     *  - VIEWING: görüntü aktif
     *  - REJECTED: istek reddedildi (geçici, sonra IDLE'a döner)
     */
    enum class ViewState {
        IDLE, REQUEST_SENT, REQUEST_RECEIVED, VIEWING, REJECTED
    }

    interface SessionListener {
        fun onRoomJoined(roomCode: String, role: Session.Role, peerCount: Int) {}
        fun onPeerJoined() {}
        fun onPeerLeft() {}
        fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {}
        fun onRemoteVideoTrack(track: VideoTrack) {}
        fun onLocalVideoTrack(track: VideoTrack) {}
        fun onError(error: Throwable) {}
        // Aşama 4 — görüntü istek callback'leri
        fun onViewRequest() {}
        fun onViewAccepted() {}
        fun onViewRejected() {}
        fun onViewStopped() {}
        fun onViewStateChanged(state: ViewState) {}
    }

    val connectionManager: ConnectionManager

    /** Overlay service buradan okur. */
    @Volatile
    var remoteVideoTrack: VideoTrack? = null
        private set

    @Volatile
    var localVideoTrack: VideoTrack? = null
        private set

    @Volatile
    var connectionState: PeerConnection.IceConnectionState =
        PeerConnection.IceConnectionState.NEW
        private set

    @Volatile
    var isOverlayActive: Boolean = false

    @Volatile
    var viewState: ViewState = ViewState.IDLE
        private set

    private val listeners = mutableListOf<SessionListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectionListener = object : ConnectionManager.ConnectionListener {
        override fun onRoomJoined(roomCode: String, role: Session.Role, peerCount: Int) {
            mainHandler.post { notifyListeners { it.onRoomJoined(roomCode, role, peerCount) } }
        }

        override fun onPeerJoined() {
            mainHandler.post { notifyListeners { it.onPeerJoined() } }
        }

        override fun onPeerLeft() {
            remoteVideoTrack = null
            localVideoTrack = null
            setViewState(ViewState.IDLE)
            mainHandler.post { notifyListeners { it.onPeerLeft() } }
        }

        override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
            connectionState = state
            mainHandler.post { notifyListeners { it.onConnectionStateChanged(state) } }
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            remoteVideoTrack = track
            setViewState(ViewState.VIEWING)
            mainHandler.post { notifyListeners { it.onRemoteVideoTrack(track) } }
        }

        override fun onRemoteAudioTrack(track: AudioTrack) {
            // Audio otomatik çalar; listener'a iletmeye gerek yok
        }

        override fun onLocalVideoTrack(track: VideoTrack) {
            localVideoTrack = track
            mainHandler.post { notifyListeners { it.onLocalVideoTrack(track) } }
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "connection error", error)
            mainHandler.post { notifyListeners { it.onError(error) } }
        }

        // Aşama 4 — görüntü istek callback'leri
        override fun onViewRequest() {
            setViewState(ViewState.REQUEST_RECEIVED)
            // Her zaman bildirim göster — Activity açıkken dialog da gösterilir
            // (Activity listener'ı onViewRequest'te dialog açar). Bildirim, Activity
            // kapalıyken kullanıcı fark etsin diye. Dialog açılınca bildirim
            // MainActivity tarafından cancel edilir.
            NotificationHelper.showViewRequestNotification(app)
            mainHandler.post { notifyListeners { it.onViewRequest() } }
        }

        override fun onViewAccepted() {
            // Karşı taraf kabul etti; offer bekleniyor. VIEWING'e track gelince geçilir.
            setViewState(ViewState.REQUEST_SENT)
            mainHandler.post { notifyListeners { it.onViewAccepted() } }
        }

        override fun onViewRejected() {
            setViewState(ViewState.REJECTED)
            mainHandler.post { notifyListeners { it.onViewRejected() } }
            // Kısa süre sonra IDLE'a dön
            mainHandler.postDelayed({ setViewState(ViewState.IDLE) }, REJECTED_RESET_MS)
        }

        override fun onViewStopped() {
            remoteVideoTrack = null
            localVideoTrack = null
            setViewState(ViewState.IDLE)
            mainHandler.post { notifyListeners { it.onViewStopped() } }
        }
    }

    init {
        connectionManager = ConnectionManager(app, connectionListener)
    }

    private fun setViewState(state: ViewState) {
        if (viewState == state) return
        viewState = state
        mainHandler.post { notifyListeners { it.onViewStateChanged(state) } }
    }

    // ---- Aşama 4: görüntü istek metotları (ConnectionManager'a delege) ----

    fun sendViewRequest() {
        setViewState(ViewState.REQUEST_SENT)
        connectionManager.sendViewRequest()
    }

    fun acceptViewRequest() {
        setViewState(ViewState.VIEWING) // track gelince kesinleşir; şimdiden viewing işaretle
        connectionManager.acceptViewRequest()
    }

    fun rejectViewRequest() {
        setViewState(ViewState.IDLE)
        connectionManager.rejectViewRequest()
    }

    fun stopViewing() {
        connectionManager.stopViewing()
        // onViewStopped callback'i state'i IDLE'a çekecek
    }

    fun addListener(listener: SessionListener) {
        mainHandler.post {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
                // Mevcut durumu hemen bildir (yeni listener için)
                remoteVideoTrack?.let { listener.onRemoteVideoTrack(it) }
                localVideoTrack?.let { listener.onLocalVideoTrack(it) }
                listener.onConnectionStateChanged(connectionState)
                listener.onViewStateChanged(viewState)
            }
        }
    }

    fun removeListener(listener: SessionListener) {
        mainHandler.post { listeners.remove(listener) }
    }

    private fun notifyListeners(block: (SessionListener) -> Unit) {
        listeners.toList().forEach { listener ->
            try {
                block(listener)
            } catch (e: Exception) {
                Log.w(TAG, "listener threw", e)
            }
        }
    }

    /**
     * Bağlantıyı tamamen kapat. Tüm listener'lar temizlenmeden önce
     * peer-left bildirimi yapılır. Overlay service de durdurulmalı (çağıran taraf).
     */
    fun disconnect() {
        connectionManager.disconnect()
        remoteVideoTrack = null
        localVideoTrack = null
        connectionState = PeerConnection.IceConnectionState.NEW
        setViewState(ViewState.IDLE)
        isOverlayActive = false
    }

    companion object {
        private const val TAG = "PeekSessionManager"
        private const val REJECTED_RESET_MS = 3000L

        @Volatile
        private var instance: PeekSessionManager? = null

        fun get(context: Context): PeekSessionManager =
            instance ?: synchronized(this) {
                instance ?: PeekSessionManager(context.applicationContext as Application)
                    .also { instance = it }
            }
    }
}
