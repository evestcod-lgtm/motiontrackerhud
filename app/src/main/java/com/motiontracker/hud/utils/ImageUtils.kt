package com.motiontracker.hud.utils

import androidx.camera.core.ImageProxy

/**
 * Utilities for converting camera frames into a cheap, small grayscale grid
 * suitable for on-device motion analysis. We deliberately avoid full-resolution
 * processing: downsampling to a small grid (e.g. 80x60) is enough to detect
 * motion blobs while keeping CPU/battery usage low.
 */
object ImageUtils {

    /**
     * Extracts the Y (luma) plane from a YUV_420_888 image and downsamples it
     * by nearest-neighbor striding into a [gridWidth] x [gridHeight] byte grid
     * of 0-255 luminance values.
     *
     * This only reads the Y plane, which is a direct grayscale representation —
     * no color conversion needed, which keeps this very fast.
     */
    fun yuvToGrayGrid(image: ImageProxy, gridWidth: Int, gridHeight: Int): IntArray {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val imageWidth = image.width
        val imageHeight = image.height

        val grid = IntArray(gridWidth * gridHeight)

        val xStep = imageWidth.toFloat() / gridWidth
        val yStep = imageHeight.toFloat() / gridHeight

        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        for (gy in 0 until gridHeight) {
            val srcY = (gy * yStep).toInt().coerceIn(0, imageHeight - 1)
            val rowOffset = srcY * rowStride
            for (gx in 0 until gridWidth) {
                val srcX = (gx * xStep).toInt().coerceIn(0, imageWidth - 1)
                val index = rowOffset + srcX * pixelStride
                val value = if (index < bytes.size) bytes[index].toInt() and 0xFF else 0
                grid[gy * gridWidth + gx] = value
            }
        }
        return grid
    }
}
