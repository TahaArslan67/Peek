package com.peek.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.peek.app.PeekApplication
import com.peek.app.PeekSessionManager
import com.peek.app.R
import com.peek.app.data.AppPreferences
import com.peek.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * SYSTEM_ALERT_WINDOW ile ekranda küçük yüzen kamera penceresi gösterir.
 *
 * Özellikler:
 *  - Foreground service (type=camera) — Activity kapansa bile devam eder
 *  - WindowManager + TYPE_APPLICATION_OVERLAY ile yüzen view
 *  - SurfaceViewRenderer: PeekSessionManager.remoteVideoTrack'e sink olarak bağlanır
 *  - Sürükleme: onTouch ACTION_DOWN/MOVE ile LayoutParams.x/y güncelle
 *  - Çift tık: boyut döngüsü (küçük → varsayılan → büyük → küçük)
 *  - Şeffaflık butonu: 3 seviye (%100 → %70 → %40 → %100)
 *  - Kapat butonu: stopSelf()
 *  - Track null ise "görüntü bekleniyor" metni
 *
 * VideoTrack Parcelable olmadığı için Intent ile geçilemez; PeekSessionManager
 * singleton'ından okunur. Service bir SessionListener olarak kaydolur ve track
 * gelince dinamik olarak addSink yapar.
 */
class OverlayWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var eglBase: org.webrtc.EglBase

    private var overlayRoot: FrameLayout? = null
    private var renderer: SurfaceViewRenderer? = null
    private var waitingText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rendererInitialized = false
    private var currentTrack: VideoTrack? = null
    private var rendererSinkAttached = false

    // Boyut seviyeleri (dp cinsinden genişlik x yükseklik)
    private val sizeCycle = arrayOf(
        intArrayOf(SIZE_SMALL_W, SIZE_SMALL_H),
        intArrayOf(SIZE_DEFAULT_W, SIZE_DEFAULT_H),
        intArrayOf(SIZE_LARGE_W, SIZE_LARGE_H),
    )
    private var sizeIndex = 1 // varsayılan

    // Şeffaflık seviyeleri
    private val alphaCycle = floatArrayOf(1.0f, 0.7f, 0.4f)
    private var alphaIndex = 0

    private val sessionListener = object : PeekSessionManager.SessionListener {
        override fun onRemoteVideoTrack(track: VideoTrack) {
            attachTrack(track)
        }

        override fun onPeerLeft() {
            detachTrack()
        }

        override fun onConnectionStateChanged(state: org.webrtc.PeerConnection.IceConnectionState) {
            // State değişimi UI'da gösterilmiyor; overlay sessiz çalışır
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preferences: AppPreferences? = null
    private var pendingSizeIndex: Int? = null
    private var pendingAlphaIndex: Int? = null
    private var pendingX: Int? = null
    private var pendingY: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        eglBase = (application as PeekApplication).eglBase
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (overlayRoot != null) {
            // Zaten çalışıyor; tekrar ekleme
            return START_STICKY
        }

        startForegroundWithCameraType()

        // Ayarları senkron oku (IO thread'inde) ve overlay'i kur
        val app = application as PeekApplication
        preferences = app.preferences
        serviceScope.launch {
            val prefs = preferences ?: return@launch
            val sizeStr = prefs.overlayDefaultSize.first()
            val alpha = prefs.overlayDefaultAlpha.first()
            val lastX = prefs.overlayLastX.first()
            val lastY = prefs.overlayLastY.first()

            pendingSizeIndex = when (sizeStr) {
                AppPreferences.OVERLAY_SIZE_SMALL -> 0
                AppPreferences.OVERLAY_SIZE_LARGE -> 2
                else -> 1
            }
            pendingAlphaIndex = when {
                alpha <= 0.5f -> 2
                alpha <= 0.85f -> 1
                else -> 0
            }
            pendingX = lastX
            pendingY = lastY

            // UI thread'inde overlay'i kur
            Handler(Looper.getMainLooper()).post {
                if (overlayRoot == null) {
                    addOverlayWindow()
                    PeekSessionManager.get(this@OverlayWindowService).isOverlayActive = true
                    PeekSessionManager.get(this@OverlayWindowService).addListener(sessionListener)
                    PeekSessionManager.get(this@OverlayWindowService).remoteVideoTrack?.let { attachTrack(it) }
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithCameraType() {
        val notification = NotificationHelper.buildForegroundNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.getNotificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NotificationHelper.getNotificationId(), notification)
        }
    }

    private fun addOverlayWindow() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        overlayRoot = root

        // SurfaceViewRenderer — uzak video
        val r = SurfaceViewRenderer(this)
        r.setBackgroundColor(Color.BLACK)
        root.addView(
            r,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        renderer = r

        // "Görüntü bekleniyor" metni (track gelene kadar)
        val waiting = TextView(this).apply {
            text = getString(R.string.overlay_waiting)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            visibility = View.VISIBLE
        }
        root.addView(
            waiting,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        waitingText = waiting

        // Kontrol barı (üstte yatay): Kapat, Şeffaflık, Boyut
        val controls = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val btnClose = makeControlButton(getString(R.string.overlay_btn_close)) { stopSelf() }
        val btnAlpha = makeControlButton("%100", null)
        btnAlpha.setOnClickListener {
            alphaIndex = (alphaIndex + 1) % alphaCycle.size
            val a = alphaCycle[alphaIndex]
            root.alpha = a
            btnAlpha.text = "${(a * 100).toInt()}%"
            // Yeni alpha'yı kaydet
            serviceScope.launch { preferences?.setOverlayDefaultAlpha(a) }
        }
        val btnSize = makeControlButton(getString(R.string.overlay_btn_size)) { cycleSize() }

        controls.addView(btnClose)
        controls.addView(btnAlpha)
        controls.addView(btnSize)
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        // Ayarlardan gelen boyut/şeffaflık/konum uygula
        pendingSizeIndex?.let { sizeIndex = it }
        pendingAlphaIndex?.let { alphaIndex = it }
        val initialAlpha = alphaCycle.getOrElse(alphaIndex) { 1.0f }
        root.alpha = initialAlpha
        btnAlpha.text = "${(initialAlpha * 100).toInt()}%"

        // WindowManager LayoutParams
        val (w, h) = sizeCycle[sizeIndex]
        val params = WindowManager.LayoutParams(
            dp(w), dp(h),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pendingX ?: 0
            y = pendingY ?: dp(100)
        }
        layoutParams = params

        // Sürükleme + çift tık
        setupTouch(root, params)

        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView hatası (overlay izni yok mu?)", e)
            stopSelf()
            return
        }

        // Renderer'ı init et (track olmasa bile surface hazır olsun)
        ensureRendererInitialized()
    }

    private fun makeControlButton(text: String, onClick: (() -> Unit)?): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(0x00000000)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            textSize = 11f
            if (onClick != null) setOnClickListener { onClick() }
            // Buton tıklamasının sürüklemeye paslanmaması için
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP) v.performClick()
                false
            }
        }
    }

    private fun setupTouch(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        val gestureDetector = android.view.GestureDetector(view.context, object :
            android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cycleSize()
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun cycleSize() {
        val root = overlayRoot ?: return
        val params = layoutParams ?: return
        sizeIndex = (sizeIndex + 1) % sizeCycle.size
        val (w, h) = sizeCycle[sizeIndex]
        params.width = dp(w)
        params.height = dp(h)
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun ensureRendererInitialized() {
        if (rendererInitialized) return
        val r = renderer ?: return
        try {
            r.init(eglBase.eglBaseContext, null)
            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            r.setMirror(false)
            rendererInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "renderer init hatası", e)
        }
    }

    private fun attachTrack(track: VideoTrack) {
        currentTrack = track
        waitingText?.visibility = View.GONE
        ensureRendererInitialized()
        if (!rendererSinkAttached) {
            try {
                track.addSink(renderer)
                rendererSinkAttached = true
            } catch (e: Exception) {
                Log.e(TAG, "addSink hatası", e)
            }
        }
    }

    private fun detachTrack() {
        currentTrack?.let { track ->
            if (rendererSinkAttached) {
                try {
                    track.removeSink(renderer)
                } catch (_: Exception) {
                }
            }
        }
        currentTrack = null
        rendererSinkAttached = false
        waitingText?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            PeekSessionManager.get(this).removeListener(sessionListener)
        } catch (_: Exception) {
        }
        PeekSessionManager.get(this).isOverlayActive = false

        // Son konumu kaydet
        val params = layoutParams
        if (params != null) {
            serviceScope.launch {
                try {
                    preferences?.setOverlayPosition(params.x, params.y)
                } catch (_: Exception) {
                }
            }
        }

        detachTrack()
        if (rendererInitialized) {
            try {
                renderer?.release()
            } catch (_: Exception) {
            }
            rendererInitialized = false
        }
        renderer = null
        overlayRoot?.let { root ->
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        overlayRoot = null
        layoutParams = null
        waitingText = null
        serviceScope.cancel()
        Log.d(TAG, "OverlayWindowService durduruldu")
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val TAG = "OverlayWindowService"

        const val ACTION_START = "com.peek.app.action.START_OVERLAY"
        const val ACTION_STOP = "com.peek.app.action.STOP_OVERLAY"

        // Boyutlar dp cinsinden (dikey aspect ratio ~2:3)
        private const val SIZE_SMALL_W = 100
        private const val SIZE_SMALL_H = 150
        private const val SIZE_DEFAULT_W = 160
        private const val SIZE_DEFAULT_H = 240
        private const val SIZE_LARGE_W = 240
        private const val SIZE_LARGE_H = 360

        fun start(context: Context) {
            val intent = Intent(context, OverlayWindowService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayWindowService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
