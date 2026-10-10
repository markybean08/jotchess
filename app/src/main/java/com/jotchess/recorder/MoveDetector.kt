package com.jotchess.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class DetectorConfig(
    val motionThreshold: Double = 6.0,
    val settleMs: Long = 500L,
    val anomalyMs: Long = 3000L,
    val squareDiffThreshold: Double = 12.0,
    val lightingBlend: Double = 0.02,
    val motionScale: Size = Size(100.0, 100.0),
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
 * Difference mapping, hand/motion gate, 3-second knocked-piece debounce.
 */
class MoveDetector(
    private val calibrator: BoardCalibrator,
    private val engine: ChessGameEngine,
    private val scope: CoroutineScope,
    private val config: DetectorConfig = DetectorConfig(),
    private val onMoveCommitted: (san: String, uci: String) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<DetectorState>(DetectorState.Idle)
    val state: StateFlow<DetectorState> = _state.asStateFlow()

    private val _changedSquares = MutableStateFlow<Set<String>>(emptySet())
    val changedSquares: StateFlow<Set<String>> = _changedSquares.asStateFlow()

    private var previousSmall: Mat? = null
    private var referenceLab: Mat? = null
    private var stillSinceMs: Long? = null
    private var anomalyJob: Job? = null
    private var enabled: Boolean = false

    fun start() {
        enabled = true
        _state.value = DetectorState.Idle
    }

    fun stop() {
        enabled = false
        cancelAnomaly()
        _state.value = DetectorState.Idle
    }

    fun resetReference(warpedRgb: Mat) {
        referenceLab?.release()
        referenceLab = toLab(warpedRgb)
        cancelAnomaly()
        _changedSquares.value = emptySet()
        _state.value = DetectorState.Settled
    }

    fun onGameReset(warpedRgb: Mat?) {
        cancelAnomaly()
        _changedSquares.value = emptySet()
        if (warpedRgb != null) {
            resetReference(warpedRgb)
        } else {
            referenceLab?.release()
            referenceLab = null
            _state.value = DetectorState.Idle
        }
    }

    /**
     * Process one warped 400x400 RGB frame. Call only when calibration is ready.
     */
    fun processFrame(warpedRgb: Mat, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return

        val moving = isHandMoving(warpedRgb)
        if (moving) {
            stillSinceMs = null
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

        when (val current = _state.value) {
            is DetectorState.Flagged -> {
                if (changed.isEmpty()) {
                    // Board restored to expected occupancy.
                    cancelAnomaly()
                    blendReference(warpedRgb)
                    _state.value = DetectorState.Settled
                } else {
                    tryCommitOrKeep(changed, flagged = true)
                }
            }

            is DetectorState.Anomaly -> {
                if (changed.isEmpty()) {
                    // Knocked piece stood back up — discard silently.
                    cancelAnomaly()
                    blendReference(warpedRgb)
                    _state.value = DetectorState.Settled
                } else if (changed != current.squares) {
                    // Pattern shifted; restart debounce with new squares.
                    startAnomaly(changed, nowMs)
                }
                // Else: wait for timer.
            }

            else -> {
                if (changed.isEmpty()) {
                    blendReference(warpedRgb)
                    _state.value = DetectorState.Settled
                } else {
                    val match = engine.matchMove(changed)
                    if (match.move != null && match.san != null && match.uci != null && !match.ambiguous) {
                        // Fast path: legal move commits immediately after settle.
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
        referenceLab?.release()
        referenceLab = null
    }

    private fun tryCommitOrKeep(changed: Set<String>, flagged: Boolean) {
        val match = engine.matchMove(changed)
        if (match.move != null && match.san != null && match.uci != null && !match.ambiguous) {
            // Need current warped frame for reference — caller path with Flagged
            // will get reference refresh on next settled empty/commit via processFrame.
            // Commit without warped mat: mark for reference refresh on next frame.
            engine.commit(match.move)
            onMoveCommitted(match.san, match.uci)
            cancelAnomaly()
            referenceLab?.release()
            referenceLab = null
            _changedSquares.value = emptySet()
            _state.value = DetectorState.Settled
        } else if (!flagged) {
            _state.value = DetectorState.Flagged(changed)
        }
    }

    private fun commitMove(move: com.github.bhlangonijr.chesslib.move.Move, san: String, uci: String, warpedRgb: Mat) {
        engine.commit(move)
        onMoveCommitted(san, uci)
        cancelAnomaly()
        resetReference(warpedRgb)
        _changedSquares.value = emptySet()
        _state.value = DetectorState.Settled
    }

    private fun startAnomaly(squares: Set<String>, nowMs: Long) {
        cancelAnomaly()
        val deadline = nowMs + config.anomalyMs
        _state.value = DetectorState.Anomaly(squares, deadline)
        anomalyJob = scope.launch {
            delay(config.anomalyMs)
            val pending = _changedSquares.value
            if (pending.isEmpty()) {
                _state.value = DetectorState.Settled
                return@launch
            }
            val match = engine.matchMove(pending)
            if (match.move != null && match.san != null && match.uci != null && !match.ambiguous) {
                engine.commit(match.move)
                onMoveCommitted(match.san, match.uci)
                referenceLab?.release()
                referenceLab = null
                _changedSquares.value = emptySet()
                _state.value = DetectorState.Settled
            } else {
                _state.value = DetectorState.Flagged(pending)
            }
        }
    }

    private fun cancelAnomaly() {
        anomalyJob?.cancel()
        anomalyJob = null
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
        val changed = mutableSetOf<String>()
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
                if (score >= config.squareDiffThreshold) {
                    changed += square
                }
            }
        }
        currentLab.release()
        return changed
    }

    private fun blendReference(warpedRgb: Mat) {
        val ref = referenceLab ?: return
        val current = toLab(warpedRgb)
        // ref = ref*(1-a) + current*a
        Core.addWeighted(ref, 1.0 - config.lightingBlend, current, config.lightingBlend, 0.0, ref)
        current.release()
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
