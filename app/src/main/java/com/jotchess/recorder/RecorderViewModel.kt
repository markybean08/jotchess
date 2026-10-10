package com.jotchess.recorder

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(
    val opencvReady: Boolean = false,
    val calibrating: Boolean = true,
    val cornerCount: Int = 0,
    val rotation: Int = 0,
    val previewBitmap: Bitmap? = null,
    val detectorState: DetectorState = DetectorState.Idle,
    val highlightSquares: Set<String> = emptySet(),
    val moves: List<String> = emptyList(),
    val pgn: String = "",
    val statusText: String = "Calibrate board corners",
    val message: String? = null,
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    val calibrator = BoardCalibrator()
    val engine = ChessGameEngine()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private val pendingCorners = mutableListOf<CornerPoint>()

    private val detector = MoveDetector(
        calibrator = calibrator,
        engine = engine,
        scope = viewModelScope,
        onMoveCommitted = { _, _ -> publishGame() },
    )

    private val _opencvReady = MutableStateFlow(false)
    private val _calibrating = MutableStateFlow(true)
    private val _cornerCount = MutableStateFlow(0)
    private val _rotation = MutableStateFlow(0)
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    private val _moves = MutableStateFlow<List<String>>(emptyList())
    private val _pgn = MutableStateFlow("")
    private val _message = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow("Starting…")
    private val _detectorState = MutableStateFlow<DetectorState>(DetectorState.Idle)
    private val _highlights = MutableStateFlow<Set<String>>(emptySet())

    private val _ui = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _ui.asStateFlow()

    private var lastPreviewMs = 0L

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val ok = OpenCVLoader.initLocal()
            _opencvReady.value = ok
            if (!ok) {
                _status.value = "OpenCV failed to load"
                emitUi()
                return@launch
            }
            val loaded = calibrator.load(getApplication())
            if (loaded) {
                _calibrating.value = false
                _rotation.value = calibrator.calibration?.rotation ?: 0
                _cornerCount.value = 4
                detector.start()
                _status.value = "Tracking…"
            } else {
                _calibrating.value = true
                _status.value = "Tap 4 board corners"
            }
            emitUi()
        }

        viewModelScope.launch {
            combine(detector.state, detector.changedSquares) { ds, squares -> ds to squares }
                .collect { (ds, squares) ->
                    _detectorState.value = ds
                    _highlights.value = when (ds) {
                        is DetectorState.Anomaly -> ds.squares
                        is DetectorState.Flagged -> ds.squares
                        else -> squares
                    }
                    if (!_calibrating.value) {
                        _status.value = statusFor(ds)
                    }
                    emitUi()
                }
        }
    }

    fun analysisExecutor(): ExecutorService = analysisExecutor

    fun imageAnalyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image ->
        if (!_opencvReady.value) {
            image.close()
            return@Analyzer
        }
        if (!analyzing.compareAndSet(false, true)) {
            image.close()
            return@Analyzer
        }
        try {
            processImage(image)
        } finally {
            analyzing.set(false)
            image.close()
        }
    }

    fun onBoardTapped(normalizedX: Float, normalizedY: Float, imageWidth: Int, imageHeight: Int) {
        if (!_calibrating.value) return
        if (pendingCorners.size >= 4) return
        pendingCorners += CornerPoint(normalizedX * imageWidth, normalizedY * imageHeight)
        _cornerCount.value = pendingCorners.size
        if (pendingCorners.size == 4) {
            calibrator.setCorners(pendingCorners.toList(), _rotation.value)
            _status.value = "Adjust rotation so a1 is correct, then Confirm"
        } else {
            _status.value = "Corner ${pendingCorners.size}/4"
        }
        emitUi()
    }

    fun cycleRotation() {
        if (calibrator.calibration == null && pendingCorners.size == 4) {
            calibrator.setCorners(pendingCorners.toList(), _rotation.value)
        }
        calibrator.cycleRotation()
        _rotation.value = calibrator.calibration?.rotation ?: ((_rotation.value + 90) % 360)
        emitUi()
    }

    fun confirmCalibration() {
        if (pendingCorners.size == 4 && calibrator.calibration == null) {
            calibrator.setCorners(pendingCorners.toList(), _rotation.value)
        }
        if (calibrator.calibration?.isComplete != true) {
            _message.value = "Tap 4 corners first"
            emitUi()
            return
        }
        calibrator.save(getApplication())
        pendingCorners.clear()
        _calibrating.value = false
        _cornerCount.value = 4
        detector.start()
        _status.value = "Tracking…"
        _message.value = null
        emitUi()
    }

    fun recalibrate() {
        detector.stop()
        calibrator.clear()
        pendingCorners.clear()
        _calibrating.value = true
        _cornerCount.value = 0
        _rotation.value = 0
        _highlights.value = emptySet()
        _status.value = "Tap 4 board corners"
        emitUi()
    }

    fun resetGame() {
        engine.reset()
        detector.onGameReset(null)
        publishGame()
        if (!_calibrating.value) {
            _status.value = "Tracking… (reference resets on next settle)"
        }
        emitUi()
    }

    fun undo() {
        if (engine.undoLast()) {
            detector.onGameReset(null)
            publishGame()
            emitUi()
        }
    }

    fun clearMessage() {
        _message.value = null
        emitUi()
    }

    fun pendingCornerPoints(): List<CornerPoint> = pendingCorners.toList()

    private fun processImage(image: ImageProxy) {
        val rgb = FrameConverter.imageProxyToRgbMat(image)
        try {
            val now = System.currentTimeMillis()
            val shouldPreview = now - lastPreviewMs > 120

            val warped = if (calibrator.calibration?.isComplete == true) {
                calibrator.warp(rgb)
            } else {
                null
            }

            if (!_calibrating.value && warped != null) {
                detector.processFrame(warped, now)
            }

            if (shouldPreview) {
                lastPreviewMs = now
                val displayMat: Mat = if (!_calibrating.value && warped != null) warped else rgb
                val bitmap = FrameConverter.matToBitmap(displayMat)
                val old = _previewBitmap.value
                _previewBitmap.value = bitmap
                old?.recycle()
                emitUi()
            }

            warped?.release()
        } finally {
            rgb.release()
        }
    }

    private fun publishGame() {
        _moves.value = engine.numberedMoveList()
        _pgn.value = engine.pgn()
        emitUi()
    }

    private fun emitUi() {
        _ui.value = UiState(
            opencvReady = _opencvReady.value,
            calibrating = _calibrating.value,
            cornerCount = _cornerCount.value,
            rotation = _rotation.value,
            previewBitmap = _previewBitmap.value,
            detectorState = _detectorState.value,
            highlightSquares = _highlights.value,
            moves = _moves.value,
            pgn = _pgn.value,
            statusText = _status.value,
            message = _message.value,
        )
    }

    private fun statusFor(ds: DetectorState): String = when (ds) {
        DetectorState.Idle -> "Ready"
        DetectorState.Moving -> "Motion…"
        DetectorState.Settled -> "Settled"
        is DetectorState.Anomaly -> "Checking disturbance…"
        is DetectorState.Flagged -> "Illegal layout — fix board"
    }

    override fun onCleared() {
        detector.release()
        analysisExecutor.shutdown()
        _previewBitmap.value?.recycle()
        super.onCleared()
    }
}
