package com.motiontracker.hud.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Renders a given View hierarchy (camera preview + HUD overlay) into a Bitmap
 * and saves it as a PNG. Uses MediaStore on API 29+ (scoped storage compliant,
 * no WRITE_EXTERNAL_STORAGE permission needed) and falls back to app-specific
 * external storage on older versions.
 */
object ScreenshotUtil {

    fun captureView(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    fun saveBitmap(context: Context, bitmap: Bitmap): Boolean {
        val filename = "motion_capture_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        }.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, filename)
            } else {
                saveViaLegacyStorage(context, bitmap, filename)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, filename: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MotionTrackerHUD")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        val stream: OutputStream = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Failed to open output stream")
        stream.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun saveViaLegacyStorage(context: Context, bitmap: Bitmap, filename: String) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IllegalStateException("External storage unavailable")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, filename)
        java.io.FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
