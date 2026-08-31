package com.example.glasses.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glasses.camera.DepthCameraController
import com.example.glasses.pipeline.AudioSpectrumBar
import com.example.glasses.pipeline.AudioWaveformBar
import com.example.glasses.pipeline.DepthAudioCoordinatorStatus
import com.example.glasses.ui.theme.AppBlue
import com.example.glasses.ui.theme.AppGreen
import com.example.glasses.ui.theme.AppMutedText
import com.example.glasses.ui.theme.AppOutline
import com.example.glasses.ui.theme.AppRed
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun DepthCameraScreen(
    settings: GlassesSettings,
    onBack: () -> Unit,
    viewModel: DepthCameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    val modelReady = state is DepthCameraUiState.WaitingForCamera ||
        state is DepthCameraUiState.Running
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val controller = remember { DepthCameraController(context.applicationContext) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted = it }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        viewModel.initialize()
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            delay(1_000L)
        }
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startAudio()
                Lifecycle.Event.ON_STOP -> viewModel.stopAudio()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startAudio()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAudio()
        }
    }

    DisposableEffect(permissionGranted, modelReady, lifecycleOwner) {
        if (permissionGranted && modelReady) {
            controller.start(
                lifecycleOwner = lifecycleOwner,
                onBitmap = viewModel::process,
                onError = viewModel::reportCameraError,
            )
        }
        onDispose { controller.stop() }
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        AssistanceTopBar(onBack = onBack)
        AssistanceStatus(elapsedSeconds = elapsedSeconds)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val current = state) {
                DepthCameraUiState.LoadingModel -> CameraPlaceholder("正在加载 YOLO26 Depth 模型...")
                DepthCameraUiState.WaitingForCamera -> CameraPlaceholder("正在启动摄像头...")
                is DepthCameraUiState.Error -> CameraPlaceholder(
                    text = "运行失败\n${current.message}",
                    color = AppRed,
                )
                is DepthCameraUiState.Running -> RunningCameraPanel(
                    state = current,
                    showGrid = settings.showGrid,
                    onFrameDisplayed = viewModel::reportUiFrameDisplayed,
                )
            }

            if (!permissionGranted && state !is DepthCameraUiState.LoadingModel) {
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("授予摄像头权限")
                }
            }

            val running = state as? DepthCameraUiState.Running
            SoundscapeStatusCard(audioState = audioState)
            StereoWaveforms(
                leftWaveform = audioState.leftWaveform,
                rightWaveform = audioState.rightWaveform,
            )
            StereoFrequencySpectra(
                leftSpectrum = audioState.leftSpectrum,
                rightSpectrum = audioState.rightSpectrum,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = running?.let {
                        String.format(Locale.US, "%s · %.1f FPS", it.accelerator, it.fps)
                    } ?: "YOLO26 Depth",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "深度图 + 障碍分类",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(2.dp))
        }

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = AppRed),
            shape = RoundedCornerShape(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .height(48.dp),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Text("停止辅助", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun AssistanceTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "实时辅助",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
    }
}

