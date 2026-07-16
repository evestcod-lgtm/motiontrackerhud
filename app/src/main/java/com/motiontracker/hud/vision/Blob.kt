package com.motiontracker.hud.vision

/**
 * A connected region of motion pixels detected in the downscaled analysis grid.
 * Coordinates are in grid cell units (not pixels, not screen coordinates).
 */
data class Blob(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val pixelCount: Int
) {
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
    val width: Int get() = (maxX - minX + 1)
    val height: Int get() = (maxY - minY + 1)
    val area: Int get() = width * height

    fun distanceTo(x: Float, y: Float): Float {
        val dx = centerX - x
        val dy = centerY - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun distanceTo(other: Blob): Float = distanceTo(other.centerX, other.centerY)
}
