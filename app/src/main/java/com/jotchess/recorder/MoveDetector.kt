package com.jotchess.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class DetectorConfig(
    val motionThreshold: Double = 6.0,
    val settleMs: Long = 500L,
    val anomalyMs: Long = 3000L,
    /** Join the changed set above this Lab mean delta. */
    val squareJoinThreshold: Double = 12.0,
    /** Leave the changed set only below this Lab mean delta. */
    val squareLeaveThreshold: Double = 8.0,
    val lightingBlend: Double = 0.02,
    val motionScale: Size = Size(100.0, 100.0),
    /** Sensor-time gap treated as board / camera motion. */
    val maxFrameGapMs: Long = 200L,
    /** Identical changed-square sets required before evaluating a move. */
    val stableFrameCount: Int = 3,
    val maxReferenceHistory: Int = 200,
)

sealed class DetectorState {
    data object Idle : DetectorState()
    data object Moving : DetectorState()
    data object Settled : DetectorState()
    data class Anomaly(
        val squares: Set<String>,
        val deadlineMs: Long,
    ) : DetectorState()
    data class Flagged(val squares: Set<String>) : DetectorState()
}

/**
 * Per-square hysteresis latch: join above [join], leave only below [leave].
 * Pure helper so unit tests need no OpenCV.
 */
class SquareChangeLatch(
    private val join: Double = 12.0,
    private val leave: Double = 8.0,
) {
    private val latched = mutableSetOf<String>()

    fun update(scores: Map<String, Double>): Set<String> {
        for ((square, score) in scores) {
            if (score > join) {
                latched += square
            } else if (score < leave) {
                latched -= square
            }
        }
        // Drop squares that disappeared from this frame's score map.
        latched.retainAll(scores.keys)
        return latched.toSet()
    }

    fun clear() {
        latched.clear()
    }

    fun snapshot(): Set<String> = latched.toSet()
}

/**
 * Difference mapping, hand/motion gate, sensor-clock anomaly debounce.
 *
 * All public methods MUST run on the analysisExecutor thread that owns
 * [referenceLab] and the undo history. Never release Mats from the UI thread.
 */