@Composable
private fun AssistanceStatus(elapsedSeconds: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(AppGreen),
        )
        Text(
            text = "运行中",
            color = AppGreen,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RunningCameraPanel(
    state: DepthCameraUiState.Running,
    showGrid: Boolean,
    onFrameDisplayed: (Long) -> Unit,
) {
    LaunchedEffect(state.performanceFrameSequence) {
        onFrameDisplayed(state.performancePublishedAtNanos)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CameraImagePanel(
            label = "深度图",
            image = state.image,
            showGrid = showGrid,
        )
        CameraImagePanel(
            label = "障碍分类",
            image = state.classificationImage,
            showGrid = showGrid,
            badge = "${state.activeObstacleCells} 个障碍区域",
        )
    }
}

@Composable
private fun CameraImagePanel(
    label: String,
    image: androidx.compose.ui.graphics.ImageBitmap,
    showGrid: Boolean,
    badge: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        Image(
            bitmap = image,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (showGrid) {
            Canvas(Modifier.fillMaxSize()) {
                val gridColor = Color.White.copy(alpha = 0.16f)
                for (index in 1 until ASSISTANCE_GRID_SIZE) {
                    val x = size.width * index / ASSISTANCE_GRID_SIZE
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.6f)
                }
                for (index in 1 until ASSISTANCE_GRID_SIZE) {
                    val y = size.height * index / ASSISTANCE_GRID_SIZE
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.6f)
                }
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.66f),
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (badge != null) {
            Surface(
                color = AppRed,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(9.dp),
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private const val ASSISTANCE_GRID_SIZE = 64

@Composable
private fun CameraPlaceholder(text: String, color: Color = AppMutedText) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(356.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF10141A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun SoundscapeStatusCard(audioState: DepthAudioUiState) {
    val title = when (audioState.status) {
        DepthAudioCoordinatorStatus.STOPPED -> "空间声景已停止"
        DepthAudioCoordinatorStatus.WAITING_FOR_FRAME -> "空间声景准备中"
        DepthAudioCoordinatorStatus.ACTIVE,
        DepthAudioCoordinatorStatus.DEPTH_ONLY,
        -> "空间声景运行中"
        DepthAudioCoordinatorStatus.STALE -> "空间声景等待画面"
        DepthAudioCoordinatorStatus.ERROR -> "空间声景异常"
    }
    val detail = when (audioState.status) {
        DepthAudioCoordinatorStatus.STOPPED -> "音频输出未启动"
        DepthAudioCoordinatorStatus.WAITING_FOR_FRAME -> "等待深度画面"
        DepthAudioCoordinatorStatus.ACTIVE,
        DepthAudioCoordinatorStatus.DEPTH_ONLY,
        -> if (audioState.activeObstacleCount > 0) {
            "${audioState.activeObstacleCount} 个障碍单元参与声景"
        } else {
            "等待障碍画面"
        }
        DepthAudioCoordinatorStatus.STALE -> "深度画面已暂停"
        DepthAudioCoordinatorStatus.ERROR -> audioState.errorMessage ?: "音频输出失败"
    }
    val progress = when {
        audioState.status == DepthAudioCoordinatorStatus.ERROR -> 1f
        audioState.leftWaveform.isNotEmpty() -> 0.78f
        audioState.status == DepthAudioCoordinatorStatus.STOPPED -> 0f
        else -> 0.18f
    }
    val accent = if (audioState.status == DepthAudioCoordinatorStatus.ERROR) AppRed else AppBlue
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(30.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = detail,
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .height(3.dp)
                        .background(Color(0xFFE7ECF4)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun StereoWaveforms(
    leftWaveform: List<AudioWaveformBar>,
    rightWaveform: List<AudioWaveformBar>,
) {
    val waiting = leftWaveform.isEmpty() || rightWaveform.isEmpty()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (waiting) {
                Text(
                    text = "等待声景生成",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            WaveformRow("左耳", AppBlue, leftWaveform)
            Spacer(Modifier.height(4.dp))
            WaveformRow("右耳", AppGreen, rightWaveform)
        }
    }
}

@Composable
private fun WaveformRow(
    label: String,
    color: Color,
    waveform: List<AudioWaveformBar>,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
        ) {
            val center = size.height / 2f
            drawLine(AppOutline, Offset(0f, center), Offset(size.width, center), 1f)
            val drawHeight = center * 0.9f
            waveform.forEachIndexed { index, bar ->
                val x = if (waveform.size == 1) {
                    size.width / 2f
                } else {
                    size.width * index / waveform.lastIndex.toFloat()
                }
                drawLine(
                    color = color.copy(alpha = 0.82f),
                    start = Offset(x, center - bar.maximum * drawHeight),
                    end = Offset(x, center - bar.minimum * drawHeight),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

@Composable
private fun StereoFrequencySpectra(
    leftSpectrum: List<AudioSpectrumBar>,
    rightSpectrum: List<AudioSpectrumBar>,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            FrequencySpectrumRow("Left frequency spectrum", AppBlue, leftSpectrum)
            Spacer(Modifier.height(8.dp))
            FrequencySpectrumRow("Right frequency spectrum", AppGreen, rightSpectrum)
        }
    }
}

@Composable
private fun FrequencySpectrumRow(
    label: String,
    color: Color,
    spectrum: List<AudioSpectrumBar>,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (spectrum.isEmpty()) {
                Text(
                    text = "等待真实 PCM",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF10141A)),
        ) {
            val gridColor = Color.White.copy(alpha = 0.12f)
            for (line in 1..3) {
                val y = size.height * line / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (spectrum.isNotEmpty()) {
                val slotWidth = size.width / spectrum.size
                val barWidth = (slotWidth * 0.68f).coerceAtLeast(1f)
                spectrum.forEachIndexed { index, bar ->
                    val x = slotWidth * (index + 0.5f)
                    val top = size.height * (1f - bar.level.coerceIn(0f, 1f))
                    drawLine(
                        color = color.copy(alpha = 0.92f),
                        start = Offset(x, size.height),
                        end = Offset(x, top),
                        strokeWidth = barWidth,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0 Hz", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            Text("8 kHz", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            Text("16 kHz", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
