package com.peek.app.data.models

/**
 * Bir WebRTC bağlantı oturumunu temsil eder.
 *
 * roomCode   : eşleşme kodu (oda adı)
 * localPeer  : bu cihazın rolü (caller / callee)
 * isConnected: peer bağlantısı kuruldu mu
 */
data class Session(
    val roomCode: String,
    val role: Role,
    val isConnected: Boolean = false,
) {
    enum class Role { CALLER, CALLEE }
}
