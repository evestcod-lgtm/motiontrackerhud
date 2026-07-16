package com.motiontracker.hud

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import androidx.camera.view.PreviewView
import com.motiontracker.hud.camera.CameraController
import com.motiontracker.hud.overlay.HudOverlayView
import com.motiontracker.hud.permissions.PermissionManager
import com.motiontracker.hud.ui.HudController
import com.motiontracker.hud.utils.EventLogger
import com.motiontracker.hud.utils.ScreenshotUtil
import com.motiontracker.hud.vision.MotionAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hudOverlayView: HudOverlayView
    private lateinit var statusLabel: TextView
    private lateinit var fpsLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var confidenceLabel: TextView
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var zoomInButton: android.widget.Button
    private lateinit var zoomOutButton: android.widget.Button
    private lateinit var pauseButton: ImageButton
    private lateinit var resetButton: ImageButton
    private lateinit var screenshotButton: ImageButton
    private lateinit var rootFrame: View

    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraController: CameraController
    private lateinit var hudController: HudController
    private lateinit var eventLogger: EventLogger
    private lateinit var motionAnalyzer: MotionAnalyzer

    private var isPaused = false
    private var cameraStarted = false

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var tapGestureDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        eventLogger = EventLogger(this)

        permissionManager = PermissionManager(
            activity = this,
            onAllGranted = { startCameraFlow() },
            onDenied = { permanentlyDenied -> showPermissionDenied(permanentlyDenied) }
        )
        permissionManager.register()

        if (permissionManager.hasAllPermissions(this)) {
            startCameraFlow()
        } else {
            showPermissionRationale()
        }

        setupGestures()
        setupButtons()
        startFpsAndZoomTicker()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        hudOverlayView = findViewById(R.id.hudOverlayView)
        statusLabel = findViewById(R.id.statusLabel)
        fpsLabel = findViewById(R.id.fpsLabel)
        zoomLabel = findViewById(R.id.zoomLabel)
        confidenceLabel = findViewById(R.id.confidenceLabel)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        zoomInButton = findViewById(R.id.zoomInButton)
        zoomOutButton = findViewById(R.id.zoomOutButton)
        pauseButton = findViewById(R.id.pauseButton)
        resetButton = findViewById(R.id.resetButton)
        screenshotButton = findViewById(R.id.screenshotButton)
        rootFrame = findViewById(android.R.id.content)
    }

    // ---- Permission flow ----

    private fun showPermissionRationale() {
        setContentView(R.layout.activity_permission_rationale)
        findViewById<View>(R.id.rationaleActionButton).setOnClickListener {
            permissionManager.requestPermissions()
        }
    }

    private fun showPermissionDenied(permanentlyDenied: Boolean) {
        setContentView(R.layout.activity_permission_rationale)
        findViewById<TextView>(R.id.rationaleTitle).text = getString(R.string.permission_denied_title)
        findViewById<TextView>(R.id.rationaleBody).text = getString(R.string.permission_denied_body)
        val button = findViewById<View>(R.id.rationaleActionButton)
        if (button is android.widget.Button) {
            button.text = getString(R.string.permission_open_settings)
        }
        button.setOnClickListener {
            if (permanentlyDenied) {
                permissionManager.openAppSettings()
            } else {
                permissionManager.requestPermissions()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user granted permission via Settings and returned, re-check and recover.
        if (!cameraStarted && permissionManager.hasAllPermissions(this)) {
            setContentView(R.layout.activity_main)
            bindViews()
            setupGestures()
            setupButtons()
            startFpsAndZoomTicker()
            startCameraFlow()
        }
    }

    // ---- Camera + vision wiring ----

    private fun startCameraFlow() {
        if (cameraStarted) return
        cameraStarted = true

        hudController = HudController(
            overlayView = hudOverlayView,
            eventLogger = eventLogger,
            onStatusChanged = { label -> statusLabel.text = label },
            onConfidenceChanged = { pct ->
                confidenceLabel.text = if (pct > 0) "CONF $pct%" else "CONF —"
            }
        )
        motionAnalyzer = hudController.buildAnalyzer()
        motionAnalyzer.sensitivity = sensitivitySeekBar.progress / 100f

        cameraController = CameraController(this, this)
        cameraController.start(
            previewView = previewView,
            analyzer = motionAnalyzer,
            onError = {
                Toast.makeText(this, "Camera failed to start", Toast.LENGTH_LONG).show()
            }
        )
        hudOverlayView.setMirror(true) // front camera is mirrored in preview by default
    }

    // ---- Gestures: pinch-to-zoom + tap-to-lock ----

    private fun setupGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (::cameraController.isInitialized) {
                    cameraController.onPinchZoom(detector.scaleFactor)
                }
                return true
            }
        })

        tapGestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!::hudController.isInitialized) return false
                val normalizedX = e.x / rootFrame.width
                val normalizedY = e.y / rootFrame.height
                hudController.handleTap(normalizedX, normalizedY, mirrored = true)
                return true
            }
        })

        rootFrame.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            tapGestureDetector.onTouchEvent(event)
            true
        }
    }

    // ---- Buttons ----

    private fun setupButtons() {
        zoomInButton.setOnClickListener {
            if (::cameraController.isInitialized) cameraController.zoomIn()
        }
        zoomOutButton.setOnClickListener {
            if (::cameraController.isInitialized) cameraController.zoomOut()
        }
        pauseButton.setOnClickListener { togglePause() }
        resetButton.setOnClickListener {
            if (::hudController.isInitialized) hudController.resetLock()
        }
        screenshotButton.setOnClickListener { captureScreenshot() }

        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (::motionAnalyzer.isInitialized) {
                    motionAnalyzer.sensitivity = progress / 100f
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun togglePause() {
        if (!::motionAnalyzer.isInitialized) return
        isPaused = !isPaused
        motionAnalyzer.isPaused = isPaused
        if (!isPaused) {
            motionAnalyzer.resetBaseline()
        }
        pauseButton.setImageResource(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
        statusLabel.text = if (isPaused) getString(R.string.status_paused) else getString(R.string.status_searching)
        eventLogger.log(if (isPaused) "ANALYSIS_PAUSED" else "ANALYSIS_RESUMED")
    }

    private fun captureScreenshot() {
        val bitmap = ScreenshotUtil.captureView(rootFrame)
        val saved = ScreenshotUtil.saveBitmap(this, bitmap)
        val message = if (saved) getString(R.string.toast_screenshot_saved) else getString(R.string.toast_screenshot_failed)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        eventLogger.log("SCREENSHOT_${if (saved) "SAVED" else "FAILED"}")
    }

    // ---- Periodic UI ticker for FPS + zoom readouts (cheap, main-thread only) ----

    private fun startFpsAndZoomTicker() {
        lifecycleScope.launch {
            while (true) {
                if (::hudController.isInitialized) {
                    fpsLabel.text = "FPS ${hudController.currentFps().toInt()}"
                }
                if (::cameraController.isInitialized) {
                    cameraController.zoomState.value?.let { state ->
                        zoomLabel.text = String.format("%.1fx", state.zoomRatio)
                    }
                }
                delay(500)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraController.isInitialized) cameraController.shutdown()
        eventLogger.shutdown()
    }
}
