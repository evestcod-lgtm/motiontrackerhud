package com.motiontracker.hud.vision

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.motiontracker.hud.utils.ImageUtils

/**
 * Lightweight on-device motion detector.
 *
 * Pipeline per analyzed frame:
 *  1. Downscale the Y (luma) plane to a small grid (GRID_W x GRID_H).
 *  2. Compute absolute difference against the previous grid.
 *  3. Threshold the difference (sensitivity-controlled) to get a binary motion mask.
 *  4. Apply a simple 3x3 majority filter to suppress salt-and-pepper noise.
 *  5. Flood-fill connected components on the mask to extract blobs.
 *  6. Drop blobs below a minimum pixel-count (further noise rejection).
 *
 * The analyzer self-throttles to [minIntervalMs] between analyzed frames so that
 * CameraX can run at full preview framerate while CPU-heavy analysis runs at a
 * capped rate (default ~12fps), which is plenty for human/object motion tracking
 * and keeps battery draw reasonable.
 */
class MotionAnalyzer(
    private val onBlobsDetected: (blobs: List<Blob>, analyzedAtMs: Long) -> Unit,
    private val onFrameThroughput: () -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        const val GRID_W = 96
        const val GRID_H = 72
        private const val MIN_BLOB_PIXELS = 6
        private const val DEFAULT_MIN_INTERVAL_MS = 80L // ~12.5 fps analysis cap
    }

    @Volatile
    var sensitivity: Float = 0.5f // 0..1, higher = more sensitive (lower threshold)

    @Volatile
    var isPaused: Boolean = false

    private var minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
    private var lastAnalyzedAt = 0L
    private var previousGrid: IntArray? = null

    override fun analyze(image: ImageProxy) {
        onFrameThroughput()

        val now = System.currentTimeMillis()
        if (isPaused || now - lastAnalyzedAt < minIntervalMs) {
            image.close()
            return
        }
        lastAnalyzedAt = now

        try {
            val currentGrid = ImageUtils.yuvToGrayGrid(image, GRID_W, GRID_H)
            val prevGrid = previousGrid

            if (prevGrid != null) {
                val blobs = detectMotionBlobs(prevGrid, currentGrid)
                onBlobsDetected(blobs, now)
            } else {
                onBlobsDetected(emptyList(), now)
            }
            previousGrid = currentGrid
        } finally {
            image.close()
        }
    }

    /** Reset baseline so the next frame doesn't diff against a stale/paused frame. */
    fun resetBaseline() {
        previousGrid = null
    }

    private fun detectMotionBlobs(prev: IntArray, curr: IntArray): List<Blob> {
        // Threshold scales inversely with sensitivity: sensitivity 1.0 -> threshold ~10, 0.0 -> threshold ~60
        val threshold = (60 - sensitivity.coerceIn(0f, 1f) * 50f).toInt().coerceIn(8, 60)

        val mask = BooleanArray(GRID_W * GRID_H)
        for (i in curr.indices) {
            val diff = kotlin.math.abs(curr[i] - prev[i])
            mask[i] = diff >= threshold
        }

        val filtered = majorityFilter(mask, GRID_W, GRID_H)
        return extractBlobs(filtered, GRID_W, GRID_H)
    }

    /**
     * Simple denoising pass: a cell only stays "on" if at least 3 of its 8
     * neighbors (plus itself) are also "on". This removes isolated single-pixel
     * false positives caused by sensor noise without needing a full Gaussian blur.
     */
    private fun majorityFilter(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val result = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx]) continue
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) {
                            count++
                        }
                    }
                }
                result[idx] = count >= 3
            }
        }
        return result
    }

    /** Iterative flood fill (stack-based, avoids recursion stack overflow) to find connected blobs. */
    private fun extractBlobs(mask: BooleanArray, w: Int, h: Int): List<Blob> {
        val visited = BooleanArray(w * h)
        val blobs = ArrayList<Blob>()
        val stack = IntArray(w * h)

        for (start in 0 until w * h) {
            if (!mask[start] || visited[start]) continue

            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true

            var minX = start % w
            var maxX = minX
            var minY = start / w
            var maxY = minY
            var pixelCount = 0

            while (stackSize > 0) {
                val idx = stack[--stackSize]
                val x = idx % w
                val y = idx / w
                pixelCount++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                // 4-connectivity neighbors
                if (x > 0) {
                    val nIdx = idx - 1
                    if (mask[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack[stackSize++] = nIdx
                    }
                }
                if (x < w - 1) {
                    val nIdx = idx + 1
                    if (mask[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack[stackSize++] = nIdx
                    }
                }
                if (y > 0) {
                    val nIdx = idx - w
                    if (mask[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack[stackSize++] = nIdx
                    }
                }
                if (y < h - 1) {
                    val nIdx = idx + w
                    if (mask[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack[stackSize++] = nIdx
                    }
                }
            }

            if (pixelCount >= MIN_BLOB_PIXELS) {
                blobs.add(Blob(minX, minY, maxX, maxY, pixelCount))
            }
        }
        return blobs
    }
}
