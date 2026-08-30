package com.peek.app.camera

import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Ön/arka kamera yönetiminden sorumludur.
 *
 * - Camera2 API üzerinden WebRTC VideoCapturer oluşturur
 * - SurfaceTextureHelper + VideoSource ile VideoTrack üretir
 * - Ön/arka kamera geçişini sağlar (switchCamera)
 * - Yaşam döngüsünü (startCapture/stopCapture/dispose) yönetir
 */
class CameraCapturerManager(
    private val context: Context,
    private val eglBase: EglBase,
) {

    private var enumerator: CameraEnumerator = Camera2Enumerator(context)
    private var capturer: VideoCapturer? = null
    private var currentFacing: String = CAMERA_FRONT

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    /** Verilen yön için capturer oluştur (henüz capture başlamaz). */
    fun createCapturer(facing: String = CAMERA_FRONT): VideoCapturer? {
        currentFacing = facing
        val deviceNames = enumerator.deviceNames
        // Önce istenen yönü ara; yoksa herhangi bir kamerayı döndür
        for (name in deviceNames) {
            val isFront = enumerator.isFrontFacing(name)
            if ((facing == CAMERA_FRONT && isFront) || (facing == CAMERA_BACK && !isFront)) {
                capturer = enumerator.createCapturer(name, null)
                return capturer
            }
        }
        if (deviceNames.isNotEmpty()) {
            capturer = enumerator.createCapturer(deviceNames[0], null)
            return capturer
        }
        return null
    }

    /**
     * Tam video akışını kur ve bir VideoTrack üret.
     *
     * factory            : PeerConnectionFactory (PeekApplication'dan)
     * facing             : ön/arka kamera
     * trackId            : üretilen track'in id'si
     * width/height/fps   : capture çözünürlüğü
     *
     * Akış: capturer -> SurfaceTextureHelper -> VideoSource -> VideoTrack
     */
    fun createVideoTrack(
        factory: PeerConnectionFactory,
        facing: String = currentFacing,
        trackId: String = VIDEO_TRACK_ID,
        width: Int = 640,
        height: Int = 480,
        fps: Int = 30,
    ): VideoTrack? {
        // Önceki kaynağı temizle (varsa)
        dispose()

        val cap = createCapturer(facing) ?: return null

        surfaceTextureHelper = SurfaceTextureHelper.create(
            CAPTURE_THREAD_NAME, eglBase.eglBaseContext
        )
        videoSource = factory.createVideoSource(/* isScreencast = */ false)

        cap.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        cap.startCapture(width, height, fps)

        videoTrack = factory.createVideoTrack(trackId, videoSource)
        return videoTrack
    }

    /** Ön/arka kamera arasında geçiş yap (capture devam ederken). */
    fun switchCamera(): Boolean {
        return try {
            val cam = capturer as? CameraVideoCapturer ?: return false
            cam.switchCamera(null)
            currentFacing = if (currentFacing == CAMERA_FRONT) CAMERA_BACK else CAMERA_FRONT
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Aktif kameranın yönünü döndür. */
    fun currentCameraFacing(): String = currentFacing

    fun stopCapture() {
        try {
            capturer?.stopCapture()
        } catch (_: InterruptedException) {
        }
    }

    /** Tüm kamera kaynaklarını serbest bırak. */
    fun dispose() {
        stopCapture()
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        capturer = null
    }

    companion object {
        const val CAMERA_FRONT = "front"
        const val CAMERA_BACK = "back"
        const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val CAPTURE_THREAD_NAME = "CaptureThread"
    }
}
