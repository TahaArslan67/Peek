package com.peek.app.webrtc

import android.content.Context
import android.util.Log
import com.peek.app.camera.CameraCapturerManager
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * WebRTC peer connection yaşam döngüsünü yönetir.
 *
 * PeerConnectionFactory PeekApplication'da global olarak başlatıldığı için
 * bu sınıf sadece PeerConnection + track + SDP/ICE akışından sorumludur.
 *
 * Akış:
 *  - createOffer: peerConnection.createOffer -> setLocalDescription -> signaling.sendOffer
 *  - handleOffer: setRemoteDescription -> createAnswer -> setLocalDescription -> signaling.sendAnswer
 *  - handleAnswer: setRemoteDescription
 *  - handleIceCandidate: peerConnection.addIceCandidate
 *  - onIceCandidate (observer): signaling.sendIceCandidate
 *  - onAddTrack (observer): uzak track'i listener'a ilet
 */
class WebRtcClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val factory: PeerConnectionFactory,
    private val signalingClient: SignalingClient,
    private val listener: WebRtcListener,
) {

    interface WebRtcListener {
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onConnectionState(state: PeerConnection.IceConnectionState)
        fun onLocalVideoTrack(track: VideoTrack)
        fun onError(error: Throwable)
    }

    private var peerConnection: PeerConnection? = null
    private var cameraManager: CameraCapturerManager? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioEnabled: Boolean = true

    /**
     * Yeni bir bağlantı için PeerConnection oluştur ve yerel medya track'lerini ekle.
     *
     * iceServers: STUN/TURN listesi (AppPreferences.getIceServers()).
     * includeLocalVideo: true ise yerel kamera açılır ve video track eklenir
     *   (görüntü VEREN taraf). false ise kamera açılmaz, sadece uzak video
     *   alınır (görüntü İSTEYEN taraf — receiver only).
     */
    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        includeLocalVideo: Boolean = true,
    ): Boolean {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // ICE candidate toplama sırasında trickle için bekleme
            // continuousGatheringPolicy varsayılan GATHER_CONTINUOUSLY
        }

        val observer = object : PeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
                listener.onConnectionState(state)
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                when (track) {
                    is VideoTrack -> {
                        Log.d(TAG, "Uzak video track alındı")
                        listener.onRemoteVideoTrack(track)
                    }
                    is AudioTrack -> {
                        Log.d(TAG, "Uzak audio track alındı")
                        listener.onRemoteAudioTrack(track)
                    }
                }
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }
        }

        peerConnection = factory.createPeerConnection(rtcConfig, observer)
        if (peerConnection == null) {
            listener.onError(IllegalStateException("PeerConnection oluşturulamadı"))
            return false
        }

        // Yerel kamera ve ses track'lerini ekle
        addLocalTracks(includeLocalVideo)
        return true
    }

    /**
     * Yerel video + audio track'lerini üret ve peerConnection'a ekle.
     *
     * includeLocalVideo: true ise kamera açılır (görüntü veren taraf).
     * false ise kamera açılmaz (görüntü isteyen taraf — receiver only).
     */
    private fun addLocalTracks(includeLocalVideo: Boolean) {
        if (includeLocalVideo) {
            // Video
            cameraManager = CameraCapturerManager(context, eglBase)
            localVideoTrack = cameraManager?.createVideoTrack(factory)
            if (localVideoTrack != null) {
                peerConnection?.addTrack(localVideoTrack, listOf(STREAM_ID))
                listener.onLocalVideoTrack(localVideoTrack!!)
            } else {
                listener.onError(IllegalStateException("Kamera track oluşturulamadı"))
            }
        }

        // Audio (her zaman — çift yönlü ses için)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(audioEnabled)
        peerConnection?.addTrack(localAudioTrack, listOf(STREAM_ID))
    }

    /** CALLER: offer üret, local description yap ve signaling ile gönder. */
    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(TAG, "setLocal offer"), sdp)
                signalingClient.sendOffer(sdp.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                listener.onError(RuntimeException("createOffer failed: $error"))
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** CALLEE: gelen offer'a answer üret ve gönder. */
    fun handleOffer(sdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(
            SimpleSdpObserver(TAG, "setRemote offer"),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answer: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(TAG, "setLocal answer"), answer)
                signalingClient.sendAnswer(answer.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                listener.onError(RuntimeException("createAnswer failed: $error"))
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** CALLER: gelen answer'ı remote description yap. */
    fun handleAnswer(sdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(
            SimpleSdpObserver(TAG, "setRemote answer"),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    /** Karşıdan gelen ICE candidate'i ekle. */
    fun handleIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val pc = peerConnection ?: return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        pc.addIceCandidate(ice)
    }

    /** Observer'dan gelen local ICE candidate'i signaling ile gönder. */
    private fun sendIceCandidate(candidate: IceCandidate) {
        val candidateJson = JSONObject().apply {
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        }
        signalingClient.sendIceCandidate(candidateJson)
    }

    /** Ön/arka kamera geçişi (capture devam ederken). */
    fun switchCamera(): Boolean = cameraManager?.switchCamera() ?: false

    /** Ses track'ini enable/disable et. */
    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
    }

    fun isAudioEnabled(): Boolean = audioEnabled

    /** Bağlantıyı ve tüm kaynakları temizle. */
    fun release() {
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "peerConnection.close hatası", e)
        }
        peerConnection = null
        try {
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "track dispose hatası", e)
        }
        localVideoTrack = null
        localAudioTrack = null
        cameraManager?.dispose()
        cameraManager = null
    }

    companion object {
        private const val TAG = "WebRtcClient"
        private const val STREAM_ID = "ARDAMS"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
    }
}

/** SdpObserver için minimal boş implementasyon; sadece log yazar. */
private class SimpleSdpObserver(private val tag: String, private val label: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {
        Log.d(tag, "[$label] setSuccess")
    }

    override fun onCreateFailure(error: String?) {
        Log.e(tag, "[$label] onCreateFailure: $error")
    }

    override fun onSetFailure(error: String?) {
        Log.e(tag, "[$label] onSetFailure: $error")
    }
}
