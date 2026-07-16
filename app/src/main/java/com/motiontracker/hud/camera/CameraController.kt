package com.motiontracker.hud.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX lifecycle: binds Preview + ImageAnalysis to the front camera,
 * and exposes zoom control that both pinch gestures and UI buttons can drive.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
        private const val ZOOM_STEP = 0.15f
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val zoomState = MutableLiveData<ZoomState?>()
    val cameraReady = MutableLiveData(false)

    private var providerFuture: ListenableFuture<ProcessCameraProvider>? = null

    fun start(
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        onError: (Throwable) -> Unit
    ) {
        providerFuture = ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindUseCases(provider, previewView, analyzer)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start camera", e)
                    onError(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer
    ) {
        provider.unbindAll()

        val preview = Preview.Builder()
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        val selector = CameraSelector.DEFAULT_FRONT_CAMERA

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageAnalysis
        )

        camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
            zoomState.value = state
        }

        cameraReady.value = true
    }

    fun zoomIn() {
        val cam = camera ?: return
        val current = cam.cameraInfo.zoomState.value ?: return
        val newRatio = (current.zoomRatio + ZOOM_STEP * current.maxZoomRatio)
            .coerceIn(current.minZoomRatio, current.maxZoomRatio)
        cam.cameraControl.setZoomRatio(newRatio)
    }

    fun zoomOut() {
        val cam = camera ?: return
        val current = cam.cameraInfo.zoomState.value ?: return
        val newRatio = (current.zoomRatio - ZOOM_STEP * current.maxZoomRatio)
            .coerceIn(current.minZoomRatio, current.maxZoomRatio)
        cam.cameraControl.setZoomRatio(newRatio)
    }

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val current = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(current.minZoomRatio, current.maxZoomRatio))
    }

    fun onPinchZoom(scaleFactor: Float) {
        val cam = camera ?: return
        val current = cam.cameraInfo.zoomState.value ?: return
        val newRatio = (current.zoomRatio * scaleFactor)
            .coerceIn(current.minZoomRatio, current.maxZoomRatio)
        cam.cameraControl.setZoomRatio(newRatio)
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
