package com.peek.app.ui.settings

import android.os.Bundle
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.peek.app.PeekApplication
import com.peek.app.R
import com.peek.app.camera.CameraCapturerManager
import com.peek.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ayarlar ekranı.
 *
 * Bölümler:
 *  1. Bağlantı: signaling URL, TURN URL/user/cred, kamera tercihi
 *  2. Görünüm: karanlık mod (Sistem/Açık/Koyu), durum göstergesi toggle
 *  3. Overlay Pencere: varsayılan boyut (Küçük/Orta/Büyük), şeffaflık (SeekBar 0-100%)
 *
 * Değerler AppPreferences (DataStore) içinde saklanır. "Kaydet" ile yazılır.
 * Karanlık mod değişirse AppCompatDelegate.setDefaultNightMode çağrılır —
 * Activity otomatik recreate edilir.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: PeekApplication
    private lateinit var prefs: AppPreferences

    // Bağlantı
    private lateinit var etSignalingUrl: TextInputEditText
    private lateinit var etTurnUrl: TextInputEditText
    private lateinit var etTurnUsername: TextInputEditText
    private lateinit var etTurnCredential: TextInputEditText
    private lateinit var switchCamera: SwitchMaterial

    // Görünüm
    private lateinit var rgDarkMode: android.widget.RadioGroup
    private lateinit var rbDarkSystem: RadioButton
    private lateinit var rbDarkLight: RadioButton
    private lateinit var rbDarkDark: RadioButton
    private lateinit var switchStatusIndicator: SwitchMaterial

    // Overlay
    private lateinit var rgOverlaySize: android.widget.RadioGroup
    private lateinit var rbSizeSmall: RadioButton
    private lateinit var rbSizeDefault: RadioButton
    private lateinit var rbSizeLarge: RadioButton
    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView

    private lateinit var btnSave: MaterialButton

    // Karanlık mod değişikliği takibi (recreate kararı için)
    private var initialDarkMode: String = AppPreferences.DARK_MODE_SYSTEM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setTitle(R.string.settings_title)

        app = application as PeekApplication
        prefs = app.preferences

        bindViews()
        loadCurrentValues()
        setupSeekAlphaListener()
        setupSaveButton()
    }

    private fun bindViews() {
        etSignalingUrl = findViewById(R.id.etSignalingUrl)
        etTurnUrl = findViewById(R.id.etTurnUrl)
        etTurnUsername = findViewById(R.id.etTurnUsername)
        etTurnCredential = findViewById(R.id.etTurnCredential)
        switchCamera = findViewById(R.id.switchCamera)

        rgDarkMode = findViewById(R.id.rgDarkMode)
        rbDarkSystem = findViewById(R.id.rbDarkSystem)
        rbDarkLight = findViewById(R.id.rbDarkLight)
        rbDarkDark = findViewById(R.id.rbDarkDark)
        switchStatusIndicator = findViewById(R.id.switchStatusIndicator)

        rgOverlaySize = findViewById(R.id.rgOverlaySize)
        rbSizeSmall = findViewById(R.id.rbSizeSmall)
        rbSizeDefault = findViewById(R.id.rbSizeDefault)
        rbSizeLarge = findViewById(R.id.rbSizeLarge)
        seekAlpha = findViewById(R.id.seekAlpha)
        tvAlphaValue = findViewById(R.id.tvAlphaValue)

        btnSave = findViewById(R.id.btnSave)
    }

    private fun loadCurrentValues() {
        lifecycleScope.launch {
            etSignalingUrl.setText(prefs.signalingServerUrl.first())
            etTurnUrl.setText(prefs.turnServerUrl.first())
            etTurnUsername.setText(prefs.turnUsername.first())
            etTurnCredential.setText(prefs.turnCredential.first())
            switchCamera.isChecked = prefs.preferredCamera.first() == CameraCapturerManager.CAMERA_FRONT

            // Karanlık mod
            val darkMode = prefs.darkMode.first()
            initialDarkMode = darkMode
            when (darkMode) {
                AppPreferences.DARK_MODE_LIGHT -> rbDarkLight.isChecked = true
                AppPreferences.DARK_MODE_DARK -> rbDarkDark.isChecked = true
                else -> rbDarkSystem.isChecked = true
            }

            // Durum göstergesi
            switchStatusIndicator.isChecked = prefs.showStatusIndicator.first()

            // Overlay boyut
            val size = prefs.overlayDefaultSize.first()
            when (size) {
                AppPreferences.OVERLAY_SIZE_SMALL -> rbSizeSmall.isChecked = true
                AppPreferences.OVERLAY_SIZE_LARGE -> rbSizeLarge.isChecked = true
                else -> rbSizeDefault.isChecked = true
            }

            // Şeffaflık
            val alpha = prefs.overlayDefaultAlpha.first()
            val percent = (alpha * 100).toInt()
            seekAlpha.progress = percent
            tvAlphaValue.text = "$percent%"
        }
    }

    private fun setupSeekAlphaListener() {
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupSaveButton() {
        btnSave.setOnClickListener {
            val signalingUrl = etSignalingUrl.text.toString().trim()
            if (signalingUrl.isEmpty()) {
                Toast.makeText(this, R.string.toast_signaling_url_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val turnUrl = etTurnUrl.text.toString().trim()
            val turnUser = etTurnUsername.text.toString().trim()
            val turnCred = etTurnCredential.text.toString().trim()
            val camera = if (switchCamera.isChecked)
                CameraCapturerManager.CAMERA_FRONT else CameraCapturerManager.CAMERA_BACK

            val darkMode = when (rgDarkMode.checkedRadioButtonId) {
                R.id.rbDarkLight -> AppPreferences.DARK_MODE_LIGHT
                R.id.rbDarkDark -> AppPreferences.DARK_MODE_DARK
                else -> AppPreferences.DARK_MODE_SYSTEM
            }

            val showIndicator = switchStatusIndicator.isChecked

            val overlaySize = when (rgOverlaySize.checkedRadioButtonId) {
                R.id.rbSizeSmall -> AppPreferences.OVERLAY_SIZE_SMALL
                R.id.rbSizeLarge -> AppPreferences.OVERLAY_SIZE_LARGE
                else -> AppPreferences.OVERLAY_SIZE_DEFAULT
            }

            val overlayAlpha = seekAlpha.progress / 100f

            lifecycleScope.launch {
                prefs.setSignalingServerUrl(signalingUrl)
                prefs.setTurnServerUrl(turnUrl)
                prefs.setTurnUsername(turnUser)
                prefs.setTurnCredential(turnCred)
                prefs.setPreferredCamera(camera)
                prefs.setDarkMode(darkMode)
                prefs.setShowStatusIndicator(showIndicator)
                prefs.setOverlayDefaultSize(overlaySize)
                prefs.setOverlayDefaultAlpha(overlayAlpha)

                Toast.makeText(this@SettingsActivity, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()

                // Karanlık mod değiştiyse uygula (Activity recreate edilir)
                if (darkMode != initialDarkMode) {
                    app.applyDarkMode(darkMode)
                    // setDefaultNightMode Activity'yi recreate eder; finish() gerekmez
                    // ama biz yine de finish edelim ki MainActivity dönsün
                }
                finish()
            }
        }
    }
}
