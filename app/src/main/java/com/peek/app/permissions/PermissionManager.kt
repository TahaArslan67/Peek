package com.peek.app.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Tüm runtime izin isteklerini merkezi olarak yönetir.
 *
 * İzinler:
 *  - CAMERA
 *  - RECORD_AUDIO
 *  - POST_NOTIFICATIONS (Android 13+)
 *  - SYSTEM_ALERT_WINDOW (overlay - Settings üzerinden ayrı istenir)
 *
 * SYSTEM_ALERT_WINDOW özel bir akış gerektirir: Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 * ile kullanıcı ayarlar ekranına yönlendirilir.
 */
object PermissionManager {

    val runtimePermissions: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun hasRuntimePermissions(context: Context): Boolean =
        runtimePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun requestRuntimePermissions(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, runtimePermissions, requestCode)
    }

    /** SYSTEM_ALERT_WINDOW izni verildi mi? */
    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

    /** Kullanıcıyı overlay izin ayar ekranına yönlendir. */
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }
}
