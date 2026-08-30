package com.peek.app.data.models

/**
 * Signaling mesaj tiplerini temsil eden sealed class.
 *
 * Server ve client arasındaki tüm mesajlar bu modellerle eşleşir.
 * Gson ile JSON'a/JSON'dan çevrilir.
 */
sealed class SignalingMessage {
    abstract val type: String

    data class Join(override val type: String = "join", val roomCode: String) : SignalingMessage()
    data class Offer(override val type: String = "offer", val sdp: String) : SignalingMessage()
    data class Answer(override val type: String = "answer", val sdp: String) : SignalingMessage()
    data class IceCandidate(
        override val type: String = "ice-candidate",
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
    ) : SignalingMessage()
    data class Leave(override val type: String = "leave") : SignalingMessage()
    data class PeerStatus(
        override val type: String = "peer-status",
        val status: String, // "joined" | "left"
    ) : SignalingMessage()
    // Aşama 4 — görüntü istek mesajları
    data class ViewRequest(override val type: String = "view-request") : SignalingMessage()
    data class ViewAccept(override val type: String = "view-accept") : SignalingMessage()
    data class ViewReject(override val type: String = "view-reject") : SignalingMessage()
    data class ViewStop(override val type: String = "view-stop") : SignalingMessage()
}
