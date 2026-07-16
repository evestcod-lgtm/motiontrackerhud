package com.motiontracker.hud.vision

enum class TrackStatus {
    SEARCHING,   // no lock yet, motion may be visible but nothing selected
    TRACKING,    // locked and actively matched this frame
    COASTING,    // locked, briefly unmatched, predicting position from velocity
    LOST         // locked target could not be recovered within the grace window
}

/**
 * A single point in the trail history, with a timestamp so old points can be aged out.
 */
data class TrailPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long
)

/**
 * Full state of the currently tracked target, expressed in analysis-grid coordinates.
 * The overlay layer is responsible for mapping these into screen-space.
 */
data class TrackedTarget(
    val id: Int,
    var centerX: Float,
    var centerY: Float,
    var halfWidth: Float,
    var halfHeight: Float,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var status: TrackStatus = TrackStatus.TRACKING,
    var confidence: Float = 1f,
    var framesSinceMatch: Int = 0,
    val trail: ArrayDeque<TrailPoint> = ArrayDeque()
) {
    fun boundsAsBlobLike(): FloatArray = floatArrayOf(
        centerX - halfWidth, centerY - halfHeight,
        centerX + halfWidth, centerY + halfHeight
    )
}
