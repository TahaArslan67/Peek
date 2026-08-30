package com.peek.app.webrtc

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver

/**
 * PeerConnection olaylarını dinlemek için boş default implementasyon.
 *
 * WebRTC'nin PeerConnection.Observer arayüzündeki tüm metotları
 * boş implementasyonla sağlar; sadece ihtiyaç duyulanları override edilir.
 * Böylece WebRtcClient içinde kalabalik boilerplate'den kurtuluruz.
 */
open class PeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onIceCandidateError(event: IceCandidateErrorEvent) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dataChannel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
}
