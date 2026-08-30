package com.peek.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.webrtc.PeerConnection

/**
 * DataStore Preferences ile uygulama ayarlarını kalıcı olarak saklar.
 *
 * Saklanan değerler:
 *  - signalingServerUrl : WebSocket sunucu adresi
 *  - turnServerUrl      : TURN sunucu adresi (metered.ca vb.)
 *  - turnUsername       : TURN kullanıcı adı
 *  - turnCredential     : TURN şifresi
 *  - preferredCamera    : ön/arka kamera tercihi
 *  - lastRoomCode       : son kullanılan eşleşme kodu
 *  - overlayDefaultSize : "small" | "default" | "large"
 *  - overlayDefaultAlpha: 1.0 / 0.7 / 0.4
 *  - overlayLastX/Y     : son overlay konumu
 *  - darkMode           : "system" | "light" | "dark"
 *  - showStatusIndicator: true/false
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "peek_settings")

class AppPreferences(private val context: Context) {

    object Keys {
        val SIGNALING_SERVER_URL = stringPreferencesKey("signaling_server_url")
        val TURN_SERVER_URL = stringPreferencesKey("turn_server_url")
        val TURN_USERNAME = stringPreferencesKey("turn_username")
        val TURN_CREDENTIAL = stringPreferencesKey("turn_credential")
        val PREFERRED_CAMERA = stringPreferencesKey("preferred_camera")
        val LAST_ROOM_CODE = stringPreferencesKey("last_room_code")
        // Aşama 5 — yeni ayarlar
        val OVERLAY_DEFAULT_SIZE = stringPreferencesKey("overlay_default_size")
        val OVERLAY_DEFAULT_ALPHA = floatPreferencesKey("overlay_default_alpha")
        val OVERLAY_LAST_X = intPreferencesKey("overlay_last_x")
        val OVERLAY_LAST_Y = intPreferencesKey("overlay_last_y")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val SHOW_STATUS_INDICATOR = booleanPreferencesKey("show_status_indicator")
    }

    val signalingServerUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.SIGNALING_SERVER_URL] ?: DEFAULT_SIGNALING_URL }

    val turnServerUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.TURN_SERVER_URL] ?: "" }

    val turnUsername: Flow<String> = context.dataStore.data
        .map { it[Keys.TURN_USERNAME] ?: "" }

    val turnCredential: Flow<String> = context.dataStore.data
        .map { it[Keys.TURN_CREDENTIAL] ?: "" }

    val preferredCamera: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_CAMERA] ?: "front" }

    val lastRoomCode: Flow<String> = context.dataStore.data
        .map { it[Keys.LAST_ROOM_CODE] ?: "" }

    // Aşama 5 — yeni flows
    val overlayDefaultSize: Flow<String> = context.dataStore.data
        .map { it[Keys.OVERLAY_DEFAULT_SIZE] ?: OVERLAY_SIZE_DEFAULT }

    val overlayDefaultAlpha: Flow<Float> = context.dataStore.data
        .map { it[Keys.OVERLAY_DEFAULT_ALPHA] ?: 1.0f }

    val overlayLastX: Flow<Int> = context.dataStore.data
        .map { it[Keys.OVERLAY_LAST_X] ?: 0 }

    val overlayLastY: Flow<Int> = context.dataStore.data
        .map { it[Keys.OVERLAY_LAST_Y] ?: 100 }

    val darkMode: Flow<String> = context.dataStore.data
        .map { it[Keys.DARK_MODE] ?: DARK_MODE_SYSTEM }

    val showStatusIndicator: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SHOW_STATUS_INDICATOR] ?: true }

    // ---- Setter'lar ----

    suspend fun setSignalingServerUrl(url: String) = context.dataStore.edit {
        it[Keys.SIGNALING_SERVER_URL] = url
    }

    suspend fun setTurnServerUrl(url: String) = context.dataStore.edit {
        it[Keys.TURN_SERVER_URL] = url
    }

    suspend fun setTurnUsername(username: String) = context.dataStore.edit {
        it[Keys.TURN_USERNAME] = username
    }

    suspend fun setTurnCredential(credential: String) = context.dataStore.edit {
        it[Keys.TURN_CREDENTIAL] = credential
    }

    suspend fun setPreferredCamera(camera: String) = context.dataStore.edit {
        it[Keys.PREFERRED_CAMERA] = camera
    }

    suspend fun setLastRoomCode(code: String) = context.dataStore.edit {
        it[Keys.LAST_ROOM_CODE] = code
    }

    // Aşama 5 — yeni setter'lar
    suspend fun setOverlayDefaultSize(size: String) = context.dataStore.edit {
        it[Keys.OVERLAY_DEFAULT_SIZE] = size
    }

    suspend fun setOverlayDefaultAlpha(alpha: Float) = context.dataStore.edit {
        it[Keys.OVERLAY_DEFAULT_ALPHA] = alpha
    }

    suspend fun setOverlayPosition(x: Int, y: Int) = context.dataStore.edit {
        it[Keys.OVERLAY_LAST_X] = x
        it[Keys.OVERLAY_LAST_Y] = y
    }

    suspend fun setDarkMode(mode: String) = context.dataStore.edit {
        it[Keys.DARK_MODE] = mode
    }

    suspend fun setShowStatusIndicator(show: Boolean) = context.dataStore.edit {
        it[Keys.SHOW_STATUS_INDICATOR] = show
    }

    /**
     * Mevcut TURN ayarlarına göre ICE server listesi üretir.
     */
    suspend fun getIceServers(): List<PeerConnection.IceServer> {
        val turnUrl = turnServerUrl.first()
        val turnUser = turnUsername.first()
        val turnCred = turnCredential.first()

        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        if (turnUrl.isNotBlank()) {
            val builder = PeerConnection.IceServer.builder(turnUrl)
            if (turnUser.isNotBlank()) builder.setUsername(turnUser)
            if (turnCred.isNotBlank()) builder.setPassword(turnCred)
            servers.add(builder.createIceServer())
        }
        return servers
    }

    companion object {
        const val DEFAULT_SIGNALING_URL = "ws://10.0.2.2:8080"

        // Overlay boyut seçenekleri
        const val OVERLAY_SIZE_SMALL = "small"
        const val OVERLAY_SIZE_DEFAULT = "default"
        const val OVERLAY_SIZE_LARGE = "large"

        // Karanlık mod seçenekleri
        const val DARK_MODE_SYSTEM = "system"
        const val DARK_MODE_LIGHT = "light"
        const val DARK_MODE_DARK = "dark"
    }
}
