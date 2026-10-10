package com.jotchess.recorder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jotchess.recorder.ui.theme.AcidYellow
import com.jotchess.recorder.ui.theme.CarbonMint
import com.jotchess.recorder.ui.theme.HardBlack
import com.jotchess.recorder.ui.theme.HotSynthPink
import com.jotchess.recorder.ui.theme.JotChessTheme
import com.jotchess.recorder.ui.theme.NeubrutalistButton
import com.jotchess.recorder.ui.theme.NeubrutalistCard
import com.jotchess.recorder.ui.theme.NeubrutalistChip
import com.jotchess.recorder.ui.theme.SurfaceContainer
import com.jotchess.recorder.ui.theme.TextPrimary
import com.jotchess.recorder.ui.theme.TextSecondary
import java.util.concurrent.TimeUnit

/**
 * Compose UI + CameraX ImageAnalysis integration.
 * Disciplined Dark-Mode Neubrutalism. No audio prompts.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureCameraPermission()

        setContent {
            JotChessTheme {
                val ui by viewModel.state.collectAsStateWithLifecycle()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RecorderScreen(
                        ui = ui,
                        hasCameraPermission = hasCameraPermission(),
                        viewModel = viewModel,
                        onCopyPgn = { copyPgn(ui.pgn) },
                    )
                }
            }
        }
    }

    private fun ensureCameraPermission() {
        if (!hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun copyPgn(pgn: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("pgn", pgn.ifBlank { "*" }))
        Toast.makeText(this, "PGN copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun RecorderScreen(
    ui: UiState,
    hasCameraPermission: Boolean,
    viewModel: RecorderViewModel,
    onCopyPgn: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    DisposableEffect(hasCameraPermission, ui.opencvReady) {
        if (!hasCameraPermission || !ui.opencvReady) {
            return@DisposableEffect onDispose { }
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            provider = cameraProviderFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(viewModel.analysisExecutor(), viewModel.imageAnalyzer()) }

            try {
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                )
            } catch (_: Exception) {
                // Camera may be unavailable on emulator without lens.
            }
        }, executor)

        onDispose {
            runCatching {
                cameraProviderFuture.get(500, TimeUnit.MILLISECONDS)?.unbindAll()
            }
            provider?.unbindAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "JOTCHESS",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
        )
        Text(
            text = ui.statusText.uppercase(),
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        BoardPreview(
            bitmap = ui.previewBitmap,
            calibrating = ui.calibrating,
            highlightSquares = ui.highlightSquares,
            detectorState = ui.detectorState,
            cornerCount = ui.cornerCount,
            pendingCorners = viewModel.pendingCornerPoints(),
            onTap = { nx, ny, w, h -> viewModel.onBoardTapped(nx, ny, w, h) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(Modifier.height(12.dp))

        StatusBadge(ui.detectorState, ui.calibrating)

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.calibrating) {
                NeubrutalistButton(
                    text = "Rotate (${ui.rotation}°)",
                    onClick = { viewModel.cycleRotation() },
                    enabled = ui.cornerCount == 4,
                    containerColor = SurfaceContainer,
                    contentColor = TextPrimary,
                )
                NeubrutalistButton(
                    text = "Record Game",
                    onClick = { viewModel.confirmCalibration() },
                    enabled = ui.cornerCount == 4,
                    containerColor = CarbonMint,
                    contentColor = HardBlack,
                )
                NeubrutalistButton(
                    text = "Clear",
                    onClick = { viewModel.recalibrate() },
                    containerColor = SurfaceContainer,
                    contentColor = TextPrimary,
                )
            } else {
                NeubrutalistButton(
                    text = "Reset Game",
                    onClick = { viewModel.resetGame() },
                    containerColor = SurfaceContainer,
                    contentColor = TextPrimary,
                )
                NeubrutalistButton(
                    text = "Copy Pgn",
                    onClick = onCopyPgn,
                    containerColor = CarbonMint,
                    contentColor = HardBlack,
                )
                NeubrutalistButton(
                    text = "Undo",
                    onClick = { viewModel.undo() },
                    containerColor = SurfaceContainer,
                    contentColor = TextPrimary,
                )
                NeubrutalistButton(
                    text = "Recalibrate",
                    onClick = { viewModel.recalibrate() },
                    containerColor = SurfaceContainer,
                    contentColor = TextPrimary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "MOVES",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        NeubrutalistCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (ui.moves.isEmpty()) {
                    item {
                        Text(
                            text = "NO MOVES YET",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    items(ui.moves) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }

        ui.message?.let {
            Text(
                text = it.uppercase(),
                color = HotSynthPink,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusBadge(state: DetectorState, calibrating: Boolean) {
    val label = when {
        calibrating -> "CALIBRATING"
        state is DetectorState.Moving -> "MOVING"
        state is DetectorState.Settled -> "SETTLED"
        state is DetectorState.Anomaly -> "TIMER 3S"
        state is DetectorState.Flagged -> "FLAGGED"
        else -> "READY"
    }
    val fill = when {
        calibrating -> HotSynthPink
        state is DetectorState.Settled -> AcidYellow
        state is DetectorState.Flagged -> HotSynthPink
        state is DetectorState.Anomaly -> HotSynthPink
        state is DetectorState.Moving -> HotSynthPink
        else -> CarbonMint
    }
    NeubrutalistChip(
        text = label,
        containerColor = fill,
        contentColor = HardBlack,
    )
}

@Composable
private fun BoardPreview(
    bitmap: Bitmap?,
    calibrating: Boolean,
    highlightSquares: Set<String>,
    detectorState: DetectorState,
    cornerCount: Int,
    pendingCorners: List<CornerPoint>,
    onTap: (normalizedX: Float, normalizedY: Float, imageWidth: Int, imageHeight: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    val highlightColor = when (detectorState) {
        is DetectorState.Flagged -> HotSynthPink.copy(alpha = 0.72f)
        is DetectorState.Anomaly -> HotSynthPink.copy(alpha = 0.55f)
        else -> HotSynthPink.copy(alpha = 0.40f)
    }

    NeubrutalistCard(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null && bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(calibrating, bitmap.width, bitmap.height) {
                            detectTapGestures { offset ->
                                if (!calibrating) return@detectTapGestures
                                val nx = (offset.x / size.width).coerceIn(0f, 1f)
                                val ny = (offset.y / size.height).coerceIn(0f, 1f)
                                onTap(nx, ny, bitmap.width, bitmap.height)
                            }
                        },
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Board camera",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val bw = bitmap.width.toFloat()
                        val bh = bitmap.height.toFloat()
                        val scale = minOf(size.width / bw, size.height / bh)
                        val dw = bw * scale
                        val dh = bh * scale
                        val left = (size.width - dw) / 2f
                        val top = (size.height - dh) / 2f

                        // Outer board frame — 3dp acid yellow
                        drawRect(
                            color = AcidYellow,
                            topLeft = Offset(left, top),
                            size = Size(dw, dh),
                            style = Stroke(width = 3.dp.toPx()),
                        )

                        if (!calibrating) {
                            val cell = dw / 8f
                            for (i in 0..8) {
                                val x = left + i * cell
                                val y = top + i * cell
                                drawLine(
                                    color = AcidYellow,
                                    start = Offset(x, top),
                                    end = Offset(x, top + dh),
                                    strokeWidth = 3.dp.toPx(),
                                )
                                drawLine(
                                    color = AcidYellow,
                                    start = Offset(left, y),
                                    end = Offset(left + dw, y),
                                    strokeWidth = 3.dp.toPx(),
                                )
                            }
                            for (sq in highlightSquares) {
                                val file = sq[0] - 'a'
                                val rank = sq[1] - '1'
                                val x = left + file * cell
                                val y = top + (7 - rank) * cell
                                drawRect(highlightColor, Offset(x, y), Size(cell, cell))
                                drawRect(
                                    color = HotSynthPink,
                                    topLeft = Offset(x, y),
                                    size = Size(cell, cell),
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                            }
                        } else {
                            pendingCorners.take(cornerCount).forEachIndexed { index, p ->
                                val cx = left + p.x / bw * dw
                                val cy = top + p.y / bh * dh
                                drawRect(
                                    color = CarbonMint,
                                    topLeft = Offset(cx - 8f, cy - 8f),
                                    size = Size(16f, 16f),
                                )
                                drawRect(
                                    color = HardBlack,
                                    topLeft = Offset(cx - 8f, cy - 8f),
                                    size = Size(16f, 16f),
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                                if (index == 0) {
                                    drawRect(
                                        color = HardBlack,
                                        topLeft = Offset(cx - 3f, cy - 3f),
                                        size = Size(6f, 6f),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = if (!calibrating) {
                        "WAITING FOR CAMERA…"
                    } else {
                        "GRANT CAMERA PERMISSION AND TAP 4 CORNERS"
                    },
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
