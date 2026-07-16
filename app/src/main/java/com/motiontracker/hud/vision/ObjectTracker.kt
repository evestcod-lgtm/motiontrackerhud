package com.motiontracker.hud.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Maintains a single locked target across frames.
 *
 * Matching strategy: nearest-centroid with a size-similarity gate. This is a
 * deliberately simple tracker (no Kalman filter, no multi-hypothesis) because
 * on a phone CPU, running this every analyzed frame must stay cheap. It is
 * however robust enough for continuous human/object motion because:
 *  - a max-distance gate rejects blobs that jumped too far to plausibly be the
 *    same object between two frames ~80ms apart,
 *  - velocity is estimated and used to predict position during brief occlusion,
 *  - a grace window (COAST_FRAMES) tolerates the target momentarily not
 *    producing a distinct motion blob (e.g. it paused) before declaring LOST.
 */
class ObjectTracker(
    private val gridWidth: Int,
    private val gridHeight: Int
) {
    companion object {
        private const val MAX_MATCH_DISTANCE_FRACTION = 0.22f // relative to grid diagonal
        private const val COAST_FRAMES = 12 // ~1s at 12fps analysis rate before LOST
        private const val TRAIL_MAX_POINTS = 40
        private const val TRAIL_MAX_AGE_MS = 6000L
        private const val CONFIDENCE_DECAY_PER_COAST_FRAME = 0.10f
        private const val CONFIDENCE_GAIN_ON_MATCH = 0.15f
    }

    private val gridDiagonal = sqrt((gridWidth * gridWidth + gridHeight * gridHeight).toFloat())
    private val maxMatchDistance = gridDiagonal * MAX_MATCH_DISTANCE_FRACTION

    var target: TrackedTarget? = null
        private set

    private var nextId = 1

    /**
     * Attempts to lock onto the blob nearest to the tapped grid coordinate.
     * Returns true if a blob was found and locked.
     */
    fun lockAt(gridX: Float, gridY: Float, blobs: List<Blob>): Boolean {
        if (blobs.isEmpty()) return false
        val nearest = blobs.minByOrNull { it.distanceTo(gridX, gridY) } ?: return false

        // Only lock if the tap was reasonably close to an actual blob (within ~15% of diagonal)
        if (nearest.distanceTo(gridX, gridY) > gridDiagonal * 0.15f) return false

        target = TrackedTarget(
            id = nextId++,
            centerX = nearest.centerX,
            centerY = nearest.centerY,
            halfWidth = max(nearest.width / 2f, 2f),
            halfHeight = max(nearest.height / 2f, 2f),
            status = TrackStatus.TRACKING,
            confidence = 0.9f
        ).also {
            it.trail.addLast(TrailPoint(it.centerX, it.centerY, System.currentTimeMillis()))
        }
        return true
    }

    fun resetLock() {
        target = null
    }

    /**
     * Updates the current target against the latest set of detected blobs.
     * Call once per analyzed frame (even if blobs is empty).
     */
    fun update(blobs: List<Blob>, timestampMs: Long) {
        val current = target ?: return

        // Predict where the target should be based on last known velocity before matching,
        // which improves match quality when the object is moving steadily.
        val predictedX = current.centerX + current.velocityX
        val predictedY = current.centerY + current.velocityY

        val candidate = blobs
            .map { it to it.distanceTo(predictedX, predictedY) }
            .filter { (blob, dist) ->
                dist <= maxMatchDistance && sizesAreCompatible(current, blob)
            }
            .minByOrNull { it.second }
            ?.first

        if (candidate != null) {
            val newVelX = candidate.centerX - current.centerX
            val newVelY = candidate.centerY - current.centerY

            // Smooth velocity (simple exponential moving average) to avoid jittery trail/prediction
            current.velocityX = current.velocityX * 0.5f + newVelX * 0.5f
            current.velocityY = current.velocityY * 0.5f + newVelY * 0.5f

            current.centerX = candidate.centerX
            current.centerY = candidate.centerY
            current.halfWidth = current.halfWidth * 0.7f + max(candidate.width / 2f, 2f) * 0.3f
            current.halfHeight = current.halfHeight * 0.7f + max(candidate.height / 2f, 2f) * 0.3f
            current.status = TrackStatus.TRACKING
            current.framesSinceMatch = 0
            current.confidence = min(1f, current.confidence + CONFIDENCE_GAIN_ON_MATCH)

            appendTrailPoint(current, timestampMs)
        } else {
            current.framesSinceMatch++
            current.confidence = max(0f, current.confidence - CONFIDENCE_DECAY_PER_COAST_FRAME)

            if (current.framesSinceMatch <= COAST_FRAMES) {
                // Coast: extrapolate position using last known velocity, decayed slightly
                current.centerX += current.velocityX * 0.6f
                current.centerY += current.velocityY * 0.6f
                current.velocityX *= 0.85f
                current.velocityY *= 0.85f
                current.centerX = current.centerX.coerceIn(0f, gridWidth.toFloat())
                current.centerY = current.centerY.coerceIn(0f, gridHeight.toFloat())
                current.status = TrackStatus.COASTING
            } else {
                current.status = TrackStatus.LOST
            }
        }

        pruneTrail(current, timestampMs)
    }

    private fun sizesAreCompatible(target: TrackedTarget, blob: Blob): Boolean {
        val targetArea = (target.halfWidth * 2f) * (target.halfHeight * 2f)
        val blobArea = (blob.width * blob.height).toFloat()
        if (targetArea <= 0f || blobArea <= 0f) return true
        val ratio = max(targetArea, blobArea) / min(targetArea, blobArea)
        return ratio <= 6f // allow generous size change (object moving closer/farther) but reject wild mismatches
    }

    private fun appendTrailPoint(t: TrackedTarget, timestampMs: Long) {
        val last = t.trail.lastOrNull()
        // Avoid flooding the trail with near-duplicate points when the target is nearly still
        if (last == null || abs(last.x - t.centerX) > 0.5f || abs(last.y - t.centerY) > 0.5f) {
            t.trail.addLast(TrailPoint(t.centerX, t.centerY, timestampMs))
            while (t.trail.size > TRAIL_MAX_POINTS) {
                t.trail.removeFirst()
            }
        }
    }

    private fun pruneTrail(t: TrackedTarget, nowMs: Long) {
        while (t.trail.isNotEmpty() && nowMs - t.trail.first().timestampMs > TRAIL_MAX_AGE_MS) {
            t.trail.removeFirst()
        }
    }
}
