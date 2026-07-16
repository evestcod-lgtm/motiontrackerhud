package com.motiontracker.hud.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Centralizes camera + microphone permission handling.
 *
 * Flow:
 *  1. If already granted -> onAllGranted() immediately.
 *  2. If not granted -> caller shows a rationale screen, then calls requestPermissions().
 *  3. If the user permanently denies (shouldShowRequestPermissionRationale == false after a denial),
 *     caller shows a "go to settings" screen via openAppSettings().
 *
 * The app is designed to never crash without permissions: camera-dependent code paths
 * are only invoked from onAllGranted().
 */
class PermissionManager(
    private val activity: AppCompatActivity,
    private val onAllGranted: () -> Unit,
    private val onDenied: (permanentlyDenied: Boolean) -> Unit
) {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private lateinit var launcher: ActivityResultLauncher<Array<String>>

    /** Must be called during Activity onCreate (before STARTED state). */
    fun register() {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                onAllGranted()
            } else {
                val permanentlyDenied = requiredPermissions.any { perm ->
                    !ContextCompat.checkSelfPermission(activity, perm)
                        .let { it == PackageManager.PERMISSION_GRANTED } &&
                        !activity.shouldShowRequestPermissionRationale(perm)
                }
                onDenied(permanentlyDenied)
            }
        }
    }

    fun hasAllPermissions(context: Context): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions() {
        launcher.launch(requiredPermissions)
    }

    fun shouldShowRationale(): Boolean {
        return requiredPermissions.any { activity.shouldShowRequestPermissionRationale(it) }
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
