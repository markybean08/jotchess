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
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(
    val opencvReady: Boolean = false,
    val calibrating: Boolean = true,
    /** True while actively recording moves (camera analysis for detection). */
    val recording: Boolean = false,
    val gameFinished: Boolean = false,
    val batterySaver: Boolean = false,
    val cornerCount: Int = 0,
    val rotation: Int = 0,
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
    val statusText: String = "Calibrate board corners",
    val message: String? = null,
    val toastMessage: String? = null,
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
    private val _recording = MutableStateFlow(false)
    private val _gameFinished = MutableStateFlow(false)
    private val _batterySaver = MutableStateFlow(false)
    private val _cornerCount = MutableStateFlow(0)
    private val _rotation = MutableStateFlow(0)
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
            val loaded = calibrator.load(getApplication())
            if (loaded) {
                _calibrating.value = false
                _recording.value = true
                _rotation.value = calibrator.calibration?.rotation ?: 0
                _cornerCount.value = 4
                detector.start()
                _status.value = "Tracking…"
            } else {
                _calibrating.value = true
                _recording.value = false
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
        if (!_calibrating.value || _gameFinished.value) return
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
        _recording.value = true
        _gameFinished.value = false
        _batterySaver.value = false
        _cornerCount.value = 4
        _savedPgnPath.value = null
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
        _recording.value = false
        _gameFinished.value = false
        _batterySaver.value = false
        _cornerCount.value = 0
        _rotation.value = 0
        _highlights.value = emptySet()
        _savedPgnPath.value = null
        _status.value = "Tap 4 board corners"
        emitUi()
    }

    fun resetGame() {
        if (!_recording.value) return
        engine.reset()
        detector.onGameReset(null)
        publishGame()
        _status.value = "Tracking… (reference resets on next settle)"
        emitUi()
    }

    fun undo() {
        if (!_recording.value) return
        if (engine.undoLast()) {
            detector.onGameReset(null)
            publishGame()
            emitUi()
        }
    }

    fun endGame() {
        if (!_recording.value && !_gameFinished.value) return
        viewModelScope.launch {
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
            val path = withContext(Dispatchers.IO) { writePgnFile(pgn) }
            _savedPgnPath.value = path

            calibrator.clear()
            pendingCorners.clear()
            _cornerCount.value = 0
            _highlights.value = emptySet()
            _calibrating.value = false

            _status.value = "Game finished / export ready"
            _toastMessage.value = "Game Saved & PGN Copied to Clipboard"
            emitUi()
        }
    }

    fun newGame() {
        detector.stop()
        engine.reset()
        calibrator.clear()
        pendingCorners.clear()
        _calibrating.value = true
        _recording.value = false
        _gameFinished.value = false
        _batterySaver.value = false
        _cornerCount.value = 0
        _rotation.value = 0
        _highlights.value = emptySet()
        _savedPgnPath.value = null
        _moves.value = emptyList()
        _lastSan.value = null
        _pgn.value = ""
        _status.value = "Tap 4 board corners"
        _message.value = null
        emitUi()
    }

    fun setBatterySaver(enabled: Boolean) {
        if (!_recording.value) return
        _batterySaver.value = enabled
        emitUi()
    }

    fun dismissBatterySaver() {
        if (_batterySaver.value) {
            _batterySaver.value = false
            emitUi()
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
        _useFrontCamera.value = !_useFrontCamera.value
        if (_calibrating.value) {
            pendingCorners.clear()
            _cornerCount.value = 0
            calibrator.clear()
            _status.value = "Tap 4 board corners"
        }
        emitUi()
    }

    private fun processImage(image: ImageProxy) {
        val rgb = FrameConverter.imageProxyToRgbMat(
            image = image,
            mirrorHorizontal = _useFrontCamera.value,
        )
        try {
            val now = System.currentTimeMillis()
            val shouldPreview = now - lastPreviewMs > 120

            val warped = if (calibrator.calibration?.isComplete == true) {
                calibrator.warp(rgb)
            } else {
                null
            }

            if (_recording.value && !_gameFinished.value && warped != null) {
                detector.processFrame(warped, now)
            }

            if (shouldPreview) {
                lastPreviewMs = now
                val displayMat: Mat =
                    if (_recording.value && warped != null) warped else rgb
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
        _ui.value = UiState(
            opencvReady = _opencvReady.value,
            calibrating = _calibrating.value,
            recording = _recording.value,
            gameFinished = _gameFinished.value,
            batterySaver = _batterySaver.value,
            cornerCount = _cornerCount.value,
            rotation = _rotation.value,
            useFrontCamera = _useFrontCamera.value,
            cornerPoints = pendingCorners.toList(),
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

    override fun onCleared() {
        detector.release()
        analysisExecutor.shutdown()
        _previewBitmap.value?.recycle()
        super.onCleared()
    }
}
