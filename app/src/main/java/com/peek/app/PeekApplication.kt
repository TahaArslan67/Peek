package com.peek.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.peek.app.data.AppPreferences
import com.peek.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Uygulama giriş noktası.
 *
 * - Global EglBase oluşturur (WebRtcClient ve SurfaceViewRenderer'lar erişir)
 * - PeerConnectionFactory'i başlatır (initialize + builder)
 * - AppPreferences singleton'ını tutar
 * - NotificationHelper channel oluşturur
 * - Karanlık mod ayarını uygular (Aşama 5)
 */
class PeekApplication : Application() {

    lateinit var eglBase: EglBase
        private set

    lateinit var factory: PeerConnectionFactory
        private set

    lateinit var preferences: AppPreferences
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // EglBase: WebRTC render ve capture için OpenGL context
        eglBase = EglBase.create()

        // AppPreferences singleton
        preferences = AppPreferences(this)

        // Karanlık mod ayarını uygula (ilk açılışta sistem default, sonra ayar uygulanır)
        appScope.launch {
            val mode = preferences.darkMode.first()
            applyDarkMode(mode)
        }

        // WebRTC native kütüphanesini yükle ve başlat
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // PeerConnectionFactory oluştur (video encoder/decoder + audio module)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                org.webrtc.DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                org.webrtc.DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .setAudioDeviceModule(
                org.webrtc.audio.JavaAudioDeviceModule.builder(this).createAudioDeviceModule()
            )
            .createPeerConnectionFactory()

        // Bildirim kanalları (foreground + görüntü istek)
        NotificationHelper.createChannel(this)
    }

    /** Karanlık mod ayarını AppCompatDelegate'e uygula. */
    fun applyDarkMode(mode: String) {
        val nightMode = when (mode) {
            AppPreferences.DARK_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppPreferences.DARK_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
