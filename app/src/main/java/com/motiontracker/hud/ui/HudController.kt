package com.motiontracker.hud.ui

import android.os.Handler
import android.os.Looper
import com.motiontracker.hud.overlay.HudOverlayView
import com.motiontracker.hud.utils.EventLogger
import com.motiontracker.hud.vision.Blob
import com.motiontracker.hud.vision.MotionAnalyzer
import com.motiontracker.hud.vision.ObjectTracker
import com.motiontracker.hud.vision.TrackStatus

/**
 * Glue layer between the vision pipeline (which runs on a background analyzer
 * thread) and the UI (overlay view + telemetry panels, which must run on the
 * main thread). Owns the ObjectTracker instance and exposes callbacks for the
 * activity to wire up status text, FPS, and confidence readouts.
 */
class HudController(
    private val overlayView: HudOverlayView,
    private val eventLogger: EventLogger,
    private val onStatusChanged: (statusText: String) -> Unit,
    private val onConfidenceChanged: (confidencePercent: Int) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fpsCounter = FpsCounter()

    private val tracker = ObjectTracker(MotionAnalyzer.GRID_W, MotionAnalyzer.GRID_H)

    @Volatile
    private var latestBlobs: List<Blob> = emptyList()

    private var lastReportedStatus: TrackStatus? = null

    init {
        overlayView.setGridSize(MotionAnalyzer.GRID_W, MotionAnalyzer.GRID_H)
    }

    fun buildAnalyzer(): MotionAnalyzer {
        return MotionAnalyzer(
            onBlobsDetected = { blobs, timestampMs ->
                latestBlobs = blobs
                tracker.update(blobs, timestampMs)
                postTrackerStateToUi()
            },
            onFrameThroughput = {
                fpsCounter.tick()
            }
        )
    }

    fun currentFps(): Float = fpsCounter.currentFps()

    /** Handle a tap on the preview, in normalized [0,1] view coordinates. */
    fun handleTap(normalizedX: Float, normalizedY: Float, mirrored: Boolean) {
        val gx = (if (mirrored) 1f - normalizedX else normalizedX) * MotionAnalyzer.GRID_W
        val gy = normalizedY * MotionAnalyzer.GRID_H
        val locked = tracker.lockAt(gx, gy, latestBlobs)
        if (locked) {
            eventLogger.log("LOCK_ACQUIRED target_id=${tracker.target?.id}")
        }
        postTrackerStateToUi()
    }

    fun resetLock() {
        eventLogger.log("LOCK_RESET target_id=${tracker.target?.id}")
        tracker.resetLock()
        postTrackerStateToUi()
    }

    private fun postTrackerStateToUi() {
        val target = tracker.target
        mainHandler.post {
            overlayView.updateTarget(target)

            val status = target?.status
            if (status != lastReportedStatus) {
                lastReportedStatus = status
                val label = when (status) {
                    TrackStatus.TRACKING -> "TRACKING ACTIVE"
                    TrackStatus.COASTING -> "RECOVERING…"
                    TrackStatus.LOST -> "TARGET LOST"
                    TrackStatus.SEARCHING, null -> "SEARCHING"
                }
                onStatusChanged(label)

                if (status == TrackStatus.LOST) {
                    eventLogger.log("TARGET_LOST target_id=${target?.id}")
                }
            }

            onConfidenceChanged(((target?.confidence ?: 0f) * 100).toInt())
        }
    }
}
