package com.motiontracker.hud.ui

/**
 * Tracks frames-per-second using a rolling 1-second window. Call [tick] once per
 * camera frame (from the analyzer's raw throughput callback, before any analysis
 * throttling), and read [currentFps] whenever the UI needs to display it.
 */
class FpsCounter {
    private var windowStartMs = System.currentTimeMillis()
    private var frameCountInWindow = 0
    private var lastComputedFps = 0f

    fun tick() {
        frameCountInWindow++
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartMs
        if (elapsed >= 1000) {
            lastComputedFps = frameCountInWindow * 1000f / elapsed
            frameCountInWindow = 0
            windowStartMs = now
        }
    }

    fun currentFps(): Float = lastComputedFps
}