class MoveDetector(
    private val calibrator: BoardCalibrator,
    private val engine: ChessGameEngine,
    private val config: DetectorConfig = DetectorConfig(),
    private val onMoveCommitted: (san: String, uci: String) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<DetectorState>(DetectorState.Idle)
    val state: StateFlow<DetectorState> = _state.asStateFlow()

    private val _changedSquares = MutableStateFlow<Set<String>>(emptySet())
    val changedSquares: StateFlow<Set<String>> = _changedSquares.asStateFlow()

    private var previousSmall: Mat? = null
    private var referenceLab: Mat? = null
    /** Cloned referenceLab mats prior to each committed move (oldest first). */
    private val referenceHistory = ArrayDeque<Mat>()
    private val squareLatch = SquareChangeLatch(
        join = config.squareJoinThreshold,
        leave = config.squareLeaveThreshold,
    )

    private var stillSinceMs: Long? = null
    private var lastFrameMs: Long? = null
    private var stableChanged: Set<String>? = null
    private var stableCount: Int = 0
    private var anomalyDeadlineMs: Long? = null
    private var anomalySquares: Set<String>? = null
    private var enabled: Boolean = false

    fun start() {
        enabled = true
        _state.value = DetectorState.Idle
    }

    fun stop() {
        enabled = false
        clearAnomaly()
        stillSinceMs = null
        lastFrameMs = null
        resetStability()
        _state.value = DetectorState.Idle
    }

    fun resetReference(warpedRgb: Mat) {
        replaceReference(toLab(warpedRgb))
        clearAnomaly()
        resetStability()
        _changedSquares.value = emptySet()
        _state.value = DetectorState.Settled
    }

    /**
     * Soft reset after Undo / Reset Game: drop live reference so the next
     * settled frame becomes the new baseline. Undo restores history separately.
     */
    fun clearLiveReference() {
        clearAnomaly()
        resetStability()
        squareLatch.clear()
        _changedSquares.value = emptySet()
        referenceLab?.release()
        referenceLab = null
        _state.value = DetectorState.Idle
    }

    fun onGameReset() {
        clearReferenceHistory()
        clearLiveReference()
    }

    /**
     * Restore the reference that was active before the last committed move.
     * Call on the analysis thread together with [ChessGameEngine.undoLast].
     */
    fun undoReference(): Boolean {
        val prior = referenceHistory.removeLastOrNull() ?: return false
        clearAnomaly()
        resetStability()
        squareLatch.clear()
        _changedSquares.value = emptySet()
        referenceLab?.release()
        referenceLab = prior
        _state.value = DetectorState.Settled
        return true
    }

    /**
     * Drop live reference after a 180° flip so the next settle recaptures Lab.
     */
    fun invalidateReferenceKeepHistory() {
        clearAnomaly()
        resetStability()
        squareLatch.clear()
        _changedSquares.value = emptySet()
        referenceLab?.release()
        referenceLab = null
        _state.value = DetectorState.Idle
    }

    /**
     * Process one warped 400x400 RGB frame.
     * [nowMs] must be derived from ImageProxy sensor timestamps (ns → ms).
     */
    fun processFrame(warpedRgb: Mat, nowMs: Long) {
        if (!enabled) return

        val prevTs = lastFrameMs
        lastFrameMs = nowMs
        if (prevTs != null) {
            val gap = nowMs - prevTs
            if (gap > config.maxFrameGapMs) {
                // Pause: treat as motion and push anomaly deadline forward.
                stillSinceMs = null
                resetStability()
                anomalyDeadlineMs?.let { anomalyDeadlineMs = it + gap }
                if (_state.value !is DetectorState.Anomaly && _state.value !is DetectorState.Flagged) {
                    _state.value = DetectorState.Moving
                }
                return
            }
        }

        val moving = isHandMoving(warpedRgb)
        if (moving) {
            stillSinceMs = null
            resetStability()
            if (_state.value !is DetectorState.Anomaly && _state.value !is DetectorState.Flagged) {
                _state.value = DetectorState.Moving
            }
            return
        }

        val settledFor = stillSinceMs?.let { nowMs - it } ?: 0L
        if (stillSinceMs == null) {
            stillSinceMs = nowMs
        }

        if (settledFor < config.settleMs) {
            resetStability()
            if (_state.value !is DetectorState.Anomaly && _state.value !is DetectorState.Flagged) {
                _state.value = DetectorState.Moving
            }
            return
        }

        val ref = referenceLab
        if (ref == null) {
            resetReference(warpedRgb)
            return
        }

        val changed = detectChangedSquares(warpedRgb, ref)
        _changedSquares.value = changed

        if (!isStable(changed)) {
            if (_state.value !is DetectorState.Anomaly && _state.value !is DetectorState.Flagged) {
                _state.value = DetectorState.Moving
            }
            return
        }

        when (val current = _state.value) {
            is DetectorState.Flagged -> {
                if (changed.isEmpty()) {
                    clearAnomaly()
                    blendReference(warpedRgb)
                    _state.value = DetectorState.Settled
                } else {
                    tryCommitOrKeep(changed, flagged = true, warpedRgb)
                }
            }

            is DetectorState.Anomaly -> {
                val deadline = anomalyDeadlineMs ?: current.deadlineMs
                if (changed.isEmpty()) {
                    clearAnomaly()
                    blendReference(warpedRgb)
                    _state.value = DetectorState.Settled
                } else if (changed != (anomalySquares ?: current.squares)) {
                    startAnomaly(changed, nowMs)
                } else if (nowMs >= deadline) {
                    resolveAnomaly(changed, warpedRgb)
                }
            }

            else -> {
                if (changed.isEmpty()) {
                    blendReference(warpedRgb)
                    _state.value = DetectorState.Settled
                } else {
                    val match = engine.matchMove(changed)
                    if (match.move != null && match.san != null && match.uci != null && !match.ambiguous) {
                        commitMove(match.move, match.san, match.uci, warpedRgb)
                    } else {
                        startAnomaly(changed, nowMs)
                    }
                }
            }
        }
    }

    fun release() {
        stop()
        previousSmall?.release()
        previousSmall = null
        clearReferenceHistory()
        referenceLab?.release()
        referenceLab = null
        squareLatch.clear()
    }

    private fun tryCommitOrKeep(changed: Set<String>, flagged: Boolean, warpedRgb: Mat) {
        val match = engine.matchMove(changed)
        if (match.move != null && match.san != null && match.uci != null && !match.ambiguous) {
            commitMove(match.move, match.san, match.uci, warpedRgb)
        } else if (!flagged) {
            _state.value = DetectorState.Flagged(changed)
        }
    }

    private fun commitMove(
        move: com.github.bhlangonijr.chesslib.move.Move,
        san: String,
        uci: String,
        warpedRgb: Mat,
    ) {
        // Snapshot pre-move reference for undo before adopting the new board.
        pushReferenceHistory()
        engine.commit(move)
        onMoveCommitted(san, uci)
        clearAnomaly()
        replaceReference(toLab(warpedRgb))
        resetStability()
        _changedSquares.value = emptySet()
        _state.value = DetectorState.Settled
    }

    private fun startAnomaly(squares: Set<String>, nowMs: Long) {
        anomalySquares = squares
        anomalyDeadlineMs = nowMs + config.anomalyMs
        _state.value = DetectorState.Anomaly(squares, anomalyDeadlineMs!!)
    }

    private fun resolveAnomaly(changed: Set<String>, warpedRgb: Mat) {
        val match = engine.matchMove(changed)
        if (match.move != null && match.san != null && match.uci != null && !match.ambiguous) {
            commitMove(match.move, match.san, match.uci, warpedRgb)
        } else {
            clearAnomaly()
            _state.value = DetectorState.Flagged(changed)
        }
    }

    private fun clearAnomaly() {
        anomalyDeadlineMs = null
        anomalySquares = null
    }

    private fun isStable(changed: Set<String>): Boolean {
        if (stableChanged == changed) {
            stableCount++
        } else {
            stableChanged = changed
            stableCount = 1
        }
        return stableCount >= config.stableFrameCount
    }

    private fun resetStability() {
        stableChanged = null
        stableCount = 0
    }

    private fun isHandMoving(warpedRgb: Mat): Boolean {
        val gray = Mat()
        Imgproc.cvtColor(warpedRgb, gray, Imgproc.COLOR_RGB2GRAY)
        val small = Mat()
        Imgproc.resize(gray, small, config.motionScale)
        gray.release()

        val prev = previousSmall
        previousSmall = small
        if (prev == null) {
            return true
        }

        val diff = Mat()
        Core.absdiff(small, prev, diff)
        prev.release()
        val mean = Core.mean(diff).`val`[0]
        diff.release()
        return mean >= config.motionThreshold
    }

    private fun detectChangedSquares(warpedRgb: Mat, referenceLab: Mat): Set<String> {
        val currentLab = toLab(warpedRgb)
        val scores = LinkedHashMap<String, Double>(64)
        for (file in 'a'..'h') {
            for (rank in 1..8) {
                val square = "$file$rank"
                val rect = calibrator.squareRect(square)
                val a = Mat(currentLab, rect)
                val b = Mat(referenceLab, rect)
                val diff = Mat()
                Core.absdiff(a, b, diff)
                val mean = Core.mean(diff)
                val score = (mean.`val`[0] + mean.`val`[1] + mean.`val`[2]) / 3.0
                diff.release()
                a.release()
                b.release()
                scores[square] = score
            }
        }
        currentLab.release()
        return squareLatch.update(scores)
    }

    private fun blendReference(warpedRgb: Mat) {
        val ref = referenceLab ?: return
        val current = toLab(warpedRgb)
        Core.addWeighted(ref, 1.0 - config.lightingBlend, current, config.lightingBlend, 0.0, ref)
        current.release()
    }

    private fun replaceReference(newLab: Mat) {
        referenceLab?.release()
        referenceLab = newLab
        squareLatch.clear()
    }

    private fun pushReferenceHistory() {
        val ref = referenceLab ?: return
        referenceHistory.addLast(ref.clone())
        while (referenceHistory.size > config.maxReferenceHistory) {
            referenceHistory.removeFirst().release()
        }
    }

    private fun clearReferenceHistory() {
        while (referenceHistory.isNotEmpty()) {
            referenceHistory.removeFirst().release()
        }
    }

    private fun toLab(rgb: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        if (lab.type() != CvType.CV_8UC3) {
            val converted = Mat()
            lab.convertTo(converted, CvType.CV_8UC3)
            lab.release()
            return converted
        }
        return lab
    }
}
