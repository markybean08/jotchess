package com.jotchess.recorder

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class CalibrationPhase {
    AUTO_SCANNING,
    MANUAL,
    CALIBRATED,
}

data class UiState(
    val opencvReady: Boolean = false,
    val calibrating: Boolean = true,
    val calibrationPhase: CalibrationPhase = CalibrationPhase.AUTO_SCANNING,
    /** Live candidate quad while auto-scanning (for mint overlay). */
    val scanQuad: List<CornerPoint> = emptyList(),
    val showManualFallback: Boolean = false,
    /** True while actively recording moves (camera analysis for detection). */
    val recording: Boolean = false,
    val gameFinished: Boolean = false,
    val batterySaver: Boolean = false,
    val cornerCount: Int = 0,
    val rotation: Int = 0,
    /** Polarity gap was too small — offer Flip 180°. */
    val orientationAmbiguous: Boolean = false,
    /** true = front (selfie) lens; false = back lens (default). */
    val useFrontCamera: Boolean = false,
    val cornerPoints: List<CornerPoint> = emptyList(),
    val previewBitmap: Bitmap? = null,
    val detectorState: DetectorState = DetectorState.Idle,
    val highlightSquares: Set<String> = emptySet(),
    val moves: List<String> = emptyList(),
    val lastSan: String? = null,
    val pgn: String = "",
    val savedPgnPath: String? = null,
    val statusText: String = "Auto-scanning board…",
    val message: String? = null,
    val toastMessage: String? = null,
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    val calibrator = BoardCalibrator()
    val engine = ChessGameEngine()
    private val autoDetector = AutoBoardDetector(calibrator)

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private val pendingCorners = mutableListOf<CornerPoint>()

    private val detector = MoveDetector(
        calibrator = calibrator,
        engine = engine,
        onMoveCommitted = { _, _ ->
            // Already on analysisExecutor.
            publishGame()
        },
    )

    private val _opencvReady = MutableStateFlow(false)
    private val _calibrating = MutableStateFlow(true)
    private val _calibrationPhase = MutableStateFlow(CalibrationPhase.AUTO_SCANNING)
    private val _scanQuad = MutableStateFlow<List<CornerPoint>>(emptyList())
    private val _showManualFallback = MutableStateFlow(false)
    private val _recording = MutableStateFlow(false)
    private val _gameFinished = MutableStateFlow(false)
    private val _batterySaver = MutableStateFlow(false)
    private val _cornerCount = MutableStateFlow(0)
    private val _rotation = MutableStateFlow(0)
    private val _orientationAmbiguous = MutableStateFlow(false)
    private val _useFrontCamera = MutableStateFlow(false)
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    private val _moves = MutableStateFlow<List<String>>(emptyList())
    private val _lastSan = MutableStateFlow<String?>(null)
    private val _pgn = MutableStateFlow("")
    private val _savedPgnPath = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    private val _toastMessage = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow("Starting…")
    private val _detectorState = MutableStateFlow<DetectorState>(DetectorState.Idle)
    private val _highlights = MutableStateFlow<Set<String>>(emptySet())

    private val _ui = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _ui.asStateFlow()

    private var lastPreviewMs = 0L
    private var lastAutoScanMs = 0L
    private var autoScanStartedMs = 0L

    /** Camera should run during calibration preview or active recording. */
    val cameraShouldRun: Boolean
        get() = _opencvReady.value && !_gameFinished.value && (_calibrating.value || _recording.value)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val ok = OpenCVLoader.initLocal()
            _opencvReady.value = ok
            if (!ok) {
                _status.value = "OpenCV failed to load"
                emitUi()
                return@launch
            }
            runOnAnalysis {
                val loaded = calibrator.load(getApplication())
                if (loaded) {
                    _calibrating.value = false
                    _recording.value = true
                    _calibrationPhase.value = CalibrationPhase.CALIBRATED
                    _rotation.value = calibrator.calibration?.rotation ?: 0
                    _cornerCount.value = 4
                    _orientationAmbiguous.value = false
                    detector.start()
                    _status.value = "Tracking…"
                } else {
                    beginAutoScan()
                }
                emitUi()
            }
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
                    if (_recording.value && !_gameFinished.value) {
                        _status.value = statusFor(ds)
                    }
                    emitUi()
                }
        }
    }

    fun analysisExecutor(): ExecutorService = analysisExecutor

    fun imageAnalyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image ->
        if (!_opencvReady.value || _gameFinished.value) {
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
        runOnAnalysis {
            if (!_calibrating.value || _gameFinished.value) return@runOnAnalysis
            if (_calibrationPhase.value != CalibrationPhase.MANUAL) return@runOnAnalysis
            if (pendingCorners.size >= 4) return@runOnAnalysis
            pendingCorners += CornerPoint(normalizedX * imageWidth, normalizedY * imageHeight)
            _cornerCount.value = pendingCorners.size
            if (pendingCorners.size == 4) {
                calibrator.setCorners(pendingCorners.toList(), _rotation.value)
                _calibrationPhase.value = CalibrationPhase.CALIBRATED
                _orientationAmbiguous.value = false
                _status.value = "Calibrated — adjust rotate if needed, then Record Game"
            } else {
                _status.value = "Corner ${pendingCorners.size}/4"
            }
            emitUi()
        }
    }

    fun cycleRotation() {
        runOnAnalysis {
            if (calibrator.calibration == null && pendingCorners.size == 4) {
                calibrator.setCorners(pendingCorners.toList(), _rotation.value)
            }
            if (calibrator.calibration == null) return@runOnAnalysis
            calibrator.cycleRotation()
            _rotation.value = calibrator.calibration?.rotation ?: 0
            _orientationAmbiguous.value = false
            if (_recording.value) {
                detector.invalidateReferenceKeepHistory()
            }
            emitUi()
        }
    }

    fun flip180() {
        runOnAnalysis {
            if (calibrator.calibration == null && pendingCorners.size == 4) {
                calibrator.setCorners(pendingCorners.toList(), _rotation.value)
            }
            val cal = calibrator.calibration ?: return@runOnAnalysis
            val next = BoardCalibrator.normalizeRotation(cal.rotation + 180)
            calibrator.setCorners(cal.corners, next)
            _rotation.value = next
            _orientationAmbiguous.value = false
            if (_recording.value) {
                detector.invalidateReferenceKeepHistory()
            }
            _status.value = "Flipped 180°"
            emitUi()
        }
    }

    fun confirmCalibration() {
        runOnAnalysis {
            if (pendingCorners.size == 4 && calibrator.calibration == null) {
                calibrator.setCorners(pendingCorners.toList(), _rotation.value)
            }
            if (calibrator.calibration?.isComplete != true) {
                _message.value = "Wait for auto-scan or tap 4 corners"
                emitUi()
                return@runOnAnalysis
            }
            calibrator.save(getApplication())
            pendingCorners.clear()
            _calibrating.value = false
            _recording.value = true
            _gameFinished.value = false
            _batterySaver.value = false
            _calibrationPhase.value = CalibrationPhase.CALIBRATED
            _scanQuad.value = emptyList()
            _cornerCount.value = 4
            _savedPgnPath.value = null
            detector.start()
            _status.value = "Tracking…"
            _message.value = null
            emitUi()
        }
    }

    fun enableManualCalibration() {
        runOnAnalysis {
            if (!_calibrating.value) return@runOnAnalysis
            detector.stop()
            calibrator.clear()
            pendingCorners.clear()
            _cornerCount.value = 0
            _rotation.value = 0
            _orientationAmbiguous.value = false
            _scanQuad.value = emptyList()
            _calibrationPhase.value = CalibrationPhase.MANUAL
            _status.value = "Tap 4 board corners"
            emitUi()
        }
    }

    fun enableAutoScan() {
        runOnAnalysis {
            if (!_calibrating.value) return@runOnAnalysis
            beginAutoScan()
            emitUi()
        }
    }

    fun recalibrate() {
        runOnAnalysis {
            detector.stop()
            beginAutoScan()
            _recording.value = false
            _gameFinished.value = false
            _batterySaver.value = false
            _highlights.value = emptySet()
            _savedPgnPath.value = null
            emitUi()
        }
    }

    fun resetGame() {
        runOnAnalysis {
            if (!_recording.value) return@runOnAnalysis
            engine.reset()
            detector.onGameReset()
            publishGame()
            _status.value = "Tracking… (reference resets on next settle)"
            emitUi()
        }
    }

    fun undo() {
        runOnAnalysis {
            if (!_recording.value) return@runOnAnalysis
            if (!engine.undoLast()) return@runOnAnalysis
            if (!detector.undoReference()) {
                // No snapshot (e.g. loaded mid-game): soft-clear so next settle rebaselines.
                detector.clearLiveReference()
            }
            publishGame()
            emitUi()
        }
    }

    fun endGame() {
        runOnAnalysis {
            if (!_recording.value && !_gameFinished.value) return@runOnAnalysis
            detector.stop()
            _recording.value = false
            _batterySaver.value = false
            _gameFinished.value = true

            val result = engine.resultTag()
            val pgn = engine.pgn(result = result)
            _pgn.value = pgn
            _moves.value = engine.numberedMoveList()
            _lastSan.value = engine.lastSan

            copyToClipboard(pgn)
            viewModelScope.launch {
                val path = withContext(Dispatchers.IO) { writePgnFile(pgn) }
                runOnAnalysis {
                    _savedPgnPath.value = path
                    calibrator.clear()
                    pendingCorners.clear()
                    _cornerCount.value = 0
                    _highlights.value = emptySet()
                    _scanQuad.value = emptyList()
                    _calibrating.value = false
                    _status.value = "Game finished / export ready"
                    _toastMessage.value = "Game Saved & PGN Copied to Clipboard"
                    emitUi()
                }
            }
            emitUi()
        }
    }

    fun newGame() {
        runOnAnalysis {
            detector.stop()
            engine.reset()
            beginAutoScan()
            _recording.value = false
            _gameFinished.value = false
            _batterySaver.value = false
            _highlights.value = emptySet()
            _savedPgnPath.value = null
            _moves.value = emptyList()
            _lastSan.value = null
            _pgn.value = ""
            _message.value = null
            emitUi()
        }
    }

    fun setBatterySaver(enabled: Boolean) {
        runOnAnalysis {
            if (!_recording.value) return@runOnAnalysis
            _batterySaver.value = enabled
            emitUi()
        }
    }

    fun dismissBatterySaver() {
        runOnAnalysis {
            if (_batterySaver.value) {
                _batterySaver.value = false
                emitUi()
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
        emitUi()
    }

    fun clearMessage() {
        _message.value = null
        emitUi()
    }

    fun toggleCameraLens() {
        runOnAnalysis {
            _useFrontCamera.value = !_useFrontCamera.value
            if (_calibrating.value) {
                beginAutoScan()
            }
            emitUi()
        }
    }

    private fun beginAutoScan() {
        calibrator.clear()
        pendingCorners.clear()
        _calibrating.value = true
        _calibrationPhase.value = CalibrationPhase.AUTO_SCANNING
        _scanQuad.value = emptyList()
        _showManualFallback.value = false
        _cornerCount.value = 0
        _rotation.value = 0
        _orientationAmbiguous.value = false
        autoScanStartedMs = System.currentTimeMillis()
        lastAutoScanMs = 0L
        _status.value = "Auto-scanning board…"
    }

    private fun processImage(image: ImageProxy) {
        val rgb = FrameConverter.imageProxyToRgbMat(image)
        try {
            val nowMs = image.imageInfo.timestamp / 1_000_000L
            val wallNow = System.currentTimeMillis()
            val shouldPreview = wallNow - lastPreviewMs > 120

            if (_calibrating.value && _calibrationPhase.value == CalibrationPhase.AUTO_SCANNING) {
                runAutoScan(rgb, wallNow)
            }

            val warped = if (calibrator.calibration?.isComplete == true) {
                calibrator.warp(rgb)
            } else {
                null
            }

            if (_recording.value && !_gameFinished.value && warped != null) {
                detector.processFrame(warped, nowMs)
            }

            if (shouldPreview) {
                lastPreviewMs = wallNow
                val displayMat: Mat =
                    if (_recording.value && warped != null) warped else rgb
                val bitmap = FrameConverter.matToBitmap(displayMat)
                // Do not recycle the previous bitmap — Compose may still be drawing it.
                _previewBitmap.value = bitmap
                emitUi()
            }

            warped?.release()
        } finally {
            rgb.release()
        }
    }

    private fun runAutoScan(rgb: Mat, nowMs: Long) {
        if (nowMs - autoScanStartedMs >= MANUAL_FALLBACK_MS) {
            _showManualFallback.value = true
        }
        if (nowMs - lastAutoScanMs < AUTO_SCAN_INTERVAL_MS) return
        lastAutoScanMs = nowMs

        val live = autoDetector.detectQuadOnly(rgb)
        if (live != null) {
            _scanQuad.value = live
        }

        val detection = autoDetector.detect(rgb) ?: return
        calibrator.setCorners(detection.corners, detection.rotation)
        pendingCorners.clear()
        pendingCorners.addAll(detection.corners)
        _cornerCount.value = 4
        _rotation.value = detection.rotation
        _orientationAmbiguous.value = detection.orientationAmbiguous
        _scanQuad.value = detection.corners
        _calibrationPhase.value = CalibrationPhase.CALIBRATED
        _status.value = if (detection.orientationAmbiguous) {
            "Calibrated — verify a1, Flip 180° if needed"
        } else {
            "Calibrated — press Record Game"
        }
        _showManualFallback.value = false
    }

    private fun publishGame() {
        _moves.value = engine.numberedMoveList()
        _lastSan.value = engine.lastSan
        _pgn.value = engine.pgn()
        emitUi()
    }

    private fun copyToClipboard(pgn: String) {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pgn", pgn))
    }

    private fun writePgnFile(pgn: String): String {
        val dir = getApplication<Application>().getExternalFilesDir(null)
            ?: getApplication<Application>().filesDir
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "jotchess-$stamp.pgn")
        file.writeText(pgn)
        return file.absolutePath
    }

    private fun emitUi() {
        val cornersForUi = when {
            pendingCorners.isNotEmpty() -> pendingCorners.toList()
            calibrator.calibration?.corners != null -> calibrator.calibration!!.corners
            else -> emptyList()
        }
        _ui.value = UiState(
            opencvReady = _opencvReady.value,
            calibrating = _calibrating.value,
            calibrationPhase = _calibrationPhase.value,
            scanQuad = _scanQuad.value,
            showManualFallback = _showManualFallback.value,
            recording = _recording.value,
            gameFinished = _gameFinished.value,
            batterySaver = _batterySaver.value,
            cornerCount = _cornerCount.value,
            rotation = _rotation.value,
            orientationAmbiguous = _orientationAmbiguous.value,
            useFrontCamera = _useFrontCamera.value,
            cornerPoints = cornersForUi,
            previewBitmap = _previewBitmap.value,
            detectorState = _detectorState.value,
            highlightSquares = _highlights.value,
            moves = _moves.value,
            lastSan = _lastSan.value,
            pgn = _pgn.value,
            savedPgnPath = _savedPgnPath.value,
            statusText = _status.value,
            message = _message.value,
            toastMessage = _toastMessage.value,
        )
    }

    private fun statusFor(ds: DetectorState): String = when (ds) {
        DetectorState.Idle -> "Ready"
        DetectorState.Moving -> "Motion…"
        DetectorState.Settled -> "Settled"
        is DetectorState.Anomaly -> "Checking disturbance…"
        is DetectorState.Flagged -> "Illegal layout — fix board"
    }

    private fun runOnAnalysis(block: () -> Unit) {
        if (analysisExecutor.isShutdown) return
        analysisExecutor.execute {
            try {
                block()
            } catch (_: Exception) {
                // Keep analyzer alive; surface via status if needed.
            }
        }
    }

    override fun onCleared() {
        try {
            if (!analysisExecutor.isShutdown) {
                val done = analysisExecutor.submit {
                    detector.release()
                }
                done.get(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            // Best-effort shutdown.
        }
        analysisExecutor.shutdown()
        // Bitmaps are left for GC — Compose may still hold the last frame.
        super.onCleared()
    }

    companion object {
        private const val AUTO_SCAN_INTERVAL_MS = 500L
        private const val MANUAL_FALLBACK_MS = 4000L
    }
}
