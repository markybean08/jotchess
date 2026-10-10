package com.jotchess.recorder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.jotchess.recorder.ui.theme.PitchAnthracite
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

                // Keep screen awake while recording so CameraX keeps frames.
                DisposableEffect(ui.recording) {
                    if (ui.recording) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // OLED battery saver: dim to 1% while overlay is up.
                DisposableEffect(ui.batterySaver) {
                    val attrs = window.attributes
                    if (ui.batterySaver) {
                        attrs.screenBrightness = 0.01f
                    } else {
                        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    window.attributes = attrs
                    onDispose {
                        val restore = window.attributes
                        restore.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = restore
                    }
                }

                LaunchedEffect(ui.toastMessage) {
                    val msg = ui.toastMessage ?: return@LaunchedEffect
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    viewModel.clearToast()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RecorderScreen(
                            ui = ui,
                            hasCameraPermission = hasCameraPermission(),
                            viewModel = viewModel,
                            onCopyPgn = { copyPgn(ui.pgn) },
                        )

                        if (ui.recording && ui.batterySaver) {
                            BatterySaverOverlay(
                                lastSan = ui.lastSan,
                                onDismiss = { viewModel.dismissBatterySaver() },
                            )
                        }
                    }
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
private fun BatterySaverOverlay(
    lastSan: String?,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        NeubrutalistChip(
            text = "REC • ${lastSan ?: "—"}",
            containerColor = CarbonMint,
            contentColor = HardBlack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )
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
    val lensFacing = if (ui.useFrontCamera) {
        CameraSelector.LENS_FACING_FRONT
    } else {
        CameraSelector.LENS_FACING_BACK
    }
    val cameraActive = hasCameraPermission && ui.opencvReady &&
        !ui.gameFinished && (ui.calibrating || ui.recording)

    DisposableEffect(cameraActive, lensFacing) {
        if (!cameraActive) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get().unbindAll() }
            }, ContextCompat.getMainExecutor(context))
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

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    analysis,
                )
            } catch (_: Exception) {
                // Requested lens may be unavailable (emulator / missing front cam).
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

        if (ui.gameFinished) {
            FinishedPanel(
                pgn = ui.pgn,
                savedPath = ui.savedPgnPath,
                moves = ui.moves,
                onCopyPgn = onCopyPgn,
                onNewGame = { viewModel.newGame() },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            BoardPreview(
                bitmap = ui.previewBitmap,
                calibrating = ui.calibrating,
                highlightSquares = ui.highlightSquares,
                detectorState = ui.detectorState,
                cornerPoints = ui.cornerPoints,
                onTap = { nx, ny, w, h -> viewModel.onBoardTapped(nx, ny, w, h) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Spacer(Modifier.height(12.dp))

            StatusBadge(ui.detectorState, ui.calibrating, ui.recording)

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeubrutalistButton(
                    text = if (ui.useFrontCamera) "Cam: Front" else "Cam: Back",
                    onClick = { viewModel.toggleCameraLens() },
                    containerColor = SurfaceContainer,
                    contentColor = TextPrimary,
                )

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
                        disabledContainerColor = CarbonMint.copy(alpha = 0.45f),
                        disabledContentColor = HardBlack.copy(alpha = 0.55f),
                    )
                    NeubrutalistButton(
                        text = "Clear",
                        onClick = { viewModel.recalibrate() },
                        containerColor = SurfaceContainer,
                        contentColor = TextPrimary,
                    )
                } else if (ui.recording) {
                    NeubrutalistButton(
                        text = "End Game",
                        onClick = { viewModel.endGame() },
                        containerColor = HotSynthPink,
                        contentColor = HardBlack,
                    )
                    NeubrutalistButton(
                        text = if (ui.batterySaver) "Saver: On" else "Battery Saver",
                        onClick = { viewModel.setBatterySaver(!ui.batterySaver) },
                        containerColor = if (ui.batterySaver) AcidYellow else SurfaceContainer,
                        contentColor = HardBlack.takeIf { ui.batterySaver } ?: TextPrimary,
                    )
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
                MoveListBody(ui.moves)
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
private fun FinishedPanel(
    pgn: String,
    savedPath: String?,
    moves: List<String>,
    onCopyPgn: () -> Unit,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        NeubrutalistCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column {
                Text(
                    text = "GAME FINISHED / EXPORT READY",
                    color = AcidYellow,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "SAVED TO",
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontSize = 11.sp,
                )
                Text(
                    text = savedPath ?: "(clipboard only)",
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeubrutalistButton(
                text = "New Game",
                onClick = onNewGame,
                containerColor = CarbonMint,
                contentColor = HardBlack,
            )
            NeubrutalistButton(
                text = "Copy Pgn",
                onClick = onCopyPgn,
                containerColor = SurfaceContainer,
                contentColor = TextPrimary,
            )
        }

        Spacer(Modifier.height(12.dp))

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
                .weight(1f),
            contentPadding = PaddingValues(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MoveListBody(moves)
                if (pgn.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pgn,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 8,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoveListBody(moves: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (moves.isEmpty()) {
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
            items(moves) { line ->
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

@Composable
private fun StatusBadge(state: DetectorState, calibrating: Boolean, recording: Boolean) {
    val label = when {
        calibrating -> "CALIBRATING"
        recording && state is DetectorState.Moving -> "MOVING"
        recording && state is DetectorState.Settled -> "SETTLED"
        recording && state is DetectorState.Anomaly -> "TIMER 3S"
        recording && state is DetectorState.Flagged -> "FLAGGED"
        recording -> "RECORDING"
        else -> "READY"
    }
    val fill = when {
        calibrating -> HotSynthPink
        state is DetectorState.Settled -> AcidYellow
        state is DetectorState.Flagged -> HotSynthPink
        state is DetectorState.Anomaly -> HotSynthPink
        state is DetectorState.Moving -> HotSynthPink
        recording -> CarbonMint
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
    cornerPoints: List<CornerPoint>,
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
            modifier = Modifier
                .fillMaxSize()
                .background(PitchAnthracite),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null && bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(calibrating, bitmap.width, bitmap.height) {
                            detectTapGestures { offset ->
                                if (!calibrating) return@detectTapGestures
                                val bw = bitmap.width.toFloat()
                                val bh = bitmap.height.toFloat()
                                val scale = minOf(size.width / bw, size.height / bh)
                                val dw = bw * scale
                                val dh = bh * scale
                                val left = (size.width - dw) / 2f
                                val top = (size.height - dh) / 2f
                                val ix = ((offset.x - left) / dw).coerceIn(0f, 1f)
                                val iy = ((offset.y - top) / dh).coerceIn(0f, 1f)
                                onTap(ix, iy, bitmap.width, bitmap.height)
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
                            cornerPoints.forEachIndexed { index, p ->
                                val cx = left + p.x / bw * dw
                                val cy = top + p.y / bh * dh
                                val r = 14.dp.toPx()
                                val arm = 10.dp.toPx()
                                val stroke = 3.dp.toPx()

                                drawCircle(
                                    color = AcidYellow,
                                    radius = r,
                                    center = Offset(cx, cy),
                                    style = Stroke(width = stroke),
                                )
                                drawCircle(
                                    color = HotSynthPink,
                                    radius = r * 0.45f,
                                    center = Offset(cx, cy),
                                )
                                drawLine(
                                    color = AcidYellow,
                                    start = Offset(cx - arm, cy),
                                    end = Offset(cx + arm, cy),
                                    strokeWidth = stroke,
                                )
                                drawLine(
                                    color = AcidYellow,
                                    start = Offset(cx, cy - arm),
                                    end = Offset(cx, cy + arm),
                                    strokeWidth = stroke,
                                )
                                if (index == 0) {
                                    drawCircle(
                                        color = HardBlack,
                                        radius = 3.dp.toPx(),
                                        center = Offset(cx, cy),
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
