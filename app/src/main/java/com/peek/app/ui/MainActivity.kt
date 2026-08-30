package com.peek.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.peek.app.PeekApplication
import com.peek.app.PeekSessionManager
import com.peek.app.R
import com.peek.app.data.models.Session
import com.peek.app.notifications.NotificationHelper
import com.peek.app.overlay.OverlayWindowService
import com.peek.app.permissions.PermissionManager
import com.peek.app.ui.settings.SettingsActivity
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Ana ekran Activity'si.
 *
 *  - Toolbar (NoActionBar theme) + menü (Ayarlar)
 *  - "Kod Üret" / "Kod Gir" ile eşleşme (PeekSessionManager üzerinden)
 *  - Eşleşince kamera KAPALI. Görüntü isteğe bağlı:
 *      "Görüntü İste" → karşı tarafa view-request
 *      Karşı taraf isteyince → dialog (Activity açıksa) / bildirim (kapalıyken)
 *      "Görüntüyü Durdur" → view-stop, eşleşme korunur
 *  - Görüntü aktifken: local + remote preview, overlay, kamera/ses kontrolleri
 *  - "Bağlantıyı Kes" → eşleşmeyi tamamen kopar
 *  - Durum göstergesi (tvStatus) renkli badge — duruma göre renk değiştir
 *
 * Bağlantı PeekSessionManager singleton'ında tutulduğu için Activity kapansa
 * bile bağlantı ve overlay service devam eder.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var app: PeekApplication
    private lateinit var session: PeekSessionManager

    // UI - toolbar
    private lateinit var toolbar: Toolbar

    // UI - üst bar
    private lateinit var tvStatus: TextView

    // UI - eşleşme paneli
    private lateinit var pairingPanel: View
    private lateinit var btnGenerateCode: MaterialButton
    private lateinit var tvGeneratedCode: TextView
    private lateinit var etCodeInput: EditText
    private lateinit var btnJoin: MaterialButton

    // UI - video paneli
    private lateinit var videoPanel: View
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var localRenderer: SurfaceViewRenderer

    // UI - çağrı kontrolleri
    private lateinit var callControls: View
    private lateinit var btnRequestView: MaterialButton
    private lateinit var btnStopView: MaterialButton
    private lateinit var btnSwitchCamera: MaterialButton
    private lateinit var btnToggleAudio: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnShowOverlay: MaterialButton
    private lateinit var btnHideOverlay: MaterialButton

    // Activity'ye ait renderer sink'leri (overlay service ayrı renderer kullanır)
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var audioEnabled: Boolean = true
    private var renderersInitialized: Boolean = false

    // Overlay izni verildi mi diye onResume'da kontrol etmek için bayrak
    private var pendingOverlayStart: Boolean = false

    // Bekleyen görüntü isteği dialog'u
    private var viewRequestDialog: AlertDialog? = null

    private val sessionListener = object : PeekSessionManager.SessionListener {
        override fun onRoomJoined(roomCode: String, role: Session.Role, peerCount: Int) {
            setStatus(getString(R.string.status_generating_code) + " Kod: $roomCode", StatusColor.NEUTRAL)
            if (role == Session.Role.CALLER) {
                tvGeneratedCode.text = roomCode
                tvGeneratedCode.visibility = View.VISIBLE
            }
        }

        override fun onPeerJoined() {
            setStatus(getString(R.string.status_connected_no_view), StatusColor.PRIMARY)
            showVideoPanel()
            updateViewButtons()
        }

        override fun onPeerLeft() {
            setStatus(getString(R.string.status_peer_left), StatusColor.NEUTRAL)
            cleanupVideoSinks()
            showPairingPanel()
        }

        override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
            if (session.viewState == PeekSessionManager.ViewState.VIEWING) {
                val text = when (state) {
                    PeerConnection.IceConnectionState.NEW,
                    PeerConnection.IceConnectionState.CHECKING -> getString(R.string.status_connecting)
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> getString(R.string.status_view_active)
                    PeerConnection.IceConnectionState.DISCONNECTED -> getString(R.string.status_connecting)
                    PeerConnection.IceConnectionState.FAILED -> getString(R.string.error_peer_connection)
                    PeerConnection.IceConnectionState.CLOSED -> getString(R.string.status_stopped)
                }
                setStatus(text, if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) StatusColor.SUCCESS else StatusColor.PRIMARY)
            }
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            remoteVideoTrack = track
            runOnUiThread {
                ensureRenderersInitialized()
                try {
                    track.addSink(remoteRenderer)
                } catch (_: Exception) {
                }
                setStatus(getString(R.string.status_view_active), StatusColor.SUCCESS)
                videoPanel.visibility = View.VISIBLE
                updateViewButtons()
            }
        }

        override fun onLocalVideoTrack(track: VideoTrack) {
            localVideoTrack = track
            runOnUiThread {
                ensureRenderersInitialized()
                try {
                    track.addSink(localRenderer)
                } catch (_: Exception) {
                }
            }
        }

        override fun onError(error: Throwable) {
            Toast.makeText(this@MainActivity, "Hata: ${error.message}", Toast.LENGTH_SHORT).show()
        }

        // ---- Aşama 4: görüntü istek callback'leri ----

        override fun onViewRequest() {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    showViewRequestDialog()
                    NotificationHelper.cancelViewRequestNotification(this@MainActivity)
                }
            }
        }

        override fun onViewAccepted() {
            runOnUiThread {
                setStatus(getString(R.string.status_accepted), StatusColor.PRIMARY)
                updateViewButtons()
            }
        }

        override fun onViewRejected() {
            runOnUiThread {
                setStatus(getString(R.string.status_rejected), StatusColor.ERROR)
                Toast.makeText(this@MainActivity, R.string.toast_view_rejected, Toast.LENGTH_SHORT).show()
                updateViewButtons()
                tvStatus.postDelayed({
                    if (session.viewState == PeekSessionManager.ViewState.IDLE) {
                        setStatus(getString(R.string.status_connected_no_view), StatusColor.PRIMARY)
                    }
                }, 2500)
            }
        }

        override fun onViewStopped() {
            runOnUiThread {
                setStatus(getString(R.string.status_stopped), StatusColor.NEUTRAL)
                cleanupVideoSinks()
                videoPanel.visibility = View.GONE
                updateViewButtons()
                tvStatus.postDelayed({
                    if (session.viewState == PeekSessionManager.ViewState.IDLE) {
                        setStatus(getString(R.string.status_connected_no_view), StatusColor.PRIMARY)
                    }
                }, 1500)
            }
        }

        override fun onViewStateChanged(state: PeekSessionManager.ViewState) {
            runOnUiThread {
                when (state) {
                    PeekSessionManager.ViewState.REQUEST_SENT -> {
                        setStatus(getString(R.string.status_request_sent), StatusColor.WARNING)
                    }
                    PeekSessionManager.ViewState.REQUEST_RECEIVED -> {
                        // onViewRequest dialog'u gösterir
                    }
                    PeekSessionManager.ViewState.VIEWING -> {
                        // onRemoteVideoTrack status'ü günceller
                    }
                    PeekSessionManager.ViewState.REJECTED -> {
                        // onViewRejected status'ü günceller
                    }
                    PeekSessionManager.ViewState.IDLE -> {
                        // onPeerJoined/onViewStopped status'ü günceller
                    }
                }
                updateViewButtons()
            }
        }
    }

    /** Durum badge'i renk kategorileri. */
    private enum class StatusColor { NEUTRAL, PRIMARY, SUCCESS, WARNING, ERROR }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash tema'dan normal temaya geç
        setTheme(R.style.Theme_Peek)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        app = application as PeekApplication
        session = PeekSessionManager.get(this)

        bindViews()
        setupToolbar()
        setupListeners()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        tvStatus = findViewById(R.id.tvStatus)
        pairingPanel = findViewById(R.id.pairingPanel)
        btnGenerateCode = findViewById(R.id.btnGenerateCode)
        tvGeneratedCode = findViewById(R.id.tvGeneratedCode)
        etCodeInput = findViewById(R.id.etCodeInput)
        btnJoin = findViewById(R.id.btnJoin)
        videoPanel = findViewById(R.id.videoPanel)
        remoteRenderer = findViewById(R.id.remoteRenderer)
        localRenderer = findViewById(R.id.localRenderer)
        callControls = findViewById(R.id.callControls)
        btnRequestView = findViewById(R.id.btnRequestView)
        btnStopView = findViewById(R.id.btnStopView)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnToggleAudio = findViewById(R.id.btnToggleAudio)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnShowOverlay = findViewById(R.id.btnShowOverlay)
        btnHideOverlay = findViewById(R.id.btnHideOverlay)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
    }

    private fun setupListeners() {
        btnGenerateCode.setOnClickListener {
            if (!ensureRuntimePermissions()) return@setOnClickListener
            setStatus(getString(R.string.status_generating_code), StatusColor.PRIMARY)
            tvGeneratedCode.visibility = View.GONE
            session.connectionManager.generateAndJoin()
        }

        btnJoin.setOnClickListener {
            val code = etCodeInput.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, R.string.toast_invalid_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureRuntimePermissions()) return@setOnClickListener
            setStatus(getString(R.string.status_connecting), StatusColor.PRIMARY)
            tvGeneratedCode.visibility = View.GONE
            session.connectionManager.joinWithCode(code)
        }

        btnRequestView.setOnClickListener {
            session.sendViewRequest()
            setStatus(getString(R.string.status_request_sent), StatusColor.WARNING)
        }

        btnStopView.setOnClickListener {
            session.stopViewing()
        }

        btnSwitchCamera.setOnClickListener {
            if (session.connectionManager.switchCamera()) {
                Toast.makeText(this, R.string.toast_camera_switched, Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleAudio.setOnClickListener {
            audioEnabled = !audioEnabled
            session.connectionManager.setAudioEnabled(audioEnabled)
            btnToggleAudio.text = if (audioEnabled) getString(R.string.btn_toggle_audio_on)
            else getString(R.string.btn_toggle_audio_off)
        }

        btnDisconnect.setOnClickListener {
            if (session.isOverlayActive) {
                OverlayWindowService.stop(this)
            }
            session.disconnect()
            cleanupVideoSinks()
            showPairingPanel()
            setStatus(getString(R.string.status_not_connected), StatusColor.NEUTRAL)
            tvGeneratedCode.visibility = View.GONE
            etCodeInput.text.clear()
            updateOverlayButtons()
            updateViewButtons()
        }

        btnShowOverlay.setOnClickListener {
            if (!PermissionManager.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_overlay_permission_needed, Toast.LENGTH_LONG).show()
                PermissionManager.requestOverlayPermission(this)
                pendingOverlayStart = true
                return@setOnClickListener
            }
            startOverlay()
        }

        btnHideOverlay.setOnClickListener {
            OverlayWindowService.stop(this)
            updateOverlayButtons()
        }
    }

    // ---- Toolbar menü ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---- Durum badge ----

    /** tvStatus metnini ve arka plan rengini güncelle. */
    private fun setStatus(text: String, color: StatusColor) {
        runOnUiThread {
            tvStatus.text = text
            val colorRes = when (color) {
                StatusColor.NEUTRAL -> R.color.peek_text_secondary
                StatusColor.PRIMARY -> R.color.peek_primary
                StatusColor.SUCCESS -> R.color.peek_success
                StatusColor.WARNING -> R.color.peek_warning
                StatusColor.ERROR -> R.color.peek_error
            }
            val bg: Drawable? = tvStatus.background
            bg?.setTint(ContextCompat.getColor(this, colorRes))
            tvStatus.background = bg
            // Metin rengi: başarı/uyarı/error için beyaz, diğerleri için beyaz (badge üzerinde)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.peek_on_primary))
        }
    }

    private fun showViewRequestDialog() {
        if (viewRequestDialog?.isShowing == true) return
        viewRequestDialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_view_request_title)
            .setMessage(R.string.dialog_view_request_message)
            .setPositiveButton(R.string.dialog_accept) { _, _ ->
                session.acceptViewRequest()
                NotificationHelper.cancelViewRequestNotification(this)
            }
            .setNegativeButton(R.string.dialog_reject) { _, _ ->
                session.rejectViewRequest()
                NotificationHelper.cancelViewRequestNotification(this)
            }
            .setCancelable(false)
            .show()
    }

    private fun startOverlay() {
        OverlayWindowService.start(this)
        updateOverlayButtons()
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }

    private fun updateOverlayButtons() {
        runOnUiThread {
            if (session.isOverlayActive) {
                btnShowOverlay.visibility = View.GONE
                btnHideOverlay.visibility = View.VISIBLE
            } else {
                btnShowOverlay.visibility = View.VISIBLE
                btnHideOverlay.visibility = View.GONE
            }
        }
    }

    private fun updateViewButtons() {
        runOnUiThread {
            val viewing = session.viewState == PeekSessionManager.ViewState.VIEWING
            btnRequestView.visibility = if (viewing) View.GONE else View.VISIBLE
            btnStopView.visibility = if (viewing) View.VISIBLE else View.GONE

            val controlsVisible = if (viewing) View.VISIBLE else View.GONE
            btnSwitchCamera.visibility = controlsVisible
            btnToggleAudio.visibility = controlsVisible
            btnShowOverlay.visibility = if (viewing && !session.isOverlayActive) View.VISIBLE else View.GONE
            btnHideOverlay.visibility = if (viewing && session.isOverlayActive) View.VISIBLE else View.GONE
        }
    }

    private fun ensureRuntimePermissions(): Boolean {
        if (!PermissionManager.hasRuntimePermissions(this)) {
            PermissionManager.requestRuntimePermissions(this, PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    private fun showVideoPanel() {
        runOnUiThread {
            pairingPanel.visibility = View.GONE
            videoPanel.visibility = View.GONE // görüntü yokken video paneli kapalı
            callControls.visibility = View.VISIBLE
            updateViewButtons()
        }
    }

    private fun showPairingPanel() {
        runOnUiThread {
            pairingPanel.visibility = View.VISIBLE
            videoPanel.visibility = View.GONE
            callControls.visibility = View.GONE
        }
    }

    private fun ensureRenderersInitialized() {
        if (renderersInitialized) return
        remoteRenderer.init(app.eglBase.eglBaseContext, null)
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localRenderer.init(app.eglBase.eglBaseContext, null)
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localRenderer.setMirror(true)
        renderersInitialized = true
    }

    private fun cleanupVideoSinks() {
        try {
            localVideoTrack?.removeSink(localRenderer)
            remoteVideoTrack?.removeSink(remoteRenderer)
        } catch (_: Exception) {
        }
        localVideoTrack = null
        remoteVideoTrack = null
    }

    override fun onResume() {
        super.onResume()
        session.addListener(sessionListener)
        if (pendingOverlayStart && PermissionManager.canDrawOverlays(this)) {
            pendingOverlayStart = false
            startOverlay()
        } else {
            pendingOverlayStart = false
        }
        updateOverlayButtons()
        updateViewButtons()
        if (session.viewState == PeekSessionManager.ViewState.REQUEST_RECEIVED &&
            viewRequestDialog?.isShowing != true
        ) {
            showViewRequestDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        session.removeListener(sessionListener)
        viewRequestDialog?.dismiss()
        viewRequestDialog = null
    }

    override fun onDestroy() {
        cleanupVideoSinks()
        if (renderersInitialized) {
            try {
                remoteRenderer.release()
                localRenderer.release()
            } catch (_: Exception) {
            }
            renderersInitialized = false
        }
        session.removeListener(sessionListener)
        viewRequestDialog?.dismiss()
        viewRequestDialog = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (PermissionManager.hasRuntimePermissions(this)) {
                Toast.makeText(this, R.string.toast_permissions_granted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_permissions_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
