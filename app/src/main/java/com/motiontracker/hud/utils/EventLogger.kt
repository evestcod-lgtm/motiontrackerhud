package com.motiontracker.hud.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes a plain-text, timestamped log of tracking events (lock acquired, lost,
 * recovered, reset) to local app storage. Purely local — no network calls, no
 * third-party analytics SDKs. Useful for reviewing what the tracker did during
 * a session.
 */
class EventLogger(context: Context) {

    val logFile: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        File(dir, "motion_events.log")
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(event: String) {
        executor.execute {
            try {
                val line = "${dateFormat.format(Date())}  $event\n"
                FileOutputStream(logFile, true).use { it.write(line.toByteArray()) }
            } catch (_: Exception) {
                // Logging must never crash the app; silently ignore write failures
                // (e.g. storage momentarily unavailable).
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
